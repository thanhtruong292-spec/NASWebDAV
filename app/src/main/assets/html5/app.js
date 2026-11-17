(function () {
  var state = null;
  var currentView = "dashboard";
  var chartTab = 0; // 0: Temp, 1: Resource, 2: Net
  var chartRange = 24;
  var zoomScale = 1.0;
  var panFraction = 1.0;
  var touchedIdx = -1;

  var COLOR_GREEN = "#66bb6a";
  var COLOR_YELLOW = "#ffc400";
  var COLOR_RED = "#ef5350";
  var COLOR_BLUE = "#38bdf8";
  var COLOR_ORANGE = "#fb923c";
  var COLOR_PURPLE = "#a78bfa";
  var COLOR_PINK = "#f472b6";

  function bridge() {
    return window.NasBridge || null;
  }

  function callBridge(fn, arg) {
    var b = bridge();
    if (!b || typeof b[fn] !== "function") return;
    if (arg !== undefined) b[fn](arg);
    else b[fn]();
  }

  function fmtBytes(bytes) {
    var n = Number(bytes || 0);
    if (n >= 1099511627776) return (n / 1099511627776).toFixed(2) + " TB";
    if (n >= 1073741824) return (n / 1073741824).toFixed(2) + " GB";
    if (n >= 1048576) return (n / 1048576).toFixed(1) + " MB";
    if (n >= 1024) return (n / 1024).toFixed(1) + " KB";
    return n + " B";
  }

  function fmtCount(n) {
    n = Number(n || 0);
    if (n >= 1000) return (n / 1000).toFixed(1) + "K";
    return String(n);
  }

  function cpuTempColor(v) {
    if (v >= 80) return COLOR_RED;
    if (v >= 60) return COLOR_YELLOW;
    return COLOR_GREEN;
  }

  function hddTempColor(v) {
    if (v >= 55) return COLOR_RED;
    if (v >= 45) return COLOR_YELLOW;
    return COLOR_GREEN;
  }

  function pctColor(v) {
    if (v >= 90) return COLOR_RED;
    if (v >= 70) return COLOR_YELLOW;
    return COLOR_GREEN;
  }

  function lerpHex(hexA, hexB, t) {
    var a = parseInt(hexA.slice(1), 16);
    var b = parseInt(hexB.slice(1), 16);
    var r = Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
    var g = Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
    var bl = Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
    return "rgb(" + r + "," + g + "," + bl + ")";
  }

  function freshnessText(tsMs) {
    if (!tsMs || tsMs <= 0) return "Đang chờ dữ liệu";
    var ageSec = Math.max(0, Math.floor((Date.now() - tsMs) / 1000));
    if (ageSec < 10) return "Vừa cập nhật";
    if (ageSec < 60) return "Cập nhật " + ageSec + " giây trước";
    if (ageSec < 3600) return "Cập nhật " + Math.floor(ageSec / 60) + " phút trước";
    return "Dữ liệu trễ " + Math.floor(ageSec / 3600) + " giờ";
  }

  function renderView() {
    var views = ["dashboard", "apps", "system", "more"];
    views.forEach(function (v) {
      var el = document.getElementById("view-" + v);
      if (el) el.classList.toggle("hidden", v !== currentView);
    });
    var titles = {
      dashboard: ["Trang chủ NAS", "Giám sát & Thống kê WebDAV"],
      apps: ["Ứng dụng NAS", "Công cụ thông minh & Tiện ích"],
      system: ["Hệ thống NAS", "Tiến trình nền & Dịch vụ"],
      more: ["Thêm", "Tài khoản & Thiết lập"]
    };
    var pair = titles[currentView] || titles.dashboard;
    document.getElementById("topTitle").textContent = pair[0];
    document.getElementById("topSub").textContent = pair[1];
  }

  function renderState() {
    if (!state) return;

    // Connection badge
    var connText = document.getElementById("connText");
    var connDot = document.getElementById("connDot");
    connText.textContent = state.connLabel || "NAS";
    connDot.className = "dot" + (state.connOk ? "" : " err");

    // Storage
    var st = state.storage || {};
    var freshEl = document.getElementById("storageFreshBadge");
    freshEl.textContent = (st.partial ? "Đang cập nhật • " : "") + freshnessText(st.updatedAtMs);
    document.getElementById("storageSummaryLine").textContent =
      "Đã quét " + fmtCount(st.totalFiles) + " tệp • " + (st.totalDirs || 0) + " thư mục • " + fmtBytes(st.totalBytes);

    var catDefs = [
      ["image", "Ảnh", COLOR_BLUE],
      ["video", "Video", COLOR_ORANGE],
      ["doc", "Tài liệu", COLOR_GREEN],
      ["audio", "Nhạc", COLOR_PURPLE],
      ["archive", "Nén", COLOR_YELLOW],
      ["other", "Khác", "#94a3b8"]
    ];
    var totalB = Math.max(1, Number(st.totalBytes || 1));
    var catsHtml = "";
    catDefs.forEach(function (def) {
      var item = (st.byType && st.byType[def[0]]) || { files: 0, bytes: 0 };
      var wPct = Math.max(2, Math.min(100, (Number(item.bytes || 0) / totalB) * 100));
      catsHtml +=
        '<div class="cat-row">' +
          '<div class="cat-name">' + def[1] + '</div>' +
          '<div class="bar-track"><div class="bar-fill" style="width:' + wPct.toFixed(1) + '%;background:' + def[2] + '"></div></div>' +
          '<div class="cat-meta">' + fmtCount(item.files) + ' • ' + fmtBytes(item.bytes) + '</div>' +
        '</div>';
    });
    document.getElementById("storageCategories").innerHTML = catsHtml;

    var largestHtml = "";
    (st.largest || []).slice(0, 5).forEach(function (f) {
      var name = String(f.path || "").split("/").pop();
      largestHtml += '<div class="file-row"><span class="file-name">' + name + '</span><span class="file-size">' + fmtBytes(f.bytes) + '</span></div>';
    });
    document.getElementById("storageLargest").innerHTML = largestHtml || '<div class="list-sub">Chưa có dữ liệu</div>';

    // System Gauges
    var sys = state.system || {};
    var cpuPct = Number(sys.cpuPercent || 0);
    var cpuTemp = Number(sys.cpuTemp || 0);
    document.getElementById("cpuVal").textContent = cpuPct.toFixed(1) + "%";
    document.getElementById("cpuSub").textContent = (cpuTemp > 0 ? cpuTemp.toFixed(0) + "°C" : "--");
    var cpuBar = document.getElementById("cpuBar");
    cpuBar.style.width = Math.min(100, Math.max(2, cpuPct)) + "%";
    cpuBar.style.background = cpuTemp > 0 ? cpuTempColor(cpuTemp) : pctColor(cpuPct);

    var ramPct = Number(sys.ramPercent || 0);
    document.getElementById("ramVal").textContent = sys.ram || "--";
    document.getElementById("ramSub").textContent = ramPct.toFixed(1) + "%";
    var ramBar = document.getElementById("ramBar");
    ramBar.style.width = Math.min(100, Math.max(2, ramPct)) + "%";
    ramBar.style.background = pctColor(ramPct);

    var diskPct = Number(sys.diskPercent || 0);
    document.getElementById("diskVal").textContent = sys.diskCap || "--";
    document.getElementById("diskSub").textContent = "Đã dùng " + diskPct.toFixed(1) + "%";
    var diskBar = document.getElementById("diskBar");
    diskBar.style.width = Math.min(100, Math.max(2, diskPct)) + "%";
    diskBar.style.background = pctColor(diskPct);

    var smartStatus = String(sys.smartStatus || "UNKNOWN").toUpperCase();
    var smartColor = COLOR_GREEN;
    var smartW = 100;
    if (smartStatus.indexOf("FAILED") >= 0) smartColor = COLOR_RED;
    else if (smartStatus.indexOf("EMMC") >= 0) smartColor = COLOR_BLUE;
    else if (smartStatus.indexOf("PASSED") < 0 && smartStatus !== "OK") { smartColor = "#64748b"; smartW = 0; }
    document.getElementById("smartVal").textContent = smartStatus;
    document.getElementById("smartSub").textContent = sys.hddTemp || "--";
    var smartBar = document.getElementById("smartBar");
    smartBar.style.width = smartW + "%";
    smartBar.style.background = smartColor;

    // Fan
    document.getElementById("fanStatusText").textContent = sys.fanStatus || "--";
    document.querySelectorAll("[data-fan]").forEach(function (btn) {
      btn.classList.toggle("active", btn.getAttribute("data-fan") === sys.fanMode);
    });

    // System tab
    var th = state.thumb || {};
    document.getElementById("thumbStatusBadge").textContent = th.actualStatus || (th.running ? "Đang quét" : "Sẵn sàng");
    var scanP = Number(th.scanProgress || 0);
    var scanT = Math.max(1, Number(th.scanTotal || 1));
    var thPct = Math.min(100, (scanP / scanT) * 100);
    document.getElementById("thumbDetailText").textContent =
      "Đã rà soát " + scanP.toLocaleString() + " / " + Number(th.scanTotal || 0).toLocaleString() + " file • " + (th.actualStatus || "");
    document.getElementById("thumbBar").style.width = thPct.toFixed(1) + "%";
    document.getElementById("backupStatusSub").textContent = state.backupSub || "Chạy nền qua WorkManager Native";
    document.getElementById("sysSmartLine").textContent = smartStatus + " • " + (sys.hddTemp || "--");
    document.getElementById("sysOmvLine").textContent = sys.omvInfo || "OpenMediaVault";

    // More tab
    document.getElementById("moreUser").textContent = state.user || "admin";
    document.getElementById("moreUrl").textContent = (state.baseUrl || "") + " (" + (state.connLabel || "") + ")";
    document.getElementById("moreVersion").textContent = "Phiên bản " + (state.versionName || "1.0");

    // Range & tab UI
    document.querySelectorAll("[data-range]").forEach(function (btn) {
      btn.classList.toggle("active", Number(btn.getAttribute("data-range")) === chartRange);
    });
    document.querySelectorAll("[data-chart-tab]").forEach(function (btn) {
      btn.classList.toggle("active", Number(btn.getAttribute("data-chart-tab")) === chartTab);
    });
    document.getElementById("zoomResetBtn").textContent = zoomScale.toFixed(1) + "x";

    drawChart();
  }

  function drawChart() {
    var canvas = document.getElementById("metricsCanvas");
    if (!canvas || !state) return;
    var history = state.metrics || [];
    var dpr = window.devicePixelRatio || 1;
    var rect = canvas.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) return;
    canvas.width = Math.round(rect.width * dpr);
    canvas.height = Math.round(rect.height * dpr);
    var ctx = canvas.getContext("2d");
    ctx.scale(dpr, dpr);

    var w = rect.width;
    var h = rect.height;
    ctx.clearRect(0, 0, w, h);

    if (history.length < 2) {
      ctx.fillStyle = "#64748b";
      ctx.font = "12px sans-serif";
      ctx.textAlign = "center";
      ctx.fillText("Chưa đủ dữ liệu biểu đồ", w / 2, h / 2);
      return;
    }

    var visibleCount = Math.max(Math.min(history.length, 8), Math.floor(history.length / zoomScale));
    var maxStart = Math.max(0, history.length - visibleCount);
    var startIdx = Math.max(0, Math.min(maxStart, Math.floor(panFraction * maxStart)));
    var endIdx = Math.min(history.length - 1, startIdx + visibleCount - 1);
    var vis = history.slice(startIdx, endIdx + 1);

    var series;
    var minVal = 0;
    var maxVal = 100;
    if (chartTab === 0) {
      minVal = 20; maxVal = 80;
      series = [
        { label: "CPU", unit: "°C", dashed: false, vals: vis.map(function (x) { return x.cpuTemp; }), colorFn: cpuTempColor, fallback: COLOR_GREEN },
        { label: "HDD", unit: "°C", dashed: true, vals: vis.map(function (x) { return x.hddTemp; }), colorFn: hddTempColor, fallback: COLOR_GREEN }
      ];
    } else if (chartTab === 1) {
      minVal = 0; maxVal = 100;
      series = [
        { label: "CPU", unit: "%", dashed: false, vals: vis.map(function (x) { return x.cpuPct; }), colorFn: pctColor, fallback: COLOR_ORANGE },
        { label: "RAM", unit: "%", dashed: true, vals: vis.map(function (x) { return x.ramPct; }), colorFn: pctColor, fallback: COLOR_BLUE }
      ];
    } else {
      minVal = 0;
      var rx = vis.map(function (x) { return Math.max(0, (x.netRx || 0) / 1024); });
      var tx = vis.map(function (x) { return Math.max(0, (x.netTx || 0) / 1024); });
      maxVal = Math.max(0.2, Math.max.apply(null, rx.concat(tx)));
      series = [
        { label: "Tải về", unit: " MB/s", dashed: false, vals: rx, colorFn: null, fallback: COLOR_GREEN },
        { label: "Tải lên", unit: " MB/s", dashed: true, vals: tx, colorFn: null, fallback: COLOR_PINK }
      ];
    }

    // Update legend
    var legIdx = (touchedIdx >= startIdx && touchedIdx <= endIdx) ? (touchedIdx - startIdx) : (vis.length - 1);
    var legHtml = "";
    series.forEach(function (s) {
      var v = s.vals[legIdx] || 0;
      var c = s.colorFn ? s.colorFn(v) : s.fallback;
      var lineStyle = s.dashed ? "- - " : "━━ ";
      var txt = (chartTab === 2 ? v.toFixed(2) : v.toFixed(1)) + s.unit;
      legHtml += '<span style="color:' + c + ';font-weight:700;">' + lineStyle + s.label + ': ' + txt + '</span>';
    });
    document.getElementById("chartLegend").innerHTML = legHtml;

    var leftPad = 38, rightPad = 10, topPad = 10, bottomPad = 20;
    var usableW = Math.max(10, w - leftPad - rightPad);
    var usableH = Math.max(10, h - topPad - bottomPad);
    var range = Math.max(0.1, maxVal - minVal);

    function xOf(i) { return leftPad + (i / (vis.length - 1)) * usableW; }
    function yOf(v) {
      var clamped = Math.max(minVal, Math.min(maxVal, v));
      return (topPad + usableH) - ((clamped - minVal) / range) * usableH;
    }

    // Grid horizontal
    ctx.strokeStyle = "rgba(241, 245, 249, 0.12)";
    ctx.lineWidth = 0.7;
    ctx.fillStyle = "rgba(160, 175, 200, 0.85)";
    ctx.font = "bold 10px sans-serif";
    ctx.textAlign = "left";
    for (var gi = 0; gi < 4; gi++) {
      var frac = gi / 3;
      var gy = topPad + frac * usableH;
      var gVal = maxVal - frac * (maxVal - minVal);
      ctx.beginPath();
      ctx.moveTo(leftPad, gy);
      ctx.lineTo(w - rightPad, gy);
      ctx.stroke();
      var gLabel = chartTab === 0 ? Math.round(gVal) + "°C" : (chartTab === 1 ? Math.round(gVal) + "%" : gVal.toFixed(1));
      ctx.fillText(gLabel, 2, gy + 3);
    }

    // Grid vertical + timestamps
    ctx.textAlign = "center";
    var vCount = Math.min(4, vis.length);
    for (var vi = 0; vi < vCount; vi++) {
      var pIdx = Math.round((vi / Math.max(1, vCount - 1)) * (vis.length - 1));
      var vx = xOf(pIdx);
      ctx.save();
      ctx.setLineDash([3, 3]);
      ctx.beginPath();
      ctx.moveTo(vx, topPad);
      ctx.lineTo(vx, topPad + usableH);
      ctx.stroke();
      ctx.restore();
      var ts = String(vis[pIdx].ts || "");
      var hhmm = ts.length >= 16 ? ts.substring(11, 16) : ts;
      ctx.fillText(hhmm, vx, h - 5);
    }

    // Series fill + colored segments + small threshold dots
    series.forEach(function (s) {
      var pts = s.vals;
      if (pts.length < 2) return;

      // Subtle threshold gradient fill
      ctx.save();
      ctx.beginPath();
      ctx.moveTo(xOf(0), topPad + usableH);
      for (var i = 0; i < pts.length; i++) ctx.lineTo(xOf(i), yOf(pts[i]));
      ctx.lineTo(xOf(pts.length - 1), topPad + usableH);
      ctx.closePath();
      if (s.colorFn) {
        var grad = ctx.createLinearGradient(0, topPad, 0, topPad + usableH);
        grad.addColorStop(0, "rgba(239, 83, 80, 0.08)");
        grad.addColorStop(0.45, "rgba(255, 196, 0, 0.06)");
        grad.addColorStop(1, "rgba(102, 187, 106, 0.04)");
        ctx.fillStyle = grad;
      } else {
        ctx.fillStyle = "rgba(102, 187, 106, 0.05)";
      }
      ctx.fill();
      ctx.restore();

      ctx.lineWidth = 1.3;
      ctx.lineCap = "round";
      for (var i = 0; i < pts.length - 1; i++) {
        var c1 = s.colorFn ? s.colorFn(pts[i]) : s.fallback;
        var c2 = s.colorFn ? s.colorFn(pts[i + 1]) : s.fallback;
        ctx.save();
        ctx.strokeStyle = lerpHex(c1, c2, 0.5);
        if (s.dashed) ctx.setLineDash([4, 3]);
        ctx.beginPath();
        ctx.moveTo(xOf(i), yOf(pts[i]));
        ctx.lineTo(xOf(i + 1), yOf(pts[i + 1]));
        ctx.stroke();
        ctx.restore();
      }

      if (s.colorFn) {
        var dotR = zoomScale > 2 ? 1.6 : 1.1;
        for (var i = 0; i < pts.length; i++) {
          ctx.fillStyle = s.colorFn(pts[i]);
          ctx.beginPath();
          ctx.arc(xOf(i), yOf(pts[i]), dotR, 0, Math.PI * 2);
          ctx.fill();
        }
      }
    });

    // Active scrub or last marker
    if (touchedIdx >= startIdx && touchedIdx <= endIdx) {
      var li = touchedIdx - startIdx;
      var cx = xOf(li);
      ctx.save();
      ctx.strokeStyle = "rgba(241,245,249,0.7)";
      ctx.setLineDash([5, 3]);
      ctx.beginPath();
      ctx.moveTo(cx, topPad);
      ctx.lineTo(cx, topPad + usableH);
      ctx.stroke();
      ctx.restore();
      series.forEach(function (s) {
        var v = s.vals[li];
        var c = s.colorFn ? s.colorFn(v) : s.fallback;
        var cy = yOf(v);
        ctx.fillStyle = c;
        ctx.beginPath();
        ctx.arc(cx, cy, 4.5, 0, Math.PI * 2);
        ctx.fill();
        ctx.fillStyle = "#fff";
        ctx.beginPath();
        ctx.arc(cx, cy, 2, 0, Math.PI * 2);
        ctx.fill();
      });
    }

    var footTs = vis[legIdx].ts || "";
    document.getElementById("chartTimeFoot").textContent =
      (touchedIdx >= startIdx && touchedIdx <= endIdx ? "Mốc chọn: " : "Cập nhật lúc: ") + footTs;
  }

  // Event wiring
  document.addEventListener("DOMContentLoaded", function () {
    document.getElementById("searchBtn").addEventListener("click", function () {
      var q = document.getElementById("searchInput").value.trim();
      if (q) callBridge("search", q);
    });
    document.getElementById("searchInput").addEventListener("keydown", function (e) {
      if (e.key === "Enter") {
        var q = e.target.value.trim();
        if (q) callBridge("search", q);
      }
    });
    document.getElementById("refreshStorageBtn").addEventListener("click", function () {
      callBridge("refreshStorage");
    });

    document.querySelectorAll("[data-action]").forEach(function (el) {
      el.addEventListener("click", function () {
        callBridge("action", el.getAttribute("data-action"));
      });
    });
    document.querySelectorAll("[data-fan]").forEach(function (el) {
      el.addEventListener("click", function () {
        callBridge("setFanMode", el.getAttribute("data-fan"));
      });
    });
    document.querySelectorAll("[data-thumb]").forEach(function (el) {
      el.addEventListener("click", function () {
        callBridge("thumbControl", el.getAttribute("data-thumb"));
      });
    });
    document.querySelectorAll("[data-range]").forEach(function (el) {
      el.addEventListener("click", function () {
        chartRange = Number(el.getAttribute("data-range"));
        callBridge("setMetricsRange", chartRange);
        renderState();
      });
    });
    document.querySelectorAll("[data-chart-tab]").forEach(function (el) {
      el.addEventListener("click", function () {
        chartTab = Number(el.getAttribute("data-chart-tab"));
        touchedIdx = -1;
        renderState();
      });
    });
    document.getElementById("zoomInBtn").addEventListener("click", function () {
      zoomScale = Math.min(8, zoomScale * 2);
      touchedIdx = -1;
      renderState();
    });
    document.getElementById("zoomOutBtn").addEventListener("click", function () {
      zoomScale = Math.max(1, zoomScale / 2);
      touchedIdx = -1;
      renderState();
    });
    document.getElementById("zoomResetBtn").addEventListener("click", function () {
      zoomScale = 1.0;
      panFraction = 1.0;
      touchedIdx = -1;
      renderState();
    });

    // Touch pinch-zoom + pan + scrub on Canvas
    var canvas = document.getElementById("metricsCanvas");
    var lastDist = 0;
    var lastMidX = 0;
    canvas.addEventListener("touchstart", function (e) {
      if (e.touches.length === 2) {
        var dx = e.touches[0].clientX - e.touches[1].clientX;
        var dy = e.touches[0].clientY - e.touches[1].clientY;
        lastDist = Math.hypot(dx, dy);
        lastMidX = (e.touches[0].clientX + e.touches[1].clientX) / 2;
        touchedIdx = -1;
      } else if (e.touches.length === 1) {
        updateScrub(e.touches[0].clientX);
      }
    }, { passive: true });
    canvas.addEventListener("touchmove", function (e) {
      if (e.touches.length === 2) {
        var dx = e.touches[0].clientX - e.touches[1].clientX;
        var dy = e.touches[0].clientY - e.touches[1].clientY;
        var dist = Math.hypot(dx, dy);
        var midX = (e.touches[0].clientX + e.touches[1].clientX) / 2;
        if (lastDist > 0) {
          zoomScale = Math.max(1, Math.min(8, zoomScale * (dist / lastDist)));
        }
        var rect = canvas.getBoundingClientRect();
        if (zoomScale > 1 && rect.width > 0) {
          panFraction = Math.max(0, Math.min(1, panFraction - (midX - lastMidX) / rect.width));
        }
        lastDist = dist;
        lastMidX = midX;
        touchedIdx = -1;
        drawChart();
      } else if (e.touches.length === 1) {
        updateScrub(e.touches[0].clientX);
      }
    }, { passive: true });
    canvas.addEventListener("touchend", function (e) {
      if (e.touches.length < 2) lastDist = 0;
      if (e.touches.length === 0) {
        touchedIdx = -1;
        drawChart();
      }
    });

    function updateScrub(clientX) {
      if (!state || !state.metrics || state.metrics.length < 2) return;
      var rect = canvas.getBoundingClientRect();
      var usableW = Math.max(10, rect.width - 48);
      var rel = Math.max(0, Math.min(1, (clientX - rect.left - 38) / usableW));
      var visibleCount = Math.max(Math.min(state.metrics.length, 8), Math.floor(state.metrics.length / zoomScale));
      var maxStart = Math.max(0, state.metrics.length - visibleCount);
      var startIdx = Math.max(0, Math.min(maxStart, Math.floor(panFraction * maxStart)));
      touchedIdx = Math.min(state.metrics.length - 1, startIdx + Math.round(rel * (visibleCount - 1)));
      renderState();
    }
  });

  window.NasUI = {
    setView: function (view) {
      currentView = view || "dashboard";
      renderView();
      renderState();
    },
    updateState: function (newState) {
      state = newState;
      renderState();
    }
  };
})();
