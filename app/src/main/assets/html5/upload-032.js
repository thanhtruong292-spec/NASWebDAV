/* Lucian UX/UI Prompt #032 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/#032 */
(function () {
  var root = document.getElementById("fileUploadDialog");
  var zone = root.querySelector(".u032-zone");
  var list = root.querySelector(".u032-list");
  var live = root.querySelector(".u032-live");
  var rows = new Map();
  var labels = {
    waiting: "Đang chờ",
    uploading: "Đang tải lên",
    done: "Đã tải lên",
    skipped: "Bỏ qua vì trùng tên",
    failed: "Tải lên thất bại",
    unavailable: "Phiên NAS đã thay đổi"
  };

  function busy(value) {
    zone.disabled = value;
    list.querySelectorAll(".u032-retry").forEach(function (button) { button.disabled = value; });
  }

  zone.addEventListener("click", function () {
    if (!window.NasBridge || typeof NasBridge.pickUploadFiles !== "function") return;
    busy(true);
    NasBridge.pickUploadFiles();
  });

  window.NasUpload032 = {
    update: function (item) {
      if (!labels[item.status]) return;
      var row = rows.get(item.id);
      if (!row) {
        row = document.createElement("li");
        row.className = "u032-row";
        row.innerHTML = '<span class="u032-ic" aria-hidden="true">TỆP</span><div class="u032-meta"><b>Tệp đã chọn</b><small></small></div><span class="u032-st" aria-hidden="true"></span><div class="u032-bar" aria-hidden="true"></div>';
        rows.set(item.id, row);
        list.appendChild(row);
      }
      var previous = row.dataset.status;
      if (item.status === "uploading" && previous !== "uploading" && previous !== "waiting") return;
      if (item.name) {
        row.querySelector("b").textContent = item.name;
        var extension = item.name.lastIndexOf(".");
        if (extension > 0 && extension < item.name.length - 1) {
          row.querySelector(".u032-ic").textContent = item.name.slice(extension + 1, extension + 5).toUpperCase();
        }
      }
      var active = item.status === "uploading" || item.status === "waiting";
      var status = labels[item.status];
      if (item.status === "uploading" && item.progress > 0) status += " · " + item.progress + "%";
      row.querySelector("small").textContent = status;
      row.style.setProperty("--p", String(item.progress / 100));
      if (previous === item.status) return;
      row.dataset.status = item.status;
      row.classList.toggle("done", item.status === "done");
      row.classList.toggle("fail", item.status === "failed");
      var indicator = row.querySelector(".u032-st");
      var retry = row.querySelector(".u032-retry");
      if (retry) retry.remove();
      indicator.innerHTML = item.status === "done"
        ? '<svg viewBox="0 0 16 16"><path d="m3 8 3 3 7-7"/></svg>'
        : active ? '<span class="u032-spin"></span>' : "";
      indicator.hidden = item.status === "failed";
      if (item.status === "failed") {
        retry = document.createElement("button");
        retry.type = "button";
        retry.className = "u032-retry";
        retry.textContent = "Thử lại";
        retry.disabled = zone.disabled;
        retry.addEventListener("click", function () {
          busy(true);
          NasBridge.retryUploadFile(item.id);
        });
        row.insertBefore(retry, indicator);
      }
      if (!active) live.textContent = row.querySelector("b").textContent + " · " + status;
    },
    finish: function () { busy(false); }
  };
})();
