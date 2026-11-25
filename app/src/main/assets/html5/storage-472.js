/* Lucian UX/UI Prompt #472 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/#472 */
(function () {
  var root = document.getElementById("storageCard");
  var bar = document.getElementById("storageMeter");
  var legend = document.getElementById("storageCategories");
  var summary = document.getElementById("storageMeterSummary");
  var details = root.querySelector(".u472-si");
  var current = -1;
  var pinned = -1;
  var data = [];
  var segments = [];
  var buttons = [];
  var signature = "";

  function fileShare(percent) {
    if (percent > 0 && percent < 0.01) return "< 0,01% số tệp";
    return percent.toLocaleString("vi-VN", { maximumFractionDigits: 2 }) + "% số tệp";
  }

  function select(index) {
    if (current === index) return;
    current = index;
    root.classList.toggle("is-hl", index >= 0);
    segments.forEach(function (segment, i) { segment.classList.toggle("is-on", i === index); });
    buttons.forEach(function (button, i) { button.classList.toggle("is-on", i === index); });
    if (index < 0) return;
    var entry = data[index];
    details.children[0].style.backgroundColor = entry.color;
    details.children[1].textContent = entry.label;
    details.children[2].textContent = entry.size;
    details.children[3].textContent = "· " + entry.files + " tệp · " + fileShare(entry.percent);
    details.classList.remove("is-tick");
    void details.offsetWidth;
    details.classList.add("is-tick");
  }

  function start() {
    root.classList.add("is-in");
  }

  if ("IntersectionObserver" in window) {
    var observer = new IntersectionObserver(function (entries) {
      root.classList.toggle("is-in", entries[0].isIntersecting);
    });
    observer.observe(bar);
  } else start();

  bar.addEventListener("pointerleave", function (event) {
    if (event.pointerType === "mouse") select(pinned);
  });
  root.addEventListener("keydown", function (event) {
    if (event.key !== "Escape" || current < 0) return;
    pinned = -1;
    select(-1);
    buttons.forEach(function (button) { button.setAttribute("aria-pressed", "false"); });
  });

  window.NasStorageMeter = {
    render: function (entries, formattedTotal) {
      if (summary.textContent !== formattedTotal) summary.textContent = formattedTotal;
      var nextSignature = JSON.stringify(entries);
      if (nextSignature === signature) return;
      signature = nextSignature;
      var focusedIndex = buttons.indexOf(document.activeElement);
      data = entries;
      segments = [];
      buttons = [];
      bar.textContent = "";
      bar.classList.toggle("is-empty", !entries.some(function (entry) { return entry.percent > 0; }));
      legend.textContent = "";
      entries.forEach(function (entry, index) {
        var segment = document.createElement("span");
        segment.className = "u472-s";
        segment.hidden = entry.percent <= 0;
        segment.style.setProperty("--u472-i", index);
        segment.style.setProperty("--u472-w", entry.percent + "%");
        segment.style.setProperty("--u472-c", entry.color);
        segment.addEventListener("pointerenter", function (event) {
          if (event.pointerType === "mouse") select(index);
        });
        segments.push(segment);
        bar.appendChild(segment);

        var row = document.createElement("li");
        var button = document.createElement("button");
        var dot = document.createElement("i");
        var caption = document.createElement("span");
        button.type = "button";
        button.style.setProperty("--u472-c", entry.color);
        caption.textContent = entry.label + " · " + entry.size;
        button.setAttribute("aria-label", caption.textContent + " · " + entry.files + " tệp · " + fileShare(entry.percent));
        button.setAttribute("aria-pressed", String(index === pinned));
        dot.setAttribute("aria-hidden", "true");
        button.appendChild(dot);
        button.appendChild(caption);
        button.addEventListener("pointerenter", function (event) {
          if (event.pointerType === "mouse") select(index);
        });
        button.addEventListener("pointerleave", function () {
          if (document.activeElement !== button) select(pinned);
        });
        button.addEventListener("focus", function () { select(index); });
        button.addEventListener("blur", function () { select(pinned); });
        button.addEventListener("click", function () {
          pinned = pinned === index ? -1 : index;
          buttons.forEach(function (candidate, i) {
            candidate.setAttribute("aria-pressed", String(i === pinned));
          });
          select(pinned);
        });
        buttons.push(button);
        row.appendChild(button);
        legend.appendChild(row);
      });
      current = -2;
      select(pinned);
      if (focusedIndex >= 0 && buttons[focusedIndex]) buttons[focusedIndex].focus({ preventScroll: true });
    }
  };
})();
