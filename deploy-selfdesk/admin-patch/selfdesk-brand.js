/*
 * SelfDesk 品牌统一脚本：在运行时抹掉上游痕迹与授权提示。
 * 由各前端页面（admin / chat / console 等）以 defer 方式引入。
 */
(() => {
  /* ── 统一视觉：与自研页面（访客端 / 客服台 / 仪表盘）共用一套 token ──
     后台是 AntD v4 编译产物，无法改主题变量，这里用同优先级+!important 覆盖。 */
  const THEME_CSS = `
:root{
  --sd-page:#F1F4F3; --sd-surface:#fff; --sd-surface-2:#F7F9F8; --sd-surface-3:#F2F5F4;
  --sd-line:#E3E8E6; --sd-line-soft:#EDF1EF;
  --sd-ink:#0E1513; --sd-ink-2:#4A5551; --sd-ink-3:#7C8781; --sd-ink-4:#A5AFAA;
  --sd-brand:#0E7A68; --sd-brand-2:#0A5F51; --sd-brand-tint:#E7F3F0;
  --sd-brand-ring:rgba(14,122,104,.14);
}
body,.ant-layout,.ant-pro-layout{background:var(--sd-page)!important;color:var(--sd-ink)!important;
  font-family:"Inter","Segoe UI",system-ui,-apple-system,"PingFang SC","Microsoft YaHei",sans-serif!important;
  -webkit-font-smoothing:antialiased}
.ant-layout-content,.ant-pro-page-container{background:transparent!important}
.ant-layout-sider,.ant-pro-sider{background:var(--sd-surface)!important;border-right:1px solid var(--sd-line)!important;
  box-shadow:none!important}
.ant-layout-header,.ant-pro-layout-header{background:var(--sd-surface)!important;border-bottom:1px solid var(--sd-line)!important;
  box-shadow:none!important}
.ant-menu{background:transparent!important;border:0!important;font-size:13px!important;color:var(--sd-ink-2)!important}
.ant-menu-item,.ant-menu-submenu-title{height:34px!important;line-height:34px!important;margin:2px 8px!important;
  border-radius:8px!important;color:var(--sd-ink-2)!important;width:auto!important}
.ant-menu-item:hover,.ant-menu-submenu-title:hover{background:var(--sd-surface-2)!important;color:var(--sd-ink)!important}
.ant-menu-item-selected{background:var(--sd-brand-tint)!important;color:var(--sd-brand-2)!important;font-weight:500!important}
.ant-menu-item-selected::after{display:none!important}
.ant-menu-item-selected .anticon,.ant-menu-item-selected a{color:var(--sd-brand-2)!important}
a{color:var(--sd-brand)}
a:hover{color:var(--sd-brand-2)}
.ant-btn{border-radius:8px!important;box-shadow:none!important;font-size:13px!important}
.ant-btn-primary{background:var(--sd-brand)!important;border-color:var(--sd-brand)!important;color:#fff!important}
.ant-btn-primary:hover,.ant-btn-primary:focus{background:var(--sd-brand-2)!important;border-color:var(--sd-brand-2)!important}
.ant-btn-default,.ant-btn-background-ghost{border-color:var(--sd-line)!important;color:var(--sd-ink-2)!important;background:var(--sd-surface)!important}
.ant-btn-default:hover{border-color:#CBD4D0!important;color:var(--sd-ink)!important}
.ant-btn-link{color:var(--sd-brand)!important}
.ant-btn-link:hover{color:var(--sd-brand-2)!important}
.ant-input,.ant-input-affix-wrapper,.ant-select-selector,.ant-picker,.ant-input-number{
  border-radius:8px!important;border-color:var(--sd-line)!important;background:var(--sd-surface-2)!important;box-shadow:none!important}
.ant-input:focus,.ant-input-affix-wrapper-focused,.ant-select-focused .ant-select-selector,.ant-picker-focused,
.ant-input-affix-wrapper:focus-within{border-color:var(--sd-brand)!important;
  box-shadow:0 0 0 3px var(--sd-brand-ring)!important;background:var(--sd-surface)!important}
.ant-card{border-radius:12px!important;border-color:var(--sd-line)!important;box-shadow:0 1px 2px rgba(14,21,19,.04)!important}
.ant-card-head{border-bottom-color:var(--sd-line-soft)!important;min-height:48px!important;padding:0 18px!important}
.ant-card-head-title{font-size:13.5px!important;font-weight:500!important}
.ant-table{font-size:12.5px!important;color:var(--sd-ink)!important;background:transparent!important}
.ant-table-thead>tr>th{background:var(--sd-surface-2)!important;color:var(--sd-ink-3)!important;font-weight:400!important;
  border-bottom-color:var(--sd-line)!important;font-size:11.5px!important}
.ant-table-tbody>tr>td{border-bottom-color:var(--sd-line-soft)!important}
.ant-table-tbody>tr:hover>td{background:var(--sd-surface-2)!important}
.ant-tabs-tab{font-size:13px!important;color:var(--sd-ink-3)!important}
.ant-tabs-tab-active .ant-tabs-tab-btn{color:var(--sd-brand)!important;font-weight:500!important}
.ant-tabs-ink-bar{background:var(--sd-brand)!important}
.ant-tag{border-radius:999px!important;font-size:11px!important;border:0!important;
  background:var(--sd-surface-3)!important;color:var(--sd-ink-2)!important;padding:0 8px!important}
.ant-tag-blue,.ant-tag-processing{background:var(--sd-brand-tint)!important;color:var(--sd-brand-2)!important}
.ant-tag-green,.ant-tag-success{background:#E4F4EC!important;color:#067647!important}
.ant-tag-orange,.ant-tag-warning{background:#FDF3DF!important;color:#B54708!important}
.ant-tag-red,.ant-tag-error{background:#FCECEA!important;color:#B42318!important}
.ant-switch-checked{background:var(--sd-brand)!important}
.ant-checkbox-checked .ant-checkbox-inner{background:var(--sd-brand)!important;border-color:var(--sd-brand)!important}
.ant-radio-checked .ant-radio-inner{border-color:var(--sd-brand)!important}
.ant-radio-checked .ant-radio-inner::after{background:var(--sd-brand)!important}
.ant-pagination-item-active{border-color:var(--sd-brand)!important}
.ant-pagination-item-active a{color:var(--sd-brand)!important}
.ant-modal-content{border-radius:14px!important}
.ant-modal-header{border-radius:14px 14px 0 0!important}
.ant-alert{border-radius:10px!important}
.ant-alert-info{background:var(--sd-brand-tint)!important;border-color:#CFE6E0!important}
.ant-alert-info .ant-alert-icon{color:var(--sd-brand)!important}
.ant-empty-description{color:var(--sd-ink-4)!important}
.ant-dropdown-menu,.ant-select-dropdown{border-radius:10px!important;box-shadow:0 16px 40px -20px rgba(14,21,19,.28)!important}
.ant-spin-dot-item,.ant-progress-bg{background:var(--sd-brand)!important}
/* 登录页：原版带一张蓝色背景图，这里换成纯色页面底 */
[style*="bg-wide"]{background-image:none!important;background-color:var(--sd-page)!important}
.ant-pro-form-login-container{background:transparent!important}
.ant-pro-form-login-title{font-size:26px!important;font-weight:500!important;letter-spacing:-.02em!important}
.ant-pro-form-login-desc{color:var(--sd-ink-3)!important}
/* 手机号/扫码登录依赖短信与微信配置，演示环境没有，先隐藏 */
.ant-pro-form-login-container .ant-tabs-tab+.ant-tabs-tab{display:none!important}
/* 访客端聊天页（/chat）：自带一套 Btn/Input/Composer 类，单独覆盖 */
body.S--wide .chat-page{background:var(--sd-page)!important}
body.S--wide .chat-navbar{background:var(--sd-surface)!important;border-bottom:1px solid var(--sd-line)!important}
body.S--wide .chat-navbar-title{color:var(--sd-ink)!important;font-weight:500!important}
body.S--wide .Composer{background:var(--sd-surface)!important;border-top:1px solid var(--sd-line)!important}
body.S--wide .Composer-input,body.S--wide .Input--outline{
  background:var(--sd-surface-2)!important;border-color:var(--sd-line)!important;border-radius:10px!important;
}
body.S--wide .Composer-input:focus,body.S--wide .Input--outline:focus{
  background:var(--sd-surface)!important;border-color:var(--sd-brand)!important;
  box-shadow:0 0 0 3px var(--sd-brand-ring)!important;
}
body.S--wide .Composer-sendBtn,body.S--wide .Btn--primary{
  background:var(--sd-brand)!important;border-color:var(--sd-brand)!important;color:#fff!important;
}
body.S--wide .Btn--primary:hover{background:var(--sd-brand-2)!important;border-color:var(--sd-brand-2)!important}
body.S--wide .QuickReply{
  border:1px solid var(--sd-line)!important;border-radius:999px!important;
  background:var(--sd-surface-2)!important;color:var(--sd-ink-2)!important;
}
body.S--wide .Btn--text{color:var(--sd-brand)!important}
::-webkit-scrollbar{width:8px;height:8px}
::-webkit-scrollbar-thumb{background:#DDE4E1;border-radius:8px}
::-webkit-scrollbar-track{background:transparent}
`;
  const themeEl = document.createElement('style');
  themeEl.id = 'selfdesk-theme';
  themeEl.textContent = THEME_CSS;
  (document.head || document.documentElement).appendChild(themeEl);

  const TEXT_REPLACEMENTS = [
    [/SelfDesk\s+SelfDesk/g, 'SelfDesk'],
    [/北京微语天下科技有限公司/g, 'SelfDesk'],
    [/微语|微語/g, 'SelfDesk'],
    [/寰|寰獮/g, 'SelfDesk'],
    [/鍖椾含寰澶╀笅绉戞妧鏈夐檺鍏徃/g, 'SelfDesk'],
    [/默认工作组描述/g, 'SelfDesk 智能客服中心'],
    [/默认工作组/g, 'SelfDesk 智能客服'],
    [/默认机器人描述/g, 'SelfDesk 智能服务助手'],
    [/默认机器人/g, 'SelfDesk 智能助手'],
    [/Default skill group description/gi, 'SelfDesk customer service center'],
    [/Default skill group/gi, 'SelfDesk Customer Service'],
    [/Default robot description/gi, 'SelfDesk customer service assistant'],
    [/Default robot/gi, 'SelfDesk Assistant'],
    [/Default customer service description/gi, 'SelfDesk customer service assistant'],
    [/Default customer service/gi, 'SelfDesk Customer Service'],
    [/i18n\.workgroup\.description/g, 'SelfDesk 智能客服中心'],
    [/i18n\.workgroup\.nickname/g, 'SelfDesk 智能客服'],
    [/i18n\.robot\.description/g, 'SelfDesk 智能服务助手'],
    [/i18n\.robot\.nickname/g, 'SelfDesk 智能助手'],
    [/Bytedesk AI for Visitor/gi, 'SelfDesk AI Customer Service'],
    [/Bytedesk Chat Example/gi, 'SelfDesk Chat Example'],
    [/Bytedesk Open Source Suite/gi, 'SelfDesk Customer Service Suite'],
    [/Bytedesk - Customer-Centric Service/gi, 'SelfDesk - AI Customer Service'],
    [/Bytedesk AI Agent Matrix/gi, 'SelfDesk AI Agent Matrix'],
    [/Bytedesk Technology Team/gi, 'SelfDesk Team'],
    [/Bytedesk出品|Bytedesk鍑哄搧/gi, 'SelfDesk出品'],
    [/Welcome to Bytedesk/gi, 'Welcome to SelfDesk'],
    [/Community Edition/gi, 'SelfDesk'],
    [/Enterprise Edition/gi, 'SelfDesk'],
    [/Professional Edition/gi, 'SelfDesk'],
    [/社区版|开源版|企业版|专业版|平台版/g, 'SelfDesk'],
    [/\bByteDesk\b/g, 'SelfDesk'],
    [/\bBytedesk\b/g, 'SelfDesk'],
    [/\bWeiyuAI\b/g, 'SelfDesk'],
    [/\bWeiyu\b/g, 'SelfDesk'],
  ];

  // 命中这些内容的文本块直接清空：属于授权/版本销售话术
  const PURGE_TEXT = /licenseKey|license\s*key|平台版|专业版|社区版|企业版|开源版|升级无需|升级至|升级到|授权码/i;

  const SCAN_ATTRS = ['title', 'placeholder', 'alt', 'aria-label', 'content'];

  const needsRewrite = (value) =>
    /微语|微語|寰|鍖椾含|默认工作组|默认机器人|Bytedesk|ByteDesk|Weiyu|Community Edition|Enterprise Edition|Professional Edition|社区版|开源版|企业版|专业版|平台版/i.test(value || '');

  const rewrite = (value) => {
    if (!needsRewrite(value)) return value;
    let next = value;
    for (const [pattern, replacement] of TEXT_REPLACEMENTS) next = next.replace(pattern, replacement);
    return next;
  };

  const purgeTextNode = (node) => {
    if (node.__selfdeskPurged) return;
    if (PURGE_TEXT.test(node.nodeValue || '')) {
      node.nodeValue = '';
      node.__selfdeskPurged = true;
    }
  };

  const hideUpgradeEntry = (el) => {
    const tag = el.tagName.toLowerCase();
    if (tag === 'main' || tag === 'body' || tag === 'html') return;
    const cls = typeof el.className === 'string' ? el.className : (el.getAttribute('class') || '');
    // 结构性容器永远不动：早期渲染时它可能只含「升级」两字，一旦被隐藏，
    // 之后挂进来的成员/角色/组织页面也会一起消失（这个坑真实踩过）
    if (/ant-app|layout-content|ant-pro-layout|ant-layout-container|ant-pro-layout-container/i.test(cls)) return;

    const label = (el.textContent || '').trim();
    if (!label || label.length > 40) return;

    // 尺寸兜底：顶栏/容器这类大块元素绝不当成按钮隐藏
    const rect = el.getBoundingClientRect();
    if (rect.width > 420 || rect.height > 80) return;

    const looksUpgrade = /^升级/.test(label)
      || /升级(至|到)?\s*(SelfDesk|服务|平台)/.test(label)
      || /(^|[\s_-])(upgrade|vip-entry|pro-entry)([\s_-]|$)/i.test(cls);
    if (!looksUpgrade) return;

    // 只处理顶栏里的销售入口，或类名明确是升级按钮的元素
    const inHeader = !!el.closest('header, .ant-pro-layout-header, .ant-layout-header');
    if (inHeader || /upgrade|vip|pro-entry/i.test(cls)) {
      el.style.setProperty('display', 'none', 'important');
    }
  };

  const walk = (node, depth = 0) => {
    if (depth > 40) return;

    if (node.nodeType === Node.TEXT_NODE) {
      purgeTextNode(node);
      const next = rewrite(node.nodeValue);
      if (next !== node.nodeValue) node.nodeValue = next;
      return;
    }

    if (node.nodeType !== Node.ELEMENT_NODE) return;
    const tag = node.tagName.toLowerCase();
    if (tag === 'script' || tag === 'style' || tag === 'textarea' || tag === 'noscript') return;

    for (const attr of SCAN_ATTRS) {
      if (!node.hasAttribute(attr)) continue;
      const current = node.getAttribute(attr) || '';
      if (PURGE_TEXT.test(current)) {
        node.removeAttribute(attr);
        continue;
      }
      const next = rewrite(current);
      if (next !== current) node.setAttribute(attr, next);
    }

    if ((tag === 'a' || tag === 'link') && node.href) {
      try {
        const url = new URL(node.href, window.location.href);
        if (/(^|\.)(weiyuai|bytedesk)\.(cn|com)$/i.test(url.hostname)) node.href = '/';
      } catch {}
    }

    // 升级/授权入口可能是 a/button，也可能只是 header 里的 span 组合，统一处理
    if (node.childElementCount <= 3) hideUpgradeEntry(node);

    for (const child of Array.from(node.childNodes)) walk(child, depth + 1);
  };

  const apply = () => {
    const currentTitle = document.title || '';
    const cleanedTitle = currentTitle.replace(PURGE_TEXT, '').trim();
    const nextTitle = rewrite(cleanedTitle) || 'SelfDesk';
    if (nextTitle !== currentTitle) document.title = nextTitle;
    walk(document.body || document.documentElement);
  };

  /* 本机免密：登录页出现时自动填入并提交。
     账号密码不写在本文件，由 runtime/_patch_brand.py 打包时从 .env.local 注入
     window.__SELFDESK_AUTOLOGIN__，源码仓库里没有明文。 */
  const autoLogin = () => {
    const cfg = window.__SELFDESK_AUTOLOGIN__;
    if (!cfg || !cfg.username || !cfg.password) return;
    if (sessionStorage.getItem('sd_autologin_tried')) return;

    const antd = document.querySelector('.ant-pro-form-login-container');
    const custom = document.querySelector('#loginForm');
    if (!antd && !custom) return;

    const setValue = (el, value) => {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
      setter.call(el, value);
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
    };

    if (antd) {
      // 页面里有一个隐藏的 input[type=text] 在真输入框前面，必须用 #username 定位
      const pwd = document.querySelector('#password') || document.querySelector('input[type="password"]');
      const user = document.querySelector('#username')
        || document.querySelector('input[placeholder*="用户名"], input[placeholder*="邮箱"]');
      if (!pwd || !user) return;
      setValue(user, cfg.username);
      setValue(pwd, cfg.password);
      // 登录页要求勾选隐私协议，否则提交被拦
      const agree = document.querySelector('.ant-pro-form-login-container input.ant-checkbox-input');
      if (agree && !agree.checked) agree.click();
      sessionStorage.setItem('sd_autologin_tried', '1');
      setTimeout(() => {
        const btn = document.querySelector('.ant-pro-form-login-container button.ant-btn-primary')
          || document.querySelector('.ant-pro-form-login-container button[type="submit"]');
        if (btn) btn.click();
      }, 200);
      return;
    }

    // 自建客服台（/console）的登录表单
    const u = document.querySelector('#loginUsername');
    const p = document.querySelector('#loginPassword');
    if (!u || !p || !custom) return;
    setValue(u, cfg.username);
    setValue(p, cfg.password);
    sessionStorage.setItem('sd_autologin_tried', '1');
    setTimeout(() => {
      const btn = custom.querySelector('button[type="submit"]');
      if (btn) btn.click();
      else if (custom.requestSubmit) custom.requestSubmit();
    }, 250);
  };

  /* 右上角「许可检测」浮层是常驻的，会挡住成员列表等页面，直接隐藏 */
  const hideLicenseNotice = () => {
    document.querySelectorAll('.ant-notification-notice').forEach((el) => {
      if (/许可(检测|证)|非法版本|许可证解密失败/.test(el.textContent || '')) {
        el.style.setProperty('display', 'none', 'important');
      }
    });
  };

  const boot = () => {
    apply();
    const observer = new MutationObserver((records) => {
      for (const record of records) {
        for (const node of record.addedNodes) walk(node);
        if (record.type === 'characterData') walk(record.target);
      }
      hideLicenseNotice();
    });
    observer.observe(document.documentElement, {
      childList: true,
      subtree: true,
      characterData: true,
    });
    // 兜底：部分内容在异步路由后才渲染
    let ticks = 0;
    const timer = setInterval(() => {
      apply();
      autoLogin();
      hideLicenseNotice();
      if (++ticks > 40) clearInterval(timer);
    }, 500);
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot, { once: true });
  } else {
    boot();
  }
})();
