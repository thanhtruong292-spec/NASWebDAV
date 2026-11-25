(function () {
  var state = null;
  var fileSnapshot = null;
  var fileRowsSignature = "";
  var fileRowsRevision = 0;
  var currentView = "dashboard";
  var chartTab = 0; // 0: Temp, 1: Resource, 2: Net
  var chartRange = 1;
  var zoomScale = 1.0;
  var panFraction = 1.0;
  var touchedIdx = -1;
  var fileFilter = "all";
  var fileViewMode = "grid3"; // grid3 | grid6 | list
  var activeModalFile = null;
  var procSortBy = "cpu"; // cpu | mem
  var smartTab = "summary"; // summary | raw
  var liveTab = "record"; // record | watch
  var liveQuality = "best";
  var activeSubUi = ""; // guest | social | performance | lanWhitelist | bandwidth | nasBackup | failedUploads | usbImport | sleepSchedule | download | duplicate | about
  var lastDupModalSig = "";
  var dupVisibleLimit = 25;
  var curPreviewGroup = null;
  var curPreviewFileIdx = 0;
  var curPreviewGroupIdx = 0;
  var pendingDeletePaths = [];

  function thumbnailUrl(apiBase, path, item) {
    if (!apiBase || !path) return "";
    var url = apiBase + "/api/thumb?path=" + encodeURIComponent(path);
    return item && item.mtime > 0 && item.size > 0
      ? url + "&v=" + encodeURIComponent(item.mtime + ":" + item.size) : url;
  }

  function openDupPreview(g, fIdx, gIdx) {
    curPreviewGroup = g;
    curPreviewFileIdx = fIdx;
    curPreviewGroupIdx = gIdx;
    var modal = document.getElementById("dupPreviewModal");
    if (!modal || !g || !g.files || !g.files[fIdx]) return;
    var f = g.files[fIdx];
    var isVid = f.isVideo || isVidFile(f.name);
    var isImg = f.isImage || isImgFile(f.name);
    var apiBase = (state && state.apiBase) ? state.apiBase.replace(/\/+$/, "") : "";
    var rel = f.relPath || "";
    if (!rel && f.path) {
      var p = f.path;
      var wIdx = p.indexOf("/webdav/");
      if (wIdx !== -1) {
        rel = decodeURIComponent(p.substring(wIdx + 8));
      } else {
        rel = p.replace(/^https?:\/\/[^\/]+/, "").replace(/^\/+/, "");
      }
    }
    var mediaUrl = apiBase ? (apiBase + "/api/media?path=" + encodeURIComponent(rel)) : f.path;
    var thumbUrl = thumbnailUrl(apiBase, rel);

    var tEl = document.getElementById("dupPrevTitle");
    if (tEl) tEl.textContent = f.name || "--";
    var fEl = document.getElementById("dupPrevFolder");
    if (fEl) fEl.textContent = "/" + (f.folder || "");
    var sEl = document.getElementById("dupPrevSizeBadge");
    if (sEl) sEl.textContent = fmtBytes(f.size || g.size || 0);

    var typeBadge = document.getElementById("dupPrevTypeBadge");
    if (typeBadge) {
      typeBadge.textContent = isVid ? "🎬 Video" : (isImg ? "📷 Ảnh" : "📁 Tệp");
      typeBadge.style.color = isVid ? "var(--ux-orange)" : "var(--ux-cyan)";
      typeBadge.style.background = isVid ? "rgba(251,146,60,0.15)" : "rgba(34,211,238,0.15)";
    }

    var loadEl = document.getElementById("dupPrevLoading");
    var loadTxt = document.getElementById("dupPrevLoadingTxt");
    var imgEl = document.getElementById("dupPrevImg");
    var vidEl = document.getElementById("dupPrevVid");
    var fbEl = document.getElementById("dupPrevFallback");
    var fbIcon = document.getElementById("dupPrevFallbackIcon");
    var fbMsg = document.getElementById("dupPrevFallbackMsg");
    var fbSub = document.getElementById("dupPrevFallbackSub");
    var fbThumb = document.getElementById("dupPrevFallbackThumb");
    var fbBtn = document.getElementById("dupPrevFallbackActionBtn");

    function showFallback(icon, msg, sub, actionText) {
      if (loadEl) loadEl.classList.add("hidden");
      if (imgEl) { imgEl.classList.add("hidden"); }
      if (vidEl) { vidEl.pause(); vidEl.src = ""; vidEl.classList.add("hidden"); }
      if (fbEl) {
        if (fbIcon) fbIcon.textContent = icon;
        if (fbMsg) fbMsg.textContent = msg;
        if (fbSub) fbSub.textContent = sub;
        if (fbBtn) {
          fbBtn.textContent = actionText || "Mở ngoài";
          fbBtn.onclick = function () { callBridge("openDuplicateMedia", f.path); };
        }
        if (fbThumb) {
          if (thumbUrl) {
            fbThumb.style.backgroundImage = "url('" + thumbUrl + "')";
            fbThumb.classList.remove("hidden");
          } else {
            fbThumb.classList.add("hidden");
          }
        }
        fbEl.classList.remove("hidden");
      }
    }

    if (fbEl) fbEl.classList.add("hidden");
    if (loadEl) {
      if (loadTxt) loadTxt.textContent = "Đang tải xem trước...";
      loadEl.classList.remove("hidden");
    }

    if (isVid) {
      if (imgEl) { imgEl.classList.add("hidden"); imgEl.src = ""; }
      var isInlineWebVid = /\.(mp4|webm)$/i.test(f.name || "");
      if (isInlineWebVid && vidEl) {
        vidEl.classList.remove("hidden");
        vidEl.src = mediaUrl;
        vidEl.onloadeddata = function () {
          if (loadEl) loadEl.classList.add("hidden");
        };
        vidEl.onerror = function () {
          var ext = (f.name || "").split(".").pop().toUpperCase();
          showFallback("🎬", "Không thể phát video " + ext + " trực tiếp", "Định dạng hoặc codec cần bộ giải mã phần cứng Native.", "▶ Phát bằng ExoPlayer / VLC");
        };
        vidEl.load();
        vidEl.play().catch(function () {});
      } else {
        var ext = (f.name || "").split(".").pop().toUpperCase();
        showFallback("🎬", "Video " + (ext || "định dạng nâng cao"), "Định dạng " + ext + " phát mượt mà nhất qua Trình phát Native / VLC.", "▶ Phát Video ngay");
      }
    } else if (isImg) {
      if (vidEl) { vidEl.pause(); vidEl.src = ""; vidEl.classList.add("hidden"); }
      if (imgEl) {
        imgEl.classList.remove("hidden");
        imgEl.onclick = function () { callBridge("openDuplicateMedia", f.path); };
        imgEl.style.cursor = "pointer";
        imgEl.onload = function () {
          if (loadEl) loadEl.classList.add("hidden");
        };
        imgEl.onerror = function () {
          if (thumbUrl && imgEl.src !== thumbUrl) {
            imgEl.src = thumbUrl;
          } else {
            showFallback("📷", "Không thể nạp ảnh trong WebView", "Nhấn nút dưới để mở ảnh bằng trình xem ảnh chuyên dụng.", "🔍 Mở ảnh toàn màn hình");
          }
        };
        imgEl.src = mediaUrl;
      }
    } else {
      showFallback("📁", f.name || "Tệp dữ liệu", "Kích thước: " + fmtBytes(f.size || g.size || 0), "Mở tệp ngoài");
    }

    var cmpBox = document.getElementById("dupPrevGroupFiles");
    if (cmpBox) {
      var chips = "";
      (g.files || []).forEach(function (cf, idx) {
        var active = (idx === fIdx);
        var lbl = (idx === 0) ? "Bản gốc" : ("Bản sao #" + idx);
        chips += '<button type="button" class="btn-pill' + (active ? ' primary' : '') + '" data-cidx="' + idx + '" style="font-size:11px;padding:4px 9px;white-space:nowrap;flex-shrink:0;">' +
          lbl + (active ? ' (Đang xem)' : '') +
        '</button>';
      });
      cmpBox.innerHTML = chips;
      cmpBox.querySelectorAll("button").forEach(function (btn) {
        btn.addEventListener("click", function () {
          var nextIdx = Number(btn.getAttribute("data-cidx"));
          if (!isNaN(nextIdx) && nextIdx !== curPreviewFileIdx) {
            openDupPreview(curPreviewGroup, nextIdx, curPreviewGroupIdx);
          }
        });
      });
    }

    var chkDel = document.getElementById("dupPrevCheckDel");
    if (chkDel) {
      var outChk = document.querySelector('.dup-file-chk[data-gidx="' + gIdx + '"][data-fidx="' + fIdx + '"]');
      chkDel.checked = outChk ? outChk.checked : (fIdx > 0);
      chkDel.onchange = function () {
        if (outChk) {
          outChk.checked = chkDel.checked;
          var subUi = document.getElementById("subUiModal");
          if (subUi) subUi.setAttribute("data-dirty", "1");
          var updateBtn = document.getElementById("dupDelSelBtn");
          if (updateBtn) {
            var cnt = document.querySelectorAll(".dup-file-chk:checked").length;
            updateBtn.textContent = cnt > 0 ? ("Xóa " + cnt + " mục đã chọn") : "Xóa mục đã chọn";
          }
        }
      };
    }

    modal.classList.remove("hidden");
  }

  function closeDupPreview() {
    var modal = document.getElementById("dupPreviewModal");
    if (modal) modal.classList.add("hidden");
    var vidEl = document.getElementById("dupPrevVid");
    if (vidEl) { vidEl.pause(); vidEl.src = ""; vidEl.classList.add("hidden"); }
    var imgEl = document.getElementById("dupPrevImg");
    if (imgEl) { imgEl.src = ""; imgEl.classList.add("hidden"); }
    var fbEl = document.getElementById("dupPrevFallback");
    if (fbEl) fbEl.classList.add("hidden");
    curPreviewGroup = null;
  }

  function openDupConfirmModal(paths, imgCount, vidCount, totalBytes) {
    var modal = document.getElementById("dupConfirmModal");
    var msgEl = document.getElementById("dupConfirmMsg");
    if (!modal || !msgEl) return;
    pendingDeletePaths = paths;
    var details = [];
    if (imgCount > 0) details.push('<b>' + imgCount + ' ảnh</b>');
    if (vidCount > 0) details.push('<b>' + vidCount + ' video</b>');
    var otherCount = paths.length - imgCount - vidCount;
    if (otherCount > 0) details.push('<b>' + otherCount + ' tệp khác</b>');

    msgEl.innerHTML = 'Bạn đang chọn xóa vĩnh viễn <b>' + paths.length + ' tệp</b> trùng lặp (' + details.join(', ') + ').<br>' +
      'Dung lượng dự kiến giải phóng: <b style="color:var(--ux-green);font-size:13.5px;">' + fmtBytes(totalBytes) + '</b>.<br>' +
      'Hành động này sẽ xóa các bản sao trên ổ cứng NAS và không thể hoàn tác.';
    modal.classList.remove("hidden");
  }

  function closeDupConfirmModal() {
    var modal = document.getElementById("dupConfirmModal");
    if (modal) modal.classList.add("hidden");
    pendingDeletePaths = [];
  }

  function openDupAutoCleanModal(totG, totVictims, imgVictims, vidVictims, totalWastedBytes, filterLabel) {
    var modal = document.getElementById("dupAutoCleanModal");
    var msgEl = document.getElementById("dupAutoCleanMsg");
    if (!modal || !msgEl) return;
    var html = '<div style="margin-bottom:8px;">Hệ thống sẽ duyệt qua <b>' + totG + ' nhóm</b> ' + (filterLabel || "tệp") + ' và tự động dọn sạch tất cả các bản sao thừa:</div>' +
      '<div style="background:rgba(255,255,255,0.04);border-radius:8px;padding:8px 10px;margin-bottom:8px;display:flex;flex-direction:column;gap:5px;">' +
        '<div style="display:flex;justify-content:space-between;align-items:center;"><span>Số bản sao thừa sẽ xóa:</span><b style="color:var(--ux-red);font-size:13.5px;">' + totVictims.toLocaleString() + ' tệp</b></div>' +
        '<div style="display:flex;justify-content:space-between;align-items:center;color:var(--ux-green);"><span>Bản gốc an toàn giữ lại:</span><b style="font-size:12px;">' + totG.toLocaleString() + ' tệp gốc</b></div>' +
        '<div style="display:flex;justify-content:space-between;align-items:center;color:var(--ux-cyan);"><span>Sau khi xóa còn lại:</span><b style="font-size:12px;">0 tệp trùng</b></div>' +
        ((imgVictims > 0 || vidVictims > 0) ? ('<div style="font-size:11px;color:var(--ux-text-2);display:flex;gap:12px;border-top:1px dashed rgba(148,163,184,0.15);padding-top:4px;margin-top:2px;"><span>📷 Ảnh: <b>' + imgVictims + '</b></span><span>🎬 Video: <b>' + vidVictims + '</b></span></div>') : '') +
        '<div style="display:flex;justify-content:space-between;align-items:center;border-top:1px dashed rgba(148,163,184,0.15);padding-top:4px;margin-top:2px;"><span>Dung lượng giải phóng:</span><b style="color:var(--ux-green);font-size:13.5px;">' + fmtBytes(totalWastedBytes) + '</b></div>' +
      '</div>';
    msgEl.innerHTML = html;
    modal.classList.remove("hidden");
  }

  function closeDupAutoCleanModal() {
    var modal = document.getElementById("dupAutoCleanModal");
    if (modal) modal.classList.add("hidden");
  }
  var bgTasksExpanded = true;
  var dashLogsExpanded = true;
  var dashLogsFilter = "all";
  var fanCustomExpanded = false;
  var selectMode = false;
  var selectedPaths = {};
  var timelineData = null;
  var timelineLoading = false;
  var lastDashLogsSignature = "";
  var lastChartSignature = "";
  var lastStorageSignature = "";
  var lastBgTasksSignature = "";

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

  function callBridge(fn, arg, arg2, arg3, arg4, arg5) {
    var b = bridge();
    if (!b || typeof b[fn] !== "function") return;
    if (arg5 !== undefined) b[fn](arg, arg2, arg3, arg4, arg5);
    else if (arg4 !== undefined) b[fn](arg, arg2, arg3, arg4);
    else if (arg3 !== undefined) b[fn](arg, arg2, arg3);
    else if (arg2 !== undefined) b[fn](arg, arg2);
    else if (arg !== undefined) b[fn](arg);
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

  var modalCloseButtons = {
    fileActionModal: "modalCancelBtn", processModal: "procCloseBtn",
    smartModal: "smartCloseBtn", toolboxModal: "toolboxCloseBtn",
    autoBackupModal: "abCloseBtn", smbModal: "smbCloseBtn",
    dockerModal: "dockerCloseBtn", logsModal: "logsCloseBtn",
    livestreamModal: "liveCloseBtn", subUiModal: "subUiCloseBtn",
    dupPreviewModal: "dupPrevCloseBtn", dupConfirmModal: "dupConfirmCloseBtn",
    dupAutoCleanModal: "dupAutoCleanCloseBtn"
  };
  var activeDialog = null;
  var dialogOpeners = new WeakMap();

  function openDialogs() {
    return Array.from(document.querySelectorAll(".ux-modal-backdrop:not(.hidden)")).sort(function (a, b) {
      return Number(getComputedStyle(a).zIndex) - Number(getComputedStyle(b).zIndex);
    });
  }

  function syncDialogs() {
    var dialogs = openDialogs();
    var next = dialogs[dialogs.length - 1] || null;
    if (next === activeDialog) return;
    var previous = activeDialog;
    var restore = previous && dialogOpeners.get(previous);
    if (next && !dialogOpeners.has(next)) {
      dialogOpeners.set(next, previous && previous.classList.contains("hidden") ? restore : document.activeElement);
    }
    activeDialog = next;
    document.querySelectorAll(".topbar, section[id^='view-']").forEach(function (section) {
      section.inert = !!next;
    });
    document.querySelectorAll(".ux-modal-backdrop").forEach(function (dialog) {
      dialog.inert = dialog !== next;
      dialog.querySelector(".ux-modal").setAttribute("aria-modal", dialog === next ? "true" : "false");
      if (dialog.classList.contains("hidden")) dialogOpeners.delete(dialog);
    });
    if (restore && restore.isConnected && !restore.closest(".hidden, [inert]") &&
        (!next || next.contains(restore))) {
      restore.focus({preventScroll: true});
    } else if (next) {
      (document.getElementById(modalCloseButtons[next.id]) || next.querySelector(".ux-modal")).focus();
    }
    callBridge("onModalVisibilityChanged", !!next);
  }

  function closeTopModal() {
    var dialogs = openDialogs();
    var next = dialogs[dialogs.length - 1];
    if (!next) return false;
    var close = document.getElementById(modalCloseButtons[next.id]);
    if (close) close.click();
    else next.classList.add("hidden");
    syncDialogs();
    return true;
  }

  function bindDialogNavigation() {
    var observer = new MutationObserver(syncDialogs);
    document.querySelectorAll(".ux-modal-backdrop").forEach(function (backdrop) {
      var dialog = backdrop.querySelector(".ux-modal");
      dialog.setAttribute("role", "dialog");
      dialog.tabIndex = -1;
      var title = dialog.querySelector(".card-title, #modalFileName");
      if (title) {
        if (!title.id) title.id = backdrop.id + "Title";
        dialog.setAttribute("aria-labelledby", title.id);
      }
      var close = document.getElementById(modalCloseButtons[backdrop.id]);
      if (close) close.setAttribute("aria-label", "Đóng hộp thoại");
      observer.observe(backdrop, {attributes: true, attributeFilter: ["class"]});
    });
    document.addEventListener("keydown", function (event) {
      if (event.key === "Escape" && closeTopModal()) {
        event.preventDefault();
        return;
      }
      if (event.key === "Tab" && activeDialog) {
        var stops = Array.from(activeDialog.querySelectorAll("button, input, select, textarea, a[href], [tabindex]"))
          .filter(function (e) { return e.tabIndex >= 0 && !e.disabled && e.getClientRects().length; });
        var index = stops.indexOf(document.activeElement);
        if (!stops.length || index < 0 || (event.shiftKey ? index === 0 : index === stops.length - 1)) {
          event.preventDefault();
          (stops[event.shiftKey ? stops.length - 1 : 0] || activeDialog.querySelector(".ux-modal")).focus();
        }
      }
      var target = event.target.closest("[role='button']");
      if (target && (event.key === "Enter" || event.key === " ")) {
        event.preventDefault();
        if (!event.repeat) target.click();
      }
    });
  }

  function thumbnailStatus(th) {
    var code = String(th.actualStatus || "").split(":")[0];
    if (code === "index_not_ready") return "Đang chờ danh mục tệp";
    if (code === "paused" || th.paused) return "Đang tạm dừng";
    if (code === "scanning" || th.running) return "Đang quét";
    if (code === "idle" || !code) return "Sẵn sàng";
    return "Chưa rõ trạng thái";
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
    if (currentView === "files" && !document.querySelector(".ux-modal-backdrop:not(.hidden)")) return;

    // Storage
    var st = state.storage || {};
    var freshEl = document.getElementById("storageFreshBadge");
    freshEl.textContent = (st.partial ? "Đang cập nhật • " : "") + freshnessText(st.updatedAtMs);
    document.getElementById("storageSummaryLine").textContent =
      "Đã quét " + fmtCount(st.totalFiles) + " tệp • " + (st.totalDirs || 0) + " thư mục • " + fmtBytes(st.totalBytes);

    var stSig = [st.updatedAtMs, st.totalFiles, st.totalDirs, st.totalBytes].join("|");
    if (stSig !== lastStorageSignature) {
      lastStorageSignature = stSig;
      var catDefs = [
        ["image", "Ảnh", COLOR_BLUE],
        ["video", "Video", COLOR_ORANGE],
        ["doc", "Tài liệu", COLOR_GREEN],
        ["audio", "Nhạc", COLOR_PURPLE],
        ["archive", "Nén", COLOR_YELLOW],
        ["other", "Khác", "#94a3b8"]
      ];
      var totalF = Math.max(1, Number(st.totalFiles || 0));
      if (totalF <= 1 && st.byType) {
        var sumF = 0;
        Object.keys(st.byType).forEach(function (k) { sumF += Number((st.byType[k] && st.byType[k].files) || 0); });
        if (sumF > 0) totalF = sumF;
      }
      var catsHtml = "";
      catDefs.forEach(function (def) {
        var item = (st.byType && st.byType[def[0]]) || { files: 0, bytes: 0 };
        var numFiles = Number(item.files || 0);
        // Progress bar thể hiện theo SỐ LƯỢNG FILE (không theo dung lượng)
        var wPct = numFiles > 0 ? Math.max(2, Math.min(100, (numFiles / totalF) * 100)) : 0;
        catsHtml +=
          '<div class="cat-row">' +
            '<div class="cat-name">' + def[1] + '</div>' +
            '<div class="bar-track"><div class="bar-fill" style="width:' + wPct.toFixed(1) + '%;background:' + def[2] + '"></div></div>' +
            '<div class="cat-meta">' + fmtCount(numFiles) + ' • ' + fmtBytes(item.bytes) + '</div>' +
          '</div>';
      });
      document.getElementById("storageCategories").innerHTML = catsHtml;

      var largestHtml = "";
      (st.largest || []).slice(0, 5).forEach(function (f) {
        var name = String(f.path || "").split("/").pop();
        largestHtml += '<div class="file-row"><span class="file-name">' + name + '</span><span class="file-size">' + fmtBytes(f.bytes) + '</span></div>';
      });
      document.getElementById("storageLargest").innerHTML = largestHtml || '<div class="list-sub">Chưa có dữ liệu</div>';
    }

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
    var fanSvg = document.getElementById("fanSvgIco");
    if (fanSvg) {
      var fanRunning = sys.fanStatus && sys.fanStatus !== "Dừng" && sys.fanStatus.toLowerCase().indexOf("dừng") === -1 && sys.fanStatus !== "--" && sys.fanStatus !== "0%";
      fanSvg.classList.toggle("fan-spinning", !!fanRunning);
    }
    var isCustom = sys.fanMode === "custom";
    document.querySelectorAll("[data-fan]").forEach(function (btn) {
      var bMode = btn.getAttribute("data-fan");
      btn.classList.toggle("active", bMode === sys.fanMode);
      if (bMode === "custom") {
        btn.textContent = isCustom ? (fanCustomExpanded ? "Tùy chỉnh ▴" : "Tùy chỉnh ▾") : "Tùy chỉnh";
      }
    });
    var onT = Math.round(Number(sys.fanOnTemp || 42));
    var offT = Math.round(Number(sys.fanOffTemp || 40));
    var ruleHint = document.getElementById("fanRuleHint");
    if (ruleHint) {
      if (sys.fanMode === "auto") {
        ruleHint.textContent = "Tự động (theo HDD): ≥42°C chạy 30% • ≥47°C 60% • ≥52°C 100% • ≤40°C dừng (CPU≥75°C / HDD≥60°C ép 100%)";
        ruleHint.style.cursor = "default";
      } else if (sys.fanMode === "custom") {
        ruleHint.textContent = "Tùy chỉnh (theo HDD): Bật ≥ " + onT + "°C • Tắt ≤ " + offT + "°C (+5°C/+10°C tăng tốc 60%/100%)";
        ruleHint.style.cursor = "pointer";
      } else {
        ruleHint.textContent = "Quạt đang tắt thủ công (chỉ ép chạy nếu quá nhiệt khẩn cấp).";
        ruleHint.style.cursor = "default";
      }
    }
    var customBox = document.getElementById("fanCustomBox");
    if (customBox) {
      if (!isCustom) {
        fanCustomExpanded = false;
        customBox.classList.add("hidden");
      } else {
        customBox.classList.toggle("hidden", !fanCustomExpanded);
        if (fanCustomExpanded && !customBox.getAttribute("data-dirty")) {
          var onSl = document.getElementById("fanOnSlider");
          var offSl = document.getElementById("fanOffSlider");
          if (onSl) { onSl.value = onT; document.getElementById("fanOnVal").textContent = onT + "°C"; }
          if (offSl) { offSl.value = offT; document.getElementById("fanOffVal").textContent = offT + "°C"; }
        }
      }
    }

    // System tab
    var th = state.thumb || {};
    document.getElementById("thumbStatusBadge").textContent = thumbnailStatus(th);
    var scanP = Number(th.scanProgress || 0);
    var scanT = Math.max(1, Number(th.scanTotal || 1));
    var thPct = Math.min(100, (scanP / scanT) * 100);
    document.getElementById("thumbDetailText").textContent =
      "Đã rà soát " + scanP.toLocaleString() + " / " + Number(th.scanTotal || 0).toLocaleString() + " tệp";
    document.getElementById("thumbBar").style.width = thPct.toFixed(1) + "%";
    document.getElementById("backupStatusSub").textContent = state.backupSub || "Chạy nền qua WorkManager Native";
    document.getElementById("sysSmartLine").textContent = smartStatus + " • " + (sys.hddTemp || "--");
    document.getElementById("sysOmvLine").textContent = sys.omvInfo || "OpenMediaVault";
    var al = state.alerts || {};
    var alBox = document.getElementById("alertCard");
    if (alBox && !alBox.getAttribute("data-dirty")) {
      document.getElementById("alertEnabledChk").checked = al.enabled !== false;
      document.getElementById("alertHddInput").value = al.hddTemp || 50;
      document.getElementById("alertCpuInput").value = al.cpuTemp || 90;
      document.getElementById("alertDiskInput").value = al.diskPct || 90;
      document.getElementById("alertSmartChk").checked = al.smart !== false;
    }

    // Sync smooth timer states for background tasks
    var dupForTimer = (state && (state.duplicate || (state.bgTasks && state.bgTasks.dup))) || {};
    syncDupTimer(dupForTimer);
    var bakForTimer = (state && state.bgTasks && state.bgTasks.backup) || {};
    syncBakTimer(bakForTimer);
    var liveJobsForTimer = (state && state.livestream && state.livestream.jobs) || [];
    syncLiveJobTimers(liveJobsForTimer);

    // Background tasks panel on Dashboard
    renderBgTasks();
    renderDashLogs();

    // More tab
    document.getElementById("moreUser").textContent = state.user || "admin";
    document.getElementById("moreUrl").textContent = (state.baseUrl || "") + " (" + (state.connLabel || "") + ")";
    document.getElementById("moreVersion").textContent = "Phiên bản " + (state.versionName || "1.0");

    if (!document.getElementById("processModal").classList.contains("hidden")) {
      renderProcesses();
    }
    if (!document.getElementById("smartModal").classList.contains("hidden")) {
      renderSmartModal();
    }
    if (!document.getElementById("toolboxModal").classList.contains("hidden")) {
      renderToolboxModal();
    }
    if (!document.getElementById("autoBackupModal").classList.contains("hidden")) {
      renderAutoBackupModal();
    }
    if (!document.getElementById("smbModal").classList.contains("hidden")) {
      renderSmbModal();
    }
    if (!document.getElementById("dockerModal").classList.contains("hidden")) {
      renderDockerModal();
    }
    if (!document.getElementById("logsModal").classList.contains("hidden")) {
      renderLogsModal();
    }
    if (!document.getElementById("livestreamModal").classList.contains("hidden")) {
      renderLivestreamModal();
    }
    if (!document.getElementById("subUiModal").classList.contains("hidden")) {
      renderSubUiModal(true);
    }

    // Range & tab UI
    if (state && typeof state.metricsHours === "number" && (state.metricsHours === 1 || state.metricsHours === 6 || state.metricsHours === 24)) {
      chartRange = state.metricsHours;
    }
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

  function syncBatchBar(itemsMap) {
    var keys = Object.keys(selectedPaths);
    var bar = document.getElementById("batchActionBar");
    var selBtn = document.getElementById("fileSelectBtn");
    if (selBtn) selBtn.classList.toggle("active", selectMode);
    if (!bar) return;
    bar.classList.toggle("hidden", !selectMode || keys.length === 0);
    document.getElementById("batchCountText").textContent = keys.length + " mục";
    var shareBtn = document.getElementById("batchShareBtn");
    if (shareBtn) {
      var singleItem = keys.length === 1 ? itemsMap[keys[0]] : null;
      shareBtn.disabled = !(singleItem && !singleItem.isDir);
    }
  }

  function renderTimeline(container, apiBase) {
    document.getElementById("filesPathTitle").textContent = "Dòng thời gian Ảnh";
    if (timelineLoading) {
      document.getElementById("filesCountBadge").textContent = "...";
      container.className = "files-center-state";
      container.innerHTML = '<div class="ux-spinner"></div><div style="font-size:13px;color:var(--ux-text-2);font-weight:600;">Đang tải dòng thời gian ảnh...</div>';
      return;
    }
    var items = (timelineData && timelineData.items) || [];
    document.getElementById("filesCountBadge").textContent = String((timelineData && timelineData.total) || items.length);
    if (items.length === 0) {
      container.className = "files-center-state";
      container.innerHTML = '<div style="font-size:13px;color:var(--ux-text-2);">Chưa có ảnh nào trong chỉ mục NAS.</div>';
      return;
    }
    var groups = {};
    var order = [];
    items.forEach(function (it) {
      var d = it.mtime ? new Date(it.mtime * 1000) : null;
      var label = (d && !isNaN(d.getTime()))
        ? ("Tháng " + String(d.getMonth() + 1).padStart(2, "0") + " / " + d.getFullYear())
        : "Khác";
      if (!groups[label]) { groups[label] = []; order.push(label); }
      groups[label].push(it);
    });
    container.className = "";
    var html = "";
    currentRenderedFiles = items;
    order.forEach(function (label) {
      html += '<div class="timeline-group-title">' + label + ' (' + groups[label].length + ')</div><div class="files-grid">';
      groups[label].forEach(function (it) {
        var idx = items.indexOf(it);
        var thumbUrl = thumbnailUrl(apiBase, it.relPath);
        var safeName = String(it.name || "").replace(/</g, "&lt;");
        html += '<div class="f-tile" data-fidx="' + idx + '" data-tlpath="' + String(it.path || "").replace(/"/g, "&quot;") + '">' +
          '<div class="f-thumb"><img loading="lazy" draggable="false" style="pointer-events:none;-webkit-user-drag:none;" src="' + thumbUrl + '" /></div>' +
          '<div class="f-name">' + safeName + '</div>' +
        '</div>';
      });
      html += '</div>';
    });
    container.innerHTML = html;
    container.querySelectorAll("[data-tlpath]").forEach(function (el) {
      el.addEventListener("click", function () {
        callBridge("openFile", el.getAttribute("data-tlpath"));
      });
    });
  }

  var lastFilesRenderSig = "";
  var currentRenderedFiles = [];

  function updateViewToggleUI() {
    var ico = document.getElementById("fileViewToggleIco");
    var txt = document.getElementById("fileViewToggleText");
    if (!ico || !txt) return;
    if (fileViewMode === "grid3") {
      ico.innerHTML = '<rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/>';
      txt.textContent = "Lưới lớn";
    } else if (fileViewMode === "grid6") {
      ico.innerHTML = '<rect x="3" y="3" width="4" height="4"/><rect x="10" y="3" width="4" height="4"/><rect x="17" y="3" width="4" height="4"/><rect x="3" y="10" width="4" height="4"/><rect x="10" y="10" width="4" height="4"/><rect x="17" y="10" width="4" height="4"/><rect x="3" y="17" width="4" height="4"/><rect x="10" y="17" width="4" height="4"/><rect x="17" y="17" width="4" height="4"/>';
      txt.textContent = "Lưới nhỏ";
    } else {
      ico.innerHTML = '<line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/><line x1="3" y1="6" x2="3.01" y2="6"/><line x1="3" y1="12" x2="3.01" y2="12"/><line x1="3" y1="18" x2="3.01" y2="18"/>';
      txt.textContent = "Danh sách";
    }
  }

  function renderFiles() {
    updateViewToggleUI();
    var fState = activeFileSnapshot() || { loading: true };
    var container = document.getElementById("filesContainer");
    if (!container) return;
    var apiBase = (state && state.apiBase) || "";
    if (fileFilter === "timeline") {
      lastFilesRenderSig = "";
      renderTimeline(container, apiBase);
      syncBatchBar({});
      return;
    }
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
      lastFilesRenderSig = "";
      container.className = "files-center-state";
      container.innerHTML = '<div class="ux-spinner"></div><div style="font-size:13px;color:var(--ux-text-2);font-weight:600;">Đang tải danh sách tệp từ NAS...</div>';
      return;
    }
    if (filtered.length === 0) {
      lastFilesRenderSig = "";
      container.className = "files-center-state";
      container.innerHTML = '<div style="font-size:13px;color:var(--ux-text-2);">Không có tệp nào phù hợp.</div>';
      return;
    }

    var selKeys = Object.keys(selectedPaths).sort().join(",");
    var fSig = [apiBase, fileRowsRevision, fileFilter, fileViewMode, q, selectMode ? 1 : 0, selKeys].join("::");
    currentRenderedFiles = filtered;
    if (fSig === lastFilesRenderSig && container.children.length > 0) {
      return;
    }
    lastFilesRenderSig = fSig;

    var isList = fileViewMode === "list";
    container.className = isList ? "files-list" : ("files-grid" + (fileViewMode === "grid6" ? " cols-6" : ""));

    var itemsMap = {};
    var html = "";
    filtered.forEach(function (f, idx) {
      itemsMap[f.path] = f;
      var isSel = !!selectedPaths[f.path];
      var selBadge = selectMode ? ('<span class="sel-check">' + (isSel ? "✓" : "") + '</span>') : "";
      var thumbUrl = !f.isDir && (isImgFile(f.name) || isVidFile(f.name))
        ? thumbnailUrl(apiBase, f.relPath, f) : "";
      var icoSvg = f.isDir
        ? '<svg class="svg-ico" style="width:26px;height:26px;color:var(--ux-cyan);" viewBox="0 0 24 24"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/></svg>'
        : isVidFile(f.name)
          ? '<svg class="svg-ico" style="width:24px;height:24px;color:var(--ux-purple);" viewBox="0 0 24 24"><rect x="2" y="5" width="15" height="14" rx="2"/><polygon points="22 7 17 12 22 17 22 7"/></svg>'
          : '<svg class="svg-ico" style="width:24px;height:24px;color:var(--ux-text-2);" viewBox="0 0 24 24"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>';
      var firstRow = isList ? 1 : (fileViewMode === "grid6" ? 6 : 3);
      var thumbHtml = icoSvg + (thumbUrl ? ('<img loading="' + (idx < firstRow ? 'eager' : 'lazy') + '" decoding="async" alt="" draggable="false" src="' + thumbUrl + '" onerror="this.remove()" />') : "");
      var safeName = String(f.name || "").replace(/</g, "&lt;");
      var meta = f.isDir ? "Thư mục" : fmtBytes(f.size);
      if (isList) {
        html += '<div class="f-row' + (isSel ? ' selected' : '') + '" data-fidx="' + idx + '">' +
          '<div class="f-row-thumb">' + thumbHtml + '</div>' +
          '<div style="flex:1;min-width:0;"><div class="list-title" style="white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">' + safeName + '</div><div class="list-sub">' + meta + '</div></div>' +
          (selectMode ? ('<span class="sel-check" style="position:static;">' + (isSel ? "✓" : "") + '</span>') : ('<button class="btn-icon" data-fmenu="' + idx + '">⋮</button>')) +
        '</div>';
      } else {
        html += '<div class="f-tile' + (isSel ? ' selected' : '') + '" data-fidx="' + idx + '">' +
          selBadge +
          '<div class="f-thumb">' + thumbHtml + '</div>' +
          '<div class="f-name">' + safeName + '</div>' +
          (fileViewMode === "grid3" ? ('<div class="f-meta">' + meta + '</div>') : "") +
        '</div>';
      }
    });
    container.innerHTML = html;
    syncBatchBar(itemsMap);

    if (!container.getAttribute("data-files-delegated")) {
      container.setAttribute("data-files-delegated", "1");
      var lpTimer = null;
      var lpStartX = 0;
      var lpStartY = 0;
      var lpFired = false;

      container.addEventListener("touchstart", function (ev) {
        if (!ev.touches || ev.touches.length !== 1) return;
        var row = ev.target && ev.target.closest ? ev.target.closest("[data-fidx]") : null;
        if (!row) return;
        lpFired = false;
        lpStartX = ev.touches[0].clientX;
        lpStartY = ev.touches[0].clientY;
        var idx = Number(row.getAttribute("data-fidx"));
        if (lpTimer) clearTimeout(lpTimer);
        lpTimer = setTimeout(function () {
          lpTimer = null;
          var item = currentRenderedFiles[idx];
          if (item) {
            lpFired = true;
            if (navigator.vibrate) try { navigator.vibrate(35); } catch (_) {}
            openFileModal(item);
          }
        }, 420);
      }, { passive: true });

      container.addEventListener("touchmove", function (ev) {
        if (!lpTimer || !ev.touches || !ev.touches[0]) return;
        var dx = Math.abs(ev.touches[0].clientX - lpStartX);
        var dy = Math.abs(ev.touches[0].clientY - lpStartY);
        if (dx > 28 || dy > 28) {
          clearTimeout(lpTimer);
          lpTimer = null;
        }
      }, { passive: true });

      var clearLp = function () {
        if (lpTimer) { clearTimeout(lpTimer); lpTimer = null; }
      };
      container.addEventListener("touchend", clearLp, { passive: true });
      container.addEventListener("touchcancel", clearLp, { passive: true });

      container.addEventListener("contextmenu", function (ev) {
        var row = ev.target && ev.target.closest ? ev.target.closest("[data-fidx]") : null;
        if (!row) return;
        ev.preventDefault();
        clearLp();
        var item = currentRenderedFiles[Number(row.getAttribute("data-fidx"))];
        if (item) {
          lpFired = true;
          openFileModal(item);
        }
      });

      container.addEventListener("click", function (ev) {
        if (lpFired) { lpFired = false; ev.preventDefault(); ev.stopPropagation(); return; }
        var menuBtn = ev.target && ev.target.closest ? ev.target.closest("[data-fmenu]") : null;
        if (menuBtn) {
          ev.stopPropagation();
          var mItem = currentRenderedFiles[Number(menuBtn.getAttribute("data-fmenu"))];
          if (mItem) openFileModal(mItem);
          return;
        }
        var row = ev.target && ev.target.closest ? ev.target.closest("[data-fidx]") : null;
        if (!row) return;
        var item = currentRenderedFiles[Number(row.getAttribute("data-fidx"))];
        if (!item) return;
        if (selectMode) {
          if (selectedPaths[item.path]) delete selectedPaths[item.path];
          else selectedPaths[item.path] = true;
          renderFiles();
        } else {
          callBridge("openFile", item.path);
        }
      });
    }
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
      html += '<div class="list-item" style="padding:9px 0;">' +
        '<div style="flex:1;min-width:0;"><div class="list-title" style="font-size:12.5px;color:var(--ux-text-2);">' + r[0] + '</div></div>' +
        '<div style="font-size:12.5px;font-weight:700;text-align:right;font-variant-numeric:tabular-nums;">' + r[1] + '</div>' +
      '</div>';
    });
    if (sumEl) sumEl.innerHTML = html;

    if (logEl) {
      var attrHtml = "";
      String(raw).split(/\r?\n/).forEach(function (line) {
        var m = line.match(/^\s*(\d+)\s+(\S+)\s+0x[0-9a-fA-F]+\s+(\d+)\s+(\d+)\s+(\d+)\s+\S+\s+\S+\s+\S+\s+(.+)$/);
        if (m) {
          var attrName = m[2].replace(/_/g, " ");
          attrHtml += '<div class="list-item" style="padding:8px 0;">' +
            '<div style="flex:1;min-width:0;">' +
              '<div class="list-title" style="font-size:12.5px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">' +
                attrName + ' <span style="color:var(--ux-text-3);font-size:10.5px;">#' + m[1] + '</span></div>' +
              '<div class="list-sub" style="font-variant-numeric:tabular-nums;">Giá trị: <b>' + m[3] + '</b> • Tệ nhất: <b>' + m[4] + '</b> • Ngưỡng: <b>' + m[5] + '</b></div>' +
            '</div>' +
            '<span class="badge" style="font-variant-numeric:tabular-nums;max-width:44%;overflow:hidden;text-overflow:ellipsis;">' + m[6].trim().replace(/</g, "&lt;") + '</span>' +
          '</div>';
        }
      });
      if (attrHtml) {
        logEl.innerHTML = attrHtml;
      } else {
        logEl.innerHTML = '<pre style="margin:0;padding:8px 0;font-size:11px;line-height:1.5;color:var(--ux-text-2);white-space:pre-wrap;word-break:break-word;">' +
          String(raw || "Chưa có nhật ký S.M.A.R.T.").replace(/</g, "&lt;") + '</pre>';
      }
    }
  }

  function openToolboxModal() {
    renderToolboxModal();
    document.getElementById("toolboxModal").classList.remove("hidden");
  }
  function renderToolboxModal() {
    var tb = (state && state.toolbox) || {};
    var bio = document.getElementById("tbBioChk");
    if (bio) bio.checked = !!tb.biometric;
  }

  function backupModeLabel(mode) {
    if (mode === "image") return "Chỉ ảnh";
    if (mode === "video") return "Chỉ video";
    return "Ảnh & Video";
  }

  function fmtDurSec(sec) {
    sec = Math.max(0, Math.floor(sec || 0));
    var h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
    return h > 0 ? (h + "h " + (m < 10 ? "0" : "") + m + "m " + (s < 10 ? "0" : "") + s + "s") : (m + "m " + (s < 10 ? "0" : "") + s + "s");
  }

  function fmtEtaSec(sec) {
    if (sec === undefined || sec === null || sec < 0) return "Đang tính...";
    if (sec === 0) return "Hoàn tất";
    return "~" + fmtDurSec(sec);
  }

  // ═══ SMOOTH 1-SECOND CLIENT CLOCK LOOP (Chống giật cục thời gian) ═══
  var dupTimerState = {
    active: false,
    baseElapsedSec: 0,
    baseEtaSec: -1,
    syncedAtMs: 0
  };
  var bakTimerState = {
    active: false,
    baseElapsedSec: 0,
    baseEtaSec: -1,
    syncedAtMs: 0
  };

  function syncDupTimer(dup) {
    if (!dup) return;
    var isRunning = !!(dup.scanning || dup.running || dup.active);
    if (!isRunning) {
      dupTimerState.active = false;
      dupTimerState.baseElapsedSec = 0;
      dupTimerState.baseEtaSec = -1;
      dupTimerState.syncedAtMs = 0;
      return;
    }
    var sElapsed = Math.max(0, Math.floor((dup.elapsedMs || 0) / 1000));
    var sEta = (dup.etaMs && dup.etaMs > 0) ? Math.floor(dup.etaMs / 1000) : -1;
    var now = Date.now();
    if (!dupTimerState.active || dupTimerState.baseElapsedSec <= 0) {
      dupTimerState.active = true;
      dupTimerState.baseElapsedSec = sElapsed;
      dupTimerState.baseEtaSec = sEta;
      dupTimerState.syncedAtMs = now;
      return;
    }
    // STRICTLY MONOTONIC: Time can NEVER decrease
    var curDisplayed = getLiveDupTime().elapsedSec;
    dupTimerState.baseElapsedSec = Math.max(curDisplayed, sElapsed);
    dupTimerState.syncedAtMs = now;
    if (sEta > 0) {
      dupTimerState.baseEtaSec = dupTimerState.baseEtaSec <= 0 ? sEta : Math.round((dupTimerState.baseEtaSec * 3 + sEta) / 4);
    }
  }

  function getLiveDupTime() {
    if (!dupTimerState.active || !dupTimerState.syncedAtMs) {
      return { elapsedSec: 0, etaSec: -1 };
    }
    var deltaSec = Math.max(0, Math.floor((Date.now() - dupTimerState.syncedAtMs) / 1000));
    var curElapsed = dupTimerState.baseElapsedSec + deltaSec;
    var curEta = dupTimerState.baseEtaSec > 0 ? Math.max(0, dupTimerState.baseEtaSec - deltaSec) : dupTimerState.baseEtaSec;
    return { elapsedSec: curElapsed, etaSec: curEta };
  }

  function syncBakTimer(bak) {
    if (!bak) return;
    var isRunning = !!bak.active;
    if (!isRunning) {
      bakTimerState.active = false;
      bakTimerState.baseElapsedSec = 0;
      bakTimerState.baseEtaSec = -1;
      bakTimerState.syncedAtMs = 0;
      abClientStartMs = 0;
      return;
    }
    var now = Date.now();
    if (!abClientStartMs) abClientStartMs = now;
    var sEta = (bak.etaSeconds && bak.etaSeconds > 0) ? Math.floor(bak.etaSeconds) : -1;
    if (!bakTimerState.active || bakTimerState.baseElapsedSec <= 0) {
      bakTimerState.active = true;
      bakTimerState.baseElapsedSec = Math.max(0, Math.floor((now - abClientStartMs) / 1000));
      bakTimerState.baseEtaSec = sEta;
      bakTimerState.syncedAtMs = now;
      return;
    }
    var curDisplayed = getLiveBakTime().elapsedSec;
    var calcElapsed = Math.max(0, Math.floor((now - abClientStartMs) / 1000));
    bakTimerState.baseElapsedSec = Math.max(curDisplayed, calcElapsed);
    bakTimerState.syncedAtMs = now;
    if (sEta > 0) {
      bakTimerState.baseEtaSec = bakTimerState.baseEtaSec <= 0 ? sEta : Math.round((bakTimerState.baseEtaSec * 3 + sEta) / 4);
    }
  }

  function getLiveBakTime() {
    if (!bakTimerState.active || !bakTimerState.syncedAtMs) {
      return { elapsedSec: 0, etaSec: -1 };
    }
    var deltaSec = Math.max(0, Math.floor((Date.now() - bakTimerState.syncedAtMs) / 1000));
    var curElapsed = bakTimerState.baseElapsedSec + deltaSec;
    var curEta = bakTimerState.baseEtaSec > 0 ? Math.max(0, bakTimerState.baseEtaSec - deltaSec) : bakTimerState.baseEtaSec;
    return { elapsedSec: curElapsed, etaSec: curEta };
  }

  var liveTimerMap = {}; // jobId -> { baseDurationSec: number, syncedAtMs: number }
  var lastLiveModalJobSig = "";

  function syncLiveJobTimers(jobs) {
    if (!Array.isArray(jobs)) return;
    var activeIds = {};
    var now = Date.now();
    jobs.forEach(function (j) {
      if (!j || !j.jobId) return;
      var jId = String(j.jobId);
      activeIds[jId] = true;
      var curSec = Math.max(0, Math.floor(Number(j.duration || 0)));
      var prev = liveTimerMap[jId];
      if (!prev) {
        liveTimerMap[jId] = { baseDurationSec: curSec, syncedAtMs: now };
      } else {
        var clientSec = prev.baseDurationSec + Math.max(0, Math.floor((now - prev.syncedAtMs) / 1000));
        // STRICTLY MONOTONIC: Time can NEVER decrease
        liveTimerMap[jId] = {
          baseDurationSec: Math.max(clientSec, curSec),
          syncedAtMs: now
        };
      }
    });
    Object.keys(liveTimerMap).forEach(function (id) {
      if (!activeIds[id]) delete liveTimerMap[id];
    });
  }

  function getLiveJobDurationSec(jobId, fallbackSec) {
    var t = liveTimerMap[String(jobId)];
    if (!t) return Number(fallbackSec || 0);
    var delta = Math.max(0, Math.floor((Date.now() - t.syncedAtMs) / 1000));
    return t.baseDurationSec + delta;
  }

  function fmtLiveModalDur(s) {
    var sec = Math.max(0, Math.floor(s || 0));
    return Math.floor(sec / 3600) + "h" + String(Math.floor((sec % 3600) / 60)).padStart(2, "0") + "m" + String(sec % 60).padStart(2, "0") + "s";
  }

  function tickSmoothClocks() {
    if (dupTimerState.active) {
      var dt = getLiveDupTime();
      var elDupElap = document.getElementById("dupModalElapsed");
      var elDupEta = document.getElementById("dupModalEta");
      if (elDupElap) elDupElap.textContent = fmtDurSec(dt.elapsedSec);
      if (elDupEta) elDupEta.textContent = fmtEtaSec(dt.etaSec);

      var elBgDupTime = document.getElementById("bgDupTimeTxt");
      if (elBgDupTime) {
        elBgDupTime.textContent = ' • ⏱ ' + fmtDurSec(dt.elapsedSec) + ' / còn ' + fmtEtaSec(dt.etaSec);
      }
    }

    if (bakTimerState.active) {
      var bt = getLiveBakTime();
      var elAbElap = document.getElementById("abLiveElapsed");
      var elAbEta = document.getElementById("abLiveEta");
      if (elAbElap) elAbElap.textContent = fmtDurSec(bt.elapsedSec);
      if (elAbEta) elAbEta.textContent = fmtEtaSec(bt.etaSec);

      var elBgBakTime = document.getElementById("bgBakTimeTxt");
      if (elBgBakTime) {
        elBgBakTime.textContent = ' • ⏱ ' + fmtDurSec(bt.elapsedSec) + ' / còn ' + fmtEtaSec(bt.etaSec);
      }
    }

    // Smooth 1-second ticks for all active Livestream jobs
    document.querySelectorAll(".live-modal-time").forEach(function (el) {
      var jId = el.getAttribute("data-job-id");
      if (jId) el.textContent = fmtLiveModalDur(getLiveJobDurationSec(jId));
    });
    document.querySelectorAll(".bg-live-time").forEach(function (el) {
      var jId = el.getAttribute("data-job-id");
      if (jId) el.textContent = fmtDurSec(getLiveJobDurationSec(jId));
    });
  }

  var smoothClockIntervalId = null;
  function startSmoothClockLoop() {
    if (!smoothClockIntervalId) {
      smoothClockIntervalId = setInterval(tickSmoothClocks, 1000);
    }
  }

  function getSelectedBackupMode() {
    var m = document.getElementById("autoBackupModal");
    var v = m && m.getAttribute("data-mode");
    if (v === "image" || v === "video" || v === "both") return v;
    var tb = (state && state.toolbox) || {};
    return tb.backupMode || "both";
  }

  function applyBackupModeButtons(mode) {
    var m = document.getElementById("autoBackupModal");
    if (m) m.setAttribute("data-mode", mode);
    document.querySelectorAll("#abModeGroup .ab-mode-btn").forEach(function (btn) {
      btn.classList.toggle("active", btn.getAttribute("data-mode") === mode);
    });
  }

  function openAutoBackupModal() {
    var m = document.getElementById("autoBackupModal");
    if (m) m.removeAttribute("data-dirty");
    callBridge("refreshBackupInfo");
    renderAutoBackupModal();
    m.classList.remove("hidden");
  }
  var abClientStartMs = 0;

  function getAbElapsedSec(bak) {
    if (!bak || !bak.active) { abClientStartMs = 0; return 0; }
    if (!abClientStartMs) abClientStartMs = Date.now();
    return Math.max(0, Math.floor((Date.now() - abClientStartMs) / 1000));
  }

  function renderAutoBackupModal() {
    var tb = (state && state.toolbox) || {};
    var bak = (state && state.bgTasks && state.bgTasks.backup) || {};
    var running = !!(tb.autoBackupRunning || bak.active);
    var m = document.getElementById("autoBackupModal");
    var mode = (m && m.getAttribute("data-dirty")) ? getSelectedBackupMode() : (tb.backupMode || bak.backupMode || "both");
    applyBackupModeButtons(mode);
    if (m && !m.getAttribute("data-dirty")) {
      document.getElementById("abEnabledChk").checked = !!tb.autoBackup;
      document.getElementById("abDeleteChk").checked = !!tb.deleteAfter;
    }

    // Thông tin thiết bị & bộ nhớ điện thoại (2x1 Gauge Grid chuẩn HTML5)
    var devName = tb.deviceName || bak.deviceName || "Android";
    var devId = tb.deviceId || bak.deviceId || "";
    var shortId = devId ? (" • #" + String(devId).slice(0, 6)) : "";
    var pFree = tb.phoneFreeBytes || bak.phoneFreeBytes || 0;
    var pTotal = tb.phoneTotalBytes || bak.phoneTotalBytes || 0;
    var freedSess = bak.freedSessionBytes || tb.freedSessionBytes || 0;
    var freedTot = Math.max(bak.freedTotalBytes || 0, tb.freedTotalBytes || 0);
    var devEl = document.getElementById("abDeviceLine");
    var stgTxt = document.getElementById("abStorageText");
    var stgBar = document.getElementById("abStorageBar");
    var frSessEl = document.getElementById("abFreedSessionLine");
    var frTotEl = document.getElementById("abFreedTotalLine");
    var frBar = document.getElementById("abFreedBar");
    if (stgTxt) stgTxt.textContent = pTotal > 0 ? (fmtBytes(pFree) + " trống") : "--";
    if (devEl) devEl.textContent = (pTotal > 0 ? ("Tổng " + fmtBytes(pTotal) + " • ") : "") + devName + shortId;
    if (stgBar) {
      var usedPct = pTotal > 0 ? Math.min(100, Math.max(0, ((pTotal - pFree) * 100) / pTotal)) : 0;
      stgBar.style.width = usedPct.toFixed(1) + "%";
      stgBar.style.background = usedPct >= 90 ? "var(--ux-red)" : (usedPct >= 75 ? "var(--ux-yellow)" : "var(--ux-cyan)");
    }
    if (frTotEl) frTotEl.textContent = fmtBytes(freedTot);
    if (frSessEl) frSessEl.textContent = "Phiên này: " + fmtBytes(freedSess);
    if (frBar) {
      var frPct = freedTot > 0 ? Math.min(100, Math.max(8, (freedSess > 0 ? ((freedSess * 100) / freedTot) : 100))) : 0;
      frBar.style.width = frPct.toFixed(1) + "%";
    }

    var stDot = document.getElementById("abStatusDot");
    var stTxt = document.getElementById("abStatusText");
    var qPos = bak.queuePosition || 0;
    if (stDot) {
      stDot.classList.toggle("warn", !!(running && (bak.isPaused || qPos > 0)));
    }
    if (stTxt) {
      stTxt.textContent = running
        ? (bak.isPaused ? "Tạm dừng" : (qPos > 0 ? ("Xếp hàng #" + qPos) : ("Đang chạy • " + backupModeLabel(mode))))
        : (tb.autoBackup ? ("Định kỳ 12h • " + backupModeLabel(mode)) : ("Sẵn sàng • " + backupModeLabel(mode)));
    }

    var activeMode = mode || bak.backupMode || "both";
    var img = bak.images || {};
    var vid = bak.videos || {};
    var pBox = document.getElementById("abProgressBox");
    if (pBox) {
      pBox.classList.toggle("hidden", !running);
      if (running) {
        var fEl = document.getElementById("abFileLine");
        var sEl = document.getElementById("abSpeedBadge");
        var pathEl = document.getElementById("abPathLine");
        var fillEl = document.getElementById("abProgFill");
        var cntEl = document.getElementById("abCountLine");
        var pctEl = document.getElementById("abPctLine");
        var totBox = document.getElementById("abTotalSummaryBox");

        var modeFile = activeMode === "image"
          ? (img.currentFile || "Đang đồng bộ ảnh...")
          : (activeMode === "video"
            ? (vid.currentFile || "Đang đồng bộ video...")
            : (bak.currentFile || tb.autoBackupFile || "Đang quét tệp..."));
        var modeSpd = activeMode === "image"
          ? (img.speedBps || 0)
          : (activeMode === "video" ? (vid.speedBps || 0) : (bak.speedBps || 0));

        if (fEl) fEl.textContent = "Tệp: " + modeFile;
        if (sEl) sEl.textContent = modeSpd > 0 ? (fmtBytes(modeSpd) + "/s") : "Đang đối chiếu...";
        if (pathEl) {
          var src = (bak.sourcePath || "").split("0/").pop();
          var dst = (bak.destPath || "").split("/webdav/").pop();
          pathEl.textContent = (src ? ("Từ: /" + src.replace(/^\/+|\/+$/g, "")) : "") + (dst ? (" → /" + dst.replace(/^\/+|\/+$/g, "")) : "");
        }

        // Chỉ hiện thanh Tổng hợp khi chạy song song Cả hai (both)
        if (totBox) totBox.classList.toggle("hidden", activeMode !== "both");
        var totalProc = Math.min(bak.processed || 0, bak.total || 0);
        var pct = bak.total > 0 ? Math.min(100, (totalProc * 100) / bak.total) : Math.min(100, Math.max(0, (bak.progress || 0) * 100));
        if (fillEl) fillEl.style.width = pct.toFixed(1) + "%";
        var bt = getLiveBakTime();
        if (cntEl) cntEl.innerHTML = "Tổng: " + totalProc + " / " + (bak.total || 0) + ' tệp • ⏱ Đã chạy <b id="abLiveElapsed">' + fmtDurSec(bt.elapsedSec) + '</b> • Còn lại <b id="abLiveEta">' + fmtEtaSec(bt.etaSec) + '</b>';
        if (pctEl) pctEl.textContent = pct.toFixed(1) + "%";
        var totBytesEl = document.getElementById("abTotalBytesLine");
        if (totBytesEl) {
          var tUp = bak.bytesWritten || 0;
          var tTot = bak.bytesTotal || 0;
          var tRem = bak.bytesRemaining != null ? bak.bytesRemaining : Math.max(0, tTot - tUp);
          totBytesEl.textContent = "Đã tải: " + fmtBytes(tUp) + " • Còn lại: " + fmtBytes(tRem) + " / Tổng: " + fmtBytes(tTot);
        }

        // Cập nhật Pipeline Ảnh (Photo Stream)
        var imgEl = document.getElementById("abImageRow");
        if (imgEl) {
          imgEl.classList.toggle("hidden", activeMode === "video");
          var imgFileEl = document.getElementById("abImageFile");
          var imgSpdEl = document.getElementById("abImageSpeed");
          var imgFillEl = document.getElementById("abImageProgFill");
          var imgCntEl = document.getElementById("abImageCount");
          var imgPctEl = document.getElementById("abImagePct");
          var imgBytesEl = document.getElementById("abImageBytesLine");
          var imgProc = Math.min(img.processed || 0, img.total || 0);
          var imgPct = img.total > 0 ? Math.min(100, (imgProc * 100) / img.total) : Math.min(100, Math.max(0, (img.progress || 0) * 100));
          if (imgFileEl) imgFileEl.textContent = "Ảnh: " + (img.currentFile || "Đang đợi...");
          if (imgSpdEl) imgSpdEl.textContent = img.speedBps > 0 ? (fmtBytes(img.speedBps) + "/s") : "--";
          if (imgFillEl) imgFillEl.style.width = imgPct.toFixed(1) + "%";
          if (imgCntEl) imgCntEl.textContent = imgProc + " / " + (img.total || 0) + " ảnh • Còn " + fmtEtaSec(img.etaSeconds);
          if (imgPctEl) imgPctEl.textContent = imgPct.toFixed(1) + "%";
          if (imgBytesEl) {
            var iUp = img.bytesUploaded || 0;
            var iTot = img.bytesTotal || 0;
            var iRem = img.bytesRemaining != null ? img.bytesRemaining : Math.max(0, iTot - iUp);
            imgBytesEl.textContent = "Đã tải: " + fmtBytes(iUp) + " • Còn lại: " + fmtBytes(iRem) + " / Tổng: " + fmtBytes(iTot);
          }
        }

        // Cập nhật Pipeline Video (Video Direct)
        var vidEl = document.getElementById("abVideoRow");
        if (vidEl) {
          vidEl.classList.toggle("hidden", activeMode === "image");
          var vidFileEl = document.getElementById("abVideoFile");
          var vidSpdEl = document.getElementById("abVideoSpeed");
          var vidSizeEl = document.getElementById("abVideoFileSize");
          var vidFilePctEl = document.getElementById("abVideoFilePct");
          var vidFileFillEl = document.getElementById("abVideoFileProgFill");
          var vidFillEl = document.getElementById("abVideoProgFill");
          var vidCntEl = document.getElementById("abVideoCount");
          var vidPctEl = document.getElementById("abVideoPct");
          var vidBytesEl = document.getElementById("abVideoBytesLine");

          var vidProc = Math.min(vid.processed || 0, vid.total || 0);
          var vUp = vid.bytesUploaded || 0;
          var vTot = vid.bytesTotalPending || 0;
          var vidPct = vTot > 0
            ? Math.min(100, Math.max(0, (vUp * 100) / vTot))
            : (vid.total > 0 ? Math.min(100, (vidProc * 100) / vid.total) : Math.min(100, Math.max(0, (vid.progress || 0) * 100)));
          if (vidFileEl) vidFileEl.textContent = "Video: " + (vid.currentFile || "Đang đợi...");
          if (vidSpdEl) vidSpdEl.textContent = vid.speedBps > 0 ? (fmtBytes(vid.speedBps) + "/s") : "--";

          // Tiến trình file video đang tải
          var bTotal = vid.bytesTotal || 0;
          var bWritten = vid.bytesWritten || 0;
          if (bTotal > 0) {
            var fPct = Math.min(100, Math.max(0, (vid.fileProgress != null && vid.fileProgress > 0) ? (vid.fileProgress * 100) : ((bWritten * 100) / bTotal)));
            if (vidSizeEl) vidSizeEl.textContent = fmtBytes(bWritten) + " / " + fmtBytes(bTotal);
            if (vidFilePctEl) vidFilePctEl.textContent = fPct.toFixed(1) + "%";
            if (vidFileFillEl) vidFileFillEl.style.width = fPct.toFixed(1) + "%";
          } else {
            if (vidSizeEl) vidSizeEl.textContent = "-- / --";
            if (vidFilePctEl) vidFilePctEl.textContent = "--";
            if (vidFileFillEl) vidFileFillEl.style.width = "0%";
          }

          if (vidFillEl) vidFillEl.style.width = vidPct.toFixed(1) + "%";
          if (vidCntEl) vidCntEl.textContent = "Tổng tiến trình: " + vidProc + " / " + (vid.total || 0) + " video • Còn " + fmtEtaSec(vid.etaSeconds);
          if (vidPctEl) vidPctEl.textContent = vidPct.toFixed(1) + "%";
          if (vidBytesEl) {
            var vRem = vid.bytesRemaining != null ? vid.bytesRemaining : Math.max(0, vTot - vUp);
            vidBytesEl.textContent = "Đã tải: " + fmtBytes(vUp) + " • Còn lại: " + fmtBytes(vRem) + " / Tổng: " + fmtBytes(vTot);
          }
        }
      }
    }
    // Danh sách các điện thoại đang sao lưu đồng thời trên NAS
    var sessions = bak.sessions || [];
    var devBox = document.getElementById("abDevicesBox");
    var devList = document.getElementById("abDevicesList");
    var devBadge = document.getElementById("abDevicesCountBadge");
    if (devBox && devList) {
      devBox.classList.toggle("hidden", sessions.length === 0);
      if (devBadge) devBadge.textContent = sessions.length + " máy (tối đa " + (bak.maxConcurrent || 2) + " luồng)";
      if (sessions.length > 0) {
        var dHtml = "";
        sessions.forEach(function (sItem) {
          var isMe = devId && sItem.deviceId === devId;
          var sPct = sItem.total > 0 ? Math.min(100, (sItem.processed * 100) / sItem.total) : 0;
          var stTxt = sItem.slotGranted ? (sItem.speedBps > 0 ? (fmtBytes(sItem.speedBps) + "/s") : "Đang đối chiếu") : ("Xếp hàng #" + (sItem.queuePosition || 1));
          var stCol = sItem.slotGranted ? "var(--ux-green)" : "var(--ux-yellow)";
          dHtml += '<div style="padding:6px 8px;border-radius:8px;background:rgba(30,41,59,0.55);border:1px solid ' + (isMe ? 'rgba(56,189,248,0.45)' : 'rgba(148,163,184,0.14)') + ';">' +
            '<div style="display:flex;justify-content:space-between;align-items:center;font-size:11.5px;">' +
              '<b style="color:var(--ux-text-1);">' + String(sItem.deviceName || "Android").replace(/</g, "&lt;") + (isMe ? ' <span style="color:var(--ux-cyan);">(Máy này)</span>' : '') + '</b>' +
              '<span style="color:' + stCol + ';font-weight:600;">' + stTxt + '</span>' +
            '</div>' +
            '<div style="display:flex;justify-content:space-between;font-size:10.5px;color:var(--ux-text-2);margin-top:2px;">' +
              '<span>' + backupModeLabel(sItem.backupMode) + ' • ' + (sItem.processed || 0) + '/' + (sItem.total || 0) + ' tệp</span>' +
              '<span>' + (sItem.freedBytes > 0 ? ('Giải phóng: ' + fmtBytes(sItem.freedBytes)) : (sPct.toFixed(0) + '%')) + '</span>' +
            '</div>' +
            '<div class="bar-track" style="margin-top:4px;height:4px;"><div class="bar-fill" style="width:' + sPct.toFixed(1) + '%;background:' + stCol + ';"></div></div>' +
          '</div>';
        });
        devList.innerHTML = dHtml;
      }
    }

    var pBtn = document.getElementById("abPauseBtn");
    if (pBtn) {
      pBtn.classList.toggle("hidden", !running);
      pBtn.textContent = bak.isPaused ? "Tiếp tục" : "Tạm dừng";
    }
    var cBtn = document.getElementById("abCancelBtn");
    if (cBtn) cBtn.classList.toggle("hidden", !running);
    var sBtn = document.getElementById("abSyncNowBtn");
    if (sBtn) sBtn.classList.toggle("hidden", running);
  }

  function openSmbModal() {
    callBridge("fetchSmb");
    renderSmbModal();
    document.getElementById("smbModal").classList.remove("hidden");
  }
  function renderSmbModal() {
    var tb = (state && state.toolbox) || {};
    var on = !!tb.smbEnabled;
    document.getElementById("smbBadge").textContent = tb.smbLoading ? "..." : (on ? "ĐANG BẬT" : "ĐANG TẮT");
    document.getElementById("smbBadge").style.color = on ? COLOR_GREEN : "var(--ux-text-2)";
    document.getElementById("smbSubText").textContent = on ? "Máy tính Windows/Mac có thể truy cập qua mạng LAN" : "Dịch vụ chia sẻ Samba đang tắt";
    document.getElementById("smbToggleBtn").textContent = on ? "Tắt Samba" : "Bật Samba";
  }

  function openDockerModal() {
    refreshDockerModal();
    document.getElementById("dockerModal").classList.remove("hidden");
  }
  function refreshDockerModal() {
    renderDockerModal(!!(bridge() && typeof bridge().fetchDocker === "function"));
    callBridge("fetchDocker");
  }
  function renderDockerModal(fetching) {
    var c = document.getElementById("dockerListContainer");
    if (!c) return;
    var list = (state && state.docker) || [];
    if (fetching || !state || state.dockerLoading || state.dockerLoadError || !list.length) {
      c.textContent = fetching || !state || state.dockerLoading ? "Đang tải danh sách container…" :
        state.dockerLoadError ? "Không tải được danh sách. Hãy bấm làm mới để thử lại." :
        "Không có container Docker.";
      return;
    }
    var html = "";
    list.forEach(function (d) {
      var up = String(d.status || "").toLowerCase().indexOf("running") >= 0 || String(d.status || "").toLowerCase().indexOf("up") >= 0;
      var safeName = String(d.name || "").replace(/</g, "&lt;");
      var attrName = String(d.name || "").replace(/"/g, "&quot;");
      html += '<div class="list-item" style="padding:9px 0;">' +
        '<div style="flex:1;min-width:0;"><div class="list-title" style="font-size:12.5px;">' + safeName + '</div>' +
          '<div class="list-sub" style="color:' + (up ? COLOR_GREEN : COLOR_YELLOW) + ';">' + String(d.status || "").replace(/</g, "&lt;") + '</div></div>' +
        '<div style="display:flex;gap:4px;">' +
          (up ? ('<button class="zoom-btn" data-dact="stop" data-dname="' + attrName + '">Stop</button>') : ('<button class="zoom-btn" data-dact="start" data-dname="' + attrName + '">Start</button>')) +
          '<button class="zoom-btn" data-dact="restart" data-dname="' + attrName + '">↻</button>' +
        '</div></div>';
    });
    c.innerHTML = html;
    c.querySelectorAll("[data-dact]").forEach(function (btn) {
      btn.addEventListener("click", function () {
        callBridge("controlDocker", btn.getAttribute("data-dact"), btn.getAttribute("data-dname"));
        showToast("Đang gửi lệnh Docker...");
      });
    });
  }

  function openLogsModal() {
    callBridge("fetchLogs");
    renderLogsModal();
    document.getElementById("logsModal").classList.remove("hidden");
  }
  function renderLogsModal() {
    var c = document.getElementById("logsListContainer");
    if (!c) return;
    var list = (state && state.logs) || [];
    if (!list.length) {
      c.innerHTML = '<div class="list-sub" style="padding:12px 0;">Chưa có nhật ký hệ thống...</div>';
      return;
    }
    var html = "";
    list.forEach(function (l) {
      var t = String(l.type || "INFO").toUpperCase();
      var col = t === "ERROR" ? COLOR_RED : (t === "WARNING" ? COLOR_YELLOW : (t === "SUCCESS" ? COLOR_GREEN : COLOR_BLUE));
      html += '<div class="list-item" style="padding:8px 0;align-items:flex-start;">' +
        '<div style="flex:1;min-width:0;">' +
          '<div class="list-title" style="font-size:11.5px;color:' + col + ';">[' + t + '] ' + String(l.module || "").replace(/</g, "&lt;") + ' <span style="color:var(--ux-text-3);font-weight:400;">• ' + (l.time || "") + '</span></div>' +
          '<div class="list-sub" style="white-space:normal;word-break:break-word;">' + String(l.message || "").replace(/</g, "&lt;") + '</div>' +
        '</div></div>';
    });
    c.innerHTML = html;
  }

  function openLivestreamModal() {
    callBridge("refreshLivestream");
    var urlEl = document.getElementById("liveUrlInput");
    if (urlEl && !urlEl.value) {
      var b = bridge();
      if (b && typeof b.getClipboardText === "function") {
        var clip = String(b.getClipboardText() || "").trim();
        if (/tiktok|facebook|fb\.watch|youtube|youtu\.be|shopee/i.test(clip)) {
          urlEl.value = clip.replace(/[\u200B\u200C\u200D\uFEFF\u200E\u200F\r\n]/g, "");
        }
      }
    }
    var wp = document.getElementById("liveWatchPane");
    if (wp) wp.removeAttribute("data-dirty");
    renderLivestreamModal();
    document.getElementById("livestreamModal").classList.remove("hidden");
  }

  function renderLivestreamModal() {
    document.getElementById("liveTabRecordBtn").classList.toggle("active", liveTab === "record");
    document.getElementById("liveTabWatchBtn").classList.toggle("active", liveTab === "watch");
    document.getElementById("liveRecordPane").classList.toggle("hidden", liveTab !== "record");
    document.getElementById("liveWatchPane").classList.toggle("hidden", liveTab !== "watch");

    var lv = (state && state.livestream) || {};
    var urlVal = (document.getElementById("liveUrlInput").value || "").trim();
    var detEl = document.getElementById("liveDetectedLine");
    if (urlVal) {
      var plat = /tiktok/i.test(urlVal) ? "TikTok Live" : (/facebook|fb\.watch/i.test(urlVal) ? "Facebook Live" : (/youtube|youtu\.be/i.test(urlVal) ? "YouTube Live" : (/shopee/i.test(urlVal) ? "Shopee Live" : "Livestream")));
      detEl.textContent = "Đã nhận diện: " + plat;
      detEl.classList.remove("hidden");
    } else {
      detEl.classList.add("hidden");
    }
    var startBtn = document.getElementById("liveStartBtn");
    startBtn.disabled = !urlVal || !!lv.starting;
    startBtn.textContent = lv.starting ? "ĐANG KẾT NỐI LUỒNG LIVE..." : "BẮT ĐẦU GHI";

    var msgBox = document.getElementById("liveMsgBox");
    if (lv.message) {
      msgBox.textContent = lv.message;
      msgBox.style.color = lv.isError ? COLOR_RED : "var(--ux-text-2)";
      msgBox.classList.remove("hidden");
    } else {
      msgBox.classList.add("hidden");
    }
    document.getElementById("liveMaxStreamActions").classList.toggle("hidden", !(lv.isError && lv.errorCode === "MAX_STREAMS"));

    // Queue
    var qHtml = "";
    (lv.queue || []).forEach(function (q, i) {
      qHtml += '<div class="list-item" style="padding:6px 0;"><span class="list-sub" style="flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">#' + (q.position || (i + 1)) + ' ' + String(q.url || "").replace(/</g, "&lt;") + '</span>' +
        (q.queueId ? ('<button class="zoom-btn" data-lqcancel="' + String(q.queueId).replace(/"/g, "&quot;") + '" style="color:var(--ux-red);">Hủy</button>') : '') + '</div>';
    });
    var qCont = document.getElementById("liveQueueContainer");
    qCont.innerHTML = qHtml;
    qCont.querySelectorAll("[data-lqcancel]").forEach(function (btn) {
      btn.addEventListener("click", function () { callBridge("cancelLivestreamQueue", btn.getAttribute("data-lqcancel")); });
    });

    // Active jobs
    var jobs = lv.jobs || [];
    syncLiveJobTimers(jobs);
    var jobSig = jobs.map(function (j) { return j.jobId; }).join(",");
    var aCont = document.getElementById("liveActiveContainer");
    if (jobSig && jobSig === lastLiveModalJobSig && aCont && aCont.children.length > 0) {
      jobs.forEach(function (j) {
        var jId = String(j.jobId || "");
        var tEl = aCont.querySelector('.live-modal-time[data-job-id="' + jId + '"]');
        if (tEl) tEl.textContent = fmtLiveModalDur(getLiveJobDurationSec(jId, j.duration));
        var sEl = aCont.querySelector('.live-modal-sub[data-job-id="' + jId + '"]');
        if (sEl) sEl.textContent = (j.speed || "—") + ' • ' + (j.fileSize || "0 B") + (j.outputFile ? (' • ' + String(j.outputFile)) : '');
      });
    } else {
      lastLiveModalJobSig = jobSig;
      var aHtml = jobs.length ? ('<div class="card-title" style="margin-bottom:6px;color:' + COLOR_RED + ';">ĐANG GHI HÌNH (' + jobs.length + ')</div>') : "";
      jobs.forEach(function (j) {
        var jId = String(j.jobId || "");
        var dur = fmtLiveModalDur(getLiveJobDurationSec(jId, j.duration));
        aHtml += '<div class="list-item" style="padding:9px 0;align-items:center;">' +
          '<div style="flex:1;min-width:0;">' +
            '<div class="list-title" style="font-size:12.5px;color:' + COLOR_RED + ';">● ' + String(j.platform || "LIVE").toUpperCase() + ' • <span class="live-modal-time" data-job-id="' + jId.replace(/"/g, "&quot;") + '">' + dur + '</span></div>' +
            '<div class="list-sub live-modal-sub" data-job-id="' + jId.replace(/"/g, "&quot;") + '">' + (j.speed || "—") + ' • ' + (j.fileSize || "0 B") + (j.outputFile ? (' • ' + String(j.outputFile).replace(/</g, "&lt;")) : '') + '</div>' +
          '</div>' +
          '<button class="btn-pill danger" data-lstop="' + jId.replace(/"/g, "&quot;") + '" style="padding:5px 10px;font-size:11px;">Dừng</button>' +
        '</div>';
      });
      aCont.innerHTML = aHtml;
      aCont.querySelectorAll("[data-lstop]").forEach(function (btn) {
        btn.addEventListener("click", function () {
          callBridge("stopLivestream", btn.getAttribute("data-lstop"));
          showToast("Đang dừng ghi hình...");
        });
      });
    }

    // Watch tab
    var wp = document.getElementById("liveWatchPane");
    if (wp && !wp.getAttribute("data-dirty")) {
      document.getElementById("liveExChk").checked = !!lv.excludeEnabled;
      document.getElementById("liveExStart").value = lv.excludeStart || "23:00";
      document.getElementById("liveExEnd").value = lv.excludeEnd || "07:00";
    }
    document.getElementById("liveWatchMeta").textContent = (lv.daemonRunning ? "Watchdog: Đang chạy" : "Watchdog: Tạm dừng") + " • Cookies: " + (lv.cookiesStatus || "unknown");
    var users = lv.watchUsers || [];
    var wHtml = users.length ? "" : '<div class="list-sub" style="padding:10px 0;">Chưa theo dõi người dùng TikTok nào.</div>';
    users.forEach(function (u) {
      var st = String(u.status || "");
      var isLive = st.toLowerCase().indexOf("live") >= 0 || st.toLowerCase().indexOf("record") >= 0;
      wHtml += '<div class="list-item" style="padding:8px 0;">' +
        '<div style="flex:1;min-width:0;">' +
          '<div class="list-title" style="font-size:12.5px;">@' + String(u.username || "").replace(/</g, "&lt;") + ' <span class="badge" style="color:' + (isLive ? COLOR_RED : COLOR_GREEN) + ';">' + (st || "Chờ") + '</span></div>' +
          '<div class="list-sub">' + (u.lastCheck ? ("Kiểm tra: " + u.lastCheck) : (u.lastError || "")) + '</div>' +
        '</div>' +
        '<button class="zoom-btn" data-wdel="' + String(u.username || "").replace(/"/g, "&quot;") + '" style="color:var(--ux-red);">✕</button>' +
      '</div>';
    });
    var wCont = document.getElementById("liveWatchListContainer");
    wCont.innerHTML = wHtml;
    wCont.querySelectorAll("[data-wdel]").forEach(function (btn) {
      btn.addEventListener("click", function () {
        callBridge("removeTikTokWatch", btn.getAttribute("data-wdel"));
        showToast("Đã xóa @" + btn.getAttribute("data-wdel"));
      });
    });
  }

  function renderBgTasks() {
    var card = document.getElementById("bgTasksCard");
    var body = document.getElementById("bgTasksBody");
    var cntEl = document.getElementById("bgTasksCount");
    var chev = document.getElementById("bgTasksChevron");
    if (!card || !body) return;
    var s = state || {};
    var bg = s.bgTasks || {};
    var dup = bg.dup || {};
    var bak = bg.backup || {};
    var usb = s.usb || {};
    var liveArr = (s.livestream && s.livestream.jobs) || [];

    var bakSessions = bak.sessions || [];
    var hasBackupActivity = !!(bak.active || bakSessions.length > 0);
    var count = (dup.active ? 1 : 0) + (hasBackupActivity ? 1 : 0) + (usb.running ? 1 : 0) + (liveArr.length ? 1 : 0);
    if (count === 0) {
      card.classList.add("hidden");
      return;
    }
    card.classList.remove("hidden");
    if (cntEl) cntEl.textContent = count + " đang thực thi";
    if (chev) chev.textContent = bgTasksExpanded ? "▴" : "▾";
    body.classList.toggle("hidden", !bgTasksExpanded);
    if (!bgTasksExpanded) return;

    var curBakMode = ((s.toolbox && s.toolbox.backupMode) || bak.backupMode || "both");
    var img = bak.images || {};
    var vid = bak.videos || {};
    var curF = curBakMode === "image" ? (img.currentFile || "...") : (curBakMode === "video" ? (vid.currentFile || "...") : (bak.currentFile || "..."));
    var bgTaskSig = [
      dup.active ? 1 : 0,
      dup.running ? 1 : 0,
      hasBackupActivity ? 1 : 0,
      bak.active ? 1 : 0,
      curBakMode || "",
      bakSessions.length,
      usb.running ? 1 : 0,
      liveArr.length
    ].join("|");

    if (bgTaskSig === lastBgTasksSignature && body.children.length > 0) {
      if (dup.active) {
        var dp = Math.min(100, Math.max(0, (dup.percent || 0) * 100));
        var dLbl = dup.resultCount > 0 ? ("✅ Tìm thấy " + dup.resultCount + " tệp trùng") : ("🟢 Đang quét Rust — Tầng " + (dup.stageNum || 1) + "/" + (dup.totalStages || 3));
        var elBar = document.getElementById("bgDupBar");
        var elPct = document.getElementById("bgDupPct");
        var elScanned = document.getElementById("bgDupScanned");
        var elFound = document.getElementById("bgDupFound");
        var elLbl = document.getElementById("bgDupLbl");
        var elStage = document.getElementById("bgDupStage");
        var elTime = document.getElementById("bgDupTimeTxt");
        if (elBar) elBar.style.width = dp.toFixed(1) + "%";
        if (elPct) elPct.textContent = dp.toFixed(1) + "%";
        if (elScanned) elScanned.textContent = (dup.scanned || 0).toLocaleString();
        if (elFound) elFound.textContent = (dup.found || 0).toLocaleString();
        if (elLbl) elLbl.textContent = dLbl;
        if (elStage) elStage.textContent = String(dup.stage || "");
        if (elTime) {
          var dt = getLiveDupTime();
          elTime.textContent = dup.running ? (' • ⏱ ' + fmtDurSec(dt.elapsedSec) + ' / còn ' + fmtEtaSec(dt.etaSec)) : '';
        }
      }
      if (bak.active) {
        var bp = Math.min(100, Math.max(0, (bak.progress || 0) * 100));
        var elBakBar = document.getElementById("bgBakBar");
        var elBakPct = document.getElementById("bgBakPct");
        var elBakCur = document.getElementById("bgBakCurFile");
        var elBakSpd = document.getElementById("bgBakSpeed");
        var elBakTime = document.getElementById("bgBakTimeTxt");
        if (elBakBar) elBakBar.style.width = bp.toFixed(1) + "%";
        if (elBakPct) elBakPct.textContent = bp.toFixed(1) + "%";
        if (elBakCur) elBakCur.textContent = String(curF || "...");
        var curSpd = curBakMode === "image" ? (img.speedBps || 0) : (curBakMode === "video" ? (vid.speedBps || 0) : (bak.speedBps || 0));
        if (elBakSpd && curSpd > 0) elBakSpd.textContent = fmtBytes(curSpd) + "/s";
        if (elBakTime) {
          var bt = getLiveBakTime();
          elBakTime.textContent = ' • ⏱ ' + fmtDurSec(bt.elapsedSec) + ' / còn ' + fmtEtaSec(bt.etaSec);
        }
      }
      if (liveArr.length) {
        liveArr.forEach(function (j) {
          var jId = String(j.jobId || "");
          var elTime = document.querySelector('.bg-live-time[data-job-id="' + jId + '"]');
          if (elTime) elTime.textContent = fmtDurSec(getLiveJobDurationSec(jId, j.duration));
          var elSub = document.querySelector('.bg-live-sub[data-job-id="' + jId + '"]');
          if (elSub) elSub.textContent = (j.fileSize || "0 B") + (j.speed ? (" • " + j.speed) : "");
          var elFname = document.querySelector('.bg-live-fname[data-job-id="' + jId + '"]');
          var fName = (j.outputFile || "").split("/").pop() || "đang tạo file...";
          if (elFname) elFname.textContent = fName;
        });
      }
      return;
    }
    lastBgTasksSignature = bgTaskSig;

    var html = "";
    if (dup.active) {
      var dp = Math.min(100, Math.max(0, (dup.percent || 0) * 100));
      var dLbl = dup.resultCount > 0 ? ("✅ Tìm thấy " + dup.resultCount + " tệp trùng") : ("🟢 Đang quét Rust — Tầng " + (dup.stageNum || 1) + "/" + (dup.totalStages || 3));
      var dt = getLiveDupTime();
      var dTimeTxt = dup.running ? (' • ⏱ ' + fmtDurSec(dt.elapsedSec) + ' / còn ' + fmtEtaSec(dt.etaSec)) : '';
      html += '<div id="bgDupOpen" data-bg-open="duplicate" style="padding:8px 0;border-bottom:1px solid rgba(148,163,184,0.12);cursor:pointer;">' +
        '<div style="display:flex;justify-content:space-between;align-items:center;gap:8px;">' +
          '<div style="flex:1;"><div class="list-title">Quét &amp; dọn trùng lặp</div><div id="bgDupLbl" class="list-sub" style="color:' + COLOR_BLUE + ';">' + dLbl + '</div></div>' +
          (dup.running ? '<div style="display:flex;gap:4px;"><button class="zoom-btn" id="bgDupCancelBtn" data-bg-cancel="duplicate" style="color:var(--ux-red);">■</button></div>' : '') +
        '</div>' +
        (dup.stage ? '<div id="bgDupStage" class="list-sub" style="margin-top:3px;">' + String(dup.stage).replace(/</g, "&lt;") + '</div>' : '<div id="bgDupStage" class="list-sub hidden" style="margin-top:3px;"></div>') +
        '<div class="bar-track" style="margin:6px 0;"><div id="bgDupBar" class="bar-fill" style="width:' + dp.toFixed(1) + '%;background:var(--ux-purple);"></div></div>' +
        '<div style="display:flex;justify-content:space-between;font-size:11px;color:var(--ux-text-2);"><span>Đã duyệt: <b id="bgDupScanned">' + (dup.scanned || 0).toLocaleString() + '</b> • Ứng viên: <b id="bgDupFound">' + (dup.found || 0).toLocaleString() + '</b><span id="bgDupTimeTxt">' + dTimeTxt + '</span></span><b id="bgDupPct">' + dp.toFixed(1) + '%</b></div>' +
      '</div>';
    }
    if (bak.active || bakSessions.length > 0) {
      var showImg = curBakMode !== "video" && (img.total > 0 || curBakMode === "image");
      var showVid = curBakMode !== "image" && (vid.total > 0 || curBakMode === "video");
      var bp = Math.min(100, Math.max(0, (bak.progress || 0) * 100));
      var curSpdVal = curBakMode === "image" ? (img.speedBps || 0) : (curBakMode === "video" ? (vid.speedBps || 0) : (bak.speedBps || 0));
      var bSpd = bak.active ? (curSpdVal > 0 ? (fmtBytes(curSpdVal) + "/s") : "Đang đối chiếu...") : (bakSessions.length + " máy trên NAS");
      var bModeLbl = backupModeLabel(curBakMode);
      var bt = getLiveBakTime();
      var bTimeTxt = bak.active ? (' • ⏱ ' + fmtDurSec(bt.elapsedSec) + ' / còn ' + fmtEtaSec(bt.etaSec)) : '';
      html += '<div id="bgBakOpen" style="padding:8px 0;border-bottom:1px solid rgba(148,163,184,0.12);cursor:pointer;">' +
        '<div style="display:flex;justify-content:space-between;align-items:center;"><div class="list-title">Đồng bộ NAS • ' + bModeLbl + '</div><span class="badge" id="bgBakSpeed" style="color:var(--ux-green);">' + bSpd + '</span></div>';

      if (bak.active) {
        html += '<div id="bgBakCurFile" class="list-sub" style="margin-top:3px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">' + String(curF).replace(/</g, "&lt;") + '</div>';
        if (showImg || showVid) {
          var ip = img.total > 0 ? Math.min(100, (img.processed * 100) / img.total) : 0;
          var vp = vid.total > 0 ? Math.min(100, (vid.processed * 100) / vid.total) : 0;
          html += '<div style="display:flex;gap:8px;margin-top:5px;">' +
            (showImg ? '<div style="flex:1;"><div style="display:flex;justify-content:space-between;font-size:10.5px;color:var(--ux-cyan);"><span>Ảnh: ' + (img.processed || 0) + '/' + (img.total || 0) + ' • ' + fmtEtaSec(img.etaSeconds) + '</span><span>' + (img.speedBps > 0 ? (fmtBytes(img.speedBps) + "/s") : "--") + '</span></div><div class="bar-track" style="margin:2px 0;"><div class="bar-fill" style="width:' + ip.toFixed(1) + '%;background:var(--ux-cyan);"></div></div><div style="font-size:10px;color:var(--ux-text-3);">' + fmtBytes(img.bytesUploaded || 0) + ' / ' + fmtBytes(img.bytesTotal || 0) + ' (còn ' + fmtBytes(img.bytesRemaining || 0) + ')</div></div>' : '') +
            (showVid ? '<div style="flex:1;"><div style="display:flex;justify-content:space-between;font-size:10.5px;color:var(--ux-yellow);"><span>Video: ' + (vid.processed || 0) + '/' + (vid.total || 0) + ' • ' + fmtEtaSec(vid.etaSeconds) + '</span><span>' + (vid.speedBps > 0 ? (fmtBytes(vid.speedBps) + "/s") : "--") + '</span></div><div class="bar-track" style="margin:2px 0;"><div class="bar-fill" style="width:' + vp.toFixed(1) + '%;background:var(--ux-yellow);"></div></div><div style="font-size:10px;color:var(--ux-text-3);">' + fmtBytes(vid.bytesUploaded || 0) + ' / ' + fmtBytes(vid.bytesTotalPending || 0) + ' (còn ' + fmtBytes(vid.bytesRemaining || 0) + ')</div></div>' : '') +
          '</div>';
        }
        if (curBakMode === "both") {
          var tUpB = bak.bytesWritten || 0;
          var tTotB = bak.bytesTotal || 0;
          var tRemB = bak.bytesRemaining != null ? bak.bytesRemaining : Math.max(0, tTotB - tUpB);
          html += '<div class="bar-track" style="margin:6px 0;"><div id="bgBakBar" class="bar-fill" style="width:' + bp.toFixed(1) + '%;background:var(--ux-green);"></div></div>' +
            '<div style="display:flex;justify-content:space-between;font-size:11px;color:var(--ux-text-2);"><span>Tổng: ' + (bak.processed || 0) + ' / ' + (bak.total || 0) + ' tệp<span id="bgBakTimeTxt">' + bTimeTxt + '</span></span><b id="bgBakPct">' + bp.toFixed(1) + '%</b></div>' +
            '<div style="font-size:10.5px;color:var(--ux-text-3);margin-top:2px;">Đã tải: ' + fmtBytes(tUpB) + ' • Còn lại: ' + fmtBytes(tRemB) + ' / Tổng: ' + fmtBytes(tTotB) + (bak.freedSessionBytes > 0 ? (' • Giải phóng ' + fmtBytes(bak.freedSessionBytes)) : '') + '</div>';
        } else {
          var modeEta = curBakMode === "image" ? img.etaSeconds : vid.etaSeconds;
          html += '<div style="font-size:10.5px;color:var(--ux-text-2);margin-top:4px;"><span id="bgBakTimeTxt">⏱ Đã chạy: ' + fmtDurSec(bt.elapsedSec) + ' • Còn lại: ' + fmtEtaSec(modeEta) + '</span></div>';
        }
      }

      if (bakSessions.length > 1 || (!bak.active && bakSessions.length > 0)) {
        html += '<div style="margin-top:6px;padding-top:5px;border-top:1px dashed rgba(148,163,184,0.18);font-size:11px;color:var(--ux-text-2);">';
        bakSessions.forEach(function (ms) {
          var mSt = ms.slotGranted ? (ms.speedBps > 0 ? (fmtBytes(ms.speedBps) + "/s") : "Đang chạy") : ("Xếp hàng #" + (ms.queuePosition || 1));
          html += '<div style="display:flex;justify-content:space-between;margin-top:2px;"><span>📱 ' + String(ms.deviceName || "Android").replace(/</g, "&lt;") + ' (' + backupModeLabel(ms.backupMode) + ' • ' + (ms.processed || 0) + '/' + (ms.total || 0) + ')</span><b style="color:' + (ms.slotGranted ? 'var(--ux-cyan)' : 'var(--ux-yellow)') + ';">' + mSt + '</b></div>';
        });
        html += '</div>';
      }
      html += '</div>';
    }
    if (usb.running) {
      var up = usb.bytesTotal > 0 ? Math.min(100, (usb.bytesProcessed * 100) / usb.bytesTotal) : (usb.filesTotal > 0 ? Math.min(100, (usb.filesDone * 100) / usb.filesTotal) : 0);
      html += '<div style="padding:8px 0;border-bottom:1px solid rgba(148,163,184,0.12);">' +
        '<div style="display:flex;justify-content:space-between;align-items:center;gap:8px;">' +
          '<div id="bgUsbOpen" style="flex:1;cursor:pointer;"><div class="list-title">Nhập dữ liệu USB</div><div class="list-sub" style="color:var(--ux-cyan);">' + (usb.phase === "cancelling" ? "Đang hủy..." : ("Đang chép từ " + (usb.device || "USB"))) + '</div></div>' +
          '<button class="zoom-btn" id="bgUsbCancelBtn" style="color:var(--ux-red);">■</button>' +
        '</div>' +
        (usb.currentFile ? '<div class="list-sub" style="margin-top:3px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">Tệp: ' + String(usb.currentFile).replace(/</g, "&lt;") + '</div>' : '') +
        '<div class="bar-track" style="margin:6px 0;"><div class="bar-fill" style="width:' + up.toFixed(1) + '%;background:var(--ux-cyan);"></div></div>' +
        '<div style="display:flex;justify-content:space-between;font-size:11px;color:var(--ux-text-2);"><span>' + (usb.filesDone || 0) + '/' + (usb.filesTotal || 0) + ' tệp • ' + fmtBytes(usb.bytesProcessed || 0) + '</span><b>' + fmtBytes(usb.speedBps || 0) + '/s</b></div>' +
      '</div>';
    }
    if (liveArr.length) {
      html += '<div id="bgLiveOpen" style="padding:8px 0;cursor:pointer;">' +
        '<div class="list-title">Ghi hình Livestream <span class="badge" style="color:var(--ux-orange);margin-left:4px;">🔴 ' + liveArr.length + ' kênh</span></div>';
      liveArr.forEach(function (j) {
        var plat = j.platform === "tiktok" ? "TikTok" : (j.platform === "facebook" ? "Facebook" : (j.platform === "youtube" ? "YouTube" : (j.platform === "shopee" ? "Shopee" : (j.platform || "Livestream"))));
        var lbl = j.watchUsername ? (plat + " • @" + j.watchUsername) : (plat + (j.jobId ? (" • " + String(j.jobId).slice(-6)) : ""));
        var fName = (j.outputFile || "").split("/").pop() || "đang tạo file...";
        var jId = String(j.jobId || "");
        html += '<div style="margin-top:6px;padding-left:8px;border-left:2px solid var(--ux-orange);">' +
          '<div style="display:flex;justify-content:space-between;font-size:12px;"><b>' + String(lbl).replace(/</g, "&lt;") + '</b><span class="bg-live-time" data-job-id="' + jId.replace(/"/g, "&quot;") + '" style="color:var(--ux-orange);">' + fmtDurSec(getLiveJobDurationSec(jId, j.duration)) + '</span></div>' +
          '<div style="display:flex;justify-content:space-between;font-size:11px;color:var(--ux-text-2);"><span class="bg-live-fname" data-job-id="' + jId.replace(/"/g, "&quot;") + '" style="white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:65%;">' + String(fName).replace(/</g, "&lt;") + '</span><span class="bg-live-sub" data-job-id="' + jId.replace(/"/g, "&quot;") + '">' + (j.fileSize || "0 B") + (j.speed ? (" • " + j.speed) : "") + '</span></div>' +
        '</div>';
      });
      html += '</div>';
    }

    body.innerHTML = html;
    if (!body.getAttribute("data-bg-delegated")) {
      body.setAttribute("data-bg-delegated", "1");
      body.addEventListener("click", function (ev) {
        var target = ev.target;
        if (!target || !target.closest) return;
        if (target.closest("#bgDupCancelBtn")) { ev.stopPropagation(); callBridge("cancelDuplicateScan"); return; }
        if (target.closest("#bgUsbCancelBtn")) { ev.stopPropagation(); callBridge("cancelUsbImport"); return; }
        if (target.closest("#bgDupOpen")) { openSubUiModal("duplicate"); return; }
        if (target.closest("#bgBakOpen")) { openAutoBackupModal(); return; }
        if (target.closest("#bgUsbOpen")) { openSubUiModal("usbImport"); return; }
        if (target.closest("#bgLiveOpen")) { openLivestreamModal(); return; }
      });
    }
  }

  function renderDashLogs() {
    var card = document.getElementById("dashLogsCard");
    var body = document.getElementById("dashLogsBody");
    var listEl = document.getElementById("dashLogsList");
    var badge = document.getElementById("dashLogsBadge");
    var chev = document.getElementById("dashLogsChevron");
    var foot = document.getElementById("dashLogsTimeFoot");
    if (!card || !body || !listEl) return;

    var list = (state && state.logs) || [];
    var errCount = 0;
    var warnCount = 0;
    list.forEach(function (l) {
      var t = String(l.type || "").toUpperCase();
      if (t === "ERROR" || t === "CRASH") errCount++;
      else if (t === "WARNING") warnCount++;
    });

    if (badge) {
      if (errCount > 0) {
        badge.textContent = errCount + " lỗi" + (warnCount > 0 ? " • " + warnCount + " cảnh báo" : "");
        badge.style.color = COLOR_RED;
      } else if (warnCount > 0) {
        badge.textContent = warnCount + " cảnh báo";
        badge.style.color = COLOR_YELLOW;
      } else {
        badge.textContent = list.length + " log";
        badge.style.color = list.length > 0 ? COLOR_GREEN : "var(--ux-text-2)";
      }
    }

    if (chev) chev.textContent = dashLogsExpanded ? "▴" : "▾";
    body.classList.toggle("hidden", !dashLogsExpanded);
    if (!dashLogsExpanded) return;

    document.querySelectorAll("[data-log-filter]").forEach(function (btn) {
      btn.classList.toggle("active", btn.getAttribute("data-log-filter") === dashLogsFilter);
    });

    var filtered = list.filter(function (l) {
      var t = String(l.type || "INFO").toLowerCase();
      if (dashLogsFilter === "error") return t === "error" || t === "crash";
      if (dashLogsFilter === "warning") return t === "warning";
      if (dashLogsFilter === "info") return t === "info" || t === "success";
      return true;
    });

    var lastLog = list[0];
    var logsSig = [
      dashLogsExpanded,
      dashLogsFilter,
      filtered.length,
      lastLog ? (lastLog.time + "|" + lastLog.message) : ""
    ].join("|");
    if (logsSig !== lastDashLogsSignature) {
      lastDashLogsSignature = logsSig;
      if (!filtered.length) {
        listEl.innerHTML = '<div class="list-sub" style="padding:14px 0;text-align:center;">' +
          (list.length ? "Không có nhật ký khớp với bộ lọc." : "Chưa có nhật ký hệ thống...") + '</div>';
      } else {
        var html = "";
        filtered.slice(0, 30).forEach(function (l) {
          var t = String(l.type || "INFO").toUpperCase();
          var col = (t === "ERROR" || t === "CRASH") ? COLOR_RED : (t === "WARNING" ? COLOR_YELLOW : (t === "SUCCESS" ? COLOR_GREEN : COLOR_BLUE));
          var bg = (t === "ERROR" || t === "CRASH") ? "rgba(239,83,80,0.12)" : ((t === "WARNING") ? "rgba(255,196,0,0.12)" : "rgba(148,163,184,0.06)");
          var mod = String(l.module || "System").replace(/</g, "&lt;");
          var msg = String(l.message || "").replace(/</g, "&lt;");
          var tm = String(l.time || "");

          html += '<div style="background:' + bg + ';border-radius:8px;padding:6px 9px;border:1px solid rgba(148,163,184,0.1);">' +
            '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:2px;">' +
              '<div style="font-size:11px;font-weight:700;color:' + col + ';">[' + t + '] ' + mod + '</div>' +
              '<div style="font-size:10px;color:var(--ux-text-3);font-variant-numeric:tabular-nums;">' + tm + '</div>' +
            '</div>' +
            '<div style="font-size:11.5px;color:var(--ux-text);white-space:normal;word-break:break-word;line-height:1.35;">' + msg + '</div>' +
          '</div>';
        });
        listEl.innerHTML = html;
      }
    }

    if (foot) {
      foot.textContent = "Hiển thị " + Math.min(filtered.length, 30) + "/" + list.length + " log";
    }
  }

  function openSubUiModal(kind) {
    activeSubUi = kind;
    var modal = document.getElementById("subUiModal");
    if (modal) modal.removeAttribute("data-dirty");
    if (kind === "lanWhitelist") callBridge("fetchLanWhitelist");
    else if (kind === "nasBackup") callBridge("fetchNasBackups");
    else if (kind === "failedUploads") callBridge("fetchFailedUploads");
    else if (kind === "usbImport") callBridge("fetchUsbImport");
    else if (kind === "sleepSchedule") callBridge("fetchSleepSchedule");
    else if (kind === "duplicate") {
      lastDupModalSig = "";
      dupVisibleLimit = 25;
      callBridge("refreshDuplicateSchedule");
    }
    renderSubUiModal(false);
    modal.classList.remove("hidden");
  }

  function renderSubUiModal(fromUpdate) {
    var titleEl = document.getElementById("subUiTitle");
    var bodyEl = document.getElementById("subUiBody");
    var refBtn = document.getElementById("subUiRefreshBtn");
    var modal = document.getElementById("subUiModal");
    if (!titleEl || !bodyEl) return;
    if (fromUpdate) {
      var ae = document.activeElement;
      if (ae && bodyEl.contains(ae) && (ae.tagName === "INPUT" || ae.tagName === "SELECT" || ae.tagName === "TEXTAREA")) return;
      if (modal && modal.getAttribute("data-dirty") === "1") return;
    }
    var s = state || {};
    var html = "";
    refBtn.classList.toggle("hidden", activeSubUi === "guest" || activeSubUi === "social" || activeSubUi === "bandwidth");

    if (activeSubUi === "guest") {
      titleEl.textContent = "KHÁCH WEBDAV / FTP";
      var g = s.guest || {};
      html += '<div style="display:flex;gap:6px;flex-wrap:wrap;margin-bottom:10px;">' +
        [15, 60, 180, 1440].map(function (m) {
          return '<button class="btn-pill primary" data-gmin="' + m + '">' + (m >= 60 ? (m / 60) + ' giờ' : m + ' phút') + '</button>';
        }).join("") + '</div>';
      if (g.loading) html += '<div class="list-sub" style="padding:8px 0;">Đang tạo tài khoản khách...</div>';
      if (g.error) html += '<div class="list-sub" style="color:' + COLOR_RED + ';padding:6px 0;">' + String(g.error).replace(/</g, "&lt;") + '</div>';
      if (g.username) {
        html += '<div class="list-item"><div><div class="list-title">User: ' + g.username + '</div><div class="list-sub">Pass: <b>' + g.password + '</b> • Hết hạn: ' + (g.expiresAt || "") + '</div></div>' +
          '<button class="btn-pill danger" id="gRevokeBtn">Thu hồi</button></div>';
      } else {
        html += '<div class="list-sub">Chọn thời hạn phía trên để tạo tài khoản FTP tạm thời cho khách.</div>';
      }
      bodyEl.innerHTML = html;
      bodyEl.querySelectorAll("[data-gmin]").forEach(function (b) {
        b.addEventListener("click", function () { callBridge("createGuestPass", Number(b.getAttribute("data-gmin"))); });
      });
      var rv = document.getElementById("gRevokeBtn");
      if (rv) rv.addEventListener("click", function () { callBridge("revokeGuestPass"); });
    } else if (activeSubUi === "social") {
      titleEl.textContent = "TẢI VIDEO MXH";
      var sc = s.social || {};
      var oldVal = (document.getElementById("socUrlInp") && document.getElementById("socUrlInp").value) || "";
      html += '<input class="ux-input" style="margin:0 0 8px;" id="socUrlInp" type="url" placeholder="Dán link TikTok, Douyin, YouTube, FB..." value="' + oldVal.replace(/"/g, "&quot;") + '" />' +
        '<div style="display:flex;gap:8px;margin-bottom:10px;">' +
          '<button class="btn-pill primary" style="flex:1;" id="socDlBtn">' + (sc.extracting ? "Đang tải..." : "Tải vào Downloads") + '</button>' +
          '<button class="btn-pill" id="socPasteBtn">Dán link</button>' +
        '</div>';
      if (sc.status) html += '<div class="list-sub" style="margin-bottom:8px;color:var(--ux-cyan);">' + String(sc.status).replace(/</g, "&lt;") + '</div>';
      (sc.history || []).forEach(function (h) {
        html += '<div class="list-item"><div style="flex:1;min-width:0;"><div class="list-title" style="font-size:12px;">' + String(h.title || "").replace(/</g, "&lt;") + '</div><div class="list-sub">' + (h.status || "") + ' • ' + (h.folder || "") + '</div></div></div>';
      });
      bodyEl.innerHTML = html;
      document.getElementById("socPasteBtn").addEventListener("click", function () {
        var b = bridge();
        if (b && typeof b.getClipboardText === "function") document.getElementById("socUrlInp").value = b.getClipboardText() || "";
      });
      document.getElementById("socDlBtn").addEventListener("click", function () {
        var u = (document.getElementById("socUrlInp").value || "").trim();
        if (u) { callBridge("downloadSocialVideo", u, "Downloads"); showToast("Đang gửi yêu cầu tải video..."); }
      });
    } else if (activeSubUi === "performance") {
      titleEl.textContent = "HIỆU NĂNG & MẠNG";
      var pf = s.perf || {};
      html += '<div class="list-item"><div class="list-title">Kết nối hiện tại</div><span class="badge">' + (s.connLabel || "NAS") + '</span></div>' +
        '<div class="list-item"><div class="list-title">Độ trễ API</div><span class="badge">' + (pf.latencyMs || 0) + ' ms</span></div>' +
        '<div class="list-item"><div class="list-title">Tốc độ Tải xuống</div><span class="badge" style="color:var(--ux-cyan);">' + (pf.dl || "--") + '</span></div>' +
        '<div class="list-item"><div class="list-title">Tốc độ Tải lên</div><span class="badge" style="color:var(--ux-green);">' + (pf.ul || "--") + '</span></div>' +
        '<button class="btn-pill primary" id="perfRunBtn" style="width:100%;margin-top:12px;">' + (pf.testing ? "Đang đo tốc độ..." : "Đo tốc độ LAN / Tailscale") + '</button>';
      bodyEl.innerHTML = html;
      document.getElementById("perfRunBtn").addEventListener("click", function () { callBridge("runSpeedTest"); showToast("Đang đo tốc độ mạng..."); });
    } else if (activeSubUi === "lanWhitelist") {
      titleEl.textContent = "LAN WHITELIST";
      var wl = s.whitelist || {};
      html += '<div style="display:flex;gap:6px;margin-bottom:8px;"><input class="ux-input" style="margin:0;flex:1;" id="wlInp" placeholder="192.168.1.50 hoặc 100.64.0.0/10" /><button class="btn-pill primary" id="wlAddBtn">Thêm</button></div>';
      if (wl.error) html += '<div class="list-sub" style="color:var(--ux-red);">' + wl.error + '</div>';
      (wl.ips || []).forEach(function (ip) {
        html += '<div class="list-item"><div class="list-title">' + ip + '</div><button class="zoom-btn" data-wldel="' + ip + '" data-wlsub="0" style="color:var(--ux-red);">✕</button></div>';
      });
      (wl.subnets || []).forEach(function (sb) {
        html += '<div class="list-item"><div class="list-title">' + sb + '</div><button class="zoom-btn" data-wldel="' + sb + '" data-wlsub="1" style="color:var(--ux-red);">✕</button></div>';
      });
      bodyEl.innerHTML = html;
      document.getElementById("wlAddBtn").addEventListener("click", function () {
        var v = (document.getElementById("wlInp").value || "").trim();
        if (v) callBridge("addLanWhitelist", v);
      });
      bodyEl.querySelectorAll("[data-wldel]").forEach(function (b) {
        b.addEventListener("click", function () { callBridge("removeLanWhitelist", b.getAttribute("data-wldel"), b.getAttribute("data-wlsub") === "1"); });
      });
    } else if (activeSubUi === "bandwidth") {
      titleEl.textContent = "GIỚI HẠN BĂNG THÔNG";
      var curBps = (s.perf && s.perf.bandwidthLimit) || 0;
      html += '<div class="list-sub" style="margin-bottom:10px;">Giới hạn tốc độ tải lên NAS để tránh nghẽn mạng gia đình.</div>' +
        [[0, "Không giới hạn"], [5 * 1024 * 1024, "5 MB/s"], [10 * 1024 * 1024, "10 MB/s"], [20 * 1024 * 1024, "20 MB/s"]].map(function (opt) {
          var act = curBps === opt[0];
          return '<div class="list-item" data-bw="' + opt[0] + '"><div class="list-title">' + opt[1] + '</div><span class="badge" style="color:' + (act ? COLOR_BLUE : "var(--ux-text-3)") + ';">' + (act ? "Đang chọn" : "Chọn") + '</span></div>';
        }).join("");
      bodyEl.innerHTML = html;
      bodyEl.querySelectorAll("[data-bw]").forEach(function (el) {
        el.addEventListener("click", function () { callBridge("setBandwidthLimit", Number(el.getAttribute("data-bw"))); });
      });
    } else if (activeSubUi === "nasBackup") {
      titleEl.textContent = "SAO LƯU CẤU HÌNH NAS";
      var nb = s.nasBackup || {};
      html += '<button class="btn-pill primary" id="nbCreateBtn" style="width:100%;margin-bottom:10px;">' + (nb.creating ? "Đang tạo..." : "+ Tạo bản sao lưu cấu hình") + '</button>';
      if (nb.message) html += '<div class="list-sub" style="margin-bottom:8px;">' + nb.message + '</div>';
      (nb.items || []).forEach(function (it) {
        html += '<div class="list-item"><div style="flex:1;min-width:0;"><div class="list-title" style="font-size:12px;">' + it.filename + '</div><div class="list-sub">' + it.size + ' • ' + it.date + '</div></div>' +
          '<div style="display:flex;gap:4px;"><button class="zoom-btn" data-nbrest="' + it.filename + '">Khôi phục</button><button class="zoom-btn" data-nbdel="' + it.filename + '" style="color:var(--ux-red);">✕</button></div></div>';
      });
      bodyEl.innerHTML = html;
      document.getElementById("nbCreateBtn").addEventListener("click", function () { callBridge("createNasBackup"); });
      bodyEl.querySelectorAll("[data-nbrest]").forEach(function (b) { b.addEventListener("click", function () { callBridge("restoreNasBackup", b.getAttribute("data-nbrest")); }); });
      bodyEl.querySelectorAll("[data-nbdel]").forEach(function (b) { b.addEventListener("click", function () { callBridge("deleteNasBackup", b.getAttribute("data-nbdel")); }); });
    } else if (activeSubUi === "failedUploads") {
      titleEl.textContent = "TỆP TẢI LÊN LỖI";
      var fu = s.failedUploads || [];
      html += fu.length ? '<button class="btn-pill primary" id="fuRetryAllBtn" style="width:100%;margin-bottom:10px;">Thử lại tất cả (' + fu.length + ')</button>' : '<div class="list-sub">Không có tệp tải lên lỗi nào.</div>';
      fu.forEach(function (f) {
        html += '<div class="list-item"><div style="flex:1;min-width:0;"><div class="list-title" style="font-size:12px;">' + String(f.name || "").replace(/</g, "&lt;") + '</div><div class="list-sub">' + String(f.error || "").replace(/</g, "&lt;") + '</div></div>' +
          '<button class="zoom-btn" data-furetry="' + f.id + '">Thử lại</button></div>';
      });
      bodyEl.innerHTML = html;
      var ra = document.getElementById("fuRetryAllBtn");
      if (ra) ra.addEventListener("click", function () { callBridge("retryAllFailedUploads"); });
      bodyEl.querySelectorAll("[data-furetry]").forEach(function (b) { b.addEventListener("click", function () { callBridge("retryFailedUpload", Number(b.getAttribute("data-furetry"))); }); });
    } else if (activeSubUi === "usbImport") {
      titleEl.textContent = "NHẬP DỮ LIỆU TỪ USB";
      var usb = s.usb || {};
      var up = usb.bytesTotal > 0 ? Math.min(100, (usb.bytesProcessed * 100) / usb.bytesTotal) : (usb.filesTotal > 0 ? Math.min(100, (usb.filesDone * 100) / usb.filesTotal) : 0);
      var usbPhases = { idle: "Chờ thiết bị USB", copying: "Đang chép", cancelling: "Đang hủy",
        done: "Đã hoàn tất", cancelled: "Đã hủy", error: "Nhập USB gặp lỗi" };
      var usbStatus = usb.loading ? "Đang kiểm tra USB" : (usbPhases[usb.phase] || "Chờ thiết bị USB");
      var usbMessage = String(usb.message || "").trim();
      if (/^(?:lỗi:\s*)?null$/i.test(usbMessage)) usbMessage = "";
      html += '<div class="list-item"><div class="list-title">Trạng thái</div><span class="badge" style="color:' + (usb.running ? COLOR_BLUE : COLOR_GREEN) + ';">' + usbStatus + '</span></div>' +
        (usb.device ? '<div class="list-item"><div class="list-title">Thiết bị USB</div><span class="badge">' + String(usb.device).replace(/</g, "&lt;") + '</span></div>' : '') +
        (usb.running ? (
          '<div class="list-sub" style="padding:4px 0;">Tệp: ' + String(usb.currentFile || "...").replace(/</g, "&lt;") + '</div>' +
          '<div class="bar-track" style="margin:6px 0;"><div class="bar-fill" style="width:' + up.toFixed(1) + '%;background:var(--ux-cyan);"></div></div>' +
          '<div class="list-sub" style="margin-bottom:8px;">' + (usb.filesDone || 0) + '/' + (usb.filesTotal || 0) + ' tệp • ' + fmtBytes(usb.bytesProcessed || 0) + ' • ' + fmtBytes(usb.speedBps || 0) + '/s</div>'
        ) : '') +
        '<div class="list-sub" style="padding:8px 0;">' + (usbMessage || "Cắm USB vào cổng USB trên NAS để kiểm tra.").replace(/</g, "&lt;") + '</div>' +
        (usb.running
          ? '<button class="btn-pill danger" id="usbCancelBtn" style="width:100%;margin-top:8px;">Hủy nhập USB</button>'
          : '<button class="btn-pill primary" id="usbStartBtn" style="width:100%;margin-top:8px;">Bắt đầu nhập từ USB</button>');
      bodyEl.innerHTML = html;
      var uStart = document.getElementById("usbStartBtn");
      if (uStart) uStart.addEventListener("click", function () { callBridge("startUsbImport"); showToast("Đang gửi lệnh nhập USB..."); });
      var uCancel = document.getElementById("usbCancelBtn");
      if (uCancel) uCancel.addEventListener("click", function () { callBridge("cancelUsbImport"); showToast("Đang hủy nhập USB..."); });
    } else if (activeSubUi === "sleepSchedule") {
      titleEl.textContent = "LỊCH NGỦ TIẾT KIỆM ĐIỆN";
      var sl = s.sleep || {};
      var modal = document.getElementById("subUiModal");
      var dirty = modal && modal.getAttribute("data-dirty");
      var curMode = dirty ? ((document.getElementById("slModeVal") && document.getElementById("slModeVal").value) || "spindown") : (sl.mode || "spindown");
      var curEn = dirty ? (document.getElementById("slEnChk") && document.getElementById("slEnChk").checked) : !!sl.enabled;
      var curIdle = dirty ? (document.getElementById("slIdleChk") && document.getElementById("slIdleChk").checked) : (sl.idleOnly !== false);
      var curStart = dirty ? Number((document.getElementById("slStartInp") && document.getElementById("slStartInp").value) || 23) : (sl.startHour !== undefined ? sl.startHour : 23);
      var curEnd = dirty ? Number((document.getElementById("slEndInp") && document.getElementById("slEndInp").value) || 7) : (sl.endHour !== undefined ? sl.endHour : 7);
      html += '<div class="list-item"><div class="list-title">Trạng thái ổ cứng</div><span class="badge">' + (sl.hddState || "unknown") + '</span></div>' +
        '<div class="list-item"><div class="list-title">Bật lịch ngủ tự động</div><input type="checkbox" id="slEnChk" ' + (curEn ? "checked" : "") + ' style="width:18px;height:18px;accent-color:var(--ux-cyan);" /></div>' +
        '<div class="list-item" style="flex-direction:column;align-items:flex-start;gap:6px;">' +
          '<div class="list-title">Chế độ ngủ NAS</div>' +
          '<div class="seg-tabs" id="slModeGroup" style="width:100%;grid-template-columns:repeat(2,1fr);margin:0;">' +
            '<button type="button" class="seg-btn' + (curMode === "spindown" ? " active" : "") + '" data-slmode="spindown">Ngủ ổ cứng (Spindown)</button>' +
            '<button type="button" class="seg-btn' + (curMode === "suspend" ? " active" : "") + '" data-slmode="suspend">Ngủ toàn hệ thống</button>' +
          '</div>' +
          '<input type="hidden" id="slModeVal" value="' + curMode + '" />' +
        '</div>' +
        '<div class="list-item"><div class="list-title">Khung giờ (giờ)</div><div style="display:flex;gap:6px;align-items:center;"><input id="slStartInp" type="number" min="0" max="23" value="' + curStart + '" class="ux-input alert-num" style="width:54px;" /><span>–</span><input id="slEndInp" type="number" min="0" max="23" value="' + curEnd + '" class="ux-input alert-num" style="width:54px;" /></div></div>' +
        '<div class="list-item"><div class="list-title">Chỉ ngủ khi NAS rảnh</div><input type="checkbox" id="slIdleChk" ' + (curIdle ? "checked" : "") + ' style="width:18px;height:18px;accent-color:var(--ux-cyan);" /></div>' +
        (sl.message ? ('<div class="list-sub" style="padding:6px 0;color:var(--ux-cyan);">' + String(sl.message).replace(/</g, "&lt;") + '</div>') : '') +
        '<button class="btn-pill primary" id="slSaveBtn" style="width:100%;margin-top:10px;">Lưu lịch ngủ NAS</button>';
      bodyEl.innerHTML = html;
      bodyEl.querySelectorAll("[data-slmode]").forEach(function (btn) {
        btn.addEventListener("click", function () {
          var m = btn.getAttribute("data-slmode") || "spindown";
          var hInp = document.getElementById("slModeVal");
          if (hInp) hInp.value = m;
          bodyEl.querySelectorAll("[data-slmode]").forEach(function (b) { b.classList.toggle("active", b === btn); });
          modal.setAttribute("data-dirty", "1");
        });
      });
      ["slEnChk", "slStartInp", "slEndInp", "slIdleChk"].forEach(function (id) {
        document.getElementById(id).addEventListener("change", function () { modal.setAttribute("data-dirty", "1"); });
      });
      document.getElementById("slSaveBtn").addEventListener("click", function () {
        modal.removeAttribute("data-dirty");
        var chosenMode = (document.getElementById("slModeVal") && document.getElementById("slModeVal").value) || "spindown";
        callBridge("saveSleepSchedule", !!document.getElementById("slEnChk").checked, chosenMode, Number(document.getElementById("slStartInp").value || 23), Number(document.getElementById("slEndInp").value || 7), !!document.getElementById("slIdleChk").checked);
        showToast("Đã gửi cấu hình lịch ngủ NAS");
      });
    } else if (activeSubUi === "download") {
      titleEl.textContent = "TẢI XUỐNG / QBITTORRENT";
      var oldDl = (document.getElementById("dlLinkInp") && document.getElementById("dlLinkInp").value) || "";
      html += '<input class="ux-input" style="margin:0 0 10px;" id="dlLinkInp" placeholder="Dán link Magnet hoặc HTTP/HTTPS..." value="' + oldDl.replace(/"/g, "&quot;") + '" />' +
        '<div style="display:flex;gap:8px;">' +
          '<button class="btn-pill" id="dlPickBtn" style="flex:1;">Chọn file .torrent</button>' +
          '<button class="btn-pill primary" id="dlSendBtn" style="flex:1;">Tải về NAS</button>' +
        '</div>';
      bodyEl.innerHTML = html;
      document.getElementById("dlPickBtn").addEventListener("click", function () { callBridge("pickTorrentFile"); });
      document.getElementById("dlSendBtn").addEventListener("click", function () {
        var u = (document.getElementById("dlLinkInp").value || "").trim();
        if (u) { callBridge("sendDownloadLink", u); document.getElementById("subUiModal").classList.add("hidden"); }
      });
    } else if (activeSubUi === "duplicate") {
      titleEl.textContent = "QUÉT & DỌN TRÙNG LẶP";
      var dup = s.duplicate || {};
      var groups = dup.groups || [];
      var apiBase = s.apiBase || "";
      var dsch = dup.schedule || {};
      var curDupFilter = (modal && modal.getAttribute("data-dup-filter")) || "all";
      var firstKey = groups.length > 0 && groups[0].files && groups[0].files[0] ? groups[0].files[0].path : "";
      var lastKey = groups.length > 0 && groups[groups.length - 1].files && groups[groups.length - 1].files[0] ? groups[groups.length - 1].files[0].path : "";
      var dupSig = [dup.serverStatus || "", dup.nasCleanupActive,
        curDupFilter,
        dup.scanning ? 1 : 0,
        dup.paused ? 1 : 0,
        groups.length,
        dup.count || 0,
        dup.totalGroups || 0,
        dup.wastedBytes || 0,
        dup.imageGroups || 0,
        dup.videoGroups || 0,
        dup.deleting ? 1 : 0,
        dup.redundantFiles || 0,
        dup.originalFiles || 0,
        dup.sessionDeletedCount || 0,
        dup.sessionFreedBytes || 0,
        dsch.enabled !== false ? 1 : 0,
        dsch.autoClean ? 1 : 0,
        dsch.hour,
        dsch.intervalDays,
        dsch.lastRunAt || 0,
        dupVisibleLimit,
        firstKey,
        lastKey
      ].join("|");
      if (fromUpdate && dup.scanning) {
        var existingScanBox = document.getElementById("dupScanBox");
        if (existingScanBox) {
          var dpVal = Math.min(100, Math.max(0, Number(dup.percent || 0)));
          var elStage = document.getElementById("dupModalStage");
          var elPct = document.getElementById("dupModalPct");
          var elFolder = document.getElementById("dupModalFolder");
          var elFile = document.getElementById("dupModalFile");
          var elBar = document.getElementById("dupModalBar");
          var elCounts = document.getElementById("dupModalCounts");
          var elStatus = document.getElementById("dupModalStatusBadge");

          if (elStatus) {
            elStatus.style.color = COLOR_YELLOW;
            elStatus.innerHTML = '<span class="dot warn"></span>' + (dup.paused ? 'Đã tạm dừng' : 'Đang quét...');
          }
          if (elStage) elStage.textContent = String(dup.stage || "Đang phân tích...");
          if (elPct) elPct.textContent = dpVal.toFixed(1) + "%";
          if (elBar) elBar.style.width = dpVal.toFixed(1) + "%";
          if (elFolder) {
            if (dup.currentFolder) {
              elFolder.textContent = "Thư mục: " + dup.currentFolder;
              elFolder.classList.remove("hidden");
            } else {
              elFolder.classList.add("hidden");
            }
          }
          if (elFile) {
            if (dup.currentFile) {
              elFile.textContent = "Tệp: " + dup.currentFile;
              elFile.classList.remove("hidden");
            } else {
              elFile.classList.add("hidden");
            }
          }
          if (elCounts) elCounts.textContent = "Đã duyệt: " + (dup.scanned || 0).toLocaleString() + " • Ứng viên: " + (dup.found || 0).toLocaleString();

          var dt = getLiveDupTime();
          var elElap = document.getElementById("dupModalElapsed");
          var elEta = document.getElementById("dupModalEta");
          if (elElap) elElap.textContent = fmtDurSec(dt.elapsedSec);
          if (elEta) elEta.textContent = fmtEtaSec(dt.etaSec);

          lastDupModalSig = dupSig;
          return;
        }
      }
      if (fromUpdate && dup.deleting) {
        var existingDelBox = document.getElementById("dupDelBox");
        if (existingDelBox) {
          var dt = Math.max(1, Number(dup.delTotal || 0));
          var dp2 = Math.min(dt, Number(dup.delProcessed || 0));
          var dd = Number(dup.delDone || 0);
          var dr = Math.max(0, Number(dup.delRemaining != null ? dup.delRemaining : (dt - dp2)));
          var pct2 = Math.min(100, Math.max(0, (dp2 / dt) * 100));
          var elPct = document.getElementById("dupDelPct");
          var elCur = document.getElementById("dupDelCur");
          var elBar = document.getElementById("dupDelBar");
          var elStats = document.getElementById("dupDelStats");
          if (elPct) elPct.textContent = pct2.toFixed(1) + "%";
          if (elCur) elCur.innerHTML = "Đang xóa: <b>" + String(dup.delCurrentFile || "...").replace(/</g, "&lt;") + "</b>";
          if (elBar) elBar.style.width = pct2.toFixed(1) + "%";
          if (elStats) elStats.innerHTML = 'Đã chọn: <b>' + dt + '</b> • Đã xóa: <b style="color:var(--ux-green);">' + dd + '</b> • Còn lại: <b>' + dr + '</b>' + (dup.delBytesFreed > 0 ? (' • Giải phóng: <b style="color:var(--ux-green);">' + fmtBytes(dup.delBytesFreed) + '</b>') : '');

          var curRed = Number(dup.redundantFiles || 0);
          var curSessDel = Number(dup.sessionDeletedCount || 0) + dd;
          var curSessFreed = Number(dup.sessionFreedBytes || 0) + Number(dup.delBytesFreed || 0);
          var curRem = Math.max(0, curRed - dd);
          var curTotClean = curRed + Number(dup.sessionDeletedCount || 0);
          var curProgPct = curTotClean > 0 ? Math.min(100, Math.max(0, (curSessDel / curTotClean) * 100)) : 0;

          var elScoreDel = document.getElementById("dupScoreDeleted");
          var elScoreRem = document.getElementById("dupScoreRemaining");
          var elScoreDelSub = document.getElementById("dupScoreDeletedSub");
          var elCleanBar = document.getElementById("dupCleanProgBar");
          var elCleanPct = document.getElementById("dupCleanProgPct");
          var elCleanSub = document.getElementById("dupCleanProgSub");
          var elPillCleaned = document.getElementById("dupPillCleaned");
          var elPillRedundant = document.getElementById("dupPillRedundant");

          if (elPillCleaned) elPillCleaned.textContent = curSessDel.toLocaleString() + " tệp (" + fmtBytes(curSessFreed) + ")";
          if (elPillRedundant) elPillRedundant.textContent = curRem.toLocaleString() + " tệp";
          if (elScoreDel) elScoreDel.innerHTML = curSessDel.toLocaleString() + ' <span class="dup-score-unit">tệp</span>';
          if (elScoreDelSub) elScoreDelSub.textContent = "Giải phóng: " + fmtBytes(curSessFreed);
          if (elScoreRem) elScoreRem.innerHTML = curRem.toLocaleString() + ' <span class="dup-score-unit">tệp</span>';
          if (elCleanBar) elCleanBar.style.width = curProgPct.toFixed(1) + "%";
          if (elCleanPct) elCleanPct.textContent = curProgPct.toFixed(1) + "%";
          if (elCleanSub) elCleanSub.innerHTML = 'Đã dọn: <b style="color:var(--ux-green);">' + curSessDel.toLocaleString() + '</b> / ' + curTotClean.toLocaleString() + ' bản sao • Còn lại: <b style="color:var(--ux-cyan);">' + curRem.toLocaleString() + '</b> tệp thừa';

          lastDupModalSig = dupSig;
          return;
        }
      }
      if (fromUpdate && dupSig === lastDupModalSig && bodyEl.children.length > 0) {
        return;
      }
      var settingsOpen = !!document.getElementById("dupScanSettings")?.open;
      var prevDupScroll = 0;
      var prevDupListEl = document.getElementById("dupGroupsList");
      if (prevDupListEl) prevDupScroll = prevDupListEl.scrollTop || 0;
      lastDupModalSig = dupSig;

      var totG = dup.totalGroups || groups.length;
      var redundantFiles = dup.redundantFiles != null ? Number(dup.redundantFiles) : Math.max(0, (dup.count || 0) - totG);
      var originalFiles = dup.originalFiles != null ? Number(dup.originalFiles) : totG;
      var sessionDeleted = Number(dup.sessionDeletedCount || 0) + (dup.deleting ? Number(dup.delDone || 0) : 0);
      var sessionFreed = Number(dup.sessionFreedBytes || 0) + (dup.deleting ? Number(dup.delBytesFreed || 0) : 0);
      var remainingFiles = Math.max(0, redundantFiles - (dup.deleting ? Number(dup.delDone || 0) : 0));
      var totalToClean = redundantFiles + Number(dup.sessionDeletedCount || 0);
      var cleanProgressPct = totalToClean > 0 ? Math.min(100, Math.max(0, (sessionDeleted / totalToClean) * 100)) : (redundantFiles === 0 && sessionDeleted > 0 ? 100 : 0);

      var statusTxt = "";
      if (dup.scanning) {
        statusTxt = dup.paused ? "Đã tạm dừng" : "Đang quét...";
      } else if (dup.serverStatus) {
        statusTxt = String(dup.serverStatus).replace(/&/g, "&amp;").replace(/</g, "&lt;");
      } else if (groups.length === 0) {
        statusTxt = dup.delSkipped > 0 ? "Dọn chưa hết • bỏ qua " + Number(dup.delSkipped) + " tệp" : "Danh sách hiện tại trống";
      } else {
        statusTxt = totG.toLocaleString() + " nhóm • " + redundantFiles.toLocaleString() + " bản sao thừa (giữ " + originalFiles.toLocaleString() + " gốc)";
      }
      html += '<div class="list-item dup-status-row"><div class="list-title">Trạng thái</div><span class="badge" id="dupModalStatusBadge" style="color:' + (dup.scanning ? COLOR_YELLOW : COLOR_GREEN) + ';"><span class="dot' + (dup.scanning ? ' warn' : '') + '"></span>' + statusTxt + '</span></div>';

      var settingsStart;
      var settingsHtml = "";
      if (dup.scanning) {
        var dp = Math.min(100, Math.max(0, Number(dup.percent || 0)));
        var dt = getLiveDupTime();
        html += '<div id="dupScanBox" style="background:rgba(9,14,28,0.72);border:1px solid rgba(148,163,184,0.11);border-radius:var(--ux-radius-inner);padding:10px 11px;margin:8px 0;">' +
          '<div style="display:flex;justify-content:space-between;align-items:center;font-size:12px;font-weight:700;color:var(--ux-cyan);"><span id="dupModalStage">' + String(dup.stage || "Đang phân tích...").replace(/</g, "&lt;") + '</span><span id="dupModalPct">' + dp.toFixed(1) + '%</span></div>' +
          '<div id="dupModalFolder" class="list-sub' + (dup.currentFolder ? '' : ' hidden') + '" style="margin-top:3px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">' + (dup.currentFolder ? ('Thư mục: ' + String(dup.currentFolder).replace(/</g, "&lt;")) : '') + '</div>' +
          '<div id="dupModalFile" class="list-sub' + (dup.currentFile ? '' : ' hidden') + '" style="margin-top:2px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">' + (dup.currentFile ? ('Tệp: ' + String(dup.currentFile).replace(/</g, "&lt;")) : '') + '</div>' +
          '<div class="bar-track" style="margin:6px 0;"><div id="dupModalBar" class="bar-fill" style="width:' + dp.toFixed(1) + '%;background:var(--ux-purple);"></div></div>' +
          '<div style="font-size:11px;color:var(--ux-text-2);margin-bottom:5px;">⏱ Đã chạy: <b id="dupModalElapsed">' + fmtDurSec(dt.elapsedSec) + '</b> • Còn lại: <b id="dupModalEta">' + fmtEtaSec(dt.etaSec) + '</b></div>' +
          '<div style="display:flex;justify-content:space-between;align-items:center;font-size:11px;color:var(--ux-text-2);"><span id="dupModalCounts">Đã duyệt: ' + (dup.scanned || 0).toLocaleString() + ' • Ứng viên: ' + (dup.found || 0).toLocaleString() + '</span>' +
          '<div style="display:flex;gap:6px;"><button class="btn-pill danger" id="dupModalCancelBtn" style="padding:4px 10px;font-size:11px;">Hủy quét</button></div></div>' +
        '</div>';
      } else {
        settingsStart = html.length;
        html += '<div class="list-item"><div class="list-title">Động cơ quét</div><span class="badge" style="color:var(--ux-cyan);">Rust 3 tầng (100% NAS)</span></div>' +
          '<div class="list-item"><div class="list-title">Quét lại từ đầu</div><input type="checkbox" id="dupForceChk" style="width:18px;height:18px;accent-color:var(--ux-cyan);" /></div>' +
          '<button class="btn-pill primary" data-dup-start style="width:100%;margin-top:6px;">Bắt đầu quét trùng lặp (Rust)</button>';
      }

      if (dup.scanning) settingsStart = html.length;
      var schEn = dsch.enabled !== false;
      var schHr = (typeof dsch.hour === "number") ? dsch.hour : 3;
      var schDays = (typeof dsch.intervalDays === "number") ? dsch.intervalDays : 7;
      var lastRunTxt = dsch.lastRunAt > 0 ? ("Lần gần nhất: " + new Date(dsch.lastRunAt).toLocaleString("vi-VN") + " (" + (dsch.lastGroups || 0) + " nhóm)") : "Chưa chạy tự động";
      html += '<div style="background:rgba(9,14,28,0.65);border:1px solid rgba(148,163,184,0.12);border-radius:var(--ux-radius-inner);padding:8px 10px;margin:8px 0;">' +
        '<div style="display:flex;justify-content:space-between;align-items:center;">' +
          '<div><div class="list-title" style="font-size:12.5px;">Tự động quét ngầm trên NAS</div><div class="list-sub" style="font-size:10.5px;">' + lastRunTxt + '</div></div>' +
          '<input type="checkbox" id="dupSchedEnChk" ' + (schEn ? 'checked' : '') + ' style="width:18px;height:18px;accent-color:var(--ux-green);" />' +
        '</div>' +
        '<div style="margin-top:8px;">' +
          '<div style="font-size:11px;font-weight:700;color:var(--ux-text-2);margin-bottom:5px;">Chu kỳ tự quét:</div>' +
          '<div class="seg-tabs" id="dupSchedDaysGroup" style="grid-template-columns:repeat(4,1fr);margin-bottom:8px;">' +
            [[1, "Mỗi ngày"], [3, "3 ngày"], [7, "Hàng tuần"], [14, "2 tuần"]].map(function(opt){
              return '<button type="button" class="seg-btn' + (schDays === opt[0] ? ' active' : '') + '" data-dup-days="' + opt[0] + '" style="padding:6px 2px;font-size:11px;">' + opt[1] + '</button>';
            }).join('') +
          '</div>' +
          '<input type="hidden" id="dupSchedDaysVal" value="' + schDays + '" />' +
          '<div style="font-size:11px;font-weight:700;color:var(--ux-text-2);margin-bottom:5px;">Giờ tự quét ban đêm:</div>' +
          '<div class="chip-row" id="dupSchedHourGroup" style="padding-bottom:3px;margin-bottom:4px;">' +
            [0,1,2,3,4,5,6,12,22,23].map(function(h){
              return '<button type="button" class="chip' + (schHr === h ? ' active' : '') + '" data-dup-hour="' + h + '" style="font-size:11px;padding:4px 8px;flex-shrink:0;">' + (h < 10 ? '0' + h : h) + ':00</button>';
            }).join('') +
          '</div>' +
          '<input type="hidden" id="dupSchedHourVal" value="' + schHr + '" />' +
        '</div>' +
        '<div style="display:flex;justify-content:space-between;align-items:center;margin-top:8px;padding-top:8px;border-top:1px solid rgba(148,163,184,0.12);">' +
          '<div><div class="list-title" style="font-size:11.5px;color:var(--ux-cyan);">Tự động dọn dẹp sau khi quét</div><div class="list-sub" style="font-size:10px;">Giữ 1 bản gốc an toàn nhất, tự dọn các bản sao thừa</div></div>' +
          '<input type="checkbox" id="dupSchedAutoCleanChk" ' + (dsch.autoClean ? 'checked' : '') + ' style="width:18px;height:18px;accent-color:var(--ux-cyan);" />' +
        '</div>' +
      '</div>';

      if (groups.length) {
        settingsHtml = html.slice(settingsStart);
        html = html.slice(0, settingsStart);
      }

      if (dup.deleting) {
        var delTot = Math.max(1, Number(dup.delTotal || 0));
        var delProc = Math.min(delTot, Number(dup.delProcessed || 0));
        var delDone = Number(dup.delDone || 0);
        var delRem = Math.max(0, Number(dup.delRemaining != null ? dup.delRemaining : (delTot - delProc)));
        var delPct = Math.min(100, Math.max(0, (delProc / delTot) * 100));
        html += '<div id="dupDelBox" style="background:rgba(239,68,68,0.12);border:1px solid rgba(239,68,68,0.35);border-radius:var(--ux-radius-inner);padding:10px 11px;margin:8px 0;">' +
          '<div style="display:flex;justify-content:space-between;align-items:center;font-size:12px;font-weight:700;color:var(--ux-red);"><span>Đang xóa trùng (Rust SHA-256 + F_SETLEASE)</span><span id="dupDelPct">' + delPct.toFixed(1) + '%</span></div>' +
          '<div id="dupDelCur" class="list-sub" style="margin-top:3px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">Đang xóa: <b>' + String(dup.delCurrentFile || "...").replace(/</g, "&lt;") + '</b></div>' +
          '<div class="bar-track" style="margin:6px 0;"><div id="dupDelBar" class="bar-fill" style="width:' + delPct.toFixed(1) + '%;background:var(--ux-red);"></div></div>' +
          '<div style="display:flex;justify-content:space-between;align-items:center;font-size:11px;color:var(--ux-text-2);flex-wrap:wrap;gap:6px;">' +
            '<span id="dupDelStats">Đã chọn: <b>' + delTot + '</b> • Đã xóa: <b style="color:var(--ux-green);">' + delDone + '</b> • Còn lại: <b>' + delRem + '</b>' + (dup.delBytesFreed > 0 ? (' • Giải phóng: <b style="color:var(--ux-green);">' + fmtBytes(dup.delBytesFreed) + '</b>') : '') + '</span>' +
            '<button class="btn-pill danger" id="dupDelCancelBtn" style="padding:4px 10px;font-size:11px;">Dừng xóa</button>' +
          '</div>' +
        '</div>';
      }

      if (groups.length > 0 || sessionDeleted > 0) {
        var scannedTxt = dup.scanned ? fmtCount(dup.scanned) : "427K";
        html += '<div class="dup-hero-card">' +
          '<div class="dup-hero-title-row">' +
            '<div class="dup-hero-wasted-box">' +
              '<span class="dup-hero-wasted-val" id="dupHeroWasted">' + fmtBytes(dup.wastedBytes || 0) + '</span>' +
              '<span class="dup-hero-wasted-lbl">Lãng phí</span>' +
            '</div>' +
            '<button type="button" class="btn-pill" data-dup-start style="padding:5px 10px;font-size:11px;font-weight:700;display:flex;align-items:center;gap:4px;">' +
              '<span>🔄</span> Quét lại' +
            '</button>' +
          '</div>' +
          '<div class="dup-hero-sub" id="dupHeroSub">' +
            'Phát hiện <b>' + (groups.length).toLocaleString() + ' nhóm trùng</b> • Giữ <b>' + originalFiles.toLocaleString() + '</b> tệp gốc' +
          '</div>' +
          '<div class="dup-hero-pills">' +
            '<div class="dup-hero-pill red">' +
              '<span class="dup-pill-dot"></span>' +
              '<span>Cần dọn: <b id="dupPillRedundant">' + redundantFiles.toLocaleString() + '</b> tệp</span>' +
            '</div>' +
            (sessionDeleted > 0 ? ('<div class="dup-hero-pill green">' +
              '<span class="dup-pill-dot"></span>' +
              '<span>Đã dọn: <b id="dupPillCleaned">' + sessionDeleted.toLocaleString() + '</b> tệp (' + fmtBytes(sessionFreed) + ')</span>' +
            '</div>') : '') +
            '<div class="dup-hero-pill cyan">' +
              '<span class="dup-pill-dot"></span>' +
              '<span>Đã quét: <b id="dupPillScanned">' + scannedTxt + '</b> tệp</span>' +
            '</div>' +
          '</div>' +
        '</div>';
      }

      if (groups.length > 0) {
        var imgGCount = dup.imageGroups != null ? dup.imageGroups : groups.filter(function (g) { return g.type === "image" || (!g.type && isImgFile(g.files && g.files[0] && g.files[0].name)); }).length;
        var vidGCount = dup.videoGroups != null ? dup.videoGroups : groups.filter(function (g) { return g.type === "video" || (!g.type && isVidFile(g.files && g.files[0] && g.files[0].name)); }).length;
        var imgFiles = Number(dup.imageFiles || 0);
        var vidFiles = Number(dup.videoFiles || 0);
        var otherFiles = Number(dup.otherFiles || 0);
        var totFiles = Number(dup.count || (imgFiles + vidFiles + otherFiles) || 1);
        if (imgFiles === 0 && vidFiles === 0 && groups.length > 0) {
          groups.forEach(function (g) {
            var isGImg = g.type === "image" || (!g.type && isImgFile(g.files && g.files[0] && g.files[0].name));
            var isGVid = g.type === "video" || (!g.type && isVidFile(g.files && g.files[0] && g.files[0].name));
            if (isGImg) imgFiles += g.count;
            else if (isGVid) vidFiles += g.count;
            else otherFiles += g.count;
          });
          totFiles = imgFiles + vidFiles + otherFiles;
        }
        var imgWasted = dup.imageWasted || 0;
        var vidWasted = dup.videoWasted || 0;
        var imgRedundant = dup.redundantImgFiles != null ? Number(dup.redundantImgFiles) : Math.max(0, imgFiles - imgGCount);
        var vidRedundant = dup.redundantVidFiles != null ? Number(dup.redundantVidFiles) : Math.max(0, vidFiles - vidGCount);

        // Bộ lọc phân loại tab gọn gàng (hiển thị rõ số bản sao thừa cần dọn)
        html += '<div class="seg-tabs" id="dupFilterTabs" style="margin:4px 0 8px;">' +
          '<button type="button" class="seg-btn' + (curDupFilter === "all" ? " active" : "") + '" data-dupf="all">Tất cả (' + redundantFiles.toLocaleString() + ')</button>' +
          '<button type="button" class="seg-btn' + (curDupFilter === "image" ? " active" : "") + '" data-dupf="image">📷 Ảnh (' + imgRedundant.toLocaleString() + ')</button>' +
          '<button type="button" class="seg-btn' + (curDupFilter === "video" ? " active" : "") + '" data-dupf="video">🎬 Video (' + vidRedundant.toLocaleString() + ')</button>' +
        '</div>';

        // Lọc danh sách nhóm hiển thị theo tab
        var filteredGroups = groups.filter(function (g) {
          var isImgG = g.type === "image" || (!g.type && isImgFile(g.files && g.files[0] && g.files[0].name));
          var isVidG = g.type === "video" || (!g.type && isVidFile(g.files && g.files[0] && g.files[0].name));
          if (curDupFilter === "image") return isImgG;
          if (curDupFilter === "video") return isVidG;
          return true;
        });

        var curFilterRedundant = curDupFilter === "image" ? imgRedundant : (curDupFilter === "video" ? vidRedundant : redundantFiles);
        var curFilterWasted = curDupFilter === "image" ? imgWasted : (curDupFilter === "video" ? vidWasted : (dup.wastedBytes || 0));

        var visibleGroups = filteredGroups.slice(0, dupVisibleLimit);
        var wastedTxt = curFilterWasted > 0 ? (' • Lãng phí <b style="color:var(--ux-green);">' + fmtBytes(curFilterWasted) + '</b>') : '';
        html += '<div class="list-sub" style="margin:4px 0 6px;">Đang xem <b>' + visibleGroups.length + '/' + filteredGroups.length + '</b> nhóm • Cần dọn: <b style="color:var(--ux-red);">' + curFilterRedundant.toLocaleString() + '</b> bản sao' + wastedTxt + '</div>' +
        '<div id="dupGroupsList" aria-label="Nhóm trùng lặp">';

        visibleGroups.forEach(function (g, gIdx) {
          var isImgG = g.type === "image" || (!g.type && isImgFile(g.files && g.files[0] && g.files[0].name));
          var isVidG = g.type === "video" || (!g.type && isVidFile(g.files && g.files[0] && g.files[0].name));
          var badgeType = isImgG ? '<span class="badge" style="background:rgba(34,211,238,0.18);color:var(--ux-cyan);border:1px solid rgba(34,211,238,0.3);margin-right:6px;font-size:10px;font-weight:700;">📷 Ảnh</span>' : (isVidG ? '<span class="badge" style="background:rgba(251,146,60,0.18);color:var(--ux-orange);border:1px solid rgba(251,146,60,0.3);margin-right:6px;font-size:10px;font-weight:700;">🎬 Video</span>' : '<span class="badge" style="margin-right:6px;font-size:10px;">📁 Khác</span>');
          html += '<div class="dup-group-box">' +
            '<div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:6px;">' +
              '<div style="font-size:12px;font-weight:800;color:var(--ux-text);display:flex;align-items:center;">' + badgeType + 'Nhóm #' + (gIdx + 1) + ' • ' + g.count + ' tệp (' + fmtBytes(g.size || 0) + ' / tệp)</div>' +
              (g.wastedBytes > 0 ? ('<span style="font-size:10.5px;color:var(--ux-text-2);">Thừa: <b style="color:var(--ux-green);">' + fmtBytes(g.wastedBytes) + '</b></span>') : '') +
            '</div>';
          (g.files || []).forEach(function (f, fIdx) {
            var isFImg = f.isImage || isImgFile(f.name);
            var isFVid = f.isVideo || isVidFile(f.name);
            var thumbUrl = (isFImg || isFVid) ? thumbnailUrl(apiBase, f.relPath) : "";
            html += '<div style="display:flex;align-items:center;gap:8px;padding:6px 0;border-top:1px solid rgba(148,163,184,0.1);">' +
              '<input type="checkbox" class="dup-file-chk" data-gidx="' + gIdx + '" data-fidx="' + fIdx + '" data-isvid="' + (isFVid ? '1' : '0') + '" data-isimg="' + (isFImg ? '1' : '0') + '" data-size="' + (f.size || g.size || 0) + '" value="' + String(f.path || "").replace(/"/g, "&quot;") + '" ' + (fIdx > 0 ? "checked" : "") + ' style="width:18px;height:18px;accent-color:var(--ux-red);cursor:pointer;flex-shrink:0;" />' +
              '<div class="dup-preview-trigger" data-gidx="' + gIdx + '" data-fidx="' + fIdx + '" style="display:flex;align-items:center;gap:8px;flex:1;min-width:0;cursor:pointer;padding:3px 4px;border-radius:8px;transition:background 0.15s;">' +
                '<div style="position:relative;width:38px;height:38px;flex-shrink:0;">' +
                  (thumbUrl ? ('<img loading="lazy" decoding="async" src="' + thumbUrl + '" style="width:38px;height:38px;border-radius:7px;object-fit:cover;background:var(--ux-surface-elevated);box-shadow:0 2px 6px rgba(0,0,0,0.3);" onerror="this.style.display=\'none\'" />') : ('<div style="width:38px;height:38px;border-radius:7px;background:rgba(255,255,255,0.06);display:flex;align-items:center;justify-content:center;font-size:16px;">' + (isFVid ? '🎬' : '📷') + '</div>')) +
                  (isFVid ? '<span style="position:absolute;bottom:2px;right:2px;background:rgba(0,0,0,0.8);color:#fff;font-size:8px;padding:0 3px;border-radius:3px;box-shadow:0 1px 3px rgba(0,0,0,0.5);">▶</span>' : '') +
                '</div>' +
                '<div style="flex:1;min-width:0;">' +
                  '<div style="font-size:12px;font-weight:700;color:var(--ux-text);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">' +
                    String(f.name || "").replace(/</g, "&lt;") + (fIdx === 0 ? ' <span style="color:var(--ux-green);font-size:10px;font-weight:700;">(Bản gốc)</span>' : '') +
                  '</div>' +
                  '<div style="font-size:10.5px;color:var(--ux-text-2);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">' +
                    '/' + String(f.folder || "").replace(/</g, "&lt;") + ' • <span style="color:var(--ux-cyan);font-weight:700;">' + fmtBytes(f.size || g.size || 0) + '</span>' +
                  '</div>' +
                '</div>' +
                '<button type="button" class="neon-chip-view">Xem trước</button>' +
              '</div>' +
            '</div>';
          });
          html += '</div>';
        });

        if (filteredGroups.length > dupVisibleLimit) {
          html += '<button class="btn-pill" id="dupLoadMoreBtn" style="width:100%;margin-top:4px;">Xem thêm ' + Math.min(25, filteredGroups.length - dupVisibleLimit) + ' nhóm (còn ' + (filteredGroups.length - dupVisibleLimit) + ' nhóm)</button>';
        }
        html += '</div>' +
          '<div style="display:flex;gap:6px;margin:6px 0 10px;">' +
          '<button type="button" class="btn-pill primary" id="dupAutoCleanAllBtn" style="flex:1.2;padding:8px 6px;font-size:11.5px;font-weight:800;background:linear-gradient(135deg,#059669,#10b981);box-shadow:0 0 14px rgba(16,185,129,0.35);border:none;">⚡ Tự động dọn tất cả (' + curFilterRedundant.toLocaleString() + ' tệp)</button>' +
          '<button type="button" class="btn-pill" id="dupSmartSelBtn" style="flex:1;padding:8px 6px;font-size:11.5px;font-weight:700;">Chọn thông minh</button>' +
          '<button type="button" class="btn-pill danger" id="dupDelSelBtn" style="flex:0.9;padding:8px 6px;font-size:11.5px;font-weight:700;">Xóa đã chọn</button>' +
        '</div>';
        html += '<details id="dupScanSettings"><summary>Cài đặt quét và lịch tự động</summary>' + settingsHtml + '</details>';
      }

      bodyEl.innerHTML = html;
      var settingsPanel = document.getElementById("dupScanSettings");
      if (dup.nasCleanupActive) {
        bodyEl.querySelectorAll("[data-dup-start], #dupAutoCleanAllBtn, #dupDelSelBtn").forEach(function (button) {
          button.disabled = true;
        });
      }
      if (settingsPanel) settingsPanel.open = settingsOpen;
      var newDupListEl = document.getElementById("dupGroupsList");
      if (newDupListEl && prevDupScroll > 0) newDupListEl.scrollTop = prevDupScroll;

      // Event listeners cho các tab bộ lọc
      var filterTabs = document.getElementById("dupFilterTabs");
      if (filterTabs) {
        filterTabs.querySelectorAll("button").forEach(function (btn) {
          btn.addEventListener("click", function () {
            var targetFilter = btn.getAttribute("data-dupf") || "all";
            if (modal) modal.setAttribute("data-dup-filter", targetFilter);
            dupVisibleLimit = 25;
            lastDupModalSig = "";
            renderSubUiModal(false);
          });
        });
      }

      // Event listener cho xem trước (preview media)
      bodyEl.querySelectorAll(".dup-preview-trigger").forEach(function (trigger) {
        trigger.addEventListener("click", function (e) {
          e.stopPropagation();
          var gIdx = Number(trigger.getAttribute("data-gidx"));
          var fIdx = Number(trigger.getAttribute("data-fidx"));
          if (!isNaN(gIdx) && !isNaN(fIdx) && filteredGroups[gIdx]) {
            openDupPreview(filteredGroups[gIdx], fIdx, gIdx);
          }
        });
      });

      var dDelCancel = document.getElementById("dupDelCancelBtn");
      if (dDelCancel) dDelCancel.addEventListener("click", function () {
        lastDupModalSig = "";
        callBridge("cancelDuplicateDeletion");
        showToast("Đang dừng tiến trình xóa...");
      });

      var dMore = document.getElementById("dupLoadMoreBtn");
      if (dMore) dMore.addEventListener("click", function () {
        dupVisibleLimit += 25;
        lastDupModalSig = "";
        renderSubUiModal(false);
      });

      var dupForceEl = document.getElementById("dupForceChk");
      if (dupForceEl) dupForceEl.addEventListener("change", function () { if (modal) modal.setAttribute("data-dirty", "1"); });

      var saveDupSched = function () {
        var en = !!(document.getElementById("dupSchedEnChk") && document.getElementById("dupSchedEnChk").checked);
        var autoClean = !!(document.getElementById("dupSchedAutoCleanChk") && document.getElementById("dupSchedAutoCleanChk").checked);
        var days = Number((document.getElementById("dupSchedDaysVal") && document.getElementById("dupSchedDaysVal").value) || 7);
        var hr = Number((document.getElementById("dupSchedHourVal") && document.getElementById("dupSchedHourVal").value) || 3);
        callBridge("saveDuplicateSchedule", en, hr, days, autoClean);
        showToast(en ? ("Đã lưu lịch tự quét NAS: " + (days === 1 ? "Mỗi ngày" : (days + " ngày")) + " lúc " + (hr < 10 ? "0" + hr : hr) + ":00" + (autoClean ? " (Tự dọn bản sao)" : "")) : "Đã tắt lịch tự quét trùng lặp trên NAS");
      };
      ["dupSchedEnChk", "dupSchedAutoCleanChk"].forEach(function (id) {
        var el = document.getElementById(id);
        if (el) el.addEventListener("change", saveDupSched);
      });
      bodyEl.querySelectorAll("[data-dup-days]").forEach(function (btn) {
        btn.addEventListener("click", function () {
          var d = Number(btn.getAttribute("data-dup-days") || 7);
          var hiddenInp = document.getElementById("dupSchedDaysVal");
          if (hiddenInp) hiddenInp.value = d;
          bodyEl.querySelectorAll("[data-dup-days]").forEach(function (b) { b.classList.toggle("active", b === btn); });
          saveDupSched();
        });
      });
      bodyEl.querySelectorAll("[data-dup-hour]").forEach(function (btn) {
        btn.addEventListener("click", function () {
          var h = Number(btn.getAttribute("data-dup-hour") || 3);
          var hiddenInp = document.getElementById("dupSchedHourVal");
          if (hiddenInp) hiddenInp.value = h;
          bodyEl.querySelectorAll("[data-dup-hour]").forEach(function (b) { b.classList.toggle("active", b === btn); });
          saveDupSched();
        });
      });

      bodyEl.querySelectorAll("[data-dup-start]").forEach(function (dStart) { dStart.addEventListener("click", function () {
        if (modal) modal.removeAttribute("data-dirty");
        lastDupModalSig = "";
        var forceEl = document.getElementById("dupForceChk");
        callBridge("startDuplicateScan", !!(forceEl && forceEl.checked), false);
        showToast("Đã gửi lệnh quét trùng lặp Rust tới NAS...");
      }); });

      var dPause = document.getElementById("dupModalPauseBtn");
      if (dPause) dPause.addEventListener("click", function () { callBridge("togglePauseDuplicateScan"); });
      var dCancel = document.getElementById("dupModalCancelBtn");
      if (dCancel) dCancel.addEventListener("click", function () {
        lastDupModalSig = "";
        dupTimerState.active = false;
        callBridge("cancelDuplicateScan");
      });

      var updateDupDelBtn = function () {
        var btn = document.getElementById("dupDelSelBtn");
        if (!btn) return;
        var checkedBoxes = bodyEl.querySelectorAll(".dup-file-chk:checked");
        var cnt = checkedBoxes.length;
        var bytes = 0;
        var vidCount = 0;
        var imgCount = 0;
        checkedBoxes.forEach(function (chk) {
          bytes += Number(chk.getAttribute("data-size") || 0);
          if (chk.getAttribute("data-isvid") === "1") vidCount++;
          else if (chk.getAttribute("data-isimg") === "1") imgCount++;
        });
        if (cnt === 0) {
          btn.textContent = "Xóa mục đã chọn";
        } else {
          var label = "Xóa " + cnt + " mục";
          if (curDupFilter === "video" || (vidCount === cnt && cnt > 0)) label = "Xóa " + cnt + " video";
          else if (curDupFilter === "image" || (imgCount === cnt && cnt > 0)) label = "Xóa " + cnt + " ảnh";
          if (bytes > 0) label += " (" + fmtBytes(bytes) + ")";
          btn.textContent = label;
        }
      };
      updateDupDelBtn();

      // Nút Tự động dọn tất cả (1-chạm): duyệt qua toàn bộ các nhóm tệp trùng
      var dAutoClean = document.getElementById("dupAutoCleanAllBtn");
      if (dAutoClean) dAutoClean.addEventListener("click", function () {
        var totG = filteredGroups.length;
        var totVictims = 0;
        var imgVictims = 0;
        var vidVictims = 0;
        var totalWasted = 0;
        filteredGroups.forEach(function (g) {
          if (g.count > 1) {
            var vCount = g.count - 1;
            totVictims += vCount;
            totalWasted += (g.wastedBytes || (vCount * (g.size || 0)));
            var isGImg = g.type === "image" || (!g.type && isImgFile(g.files && g.files[0] && g.files[0].name));
            var isGVid = g.type === "video" || (!g.type && isVidFile(g.files && g.files[0] && g.files[0].name));
            if (isGImg) imgVictims += vCount;
            else if (isGVid) vidVictims += vCount;
          }
        });
        if (totVictims <= 0) {
          showToast("Không có tệp trùng lặp nào để tự động dọn dẹp");
          return;
        }
        var filterLabel = curDupFilter === "image" ? "ảnh" : (curDupFilter === "video" ? "video" : "tệp");
        openDupAutoCleanModal(totG, totVictims, imgVictims, vidVictims, totalWasted, filterLabel);
      });

      // Nút Chọn thông minh: thông minh theo tab hiện tại!
      var dSmart = document.getElementById("dupSmartSelBtn");
      if (dSmart) dSmart.addEventListener("click", function () {
        bodyEl.querySelectorAll(".dup-file-chk").forEach(function (chk) {
          chk.checked = Number(chk.getAttribute("data-fidx")) > 0;
        });
        if (modal) modal.setAttribute("data-dirty", "1");
        updateDupDelBtn();
        var filterMsg = curDupFilter === "image" ? "ảnh" : (curDupFilter === "video" ? "video" : "");
        showToast("Đã chọn các bản sao " + filterMsg + ", giữ lại 1 bản gốc mỗi nhóm");
      });

      // Nút Xóa mục đã chọn: mở hộp thoại xác nhận chi tiết an toàn
      var dDel = document.getElementById("dupDelSelBtn");
      if (dDel) dDel.addEventListener("click", function () {
        if (dDel.disabled) return;
        var paths = [];
        var imgCount = 0;
        var vidCount = 0;
        var totalBytes = 0;
        bodyEl.querySelectorAll(".dup-file-chk:checked").forEach(function (chk) {
          paths.push(chk.value);
          totalBytes += Number(chk.getAttribute("data-size") || 0);
          if (chk.getAttribute("data-isvid") === "1") vidCount++;
          else if (chk.getAttribute("data-isimg") === "1") imgCount++;
        });
        if (!paths.length) { showToast("Chưa chọn tệp trùng nào để xóa"); return; }
        openDupConfirmModal(paths, imgCount, vidCount, totalBytes);
      });

      bodyEl.querySelectorAll(".dup-file-chk").forEach(function (chk) {
        chk.addEventListener("change", function () {
          if (modal) modal.setAttribute("data-dirty", "1");
          updateDupDelBtn();
        });
      });
    } else if (activeSubUi === "organizer") {
      titleEl.textContent = "PHÂN LOẠI TỆP AI";
      var org = s.organizer || {};
      var curFilter = (modal && modal.getAttribute("data-org-filter")) || "all";
      var curPath = (document.getElementById("orgPathInp") && document.getElementById("orgPathInp").value) || "";
      html += '<div class="seg-tabs" id="orgFilterTabs" style="margin-bottom:8px;">' +
        '<button type="button" class="seg-btn' + (curFilter === "all" ? " active" : "") + '" data-orgf="all">Tất cả</button>' +
        '<button type="button" class="seg-btn' + (curFilter === "image" ? " active" : "") + '" data-orgf="image">Chỉ ảnh</button>' +
        '<button type="button" class="seg-btn' + (curFilter === "video" ? " active" : "") + '" data-orgf="video">Chỉ video</button>' +
      '</div>' +
      '<input class="ux-input" style="margin:0 0 8px;" id="orgPathInp" placeholder="Thư mục nguồn (để trống = toàn bộ NAS)..." value="' + curPath.replace(/"/g, "&quot;") + '" />' +
      '<div style="display:flex;gap:8px;margin-bottom:10px;">' +
        '<button class="btn-pill primary" id="orgScanBtn" style="flex:1;">' + (org.scanning ? "Đang phân tích..." : "Phân tích & Xem trước") + '</button>' +
        ((org.groups && org.groups.length > 0) ? ('<button class="btn-pill" id="orgExecBtn" style="flex:1;background:rgba(102,187,106,0.18);color:var(--ux-green);border-color:rgba(102,187,106,0.35);">' + (org.executing ? "Đang sắp xếp..." : "Thực thi sắp xếp") + '</button>') : '') +
      '</div>';
      if (org.error) html += '<div class="list-sub" style="color:var(--ux-red);margin-bottom:8px;">' + String(org.error).replace(/</g, "&lt;") + '</div>';
      if (org.result) html += '<div class="list-sub" style="color:var(--ux-green);margin-bottom:8px;font-weight:700;">' + String(org.result).replace(/</g, "&lt;") + '</div>';
      if (org.groups && org.groups.length > 0) {
        html += '<div class="list-sub" style="margin-bottom:6px;">Tổng cộng: <b>' + (org.totalFiles || 0) + ' tệp</b> trong ' + org.groups.length + ' nhóm Năm/Tháng:</div>' +
          '<div style="max-height:260px;overflow-y:auto;display:flex;flex-direction:column;gap:6px;">';
        org.groups.forEach(function (g) {
          html += '<div style="background:rgba(9,14,28,0.72);border:1px solid rgba(148,163,184,0.11);border-radius:var(--ux-radius-inner);padding:8px 10px;">' +
            '<div style="display:flex;justify-content:space-between;font-size:12px;font-weight:700;"><span style="color:var(--ux-cyan);">' + String(g.label || "").replace(/</g, "&lt;") + '</span><span>' + (g.count || 0) + ' tệp • ' + fmtBytes(g.size || 0) + '</span></div>' +
            ((g.samples && g.samples.length) ? ('<div class="list-sub" style="margin-top:3px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">Mẫu: ' + g.samples.map(function (n) { return String(n).replace(/</g, "&lt;"); }).join(", ") + '</div>') : '') +
          '</div>';
        });
        html += '</div>';
      }
      bodyEl.innerHTML = html;
      bodyEl.querySelectorAll("[data-orgf]").forEach(function (btn) {
        btn.addEventListener("click", function () {
          if (modal) modal.setAttribute("data-org-filter", btn.getAttribute("data-orgf") || "all");
          renderSubUiModal(false);
        });
      });
      var oScan = document.getElementById("orgScanBtn");
      if (oScan) oScan.addEventListener("click", function () {
        if (modal) modal.removeAttribute("data-dirty");
        var f = (modal && modal.getAttribute("data-org-filter")) || "all";
        var p = (document.getElementById("orgPathInp").value || "").trim();
        callBridge("smartOrganizeScan", f, p);
        showToast("Đang phân tích cấu trúc tệp trên NAS...");
      });
      var oExec = document.getElementById("orgExecBtn");
      if (oExec) oExec.addEventListener("click", function () {
        if (modal) modal.removeAttribute("data-dirty");
        var f = (modal && modal.getAttribute("data-org-filter")) || "all";
        var p = (document.getElementById("orgPathInp").value || "").trim();
        callBridge("smartOrganizeExecute", f, p);
        showToast("Đang sắp xếp tệp tự động trên NAS...");
      });
    } else if (activeSubUi === "about") {
      titleEl.textContent = "GIỚI THIỆU ỨNG DỤNG";
      html += '<div class="list-item"><div class="list-title">Ứng dụng</div><span class="badge">NAS WebDAV</span></div>' +
        '<div class="list-item"><div class="list-title">Phiên bản</div><span class="badge">' + (s.versionName || "1.0") + '</span></div>' +
        '<div class="list-item"><div class="list-title">Máy chủ</div><span class="badge">' + (s.connLabel || "NAS") + '</span></div>' +
        '<div class="list-sub" style="padding:10px 0;">Ứng dụng quản lý NAS Chainedbox L1 Pro qua WebDAV &amp; API đồng bộ HTML5 + Native.</div>';
      bodyEl.innerHTML = html;
    }
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
    if (!item) return;
    activeModalFile = item;
    var apiBase = (state && state.apiBase) ? state.apiBase.replace(/\/+$/, "") : "";
    var thumbEl = document.getElementById("modalFileThumb");
    var nameEl = document.getElementById("modalFileName");
    var metaEl = document.getElementById("modalFileMeta");
    var inputEl = document.getElementById("modalRenameInput");

    if (nameEl) nameEl.textContent = item.name;
    var dateStr = item.mtime ? new Date(item.mtime * 1000).toLocaleString("vi-VN") : "";
    var metaStr = item.isDir ? "Thư mục" : (fmtBytes(item.size) + (dateStr ? " • " + dateStr : ""));
    if (metaEl) metaEl.textContent = metaStr;
    if (inputEl) inputEl.value = item.name;

    // Reset collapsible containers
    var rBox = document.getElementById("modalRenameContainer");
    if (rBox) rBox.classList.add("hidden");
    var dBox = document.getElementById("modalDetailsContainer");
    if (dBox) dBox.classList.add("hidden");
    var cBox = document.getElementById("modalDeleteConfirmBox");
    if (cBox) cBox.classList.add("hidden");

    // Populate details
    var dfName = document.getElementById("detFileName");
    if (dfName) dfName.textContent = item.name;
    var dfPath = document.getElementById("detFilePath");
    if (dfPath) dfPath.textContent = item.path || item.relPath || "--";
    var dfSize = document.getElementById("detFileSize");
    if (dfSize) dfSize.textContent = item.isDir ? "Thư mục" : (fmtBytes(item.size) + " (" + (item.size || 0).toLocaleString() + " bytes)");
    var dfDate = document.getElementById("detFileDate");
    if (dfDate) dfDate.textContent = dateStr || "Không rõ";

    // Set thumbnail or SVG icon
    if (thumbEl) {
      var relP = item.relPath || (item.path && item.path.indexOf("/webdav/") >= 0 ? item.path.substring(item.path.indexOf("/webdav/")) : "");
      var thumbUrl = (!item.isDir && (isImgFile(item.name) || isVidFile(item.name)))
        ? thumbnailUrl(apiBase, relP, item) : "";
      if (thumbUrl) {
        thumbEl.innerHTML = '<img src="' + thumbUrl + '" style="width:100%;height:100%;object-fit:cover;pointer-events:none;" onerror="this.style.display=\'none\'" />';
      } else if (item.isDir) {
        thumbEl.innerHTML = '<svg class="svg-ico" style="width:24px;height:24px;color:var(--ux-cyan);" viewBox="0 0 24 24"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/></svg>';
      } else if (isVidFile(item.name)) {
        thumbEl.innerHTML = '<svg class="svg-ico" style="width:22px;height:22px;color:var(--ux-purple);" viewBox="0 0 24 24"><rect x="2" y="5" width="15" height="14" rx="2"/><polygon points="22 7 17 12 22 17 22 7"/></svg>';
      } else {
        thumbEl.innerHTML = '<svg class="svg-ico" style="width:22px;height:22px;color:var(--ux-text-2);" viewBox="0 0 24 24"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>';
      }
    }

    var openLbl = document.getElementById("modalOpenLbl");
    if (openLbl) openLbl.textContent = item.isDir ? "Mở thư mục" : "Mở tệp";

    document.getElementById("fileActionModal").classList.remove("hidden");
  }

  function drawChart() {
    var canvas = document.getElementById("metricsCanvas");
    if (!canvas || !state) return;
    var history = state.metrics || [];
    var dpr = window.devicePixelRatio || 1;
    var rect = canvas.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) return;

    var lastPointTs = history.length > 0 ? (history[history.length - 1].ts || "") : "";
    var chartSig = [
      chartTab, chartRange, zoomScale.toFixed(2), panFraction.toFixed(2), touchedIdx,
      Math.round(rect.width), Math.round(rect.height), history.length, lastPointTs,
      state.metricsLoading ? "L" : (state.metricsError || "OK")
    ].join("|");
    if (chartSig === lastChartSignature) return;
    lastChartSignature = chartSig;

    canvas.width = Math.round(rect.width * dpr);
    canvas.height = Math.round(rect.height * dpr);
    var ctx = canvas.getContext("2d");
    ctx.scale(dpr, dpr);

    var w = rect.width;
    var h = rect.height;
    ctx.clearRect(0, 0, w, h);

    if (history.length < 2) {
      ctx.fillStyle = (state && state.metricsLoading) ? "#38bdf8" : "#64748b";
      ctx.font = "12px sans-serif";
      ctx.textAlign = "center";
      var msg = (state && state.metricsLoading)
        ? "Đang tải dữ liệu biểu đồ (" + chartRange + "h)..."
        : ((state && state.metricsError) ? ("Lỗi tải biểu đồ: " + state.metricsError) : "Chưa đủ dữ liệu biểu đồ");
      ctx.fillText(msg, w / 2, h / 2);
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
      var halfLabel = ctx.measureText(hhmm).width / 2;
      var labelX = Math.max(halfLabel + 2, Math.min(vx, w - halfLabel - 2));
      ctx.fillText(hhmm, labelX, h - 5);
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

      ctx.lineWidth = 1.6;
      ctx.lineCap = "round";
      ctx.lineJoin = "round";
      if (s.dashed) ctx.setLineDash([4, 3]);
      else ctx.setLineDash([]);
      for (var i = 0; i < pts.length - 1; i++) {
        var c1 = s.colorFn ? s.colorFn(pts[i]) : s.fallback;
        var c2 = s.colorFn ? s.colorFn(pts[i + 1]) : s.fallback;
        ctx.strokeStyle = lerpHex(c1, c2, 0.5);
        ctx.beginPath();
        ctx.moveTo(xOf(i), yOf(pts[i]));
        ctx.lineTo(xOf(i + 1), yOf(pts[i + 1]));
        ctx.stroke();
      }
      if (s.dashed) ctx.setLineDash([]);

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
    bindDialogNavigation();
    startSmoothClockLoop();
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
    var bgHdr = document.getElementById("bgTasksHeader");
    if (bgHdr) {
      bgHdr.addEventListener("click", function () {
        bgTasksExpanded = !bgTasksExpanded;
        renderBgTasks();
      });
    }

    var dlHdr = document.getElementById("dashLogsHeader");
    if (dlHdr) {
      dlHdr.addEventListener("click", function (e) {
        if (e.target && e.target.closest("button")) return;
        dashLogsExpanded = !dashLogsExpanded;
        renderDashLogs();
      });
    }
    var dlRef = document.getElementById("dashLogsRefreshBtn");
    if (dlRef) {
      dlRef.addEventListener("click", function (e) {
        e.stopPropagation();
        callBridge("fetchLogs");
        showToast("Đang tải nhật ký...");
      });
    }
    var dlClr = document.getElementById("dashLogsClearBtn");
    if (dlClr) {
      dlClr.addEventListener("click", function (e) {
        e.stopPropagation();
        callBridge("clearLogs");
        showToast("Đã xóa nhật ký hệ thống");
      });
    }
    var dlOpen = document.getElementById("dashLogsOpenModalBtn");
    if (dlOpen) {
      dlOpen.addEventListener("click", function () {
        openLogsModal();
      });
    }
    document.querySelectorAll("[data-log-filter]").forEach(function (btn) {
      btn.addEventListener("click", function (e) {
        e.stopPropagation();
        dashLogsFilter = btn.getAttribute("data-log-filter") || "all";
        renderDashLogs();
      });
    });

    function dispatchHtml5OrNativeAction(act) {
      if (act === "procCpu") openProcessModal("cpu");
      else if (act === "procMem") openProcessModal("mem");
      else if (act === "smartDetails" || act === "diskProfile") openSmartModal();
      else if (act === "toolbox") openToolboxModal();
      else if (act === "backup" || act === "sync") openAutoBackupModal();
      else if (act === "smb") openSmbModal();
      else if (act === "docker") openDockerModal();
      else if (act === "logs") openLogsModal();
      else if (act === "livestream" || act === "stream") openLivestreamModal();
      else if (act === "guest" || act === "social" || act === "performance" || act === "lanWhitelist" || act === "bandwidth" || act === "nasBackup" || act === "failedUploads" || act === "usbImport" || act === "sleepSchedule" || act === "download" || act === "duplicate" || act === "organizer" || act === "about") openSubUiModal(act);
      else callBridge("action", act);
    }

    document.querySelectorAll("[data-action]").forEach(function (el) {
      el.addEventListener("click", function () {
        dispatchHtml5OrNativeAction(el.getAttribute("data-action"));
      });
    });

    document.getElementById("toolboxCloseBtn").addEventListener("click", function () { document.getElementById("toolboxModal").classList.add("hidden"); });
    document.getElementById("tbBioChk").addEventListener("change", function (e) {
      callBridge("setBiometric", !!e.target.checked);
      showToast(e.target.checked ? "Đã bật khóa vân tay" : "Đã tắt khóa vân tay");
    });
    document.querySelectorAll("[data-tbact]").forEach(function (el) {
      el.addEventListener("click", function () {
        document.getElementById("toolboxModal").classList.add("hidden");
        dispatchHtml5OrNativeAction(el.getAttribute("data-tbact"));
      });
    });
    document.getElementById("abCloseBtn").addEventListener("click", function () { document.getElementById("autoBackupModal").classList.add("hidden"); });
    document.getElementById("abEnabledChk").addEventListener("change", function () { document.getElementById("autoBackupModal").setAttribute("data-dirty", "1"); });
    document.getElementById("abDeleteChk").addEventListener("change", function () {
      document.getElementById("autoBackupModal").setAttribute("data-dirty", "1");
      callBridge("setDeleteAfterBackup", !!document.getElementById("abDeleteChk").checked);
    });
    document.querySelectorAll("#abModeGroup .ab-mode-btn").forEach(function (btn) {
      btn.addEventListener("click", function () {
        var mode = btn.getAttribute("data-mode") || "both";
        document.getElementById("autoBackupModal").setAttribute("data-dirty", "1");
        if (state && state.toolbox) state.toolbox.backupMode = mode;
        if (state && state.bgTasks && state.bgTasks.backup) state.bgTasks.backup.backupMode = mode;
        applyBackupModeButtons(mode);
        callBridge("setBackupMode", mode);
        renderAutoBackupModal();
        renderBgTasks();
      });
    });
    document.getElementById("abSaveBtn").addEventListener("click", function () {
      var mode = getSelectedBackupMode();
      document.getElementById("autoBackupModal").removeAttribute("data-dirty");
      callBridge("saveAutoBackup", !!document.getElementById("abEnabledChk").checked, !!document.getElementById("abDeleteChk").checked, mode);
      document.getElementById("autoBackupModal").classList.add("hidden");
    });
    document.getElementById("abSyncNowBtn").addEventListener("click", function () {
      callBridge("setBackupMode", getSelectedBackupMode());
      callBridge("triggerAutoBackup");
      showToast("Đang bắt đầu đồng bộ lên NAS...");
    });
    document.getElementById("abPauseBtn").addEventListener("click", function () {
      callBridge("toggleAutoBackupPause");
    });
    document.getElementById("abCancelBtn").addEventListener("click", function () {
      callBridge("cancelAutoBackup");
      showToast("Đang hủy tiến trình đồng bộ...");
    });
    document.getElementById("smbCloseBtn").addEventListener("click", function () { document.getElementById("smbModal").classList.add("hidden"); });
    document.getElementById("smbToggleBtn").addEventListener("click", function () {
      var tb = (state && state.toolbox) || {};
      callBridge("toggleSmb", !tb.smbEnabled);
      showToast("Đang chuyển trạng thái Samba...");
    });
    document.getElementById("dockerCloseBtn").addEventListener("click", function () { document.getElementById("dockerModal").classList.add("hidden"); });
    document.getElementById("dockerRefreshBtn").addEventListener("click", refreshDockerModal);
    document.getElementById("logsCloseBtn").addEventListener("click", function () { document.getElementById("logsModal").classList.add("hidden"); });
    document.getElementById("logsRefreshBtn").addEventListener("click", function () { callBridge("fetchLogs"); showToast("Đang tải nhật ký..."); });
    document.getElementById("liveCloseBtn").addEventListener("click", function () { document.getElementById("livestreamModal").classList.add("hidden"); });
    document.getElementById("subUiCloseBtn").addEventListener("click", function () { document.getElementById("subUiModal").classList.add("hidden"); });
    document.getElementById("subUiRefreshBtn").addEventListener("click", function () { openSubUiModal(activeSubUi); showToast("Đang cập nhật..."); });

    var prevCloseBtn = document.getElementById("dupPrevCloseBtn");
    if (prevCloseBtn) prevCloseBtn.addEventListener("click", closeDupPreview);
    var prevOkBtn = document.getElementById("dupPrevOkBtn");
    if (prevOkBtn) prevOkBtn.addEventListener("click", closeDupPreview);
    var prevExtBtn = document.getElementById("dupPrevExternalBtn");
    if (prevExtBtn) prevExtBtn.addEventListener("click", function () {
      if (curPreviewGroup && curPreviewGroup.files && curPreviewGroup.files[curPreviewFileIdx]) {
        callBridge("openDuplicateMedia", curPreviewGroup.files[curPreviewFileIdx].path);
      }
    });

    var confCloseBtn = document.getElementById("dupConfirmCloseBtn");
    if (confCloseBtn) confCloseBtn.addEventListener("click", closeDupConfirmModal);
    var confCancelBtn = document.getElementById("dupConfirmCancelBtn");
    if (confCancelBtn) confCancelBtn.addEventListener("click", closeDupConfirmModal);
    var confExecBtn = document.getElementById("dupConfirmExecBtn");
    if (confExecBtn) confExecBtn.addEventListener("click", function () {
      if (!pendingDeletePaths || !pendingDeletePaths.length) {
        closeDupConfirmModal();
        return;
      }
      var paths = pendingDeletePaths.slice();
      closeDupConfirmModal();
      var dDel = document.getElementById("dupDelSelBtn");
      if (dDel) {
        dDel.disabled = true;
        dDel.textContent = "Đang xóa " + paths.length + " tệp...";
      }
      var modal = document.getElementById("subUiModal");
      if (modal) modal.removeAttribute("data-dirty");
      lastDupModalSig = "";
      callBridge("deleteDuplicateFiles", JSON.stringify(paths));
      showToast("Đang xóa " + paths.length + " tệp trùng theo lô trên NAS...");
    });

    var autoCleanCloseBtn = document.getElementById("dupAutoCleanCloseBtn");
    if (autoCleanCloseBtn) autoCleanCloseBtn.addEventListener("click", closeDupAutoCleanModal);
    var autoCleanCancelBtn = document.getElementById("dupAutoCleanCancelBtn");
    if (autoCleanCancelBtn) autoCleanCancelBtn.addEventListener("click", closeDupAutoCleanModal);
    var autoCleanExecBtn = document.getElementById("dupAutoCleanExecBtn");
    if (autoCleanExecBtn) autoCleanExecBtn.addEventListener("click", function () {
      closeDupAutoCleanModal();
      var modal = document.getElementById("subUiModal");
      if (modal) modal.removeAttribute("data-dirty");
      lastDupModalSig = "";
      var curFilter = (modal && modal.getAttribute("data-dup-filter")) || "all";
      callBridge("autoCleanAllDuplicates", curFilter);
      showToast("Đang bắt đầu tự động dọn dẹp toàn bộ tệp trùng...");
    });
    document.getElementById("liveRefreshBtn").addEventListener("click", function () { callBridge("refreshLivestream"); showToast("Đang cập nhật Livestream..."); });
    document.getElementById("liveTabRecordBtn").addEventListener("click", function () { liveTab = "record"; renderLivestreamModal(); });
    document.getElementById("liveTabWatchBtn").addEventListener("click", function () { liveTab = "watch"; renderLivestreamModal(); });
    document.getElementById("liveUrlInput").addEventListener("input", function () { callBridge("clearLivestreamMessage"); renderLivestreamModal(); });
    document.querySelectorAll("[data-lqual]").forEach(function (btn) {
      btn.addEventListener("click", function () {
        liveQuality = btn.getAttribute("data-lqual");
        document.querySelectorAll("[data-lqual]").forEach(function (b) { b.classList.toggle("active", b === btn); });
      });
    });
    document.getElementById("liveStartBtn").addEventListener("click", function () {
      var u = (document.getElementById("liveUrlInput").value || "").trim();
      if (u) callBridge("startLivestream", u, liveQuality);
    });
    document.getElementById("liveQueueBtn").addEventListener("click", function () {
      var u = (document.getElementById("liveUrlInput").value || "").trim();
      if (u) callBridge("enqueueLivestream", u, liveQuality, "");
    });
    document.getElementById("liveTry720Btn").addEventListener("click", function () {
      var u = (document.getElementById("liveUrlInput").value || "").trim();
      liveQuality = "720p";
      if (u) callBridge("startLivestream", u, "720p");
    });
    document.getElementById("liveWatchAddBtn").addEventListener("click", function () {
      var inp = document.getElementById("liveWatchInput");
      var u = (inp.value || "").trim().replace(/^@+/, "");
      if (u) { callBridge("addTikTokWatch", u); inp.value = ""; showToast("Đã thêm @" + u); }
    });
    ["liveExChk", "liveExStart", "liveExEnd"].forEach(function (id) {
      document.getElementById(id).addEventListener("change", function () { document.getElementById("liveWatchPane").setAttribute("data-dirty", "1"); });
    });
    document.getElementById("liveExSaveBtn").addEventListener("click", function () {
      document.getElementById("liveWatchPane").removeAttribute("data-dirty");
      callBridge("saveTikTokWatchSchedule", !!document.getElementById("liveExChk").checked, document.getElementById("liveExStart").value || "23:00", document.getElementById("liveExEnd").value || "07:00");
      showToast("Đã lưu lịch nghỉ theo dõi TikTok");
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
        var customBox = document.getElementById("fanCustomBox");
        var customBtn = document.querySelector('[data-fan="custom"]');
        var curMode = (state && state.system && state.system.fanMode) || "";

        if (mode === "custom") {
          if (curMode !== "custom") {
            fanCustomExpanded = true;
            if (customBox) {
              customBox.removeAttribute("data-dirty");
              customBox.classList.remove("hidden");
            }
            if (customBtn) customBtn.textContent = "Tùy chỉnh ▴";
            callBridge("setFanMode", "custom");
            showToast("Đã chuyển chế độ quạt: Tùy chỉnh (chạm lại để ẩn/hiện)");
          } else {
            // Đã ở chế độ Tùy chỉnh: chạm vào nút để ẩn/hiện bảng cài đặt ngưỡng
            fanCustomExpanded = !fanCustomExpanded;
            if (customBox) {
              customBox.removeAttribute("data-dirty");
              customBox.classList.toggle("hidden", !fanCustomExpanded);
            }
            if (customBtn) {
              customBtn.textContent = fanCustomExpanded ? "Tùy chỉnh ▴" : "Tùy chỉnh ▾";
            }
            showToast(fanCustomExpanded ? "Đã mở cài đặt ngưỡng quạt" : "Đã thu gọn cài đặt ngưỡng quạt");
          }
        } else {
          fanCustomExpanded = false;
          if (customBox) {
            customBox.removeAttribute("data-dirty");
            customBox.classList.add("hidden");
          }
          if (customBtn) customBtn.textContent = "Tùy chỉnh";
          callBridge("setFanMode", mode);
          showToast("Đã chuyển chế độ quạt: " + (mode === "auto" ? "Tự động" : "Dừng"));
        }
      });
    });
    var ruleHintEl = document.getElementById("fanRuleHint");
    if (ruleHintEl) {
      ruleHintEl.addEventListener("click", function () {
        var curMode = (state && state.system && state.system.fanMode) || "";
        if (curMode === "custom") {
          fanCustomExpanded = !fanCustomExpanded;
          var customBox = document.getElementById("fanCustomBox");
          if (customBox) {
            customBox.removeAttribute("data-dirty");
            customBox.classList.toggle("hidden", !fanCustomExpanded);
          }
          var customBtn = document.querySelector('[data-fan="custom"]');
          if (customBtn) {
            customBtn.textContent = fanCustomExpanded ? "Tùy chỉnh ▴" : "Tùy chỉnh ▾";
          }
        }
      });
    }
    var fanOnSl = document.getElementById("fanOnSlider");
    var fanOffSl = document.getElementById("fanOffSlider");
    if (fanOnSl && fanOffSl) {
      fanOnSl.addEventListener("input", function () {
        document.getElementById("fanCustomBox").setAttribute("data-dirty", "1");
        var v = Number(fanOnSl.value);
        if (Number(fanOffSl.value) >= v) {
          fanOffSl.value = Math.max(28, v - 2);
          document.getElementById("fanOffVal").textContent = fanOffSl.value + "°C";
        }
        document.getElementById("fanOnVal").textContent = v + "°C";
      });
      fanOffSl.addEventListener("input", function () {
        document.getElementById("fanCustomBox").setAttribute("data-dirty", "1");
        var v = Number(fanOffSl.value);
        if (v >= Number(fanOnSl.value)) {
          fanOnSl.value = Math.min(58, v + 2);
          document.getElementById("fanOnVal").textContent = fanOnSl.value + "°C";
        }
        document.getElementById("fanOffVal").textContent = v + "°C";
      });
    }
    var applyFanBtn = document.getElementById("fanApplyCustomBtn");
    if (applyFanBtn && fanOnSl && fanOffSl) {
      applyFanBtn.addEventListener("click", function () {
        var onV = Number(fanOnSl.value);
        var offV = Number(fanOffSl.value);
        var customBox = document.getElementById("fanCustomBox");
        if (customBox) {
          customBox.removeAttribute("data-dirty");
          // Tự động thu gọn sau khi lưu để tránh chạm kéo nhầm khi cuộn
          fanCustomExpanded = false;
          customBox.classList.add("hidden");
        }
        var customBtn = document.querySelector('[data-fan="custom"]');
        if (customBtn) {
          customBtn.textContent = "Tùy chỉnh ▾";
        }
        callBridge("setFanCustom", onV, offV);
        showToast("Đã lưu ngưỡng quạt: Bật ≥ " + onV + "°C • Tắt ≤ " + offV + "°C");
      });
    }
    var closeFanBtn = document.getElementById("fanCloseCustomBtn");
    if (closeFanBtn) {
      closeFanBtn.addEventListener("click", function () {
        fanCustomExpanded = false;
        var customBox = document.getElementById("fanCustomBox");
        if (customBox) {
          customBox.removeAttribute("data-dirty");
          customBox.classList.add("hidden");
        }
        var customBtn = document.querySelector('[data-fan="custom"]');
        if (customBtn) {
          customBtn.textContent = "Tùy chỉnh ▾";
        }
      });
    }
    document.querySelectorAll("[data-thumb]").forEach(function (el) {
      el.addEventListener("click", function () {
        var act = el.getAttribute("data-thumb");
        callBridge("thumbControl", act);
        showToast("Đã gửi lệnh Thumbnail: " + act);
      });
    });
    document.querySelectorAll("[data-range]").forEach(function (el) {
      el.addEventListener("click", function () {
        var newRange = Number(el.getAttribute("data-range"));
        chartRange = newRange;
        touchedIdx = -1;
        document.querySelectorAll("[data-range]").forEach(function (btn) {
          btn.classList.toggle("active", Number(btn.getAttribute("data-range")) === chartRange);
        });
        callBridge("setMetricsRange", chartRange);
        showToast("Đang tải biểu đồ " + chartRange + "h...");
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
    document.getElementById("fileUploadBtn").addEventListener("click", function () { callBridge("pickUploadFiles"); });
    document.getElementById("fileSelectBtn").addEventListener("click", function () {
      selectMode = !selectMode;
      selectedPaths = {};
      renderFiles();
    });
    document.getElementById("batchCancelBtn").addEventListener("click", function () {
      selectMode = false;
      selectedPaths = {};
      renderFiles();
    });
    document.getElementById("batchDeleteBtn").addEventListener("click", function () {
      var paths = Object.keys(selectedPaths);
      if (!paths.length) return;
      callBridge("fileDeleteBatch", JSON.stringify(paths));
      showToast("Đang xóa " + paths.length + " mục...");
      selectMode = false;
      selectedPaths = {};
      renderFiles();
    });
    document.getElementById("batchDownloadBtn").addEventListener("click", function () {
      var paths = Object.keys(selectedPaths);
      if (!paths.length) return;
      callBridge("fileDownloadBatch", JSON.stringify(paths));
      showToast("Đang tải " + paths.length + " tệp về Downloads/NASWebDAV...");
    });
    document.getElementById("batchShareBtn").addEventListener("click", function () {
      var keys = Object.keys(selectedPaths);
      if (keys.length !== 1) return;
      var list = (activeFileSnapshot() || {}).items || [];
      var item = list.find(function (f) { return f.path === keys[0]; });
      if (item && item.relPath) callBridge("createShareLink", item.relPath);
    });
    document.getElementById("fileRefreshBtn").addEventListener("click", function () {
      if (fileFilter === "timeline") {
        timelineLoading = true;
        renderFiles();
        callBridge("fetchTimeline", 0);
      } else {
        lastFilesRenderSig = "";
        renderFiles();
        callBridge("fileRefresh");
        showToast("Đang tải lại thư mục...");
      }
    });
    document.getElementById("fileViewToggleBtn").addEventListener("click", function () {
      fileViewMode = fileViewMode === "grid3" ? "grid6" : (fileViewMode === "grid6" ? "list" : "grid3");
      updateViewToggleUI();
      var label = fileViewMode === "grid3" ? "Lưới lớn (3 cột)" : (fileViewMode === "grid6" ? "Lưới nhỏ (6 cột)" : "Danh sách chi tiết");
      showToast("Chế độ xem: " + label);
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
        if (fileFilter === "timeline") {
          timelineLoading = true;
          callBridge("fetchTimeline", 0);
        }
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
    var mCancel = document.getElementById("modalCancelBtn");
    if (mCancel) {
      mCancel.addEventListener("click", function () {
        document.getElementById("fileActionModal").classList.add("hidden");
      });
    }

    var mOpen = document.getElementById("modalOpenBtn");
    if (mOpen) {
      mOpen.addEventListener("click", function () {
        if (!activeModalFile) return;
        document.getElementById("fileActionModal").classList.add("hidden");
        callBridge("openFile", activeModalFile.path);
      });
    }

    var mDownload = document.getElementById("modalDownloadBtn");
    if (mDownload) {
      mDownload.addEventListener("click", function () {
        if (!activeModalFile) return;
        document.getElementById("fileActionModal").classList.add("hidden");
        callBridge("fileDownloadBatch", JSON.stringify([activeModalFile.path]));
        showToast("Bắt đầu tải về: " + activeModalFile.name);
      });
    }

    var mShare = document.getElementById("modalShareBtn");
    if (mShare) {
      mShare.addEventListener("click", function () {
        if (activeModalFile && activeModalFile.relPath) {
          callBridge("createShareLink", activeModalFile.relPath);
        }
        document.getElementById("fileActionModal").classList.add("hidden");
      });
    }

    var mDelete = document.getElementById("modalDeleteBtn");
    if (mDelete) {
      mDelete.addEventListener("click", function () {
        var cBox = document.getElementById("modalDeleteConfirmBox");
        if (cBox) cBox.classList.toggle("hidden");
      });
    }

    var mDelCancel = document.getElementById("modalDeleteCancelBtn");
    if (mDelCancel) {
      mDelCancel.addEventListener("click", function () {
        var cBox = document.getElementById("modalDeleteConfirmBox");
        if (cBox) cBox.classList.add("hidden");
      });
    }

    var mDelConfirm = document.getElementById("modalDeleteConfirmBtn");
    if (mDelConfirm) {
      mDelConfirm.addEventListener("click", function () {
        if (!activeModalFile) return;
        callBridge("fileDelete", activeModalFile.path);
        showToast("Đang chuyển vào Thùng rác...");
        document.getElementById("fileActionModal").classList.add("hidden");
      });
    }

    var rToggle = document.getElementById("fActRenameToggle");
    if (rToggle) {
      rToggle.addEventListener("click", function () {
        var rBox = document.getElementById("modalRenameContainer");
        if (rBox) {
          rBox.classList.toggle("hidden");
          if (!rBox.classList.contains("hidden")) {
            var inp = document.getElementById("modalRenameInput");
            if (inp) { inp.focus(); inp.select(); }
          }
        }
      });
    }

    var rCancel = document.getElementById("modalRenameCancelBtn");
    if (rCancel) {
      rCancel.addEventListener("click", function () {
        var rBox = document.getElementById("modalRenameContainer");
        if (rBox) rBox.classList.add("hidden");
      });
    }

    var rBtn = document.getElementById("modalRenameBtn");
    if (rBtn) {
      rBtn.addEventListener("click", function () {
        if (!activeModalFile) return;
        var nn = document.getElementById("modalRenameInput").value.trim();
        if (nn && nn !== activeModalFile.name) {
          callBridge("fileRename", JSON.stringify({ path: activeModalFile.path, name: nn }));
          showToast("Đang đổi tên...");
        }
        document.getElementById("fileActionModal").classList.add("hidden");
      });
    }

    var sRow = document.getElementById("fActSelectRow");
    if (sRow) {
      sRow.addEventListener("click", function () {
        if (!activeModalFile) return;
        selectMode = true;
        selectedPaths[activeModalFile.path] = true;
        document.getElementById("fileActionModal").classList.add("hidden");
        renderFiles();
        showToast("Đã chọn: " + activeModalFile.name);
      });
    }

    var cRow = document.getElementById("fActCopyLinkRow");
    if (cRow) {
      cRow.addEventListener("click", function () {
        if (!activeModalFile) return;
        var p = activeModalFile.path || activeModalFile.relPath || "";
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(p);
        } else {
          var ta = document.createElement("textarea");
          ta.value = p;
          document.body.appendChild(ta);
          ta.select();
          document.execCommand("copy");
          document.body.removeChild(ta);
        }
        showToast("Đã sao chép liên kết");
        document.getElementById("fileActionModal").classList.add("hidden");
      });
    }

    var dToggle = document.getElementById("fActDetailsToggle");
    if (dToggle) {
      dToggle.addEventListener("click", function () {
        var dBox = document.getElementById("modalDetailsContainer");
        if (dBox) dBox.classList.toggle("hidden");
      });
    }
    var alertCard = document.getElementById("alertCard");
    if (alertCard) {
      alertCard.querySelectorAll("input").forEach(function (inp) {
        inp.addEventListener("input", function () { alertCard.setAttribute("data-dirty", "1"); });
        inp.addEventListener("change", function () { alertCard.setAttribute("data-dirty", "1"); });
      });
      document.getElementById("saveAlertsBtn").addEventListener("click", function () {
        alertCard.removeAttribute("data-dirty");
        callBridge("saveAlertRules", JSON.stringify({
          enabled: document.getElementById("alertEnabledChk").checked,
          hddTemp: Number(document.getElementById("alertHddInput").value || 50),
          cpuTemp: Number(document.getElementById("alertCpuInput").value || 90),
          diskPct: Number(document.getElementById("alertDiskInput").value || 90),
          smart: document.getElementById("alertSmartChk").checked
        }));
        showToast("Đã lưu cài đặt cảnh báo thông minh");
      });
    }

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

    window.addEventListener("resize", function () {
      drawChart();
    });
  });

  function activeFileSnapshot() {
    return state && fileSnapshot && state.baseUrl === fileSnapshot.baseUrl && state.user === fileSnapshot.user
      ? fileSnapshot : null;
  }

  window.NasUI = {
    closeTopModal: closeTopModal,
    setView: function (view) {
      var requested = view || "dashboard";
      if (requested !== currentView) {
        while (closeTopModal()) {}
      }
      currentView = requested;
      renderView();
      renderState();
      if (currentView === "files") renderFiles();
    },
    updateState: function (newState) {
      var connectionChanged = !state || state.baseUrl !== newState.baseUrl || state.user !== newState.user || state.apiBase !== newState.apiBase;
      state = newState;
      renderState();
      if (currentView === "files" && connectionChanged) renderFiles();
    },
    updateFiles: function (files) {
      fileSnapshot = files;
      var sig = JSON.stringify([files.baseUrl, files.user, files.title, files.items]);
      if (sig !== fileRowsSignature) {
        fileRowsSignature = sig;
        fileRowsRevision++;
      }
      if (currentView === "files" && fileFilter !== "timeline") renderFiles();
    },
    onTimelineLoaded: function (data) {
      timelineData = data || { total: 0, items: [] };
      timelineLoading = false;
      if (currentView === "files" && fileFilter === "timeline") {
        renderFiles();
      }
    }
  };

  startSmoothClockLoop();
})();
