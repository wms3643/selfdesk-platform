/*
 * SelfDesk：把空的「仪表盘」页替换为自建运营仪表盘。
 * 由 admin/index.html 以 <script defer> 引入，运行在后台 SPA 内。
 * 原理：SPA 路由变化后检测页面占位文案，命中时以 iframe 覆盖内容区。
 */
(function () {
  if (window.__SELFDESK_DASH_PATCHED__) return;
  window.__SELFDESK_DASH_PATCHED__ = true;

  var DASH_URL = location.protocol + "//" + location.hostname + ":8002/?embed=1";
  var panel = null;
  var timer = null;

  function px(el) {
    if (!el) return 0;
    var r = el.getBoundingClientRect();
    return r && r.width > 0 ? r : null;
  }

  function siderWidth() {
    var candidates = [
      ".ant-pro-sider", ".ant-pro-layout-sider", ".ant-layout-sider",
      "aside", "[class*='sider']", "[class*='sidebar']"
    ];
    for (var i = 0; i < candidates.length; i++) {
      var els = document.querySelectorAll(candidates[i]);
      for (var j = 0; j < els.length; j++) {
        var r = px(els[j]);
        if (r && r.width >= 80 && r.width <= 400) return r.width;
      }
    }
    return 208;
  }

  function headerHeight() {
    var candidates = [
      ".ant-pro-layout-header", ".ant-layout-header", "[class*='header']", "header"
    ];
    for (var i = 0; i < candidates.length; i++) {
      var els = document.querySelectorAll(candidates[i]);
      for (var j = 0; j < els.length; j++) {
        var r = px(els[j]);
        if (r && r.height >= 30 && r.height <= 120) return r.height;
      }
    }
    return 56;
  }

  function contentBox() {
    return document.querySelector(".ant-pro-layout-content")
      || document.querySelector(".ant-layout-content")
      || document.querySelector("main");
  }

  function isHomeRoute() {
    var path = (location.pathname || "").replace(/\/+$/, "");
    var hash = (location.hash || "").replace(/\/+$/, "");
    // hash 非空且不是首页 → 一定不是首页（之前只看 hash 为空就返回 true，
    // 导致 /admin/ai/model 这类路径也被判成首页，仪表盘把模型列表盖住了）
    if (hash && hash !== "#" && hash !== "#/dashboard") return false;
    return path === "" || path === "/admin" || path === "/admin/welcome" || path === "/admin/dashboard";
  }

  /* 只在「首页」且内容区是占位页时挂载仪表盘。
     之前用 body.innerText 判断，结果右上角那条「许可检测」浮层是常驻的，
     导致成员/角色/组织等所有页面都被 iframe 盖住 —— 功能全看不见。
     现在：路由必须是首页，且内容区没有表格/表单等真实业务内容。 */
  function shouldMount() {
    if (!isHomeRoute()) return false;
    var box = contentBox();
    if (!box) return false;
    if (box.querySelector(".ant-table, .ant-pro-table, form, .ant-list, .ant-descriptions")) {
      return false;
    }
    return true;
  }

  function layout() {
    if (!panel) return;
    // 优先贴合内容容器：比猜侧栏宽度/顶栏高度可靠（首页没有顶栏，猜错会留一条空白）
    var rect = px(contentBox());
    if (rect) {
      panel.style.left = Math.round(rect.left) + "px";
      // 占满内容区：顶栏在「许可检测」页只露出半截，干脆一起盖住，避免出现残条
      panel.style.top = "0px";
      return;
    }
    panel.style.left = siderWidth() + "px";
    panel.style.top = headerHeight() + "px";
  }

  function mount() {
    if (!shouldMount()) {
      unmount();
      return;
    }
    if (panel) {
      layout();
      return;
    }
    panel = document.createElement("div");
    panel.id = "__selfdesk_dash_panel";
    panel.style.cssText = "position:fixed;right:0;bottom:0;background:#F1F4F3;z-index:2147483000;overflow:hidden;";

    var frame = document.createElement("iframe");
    frame.src = DASH_URL;
    frame.title = "SelfDesk 运营仪表盘";
    frame.style.cssText = "width:100%;height:100%;border:0;display:block;background:#F1F4F3;";

    panel.appendChild(frame);
    document.body.appendChild(panel);
    layout();
  }

  function unmount() {
    if (panel) {
      panel.remove();
      panel = null;
    }
  }

  function start() {
    if (timer) return;
    timer = setInterval(mount, 300);
    window.addEventListener("resize", layout);
    new MutationObserver(function () { mount(); })
      .observe(document.body, { childList: true, subtree: true });
    mount();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start);
  } else {
    start();
  }
})();
