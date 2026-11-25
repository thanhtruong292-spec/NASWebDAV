/* Lucian UX/UI Prompt #415 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/#415 */
(function () {
  var list = document.getElementById("filesBreadcrumbs");
  var fallback = document.getElementById("filesPathTitle");
  var nav = list.parentElement;
  var signature = "";
  var focusAfterNavigation = false;
  var separator = '<span class="u415-sep" aria-hidden="true"><svg viewBox="0 0 10 10"><path d="M3.8 2 6.8 5l-3 3"/></svg></span>';
  var home = '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M2.5 7 8 2.5 13.5 7v6.5H9.8V10H6.2v3.5H2.5z"/></svg>';

  function item(withSeparator) {
    var li = document.createElement("li");
    li.className = "u415-li";
    li.innerHTML = '<span><span class="u415-in">' + (withSeparator ? separator : "") + '</span></span>';
    return li;
  }

  window.NasBreadcrumb = {
    render: function (title, crumbs, folder, navigate) {
      var nextSignature = JSON.stringify([title, crumbs, folder]);
      if (nextSignature === signature) return;
      signature = nextSignature;
      list.textContent = "";
      fallback.textContent = title;
      fallback.hidden = !!crumbs.length;
      if (!crumbs.length) return;
      var collapsed = crumbs.length > 4;
      var hiddenItems = [];
      var moreItem = item(true);
      var more = document.createElement("button");
      more.type = "button";
      more.className = "u415-more";
      more.textContent = "…";
      more.setAttribute("aria-expanded", "false");
      more.setAttribute("aria-label", "Mở " + (crumbs.length - 3) + " thư mục cha");
      moreItem.querySelector(".u415-in").appendChild(more);
      var current;
      crumbs.forEach(function (name, index) {
        var li = item(index > 0);
        var isCurrent = index === crumbs.length - 1;
        var node = document.createElement(isCurrent ? "span" : "button");
        node.className = isCurrent ? "u415-cur" : "u415-a";
        node.textContent = name;
        if (isCurrent) {
          node.setAttribute("aria-current", "location");
          node.tabIndex = -1;
          current = node;
        } else {
          node.type = "button";
          if (index === 0) {
            node.classList.add("hm");
            node.setAttribute("aria-label", "Thư mục gốc WebDAV");
            node.innerHTML = home;
          }
          node.addEventListener("click", function () {
            focusAfterNavigation = true;
            navigate(index, folder);
          });
        }
        if (collapsed && index > 0 && index < crumbs.length - 2) {
          if (index === 1) list.appendChild(moreItem);
          li.classList.add("is-shut");
          li.inert = true;
          hiddenItems.push(li);
        }
        li.querySelector(".u415-in").appendChild(node);
        list.appendChild(li);
      });
      more.addEventListener("click", function () {
        more.setAttribute("aria-expanded", "true");
        hiddenItems.forEach(function (li) { li.inert = false; li.classList.remove("is-shut"); });
        hiddenItems[0].querySelector("button").focus({ preventScroll: true });
        moreItem.inert = true;
        moreItem.classList.add("is-shut");
      });
      if (focusAfterNavigation) current.focus({ preventScroll: true });
      focusAfterNavigation = false;
      nav.scrollLeft = nav.scrollWidth;
    }
  };
})();
