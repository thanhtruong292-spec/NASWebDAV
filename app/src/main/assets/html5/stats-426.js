/* Lucian UX/UI Prompt #426 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/#426 */
(function () {
  var motion = window.matchMedia("(prefers-reduced-motion: reduce)");
  var states = new Map();
  var playedByScreen = new WeakMap();
  var screenSelector = 'section[id^="view-"], .ux-modal-backdrop';
  var excluded = 'script,style,input,textarea,select,svg,canvas,.u415,.u277,.u080,.u472-lg,.u472-si,.u426,.list-title,.file-name,.f-name,.file-label,#modalFileName,#detFileName,#bgBakCurFile,.app-tile-title,.app-tile-sub,[id*="Path"],[id*="Date"],[id$="File"],#dashLogsList,#logsListContainer';
  var metric = /(?:^|[^\p{L}\d.])\d[\d.,]*(?:\s*(?:%|°[CF]|(?:[KMGT]i?B|B|bytes|ms|Mbps|Gbps|rpm|giây|phút|giờ|ngày|tệp|ảnh|video|nhóm|máy|lần|mục|log|bản sao)(?![\p{L}\d_.])))/iu;
  var bare = /^\s*[-+]?\d[\d.,]*(?:\s*[/·•]\s*\d[\d.,]*)*\s*$/;
  var numericContext = '.chart-legend,.cat-meta,.file-size,#smartCleanSummary,#smartModalLog,#bgTasksBody,#dupConfirmMsg,#dupAutoCleanMsg,#subUiBody,#abDevicesList';
  var digits = "01234567890123456789";
  var digitMarkup = digits.split("").map(function (digit) { return "<b>" + digit + "</b>"; }).join("");

  function settle(cell, state) {
    clearTimeout(state.timer);
    if (state.visual && state.visual.parentNode === cell) cell.textContent = state.value;
    state.visual = null;
    state.spoken = null;
  }

  function roll(cell, state, hover) {
    if (motion.matches || state.visual || !/\d/.test(cell.textContent)) return;
    state.value = cell.textContent;
    var visual = document.createElement("span");
    var spoken = document.createElement("span");
    var tracks = [];
    visual.setAttribute("aria-hidden", "true");
    spoken.className = "u426-spoken";
    spoken.textContent = state.value;
    Array.from(state.value).forEach(function (char) {
      if (!/\d/.test(char)) {
        visual.appendChild(document.createTextNode(char));
        return;
      }
      var wheel = document.createElement("span");
      var track = document.createElement("span");
      wheel.className = "u426-wheel";
      track.className = "u426-track";
      track.innerHTML = digitMarkup;
      track.style.setProperty("--u426-order", tracks.length);
      track.style.setProperty("--u426-digit", hover ? char : "0");
      wheel.appendChild(track);
      visual.appendChild(wheel);
      tracks.push([track, Number(char) + 10]);
    });
    state.spoken = spoken;
    state.visual = visual;
    cell.textContent = "";
    cell.appendChild(spoken);
    cell.appendChild(visual);
    void visual.offsetWidth;
    visual.classList.add("u426-roll");
    tracks.forEach(function (entry) {
      entry[0].style.setProperty("--u426-digit", entry[1]);
    });
    state.timer = setTimeout(function () { settle(cell, state); }, 1300 + (tracks.length - 1) * 70 + 34);
  }

  function reveal(cell, state) {
    if (!state.visible || state.played || !/\d/.test(cell.textContent)) return;
    state.played = true;
    if (state.key) {
      var played = playedByScreen.get(state.screen);
      if (!played) {
        played = new Set();
        playedByScreen.set(state.screen, played);
      }
      if (played.has(state.key)) return;
      played.add(state.key);
    }
    roll(cell, state, false);
  }

  if (!("IntersectionObserver" in window)) return;

  var visibility = new IntersectionObserver(function (entries) {
    entries.forEach(function (entry) {
      var cell = entry.target;
      var state = states.get(cell);
      if (!state) return;
      state.visible = entry.isIntersecting;
      if (!state.visible) {
        state.played = false;
        settle(cell, state);
      } else {
        reveal(cell, state);
      }
    });
  });

  function track(cell, screen, key) {
    if (states.has(cell)) return;
    states.set(cell, { visible: false, played: false, visual: null, screen: screen, key: key });
    visibility.observe(cell);
  }

  function inspectText(node) {
    var parent = node.parentElement;
    if (!parent || !/\d/.test(node.data) || parent.closest(excluded)) return;
    var item = parent.closest("#subUiBody .list-item");
    var subtitle = item && item.querySelector(".list-sub");
    if (subtitle && /^Pass:/i.test(subtitle.textContent)) return;
    var screen = parent.closest(screenSelector);
    if (!screen || (!metric.test(node.data) && !(bare.test(node.data) && parent.closest(numericContext)))) return;
    var key = String(Array.prototype.indexOf.call(parent.childNodes, node));
    for (var el = parent; el !== screen; el = el.parentElement) {
      if (el.id) { key = el.id + "/" + key; break; }
      key = Array.prototype.indexOf.call(el.parentElement.children, el) + "/" + key;
    }
    var cell = document.createElement("span");
    cell.className = "u426";
    parent.insertBefore(cell, node);
    cell.appendChild(node);
    track(cell, screen, key);
  }

  function inspect(node) {
    if (node.nodeType === Node.TEXT_NODE) {
      inspectText(node);
      return;
    }
    if (node.nodeType !== Node.ELEMENT_NODE || node.closest(excluded)) return;
    var walker = document.createTreeWalker(node, NodeFilter.SHOW_TEXT);
    var textNodes = [];
    while (walker.nextNode()) textNodes.push(walker.currentNode);
    textNodes.forEach(inspectText);
  }

  var values = new MutationObserver(function (entries) {
    var removed = false;
    entries.forEach(function (entry) {
      if (entry.type === "attributes") {
        if (entry.target.matches(screenSelector) && entry.target.classList.contains("hidden")) {
          playedByScreen.delete(entry.target);
        }
        return;
      }
      var cell = entry.target;
      var state = states.get(cell);
      if (state) {
        if (state.visual && state.visual.parentNode === cell) return;
        if (state.visual) settle(cell, state);
        reveal(cell, state);
        return;
      }
      if (cell.nodeType === Node.TEXT_NODE) { inspectText(cell); return; }
      if (entry.removedNodes.length) removed = true;
      entry.addedNodes.forEach(inspect);
    });
    if (removed) states.forEach(function (state, cell) {
      if (cell.isConnected) return;
      clearTimeout(state.timer);
      visibility.unobserve(cell);
      states.delete(cell);
    });
  });

  document.querySelectorAll(".u426").forEach(function (cell) { track(cell); });
  document.querySelectorAll(screenSelector).forEach(inspect);
  values.observe(document.body, {
    childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ["class"]
  });
  document.body.addEventListener("pointerover", function (event) {
    var cell = event.target.closest(".u426");
    if (event.pointerType !== "mouse" || !cell || cell.contains(event.relatedTarget)) return;
    var state = states.get(cell);
    if (state && state.visible && state.played) roll(cell, state, true);
  });

  window.NasStatsMotion = {
    setText: function (cell, value) {
      var state = states.get(cell);
      value = String(value);
      if (state && state.visual && state.visual.parentNode === cell) {
        if (!/\d/.test(value)) {
          settle(cell, state);
        } else {
          state.value = value;
          state.spoken.textContent = value;
          return;
        }
      }
      if (cell.textContent !== value) cell.textContent = value;
    }
  };

  if (motion.addEventListener) motion.addEventListener("change", function () {
    if (motion.matches) states.forEach(function (state, cell) { settle(cell, state); });
  });
})();
