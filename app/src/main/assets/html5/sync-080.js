/* Lucian UX/UI Prompt #080 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/#080 */
(function () {
  var root = document.getElementById("backupOrbit");
  var sys = root.querySelector(".u080-sys");
  var core = root.querySelector(".u080-core");
  var title = root.querySelector(".u080-t b");
  var detail = root.querySelector(".u080-sub");
  var motion = window.matchMedia("(prefers-reduced-motion: reduce)");
  var planets = [];
  var visible = false;
  var running = false;
  var last = 0;
  var time = 0;
  var frame = 0;
  [[105, -18, 5.5, "#22C3A6", 12], [75, 24, 3.6, "#FF6B8B", 10], [50, -58, 2.2, "#FFB547", 9]].forEach(function (orbit, i) {
    [0, Math.PI].forEach(function (offset, j) {
      var dot = document.createElement("i");
      dot.className = "u080-p";
      dot.style.setProperty("--c", orbit[3]);
      dot.style.setProperty("--s", (orbit[4] - j * 4) + "px");
      sys.insertBefore(dot, core);
      planets.push({ dot: dot, radius: orbit[0], tilt: orbit[1] * Math.PI / 180,
        period: orbit[2], offset: offset + i });
    });
  });

  function position() {
    planets.forEach(function (planet) {
      var angle = planet.offset + time * 2 * Math.PI / planet.period;
      var x = Math.cos(angle) * planet.radius;
      var y = Math.sin(angle) * planet.radius * .38;
      var rx = x * Math.cos(planet.tilt) - y * Math.sin(planet.tilt);
      var ry = x * Math.sin(planet.tilt) + y * Math.cos(planet.tilt);
      planet.dot.style.transform = "translate(" + (115 + rx) + "px," + (100 + ry) + "px)";
      planet.dot.style.zIndex = Math.sin(angle) < 0 ? 0 : 2;
    });
  }

  function tick(now) {
    frame = 0;
    if (!visible || !running || motion.matches) return;
    if (last) time += Math.min(50, now - last) / 1000;
    last = now;
    position();
    frame = requestAnimationFrame(tick);
  }

  function schedule() {
    if (visible && running && !motion.matches) {
      if (!frame) frame = requestAnimationFrame(tick);
    } else {
      cancelAnimationFrame(frame);
      frame = last = 0;
      position();
    }
  }

  if ("IntersectionObserver" in window) new IntersectionObserver(function (entries) {
    visible = entries[0].isIntersecting;
    schedule();
  }).observe(root);
  else visible = true;
  if (motion.addEventListener) motion.addEventListener("change", schedule);

  window.NasBackupOrbit = {
    render: function (stage, caption, counts) {
      root.classList.remove("hidden");
      root.classList.toggle("is-running", stage === "running");
      root.classList.toggle("is-done", stage === "done");
      running = stage === "running";
      if (title.textContent !== caption) title.textContent = caption;
      if (detail.textContent !== counts) detail.textContent = counts;
      schedule();
    }
  };
})();
