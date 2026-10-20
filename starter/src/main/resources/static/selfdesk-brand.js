(() => {
  const replacements = [
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
    [/Bytedesk AI for Visitor/g, 'SelfDesk AI Customer Service'],
    [/Bytedesk Chat Example/g, 'SelfDesk Chat Example'],
    [/Bytedesk Open Source Suite/g, 'SelfDesk Customer Service Suite'],
    [/Bytedesk - Customer-Centric Service/g, 'SelfDesk - AI Customer Service'],
    [/Bytedesk AI Agent Matrix/g, 'SelfDesk AI Agent Matrix'],
    [/Bytedesk Technology Team/g, 'SelfDesk Team'],
    [/Bytedesk出品|Bytedesk鍑哄搧/g, 'SelfDesk出品'],
    [/Welcome to Bytedesk/g, 'Welcome to SelfDesk'],
    [/Community Edition/gi, 'SelfDesk'],
    [/Enterprise Edition/gi, 'SelfDesk Pro'],
    [/Professional Edition/gi, 'SelfDesk Pro'],
    [/社区版|开源版|企业版|专业版/g, 'SelfDesk'],
    [/\bByteDesk\b/g, 'SelfDesk'],
    [/\bBytedesk\b/g, 'SelfDesk'],
    [/\bWeiyuAI\b/g, 'SelfDesk'],
    [/\bWeiyu\b/g, 'SelfDesk'],
  ];

  const needsRewrite = value =>
    /微语|微語|寰|鍖椾含|默认工作组|默认机器人|Bytedesk|ByteDesk|Weiyu|Community Edition|Enterprise Edition|Professional Edition|社区版|开源版|企业版|专业版/.test(value || '');

  const rewrite = value => {
    if (!needsRewrite(value)) return value;
    let next = value;
    for (const [pattern, replacement] of replacements) next = next.replace(pattern, replacement);
    return next;
  };

  const rewriteNode = node => {
    if (node.nodeType === Node.TEXT_NODE) {
      const next = rewrite(node.nodeValue);
      if (next !== node.nodeValue) node.nodeValue = next;
      return;
    }
    if (node.nodeType !== Node.ELEMENT_NODE) return;
    const tag = node.tagName.toLowerCase();
    if (tag === 'script' || tag === 'style' || tag === 'textarea') return;
    for (const attr of ['title', 'placeholder', 'alt', 'aria-label', 'content']) {
      if (!node.hasAttribute(attr)) continue;
      const current = node.getAttribute(attr);
      const next = rewrite(current);
      if (next !== current) node.setAttribute(attr, next);
    }
    if (tag === 'a' && node.href) {
      try {
        const url = new URL(node.href, window.location.href);
        if (/(^|\.)weiyuai\.cn$/i.test(url.hostname)) node.href = '/';
      } catch {}
    }
    for (const child of node.childNodes) rewriteNode(child);
  };

  const apply = () => {
    const currentTitle = document.title || '';
    const nextTitle = rewrite(currentTitle) || 'SelfDesk 智能客服';
    if (nextTitle !== currentTitle) document.title = nextTitle;
    rewriteNode(document.body || document.documentElement);
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', apply, { once: true });
  } else {
    apply();
  }

  const observer = new MutationObserver(records => {
    for (const record of records) {
      if (record.type === 'characterData') rewriteNode(record.target);
      if (record.type === 'attributes') rewriteNode(record.target);
      for (const node of record.addedNodes) rewriteNode(node);
    }
    const currentTitle = document.title;
    const nextTitle = rewrite(currentTitle);
    if (nextTitle !== currentTitle) document.title = nextTitle;
  });
  observer.observe(document.documentElement, {
    childList: true,
    subtree: true,
    characterData: true,
    attributes: true,
    attributeFilter: ['title', 'placeholder', 'alt', 'aria-label', 'content'],
  });
})();
