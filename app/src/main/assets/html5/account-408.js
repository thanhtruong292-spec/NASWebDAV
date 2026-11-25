/* Lucian UX/UI Prompt #408 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/#408 */
(function () {
  var root = document.getElementById("accountControl");
  var trigger = document.getElementById("accountTrigger");
  var menu = document.getElementById("accountMenu");
  var user = document.getElementById("accountUser");
  var server = document.getElementById("accountServer");
  var initial = document.getElementById("accountInitial");
  var dot = document.getElementById("accountDot");
  var items = Array.from(menu.querySelectorAll('[role="menuitem"]'));

  function notifyAndroid(open) {
    var bridge = window.NasBridge;
    if (bridge && typeof bridge.onModalVisibilityChanged === "function") bridge.onModalVisibilityChanged(open);
  }

  function isOpen() {
    return root.classList.contains("is-open");
  }

  function open(index) {
    menu.inert = false;
    root.classList.add("is-open");
    trigger.setAttribute("aria-expanded", "true");
    notifyAndroid(true);
    if (index != null) {
      requestAnimationFrame(function () {
        if (isOpen()) items[index].focus({ preventScroll: true });
      });
    }
  }

  function close(restoreFocus) {
    if (!isOpen()) return false;
    trigger.setAttribute("aria-expanded", "false");
    if (restoreFocus) trigger.focus({ preventScroll: true });
    menu.inert = true;
    root.classList.remove("is-open");
    notifyAndroid(false);
    return true;
  }

  trigger.addEventListener("click", function (event) {
    if (isOpen()) close(false);
    else open(event.detail === 0 ? 0 : null);
  });
  trigger.addEventListener("keydown", function (event) {
    if (event.key !== "ArrowDown" && event.key !== "ArrowUp") return;
    event.preventDefault();
    open(event.key === "ArrowDown" ? 0 : items.length - 1);
  });
  root.addEventListener("keydown", function (event) {
    if (!isOpen()) return;
    if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      close(true);
    } else if (event.key === "Tab") {
      if (menu.contains(document.activeElement)) trigger.focus({ preventScroll: true });
      close(false);
    } else if (menu.contains(document.activeElement)) {
      var current = items.indexOf(document.activeElement);
      var target = {
        ArrowDown: (current + 1) % items.length,
        ArrowUp: (current - 1 + items.length) % items.length,
        Home: 0, End: items.length - 1
      }[event.key];
      if (target == null) return;
      event.preventDefault();
      items[target].focus({ preventScroll: true });
    }
  });
  items.forEach(function (item) {
    item.addEventListener("click", function () { close(true); });
  });
  document.addEventListener("pointerdown", function (event) {
    if (!root.contains(event.target)) close(false);
  }, true);

  window.NasAccountMenu = {
    close: close,
    update: function (state) {
      var name = String(state.user || "").trim();
      var host = "";
      try { host = new URL(state.baseUrl).host; } catch (e) {}
      var description = [host, state.connLabel || ""].filter(Boolean).join(" · ");
      var first = name ? (Array.from(name)[0] || "?").toLocaleUpperCase("vi") : "?";
      if (user.textContent !== (name || "Tài khoản NAS")) user.textContent = name || "Tài khoản NAS";
      if (server.textContent !== description) server.textContent = description;
      if (initial.textContent !== first) initial.textContent = first;
      dot.classList.toggle("is-unknown", typeof state.connOk !== "boolean");
      dot.classList.toggle("is-offline", state.connOk === false);
    }
  };
})();
