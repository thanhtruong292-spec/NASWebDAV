(function () {
  var state = null;
  var currentView = "dashboard";
  var chartTab = 0; // 0: Temp, 1: Resource, 2: Net
  var chartRange = 24;
  var zoomScale = 1.0;
  var panFraction = 1.0;
  var touchedIdx = -1;
  var fileFilter = "all";
  var fileViewMode = "grid3"; // grid3 | grid6 | list
  var activeModalFile = null;
  var procSortBy = "cpu"; // cpu | mem
  var smartTab = "summary"; // summary | raw

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

  var toastTimer = null;
  function showToast(msg) {
    var el = document.getElementById("uxToast");
    if (!el) return;
    el.textContent = msg;
    el.classList.add("show");
    if (toastTimer) clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.classList.remove("show"); }, 1800);
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
    var views = ["dashboard", "apps", "files", "system", "more"];
    views.forEach(function (v) {
      var el = document.getElementById("view-" + v);
      if (el) el.classList.toggle("hidden", v !== currentView);
    });
    var titles = {
      dashboard: ["Trang chủ NAS", "Giám sát & Thống kê WebDAV"],
      apps: ["Ứng dụng NAS", "Công cụ thông minh & Tiện ích"],
      files: ["Quản lý Tệp", "Duyệt, xem ảnh & phát Video"],
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

    // Files tab
    if (currentView === "files") {
      renderFiles();
    }
    if (!document.getElementById("processModal").classList.contains("hidden")) {
      renderProcesses();
    }
    if (!document.getElementById("smartModal").classList.contains("hidden")) {
      renderSmartModal();
    }

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

  function isImgFile(name) {
    return /\.(jpg|jpeg|png|webp|gif|bmp|heic|heif)$/i.test(name || "");
  }
  function isVidFile(name) {
    return /\.(mp4|mkv|avi|mov|mpg|mpeg|wmv|flv|ts|m4v|webm)$/i.test(name || "");
  }

  function renderFiles() {
    var fState = (state && state.files) || {};
    var container = document.getElementById("filesContainer");
    if (!container) return;
    document.getElementById("filesPathTitle").textContent = fState.title || "/webdav/";
    var list = fState.items || [];
    var q = (document.getElementById("fileSearchInput").value || "").trim().toLowerCase();
    var filtered = list.filter(function (f) {
      if (q && String(f.name || "").toLowerCase().indexOf(q) < 0) return false;
      if (fileFilter === "dir") return !!f.isDir;
      if (fileFilter === "image") return !f.isDir && isImgFile(f.name);
      if (fileFilter === "video") return !f.isDir && isVidFile(f.name);
      return true;
    });
    document.getElementById("filesCountBadge").textContent = fState.loading ? "..." : String(filtered.length);
    if (fState.loading && filtered.length === 0) {
      container.innerHTML = '<div class="card"><div class="list-sub">Đang tải danh sách tệp từ NAS...</div></div>';
      return;
    }
    if (filtered.length === 0) {
      container.innerHTML = '<div class="card"><div class="list-sub">Không có tệp nào phù hợp.</div></div>';
      return;
    }

    var apiBase = (state && state.apiBase) || "";
    var isList = fileViewMode === "list";
    container.className = isList ? "files-list" : ("files-grid" + (fileViewMode === "grid6" ? " cols-6" : ""));

    var html = "";
    filtered.forEach(function (f, idx) {
      var thumbUrl = (!f.isDir && (isImgFile(f.name) || isVidFile(f.name)) && apiBase && f.relPath)
        ? (apiBase + "/api/thumb?path=" + encodeURIComponent(f.relPath)) : "";
      var icoSvg = f.isDir
        ? '<svg class="svg-ico" style="width:26px;height:26px;color:var(--ux-cyan);" viewBox="0 0 24 24"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/></svg>'
        : isVidFile(f.name)
          ? '<svg class="svg-ico" style="width:24px;height:24px;color:var(--ux-purple);" viewBox="0 0 24 24"><rect x="2" y="5" width="15" height="14" rx="2"/><polygon points="22 7 17 12 22 17 22 7"/></svg>'
          : '<svg class="svg-ico" style="width:24px;height:24px;color:var(--ux-text-2);" viewBox="0 0 24 24"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>';
      var thumbHtml = thumbUrl ? ('<img loading="lazy" src="' + thumbUrl + '" onerror="this.style.display=\'none\'" />') : icoSvg;
      var safeName = String(f.name || "").replace(/</g, "&lt;");
      var meta = f.isDir ? "Thư mục" : fmtBytes(f.size);
      if (isList) {
        html += '<div class="f-row" data-fidx="' + idx + '">' +
          '<div class="f-row-thumb">' + thumbHtml + '</div>' +
          '<div style="flex:1;min-width:0;"><div class="list-title" style="white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">' + safeName + '</div><div class="list-sub">' + meta + '</div></div>' +
          '<button class="btn-icon" data-fmenu="' + idx + '">⋮</button>' +
        '</div>';
      } else {
        html += '<div class="f-tile" data-fidx="' + idx + '">' +
          '<div class="f-thumb">' + thumbHtml + '</div>' +
          '<div class="f-name">' + safeName + '</div>' +
          (fileViewMode === "grid3" ? ('<div class="f-meta">' + meta + '</div>') : "") +
        '</div>';
      }
    });
    container.innerHTML = html;

    container.querySelectorAll("[data-fidx]").forEach(function (el) {
      el.addEventListener("click", function (ev) {
        if (ev.target && ev.target.hasAttribute("data-fmenu")) return;
        var item = filtered[Number(el.getAttribute("data-fidx"))];
        if (item) callBridge("openFile", item.path);
      });
      var timer = null;
      el.addEventListener("touchstart", function () {
        var item = filtered[Number(el.getAttribute("data-fidx"))];
        timer = setTimeout(function () { if (item) openFileModal(item); }, 520);
      }, { passive: true });
      el.addEventListener("touchend", function () { if (timer) clearTimeout(timer); });
      el.addEventListener("touchmove", function () { if (timer) clearTimeout(timer); });
    });
    container.querySelectorAll("[data-fmenu]").forEach(function (btn) {
      btn.addEventListener("click", function (ev) {
        ev.stopPropagation();
        var item = filtered[Number(btn.getAttribute("data-fmenu"))];
        if (item) openFileModal(item);
      });
    });
  }

  function openSmartModal(tab) {
    smartTab = tab || "summary";
    renderSmartModal();
    document.getElementById("smartModal").classList.remove("hidden");
  }

  function parseSmartAttr(raw, attrName) {
    if (!raw) return null;
    var lines = String(raw).split(/\r?\n/);
    for (var i = 0; i < lines.length; i++) {
      if (lines[i].indexOf(attrName) >= 0) {
        var parts = lines[i].trim().split(/\s+/);
        var last = parts[parts.length - 1];
        if (/^\d+$/.test(last)) return Number(last);
      }
    }
    return null;
  }

  function parseSmartHeader(raw, key) {
    if (!raw) return "";
    var re = new RegExp(key + "\\s*:\\s*(.+)", "i");
    var m = String(raw).match(re);
    return m ? m[1].trim() : "";
  }

  function renderSmartModal() {
    var sumBtn = document.getElementById("smartTabSummaryBtn");
    var rawBtn = document.getElementById("smartTabRawBtn");
    var sumEl = document.getElementById("smartCleanSummary");
    var logEl = document.getElementById("smartModalLog");
    if (sumBtn) sumBtn.classList.toggle("active", smartTab === "summary");
    if (rawBtn) rawBtn.classList.toggle("active", smartTab === "raw");
    if (sumEl) sumEl.classList.toggle("hidden", smartTab !== "summary");
    if (logEl) logEl.classList.toggle("hidden", smartTab !== "raw");

    var sys = (state && state.system) || {};
    var raw = sys.smartRaw || "";
    var status = String(sys.smartStatus || "UNKNOWN").toUpperCase();
    var isOk = status.indexOf("PASSED") >= 0 || status === "OK";
    var isFail = status.indexOf("FAILED") >= 0;
    var statusBadgeColor = isFail ? COLOR_RED : (isOk ? COLOR_GREEN : COLOR_YELLOW);
    var statusLabel = isOk ? "Khỏe mạnh (PASSED)" : (isFail ? "Cảnh báo lỗi (FAILED)" : status);

    var model = parseSmartHeader(raw, "Device Model") || parseSmartHeader(raw, "Model Number") || "Ổ cứng NAS";
    var hours = parseSmartAttr(raw, "Power_On_Hours");
    var cycles = parseSmartAttr(raw, "Power_Cycle_Count");
    var realloc = parseSmartAttr(raw, "Reallocated_Sector_Ct");
    var pending = parseSmartAttr(raw, "Current_Pending_Sector");

    var rows = [
      ["Trạng thái", '<span style="color:' + statusBadgeColor + ';font-weight:800;">' + statusLabel + '</span>'],
      ["Ổ đĩa", model],
      ["Nhiệt độ hiện tại", sys.hddTemp || "--"],
      ["Dung lượng đã dùng", (sys.diskCap || "--") + " (" + Number(sys.diskPercent || 0).toFixed(1) + "%)"]
    ];
    if (hours !== null) {
      rows.push(["Thời gian hoạt động", hours.toLocaleString() + " giờ (~" + Math.floor(hours / 24) + " ngày)"]);
    }
    if (cycles !== null) {
      rows.push(["Số lần khởi động", cycles.toLocaleString() + " lần"]);
    }
    if (realloc !== null || pending !== null) {
      var bad = Number(realloc || 0) + Number(pending || 0);
      var badTxt = bad === 0
        ? '<span style="color:' + COLOR_GREEN + ';font-weight:700;">0 lỗi (An toàn)</span>'
        : '<span style="color:' + COLOR_RED + ';font-weight:700;">' + bad + ' sector lỗi</span>';
      rows.push(["Sector bad / chờ xử lý", badTxt]);
    }

    var html = "";
    rows.forEach(function (r) {
      html += '<div style="display:flex;justify-content:space-between;align-items:center;gap:10px;padding:7px 10px;background:rgba(9,14,28,0.72);border:1px solid rgba(148,163,184,0.1);border-radius:10px;font-size:12px;">' +
        '<span style="color:var(--ux-text-2);">' + r[0] + '</span>' +
        '<span style="font-weight:700;text-align:right;">' + r[1] + '</span>' +
      '</div>';
    });
    document.getElementById("smartCleanSummary").innerHTML = html;
    document.getElementById("smartModalLog").textContent = raw || "Chưa có nhật ký S.M.A.R.T.";
  }

  function openProcessModal(sortMode) {
    procSortBy = sortMode || "cpu";
    callBridge("fetchProcesses");
    renderProcesses();
    document.getElementById("processModal").classList.remove("hidden");
  }

  function renderProcesses() {
    var container = document.getElementById("procListContainer");
    if (!container) return;
    document.getElementById("procSortCpuBtn").classList.toggle("active", procSortBy === "cpu");
    document.getElementById("procSortMemBtn").classList.toggle("active", procSortBy === "mem");
    var procs = ((state && state.processes) || []).slice();
    procs.sort(function (a, b) {
      return procSortBy === "mem" ? (b.mem - a.mem) : (b.cpu - a.cpu);
    });
    if (procs.length === 0) {
      container.innerHTML = '<div class="list-sub" style="padding:12px 0;">Đang tải danh sách tiến trình từ NAS...</div>';
      return;
    }
    var html = "";
    procs.slice(0, 40).forEach(function (p) {
      var val = procSortBy === "mem" ? p.mem : p.cpu;
      var c = pctColor(val);
      html += '<div class="list-item" style="padding:8px 0;">' +
        '<div style="flex:1;min-width:0;">' +
          '<div class="list-title" style="font-size:12.5px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">' +
            String(p.name || "?").replace(/</g, "&lt;") +
            ' <span style="color:var(--ux-text-3);font-size:10.5px;">#' + p.pid + ' (' + (p.user || '') + ')</span></div>' +
          '<div class="list-sub" style="font-variant-numeric:tabular-nums;">CPU: <b style="color:' + pctColor(p.cpu) + '">' + Number(p.cpu || 0).toFixed(1) + '%</b> • RAM: <b style="color:' + pctColor(p.mem) + '">' + Number(p.mem || 0).toFixed(1) + '%</b></div>' +
        '</div>' +
        (!p.isSystem ? ('<button class="btn-pill danger" data-killpid="' + p.pid + '" style="padding:4px 8px;font-size:11px;">Dừng</button>') : '<span class="badge">Hệ thống</span>') +
      '</div>';
    });
    container.innerHTML = html;
    container.querySelectorAll("[data-killpid]").forEach(function (btn) {
      btn.addEventListener("click", function () {
        var pid = Number(btn.getAttribute("data-killpid"));
        callBridge("killProcess", pid);
        showToast("Đã gửi lệnh dừng PID " + pid);
      });
    });
  }

  function openFileModal(item) {
    activeModalFile = item;
    document.getElementById("modalFileName").textContent = item.name;
    document.getElementById("modalFileMeta").textContent = item.isDir ? "Thư mục" : fmtBytes(item.size);
    document.getElementById("modalRenameInput").value = item.name;
    document.getElementById("fileActionModal").classList.remove("hidden");
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

      // Lucian-style floating scrub tooltip pill
      var tipParts = series.map(function (s) {
        var v = s.vals[li];
        return s.label + ": " + (chartTab === 2 ? v.toFixed(2) : v.toFixed(1)) + s.unit;
      });
      var tipText = tipParts.join("  •  ");
      ctx.save();
      ctx.font = "bold 10.5px sans-serif";
      var tw = ctx.measureText(tipText).width + 16;
      var tx = Math.max(leftPad + tw / 2, Math.min(w - rightPad - tw / 2, cx));
      var ty = topPad + 12;
      ctx.fillStyle = "rgba(8, 13, 26, 0.92)";
      ctx.strokeStyle = "rgba(34, 211, 238, 0.45)";
      ctx.lineWidth = 1;
      ctx.beginPath();
      if (ctx.roundRect) {
        ctx.roundRect(tx - tw / 2, ty - 11, tw, 20, 7);
      } else {
        ctx.rect(tx - tw / 2, ty - 11, tw, 20);
      }
      ctx.fill();
      ctx.stroke();
      ctx.fillStyle = "#f1f5f9";
      ctx.textAlign = "center";
      ctx.fillText(tipText, tx, ty + 3);
      ctx.restore();
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
      showToast("Đang làm mới thống kê lưu trữ...");
    });

    document.querySelectorAll("[data-action]").forEach(function (el) {
      el.addEventListener("click", function () {
        var act = el.getAttribute("data-action");
        if (act === "procCpu") openProcessModal("cpu");
        else if (act === "procMem") openProcessModal("mem");
        else if (act === "smartDetails" || act === "diskProfile") openSmartModal();
        else callBridge("action", act);
      });
    });

    document.getElementById("procSortCpuBtn").addEventListener("click", function () { procSortBy = "cpu"; renderProcesses(); });
    document.getElementById("procSortMemBtn").addEventListener("click", function () { procSortBy = "mem"; renderProcesses(); });
    document.getElementById("procRefreshBtn").addEventListener("click", function () { callBridge("fetchProcesses"); showToast("Đang cập nhật tiến trình..."); });
    document.getElementById("procCloseBtn").addEventListener("click", function () { document.getElementById("processModal").classList.add("hidden"); });
    document.getElementById("smartTabSummaryBtn").addEventListener("click", function () { smartTab = "summary"; renderSmartModal(); });
    document.getElementById("smartTabRawBtn").addEventListener("click", function () { smartTab = "raw"; renderSmartModal(); });
    document.getElementById("smartRefreshBtn").addEventListener("click", function () { callBridge("refreshStorage"); showToast("Đang cập nhật S.M.A.R.T..."); });
    document.getElementById("smartCloseBtn").addEventListener("click", function () { document.getElementById("smartModal").classList.add("hidden"); });
    document.querySelectorAll("[data-fan]").forEach(function (el) {
      el.addEventListener("click", function () {
        var mode = el.getAttribute("data-fan");
        callBridge("setFanMode", mode);
        showToast("Đã chuyển chế độ quạt: " + mode);
      });
    });
    document.querySelectorAll("[data-thumb]").forEach(function (el) {
      el.addEventListener("click", function () {
        var act = el.getAttribute("data-thumb");
        callBridge("thumbControl", act);
        showToast("Đã gửi lệnh Thumbnail: " + act);
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

    // File Manager controls
    document.getElementById("fileBackBtn").addEventListener("click", function () { callBridge("fileBack"); });
    document.getElementById("fileRefreshBtn").addEventListener("click", function () {
      callBridge("fileRefresh");
      showToast("Đang tải lại thư mục...");
    });
    document.getElementById("fileViewToggleBtn").addEventListener("click", function () {
      fileViewMode = fileViewMode === "grid3" ? "grid6" : (fileViewMode === "grid6" ? "list" : "grid3");
      showToast("Chế độ xem: " + fileViewMode);
      renderFiles();
    });
    document.getElementById("fileSearchInput").addEventListener("input", function () { renderFiles(); });
    document.getElementById("fileSearchInput").addEventListener("keydown", function (e) {
      if (e.key === "Enter") {
        var kw = e.target.value.trim();
        if (kw) callBridge("search", kw);
      }
    });
    document.querySelectorAll("[data-ffilter]").forEach(function (btn) {
      btn.addEventListener("click", function () {
        fileFilter = btn.getAttribute("data-ffilter");
        document.querySelectorAll("[data-ffilter]").forEach(function (b) { b.classList.toggle("active", b === btn); });
        renderFiles();
      });
    });
    document.querySelectorAll("[data-fsort]").forEach(function (btn) {
      btn.addEventListener("click", function () {
        var mode = btn.getAttribute("data-fsort");
        callBridge("fileSort", mode);
        showToast("Sắp xếp theo: " + mode);
      });
    });
    document.getElementById("modalCancelBtn").addEventListener("click", function () {
      document.getElementById("fileActionModal").classList.add("hidden");
    });
    document.getElementById("modalRenameBtn").addEventListener("click", function () {
      if (!activeModalFile) return;
      var nn = document.getElementById("modalRenameInput").value.trim();
      if (nn && nn !== activeModalFile.name) {
        callBridge("fileRename", JSON.stringify({ path: activeModalFile.path, name: nn }));
        showToast("Đang đổi tên...");
      }
      document.getElementById("fileActionModal").classList.add("hidden");
    });
    document.getElementById("modalDeleteBtn").addEventListener("click", function () {
      if (!activeModalFile) return;
      callBridge("fileDelete", activeModalFile.path);
      showToast("Đang chuyển vào Thùng rác...");
      document.getElementById("fileActionModal").classList.add("hidden");
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
