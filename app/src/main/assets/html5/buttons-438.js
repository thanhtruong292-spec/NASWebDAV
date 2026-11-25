/* Lucian UX/UI Prompt #438 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/#438 */
(function () {
  var active = new WeakMap();
  var status = document.createElement("span");
  status.className = "u438-status";
  status.setAttribute("role", "status");
  document.body.appendChild(status);

  function reset(button, state) {
    clearTimeout(state.timer);
    while (state.label.firstChild) button.insertBefore(state.label.firstChild, state.label);
    state.label.remove();
    state.indicator.remove();
    if (state.ring) state.ring.remove();
    button.removeAttribute("data-u438-state");
    button.removeAttribute("aria-busy");
    button.removeAttribute("aria-disabled");
    active.delete(button);
  }

  document.addEventListener("click", function (event) {
    var button = event.target.closest(".u438 button, .u438 [role=button]");
    if (!button || button.disabled) return;
    if (button.hasAttribute("data-u438-state")) {
      event.preventDefault();
      event.stopImmediatePropagation();
      return;
    }
    if (button.getAttribute("aria-disabled") === "true" ||
        window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    button.classList.remove("u438-tapped");
    void button.offsetWidth;
    button.classList.add("u438-tapped");
  }, true);
  document.addEventListener("animationend", function (event) {
    if (event.animationName === "u438-release") event.target.classList.remove("u438-tapped");
  });

  window.NasButtonFeedback = {
    start: function (id) {
      var button = document.getElementById(id);
      if (!button || active.has(button)) return;
      var label = document.createElement("span");
      label.className = "u438-label";
      while (button.firstChild) label.appendChild(button.firstChild);
      var spinner = document.createElement("i");
      spinner.className = "u438-spinner";
      spinner.setAttribute("aria-hidden", "true");
      button.classList.remove("u438-tapped");
      button.appendChild(label);
      button.appendChild(spinner);
      button.dataset.u438State = "busy";
      button.setAttribute("aria-busy", "true");
      button.setAttribute("aria-disabled", "true");
      var state = { label: label, indicator: spinner };
      active.set(button, state);
      state.timer = setTimeout(function () {
        reset(button, state);
        status.textContent = "Chưa nhận được kết quả";
      }, 30000);
      status.textContent = "Đang tải";
    },
    finish: function (id, success) {
      var button = document.getElementById(id);
      var state = active.get(button);
      if (!state || button.dataset.u438State !== "busy") return;
      if (!success) {
        reset(button, state);
        status.textContent = "Không tải được dữ liệu";
        return;
      }
      clearTimeout(state.timer);
      state.indicator.remove();
      var check = document.createElementNS("http://www.w3.org/2000/svg", "svg");
      check.setAttribute("class", "u438-check");
      check.setAttribute("viewBox", "0 0 24 24");
      check.setAttribute("aria-hidden", "true");
      var path = document.createElementNS(check.namespaceURI, "path");
      path.setAttribute("d", "m5.5 12.5 4.5 4.5 8.5-9.5");
      check.appendChild(path);
      state.indicator = check;
      state.ring = document.createElement("i");
      state.ring.className = "u438-ring";
      button.appendChild(check);
      button.appendChild(state.ring);
      button.dataset.u438State = "ok";
      button.removeAttribute("aria-busy");
      status.textContent = "Đã cập nhật";
      state.timer = setTimeout(function () { reset(button, state); }, 1000);
    },
    reset: function (id) {
      var button = document.getElementById(id);
      var state = active.get(button);
      if (state) reset(button, state);
    }
  };
})();
