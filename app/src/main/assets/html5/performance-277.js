/* Lucian UX/UI Prompt #277 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/#277 */
(function () {
  var scale = [0, 5, 10, 25, 50, 100, 250, 500];
  var motion = window.matchMedia("(prefers-reduced-motion: reduce)");

  function coordinate(fraction, radius) {
    var angle = (-120 + 240 * fraction) * Math.PI / 180;
    return [130 + radius * Math.sin(angle), 130 - radius * Math.cos(angle)];
  }

  function fraction(value) {
    for (var i = 1; i < scale.length; i++) {
      if (value <= scale[i]) return (i - 1 + (value - scale[i - 1]) / (scale[i] - scale[i - 1])) / (scale.length - 1);
    }
    return 1;
  }

  function mount(container, start) {
    container.innerHTML = '<div class="u277 is-idle">' +
      '<svg class="u277-sv" viewBox="0 0 260 220" aria-hidden="true"><path class="u277-tr" fill="none" stroke="#34385e" stroke-width="13" stroke-linecap="round"/>' +
      '<path class="u277-pg" fill="none" stroke="var(--ux-cyan)" stroke-width="13" stroke-linecap="round" pathLength="100" stroke-dasharray="100"/><g class="u277-tks"></g>' +
      '<g class="u277-nd"><path d="M-3 0 0 -88 3 0z" fill="#f1f5f9"/><circle r="5" fill="#f1f5f9"/></g></svg>' +
      '<div class="u277-ro" role="status"><div class="u277-num"></div><div class="u277-ph"></div></div>' +
      '<button type="button" class="u277-go" id="perfRunBtn">Đo</button>' +
      '<div class="u277-ts"><div class="u277-t" style="--c:var(--ux-yellow)"><span><i></i>Độ trễ API</span><b class="u277-vp"></b></div>' +
      '<div class="u277-t" style="--c:var(--ux-cyan)"><span><i></i>Đọc NAS</span><b class="u277-vd"></b></div>' +
      '<div class="u277-t" style="--c:var(--ux-pink)"><span><i></i>Ghi NAS</span><b class="u277-vu"></b></div></div></div>' +
      '<p class="list-sub" style="margin-top:8px"></p>';
    var root = container.querySelector(".u277");
    var button = root.querySelector(".u277-go");
    var p0 = coordinate(0, 100), p1 = coordinate(1, 100);
    var arc = "M" + p0.join(" ") + " A100 100 0 1 1 " + p1.join(" ");
    root.querySelector(".u277-tr").setAttribute("d", arc);
    root.querySelector(".u277-pg").setAttribute("d", arc);
    root.querySelector(".u277-tks").innerHTML = scale.map(function (value, index) {
      var p = coordinate(index / (scale.length - 1), 76);
      return '<text class="u277-tl" x="' + p[0] + '" y="' + (p[1] + 3) + '">' + value + '</text>';
    }).join("");
    button.addEventListener("click", function () {
      if (!start()) return;
      button.disabled = true;
      render(container, { testing: true }, container.lastElementChild.textContent);
    });
  }

  function render(container, perf, connectionLabel) {
    var root = container.querySelector(".u277");
    var read = /^([0-9]+(?:\.[0-9]+)?)\s+MB\/s$/i.exec(perf.dl || "");
    var write = /^([0-9]+(?:\.[0-9]+)?)\s+MB\/s$/i.exec(perf.ul || "");
    var tested = !!(read || write || (perf.dl && perf.dl !== "--" && !perf.testing));
    var value = read ? Number(read[1]) : (write ? Number(write[1]) : 0);
    var reached = fraction(value);
    var button = root.querySelector(".u277-go");
    root.classList.toggle("is-idle", !tested && !perf.testing);
    root.classList.toggle("is-run", !!perf.testing);
    root.classList.toggle("is-done", tested && !perf.testing);
    button.disabled = !!perf.testing;
    button.textContent = tested ? "Đo lại" : "Đo";
    var needle = root.querySelector(".u277-nd");
    needle.style.transformOrigin = "130px 130px";
    needle.style.transition = motion.matches ? "none" : "transform .9s cubic-bezier(.25,1.16,.4,1)";
    needle.style.transform = "rotate(" + (-120 + 240 * reached) + "deg)";
    needle.querySelectorAll("path,circle").forEach(function (shape) { shape.setAttribute("transform", "translate(130 130)"); });
    root.querySelector(".u277-pg").style.strokeDashoffset = String(100 - reached * 100);
    root.querySelector(".u277-num").textContent = perf.testing ? "…" : read || write ? value.toFixed(1) : "—";
    root.querySelector(".u277-ph").textContent = perf.testing ? "Đang đo tại NAS" : read ? "Đọc ổ đĩa · MB/s" :
      write ? "Ghi ổ đĩa · MB/s" : tested ? "Không đo được tốc độ" : "";
    root.querySelector(".u277-vp").textContent = perf.latencyMs > 0 ? perf.latencyMs + " ms" : "—";
    root.querySelector(".u277-vd").textContent = perf.testing ? "…" : perf.dl || "—";
    root.querySelector(".u277-vu").textContent = perf.testing ? "…" : perf.ul || "—";
    container.lastElementChild.textContent = "Kết nối: " + (connectionLabel || "NAS") + " · Đo tốc độ ổ đĩa NAS";
  }

  window.NasPerformanceGauge = { mount: mount, render: render };
})();
