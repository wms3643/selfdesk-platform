(() => {
  const HTTP_CHANNEL = 'WEB';
  const TOKEN_KEY = 'selfdesk_token';
  const ACTIVE_STATUSES = new Set(['NEW', 'ROBOTING', 'QUEUING', 'CHATTING']);

  const state = {
    token: '',
    profile: null,
    agent: null,
    orgUid: '',
    threads: [],
    selected: null,
    messages: [],
    filter: 'all',
    search: '',
    loadingThreads: false,
    pollTimer: null
  };

  const $ = (selector) => document.querySelector(selector);
  const loginView = $('#loginView');
  const appView = $('#appView');
  const loginForm = $('#loginForm');
  const loginError = $('#loginError');
  const loginButton = $('#loginButton');
  const threadList = $('#threadList');
  const threadSearch = $('#threadSearch');
  const messageList = $('#messageList');
  const messageInput = $('#messageInput');
  const sendButton = $('#sendButton');
  const sendHint = $('#sendHint');
  const chatEmpty = $('#chatEmpty');
  const chatView = $('#chatView');
  const workspace = $('.workspace');
  const toast = $('#toast');
  const connectionState = $('#connectionState');

  function randomId() {
    if (window.crypto && typeof window.crypto.randomUUID === 'function') {
      return window.crypto.randomUUID().replace(/-/g, '');
    }
    return `${Date.now().toString(16)}${Math.random().toString(16).slice(2)}`;
  }

  function normalizeToken(value) {
    return String(value || '').trim().replace(/^Bearer\s+/i, '');
  }

  function formatServerMessage(value) {
    const text = String(value || '').trim();
    const [key, ...args] = text.split('|');
    const messages = {
      'i18n.auth.account.locked.retry.after': () => `账号已锁定，请在 ${args[0] || '稍后'} 后重试。`,
      'i18n.auth.account.locked.minutes': () => `密码错误次数过多，账号已锁定 ${args[0] || ''} 分钟。`,
      'i18n.auth.password.attempts.remaining': () => `用户名或密码错误，还可尝试 ${args[0] || 0} 次。`,
      'i18n.username.or.password.incorrect': () => '用户名或密码错误。',
      'i18n.auth.captcha.error': () => '验证码校验失败，请刷新后重试。',
      'i18n.auth.captcha.validate.failed': () => '验证码已失效，请重新获取。',
      'i18n.user.disabled': () => '账号已被禁用，请联系管理员。',
      'i18n.user.signup.first': () => '账号不存在，请先注册。',
      'i18n.user.blocked': () => '账号已被停用。',
      'i18n.auth.password.required': () => '请输入登录密码。',
      'i18n.auth.username.required': () => '请输入登录账号。'
    };
    if (messages[key]) return messages[key]();
    if (key.startsWith('i18n.')) return '请求未完成，请检查账号状态后重试。';
    return text || '登录失败，请稍后重试。';
  }

  function looksLikeJwt(value) {
    return typeof value === 'string'
      && value.length > 40
      && (value.match(/\./g) || []).length === 2;
  }

  function readStoredToken() {
    const directKeys = [TOKEN_KEY, 'accessToken', 'token', 'access_token', 'bytedesk_token'];
    for (const storage of [localStorage, sessionStorage]) {
      for (const key of directKeys) {
        try {
          const value = normalizeToken(storage.getItem(key));
          if (looksLikeJwt(value)) return value;
        } catch {}
      }
      try {
        for (let index = 0; index < storage.length; index += 1) {
          const value = normalizeToken(storage.getItem(storage.key(index)));
          if (looksLikeJwt(value)) return value;
        }
      } catch {}
    }
    return '';
  }

  function showToast(text, kind = '') {
    toast.textContent = text;
    toast.className = `toast show ${kind}`.trim();
    clearTimeout(showToast.timer);
    showToast.timer = setTimeout(() => {
      toast.className = 'toast';
    }, kind === 'error' ? 5000 : 2600);
  }

  function setConnection(kind, label) {
    connectionState.className = `connection ${kind}`.trim();
    connectionState.innerHTML = '';
    const dot = document.createElement('i');
    connectionState.append(dot, document.createTextNode(label));
  }

  async function api(path, options = {}) {
    const { method = 'GET', query, body, auth = true } = options;
    const url = new URL(path, window.location.origin);
    if (query) {
      Object.entries(query).forEach(([key, value]) => {
        if (value !== undefined && value !== null && value !== '') {
          url.searchParams.set(key, String(value));
        }
      });
    }

    const headers = { Accept: 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (auth && state.token) headers.Authorization = `Bearer ${state.token}`;

    let response;
    try {
      response = await fetch(url, {
        method,
        headers,
        credentials: 'same-origin',
        body: body === undefined ? undefined : JSON.stringify(body)
      });
    } catch (error) {
      throw new Error('无法连接服务器，请检查服务是否已启动。');
    }

    const payload = await response.json().catch(() => null);
    if (response.status === 401) {
      clearSession();
      throw new Error('登录已失效，请重新登录。');
    }
    if (!response.ok) {
      throw new Error(formatServerMessage(payload?.message) || `请求失败（HTTP ${response.status}）`);
    }
    if (!payload) {
      throw new Error('服务器返回了空响应。');
    }
    if (Number(payload.code) !== 200 && Number(payload.code) !== 0) {
      throw new Error(formatServerMessage(payload.message) || '业务请求失败。');
    }
    return payload.data;
  }

  function clearSession() {
    state.token = '';
    try { localStorage.removeItem(TOKEN_KEY); } catch {}
    if (state.pollTimer) clearInterval(state.pollTimer);
    state.pollTimer = null;
    appView.hidden = true;
    loginView.hidden = false;
  }

  async function login(username, password) {
    loginButton.disabled = true;
    loginError.textContent = '';
    try {
      const data = await api('/auth/v1/login', {
        method: 'POST',
        auth: false,
        body: {
          username,
          password,
          platform: 'BYTEDESK',
          channel: HTTP_CHANNEL,
          deviceUid: randomId(),
          captchaUid: '',
          captchaCode: ''
        }
      });
      const token = normalizeToken(data?.accessToken);
      if (!token) throw new Error('登录成功但没有返回访问令牌。');
      state.token = token;
      try { localStorage.setItem(TOKEN_KEY, token); } catch {}
      await bootstrap();
    } catch (error) {
      loginError.textContent = error.message;
    } finally {
      loginButton.disabled = false;
    }
  }

  async function bootstrap() {
    setConnection('', '连接中');
    loginError.textContent = '';
    try {
      const profile = await api('/api/v1/user/profile', {
        query: { channel: HTTP_CHANNEL }
      });
      if (!profile) throw new Error('无法读取当前账号信息。');
      state.profile = profile;
      state.orgUid = profile.currentOrganization?.uid || profile.orgUid || '';

      if (!state.orgUid) {
        try {
          const organizations = await api('/api/v1/user/organizations', {
            query: { channel: HTTP_CHANNEL }
          });
          const list = Array.isArray(organizations) ? organizations : organizations?.content || [];
          state.orgUid = list[0]?.uid || list[0]?.orgUid || '';
        } catch {}
      }
      if (!state.orgUid) throw new Error('当前账号没有可用组织。');

      try {
        const agentData = await api('/api/v1/agent/query/user/uid', {
          query: { userUid: profile.uid, channel: HTTP_CHANNEL }
        });
        state.agent = Array.isArray(agentData)
          ? agentData[0]
          : (agentData?.content?.[0] || agentData);
      } catch {
        state.agent = null;
      }

      renderProfile();
      loginView.hidden = true;
      appView.hidden = false;
      setConnection('ok', '已连接');
      await loadThreads(true);
      startPolling();
    } catch (error) {
      setConnection('error', '连接失败');
      loginError.textContent = error.message;
      clearSession();
    }
  }

  function agentUid() {
    return state.agent?.uid || state.profile?.uid || '';
  }

  function agentName() {
    return state.agent?.nickname || state.profile?.nickname || state.profile?.username || '客服';
  }

  function agentAvatar() {
    return state.agent?.avatar || state.profile?.avatar || '';
  }

  function renderProfile() {
    $('#agentName').textContent = agentName();
    $('#agentMeta').textContent = state.agent?.agentNo
      ? `客服 ${state.agent.agentNo}`
      : (state.profile?.email || '客服工作台');
    const avatar = $('#agentAvatar');
    setAvatar(avatar, agentAvatar(), agentName());
    const online = String(state.agent?.status || '').toUpperCase() === 'AVAILABLE'
      || String(state.agent?.status || '').toUpperCase() === 'BUSY';
    $('#agentOnline').className = `status-dot ${online ? 'online' : ''}`.trim();
    $('#agentOnline').title = online ? '在线' : '离线';
  }

  function setAvatar(element, url, name) {
    element.textContent = '';
    if (url) {
      const image = document.createElement('img');
      image.alt = '';
      image.src = url;
      image.addEventListener('error', () => {
        element.textContent = initial(name);
      }, { once: true });
      element.appendChild(image);
    } else {
      element.textContent = initial(name);
    }
  }

  function initial(name) {
    return String(name || '访').trim().slice(0, 1).toUpperCase();
  }

  async function loadThreads(autoSelect = false) {
    if (state.loadingThreads) return;
    state.loadingThreads = true;
    try {
      const query = {
        pageNumber: 0,
        pageSize: 100,
        orgUid: state.orgUid,
        mergeByTopic: true,
        sortBy: 'updatedAt',
        sortDirection: 'descend'
      };
      let page;
      try {
        page = await api('/api/v1/thread/query/org', { query });
      } catch {
        page = await api('/api/v1/thread/query', { query });
      }
      const list = page?.content || page?.page?.content || [];
      state.threads = list.filter((thread) => !thread.hide);
      setConnection('ok', '已连接');

      if (state.selected) {
        const latest = state.threads.find((thread) => thread.uid === state.selected.uid);
        if (latest) state.selected = latest;
      }
      renderThreads();

      if (!state.selected && autoSelect) {
        const first = visibleThreads()[0];
        if (first) await selectThread(first.uid, false);
      }
    } catch (error) {
      setConnection('error', '同步失败');
      if (!state.threads.length) {
        threadList.innerHTML = '';
        const empty = document.createElement('div');
        empty.className = 'empty-state';
        empty.textContent = error.message;
        threadList.appendChild(empty);
      }
    } finally {
      state.loadingThreads = false;
    }
  }

  function visibleThreads() {
    const search = state.search.trim().toLowerCase();
    return state.threads.filter((thread) => {
      const status = String(thread.status || '').toUpperCase();
      if (state.filter === 'active' && !ACTIVE_STATUSES.has(status)) return false;
      if (!search) return true;
      const haystack = [
        thread.user?.nickname,
        thread.user?.uid,
        thread.userNickname,
        thread.content,
        thread.uid,
        thread.topic
      ].filter(Boolean).join(' ').toLowerCase();
      return haystack.includes(search);
    });
  }

  function renderThreads() {
    threadList.textContent = '';
    const threads = visibleThreads();
    if (!threads.length) {
      const empty = document.createElement('div');
      empty.className = 'empty-state';
      empty.textContent = state.search
        ? '没有匹配的会话'
        : (state.filter === 'active' ? '暂无进行中的会话' : '暂无会话记录');
      threadList.appendChild(empty);
      return;
    }

    threads.forEach((thread) => {
      const item = document.createElement('button');
      item.type = 'button';
      item.className = `thread-item${state.selected?.uid === thread.uid ? ' active' : ''}`;
      item.addEventListener('click', () => selectThread(thread.uid));

      const avatar = document.createElement('div');
      avatar.className = 'avatar';
      setAvatar(avatar, thread.user?.avatar, threadName(thread));

      const main = document.createElement('div');
      main.className = 'thread-item-main';
      const top = document.createElement('div');
      top.className = 'thread-item-top';
      const name = document.createElement('span');
      name.className = 'thread-item-name';
      name.textContent = threadName(thread);
      const status = document.createElement('span');
      status.className = 'thread-item-time';
      status.textContent = statusLabel(thread.status);
      top.append(name, status);
      const preview = document.createElement('div');
      preview.className = 'thread-item-preview';
      preview.textContent = messagePreview(thread.content) || '暂无消息';
      main.append(top, preview);

      const meta = document.createElement('div');
      meta.className = 'thread-item-meta';
      const time = document.createElement('span');
      time.className = 'thread-item-time';
      time.textContent = relativeTime(thread.updatedAt || thread.createdAt);
      meta.appendChild(time);
      if (Number(thread.unreadCount) > 0) {
        const badge = document.createElement('span');
        badge.className = 'unread-badge';
        badge.textContent = String(thread.unreadCount);
        meta.appendChild(badge);
      }
      item.append(avatar, main, meta);
      threadList.appendChild(item);
    });
  }

  function threadName(thread) {
    return thread?.user?.nickname || thread?.userNickname || thread?.user?.uid || '访客';
  }

  function statusLabel(status) {
    const value = String(status || '').toUpperCase();
    return {
      NEW: '新会话',
      ROBOTING: '机器人',
      QUEUING: '排队中',
      CHATTING: '对话中',
      CLOSED: '已结束',
      TIMEOUT: '已超时',
      OFFLINE: '离线'
    }[value] || (value || '会话');
  }

  async function selectThread(uid, reload = true) {
    const thread = state.threads.find((item) => item.uid === uid);
    if (!thread) return;
    state.selected = thread;
    workspace.classList.add('chat-open');
    chatEmpty.hidden = true;
    chatView.hidden = false;
    $('#chatTitle').textContent = threadName(thread);
    $('#chatSubtitle').textContent = `${statusLabel(thread.status)} · ${thread.type || '会话'} · ${thread.uid}`;
    renderThreads();
    if (reload) {
      state.messages = [];
      messageList.innerHTML = '';
    }
    await loadMessages(thread);
    messageInput.focus();
  }

  async function loadMessages(thread = state.selected) {
    if (!thread) return;
    try {
      const page = await api('/api/v1/message/thread/uid', {
        query: {
          pageNumber: 0,
          pageSize: 100,
          threadUid: thread.uid,
          orgUid: state.orgUid,
          sortBy: 'createdAt',
          sortDirection: 'ascend'
        }
      });
      const list = page?.content || page?.page?.content || [];
      const unique = new Map();
      list.forEach((message) => unique.set(String(message.uid || randomId()), message));
      state.messages = Array.from(unique.values()).sort(compareMessages);
      renderMessages(true);
      markThreadRead(thread);
      thread.unreadCount = 0;
      renderThreads();
    } catch (error) {
      if (!state.messages.length) {
        messageList.innerHTML = '';
        const empty = document.createElement('div');
        empty.className = 'empty-state';
        empty.textContent = error.message;
        messageList.appendChild(empty);
      }
    }
  }

  function compareMessages(left, right) {
    return messageTime(left) - messageTime(right);
  }

  function messageTime(message) {
    if (message?.timestamp) return Number(message.timestamp);
    const text = String(message?.createdAt || '').replace(/\[[^\]]*\]$/, '');
    const date = new Date(text);
    return Number.isNaN(date.getTime()) ? 0 : date.getTime();
  }

  function renderMessages(scrollToEnd = false) {
    const list = messageList;
    const wasNearBottom = list.scrollHeight - list.scrollTop - list.clientHeight < 80;
    list.textContent = '';
    if (!state.messages.length) {
      const empty = document.createElement('div');
      empty.className = 'empty-state';
      empty.textContent = '暂无消息，发送第一条回复吧。';
      list.appendChild(empty);
      return;
    }

    state.messages.forEach((message) => {
      const userType = String(message.user?.type || '').toUpperCase();
      const type = String(message.type || '').toUpperCase();
      if (type === 'SYSTEM' || type === 'NOTICE' || userType.includes('SYSTEM')) {
        const system = document.createElement('div');
        system.className = 'system-message';
        system.textContent = messageContent(message);
        list.appendChild(system);
        return;
      }

      const outgoing = String(message.user?.uid || '') === String(agentUid());
      const row = document.createElement('div');
      row.className = `message-row ${outgoing ? 'out' : 'in'}`;
      const avatar = document.createElement('div');
      avatar.className = 'avatar message-avatar';
      setAvatar(avatar, message.user?.avatar, message.user?.nickname || (outgoing ? agentName() : '访客'));
      const body = document.createElement('div');
      body.className = 'message-body';
      const bubble = document.createElement('div');
      bubble.className = 'bubble';
      bubble.textContent = messageContent(message);
      const meta = document.createElement('div');
      meta.className = 'message-meta';
      meta.textContent = `${message.user?.nickname || (outgoing ? agentName() : '访客')} · ${formatTime(message.createdAt || message.timestamp)}`;
      body.append(bubble, meta);
      row.append(avatar, body);
      list.appendChild(row);
    });

    if (scrollToEnd || wasNearBottom) {
      requestAnimationFrame(() => {
        list.scrollTop = list.scrollHeight;
      });
    }
  }

  function messageContent(message) {
    const type = String(message.type || '').toUpperCase();
    if (type === 'IMAGE') return '[图片]';
    if (type === 'FILE') return '[文件]';
    if (type === 'AUDIO' || type === 'VOICE') return '[语音]';
    if (type === 'VIDEO') return '[视频]';
    const raw = String(message.content || '');
    if (!raw) return '';
    try {
      const parsed = JSON.parse(raw);
      if (typeof parsed === 'string') return parsed;
      if (parsed && typeof parsed === 'object') {
        const value = parsed.text ?? parsed.content ?? parsed.preview ?? parsed.label;
        if (typeof value === 'string' && value.trim()) return stripHtml(value.trim());
        if (parsed.url && typeof parsed.url === 'string') return parsed.url;
      }
    } catch {}
    return stripHtml(raw);
  }

  function messagePreview(content) {
    if (!content) return '';
    return messageContent({ type: 'TEXT', content }).replace(/\s+/g, ' ').slice(0, 80);
  }

  function stripHtml(value) {
    const container = document.createElement('div');
    container.innerHTML = value;
    return (container.textContent || '').trim();
  }

  function formatTime(value) {
    if (!value) return '';
    const date = parseDate(value);
    return date ? date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) : '';
  }

  function relativeTime(value) {
    const date = parseDate(value);
    if (!date) return '';
    const diff = Date.now() - date.getTime();
    if (diff < 60_000) return '刚刚';
    if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟`;
    if (diff < 86_400_000) return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
    return date.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' });
  }

  function parseDate(value) {
    if (typeof value === 'number') {
      const date = new Date(value);
      return Number.isNaN(date.getTime()) ? null : date;
    }
    const text = String(value).replace(/\[[^\]]*\]$/, '');
    const date = new Date(text);
    return Number.isNaN(date.getTime()) ? null : date;
  }

  function currentTimestamp() {
    const now = new Date();
    const pad = (value) => String(value).padStart(2, '0');
    return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} `
      + `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`;
  }

  async function markThreadRead(thread) {
    if (!thread?.uid) return;
    try {
      await api(`/api/v1/message/thread/${encodeURIComponent(thread.uid)}/read`, {
        method: 'POST'
      });
    } catch {}
  }

  async function sendMessage() {
    const text = messageInput.value.trim();
    const thread = state.selected;
    if (!text || !thread) return;
    sendButton.disabled = true;
    sendHint.textContent = '正在发送…';
    try {
      const meta = await api('/api/v1/thread/message/meta', {
        method: 'POST',
        body: { uid: thread.uid, channel: HTTP_CHANNEL }
      });
      const messageUid = String(meta?.messageUid || randomId());
      const createdAt = currentTimestamp();
      const extra = meta?.extra || JSON.stringify({ orgUid: state.orgUid, visibility: 'public' });
      const message = {
        uid: messageUid,
        type: 'TEXT',
        status: 'SENDING',
        createdAt,
        channel: HTTP_CHANNEL,
        content: text,
        extra,
        user: {
          uid: agentUid(),
          nickname: agentName(),
          avatar: agentAvatar(),
          type: 'AGENT'
        },
        thread: {
          uid: thread.uid,
          type: thread.type,
          topic: thread.topic,
          user: {
            uid: thread.user?.uid,
            nickname: thread.user?.nickname,
            avatar: thread.user?.avatar
          }
        }
      };
      await api('/api/v1/message/rest/send', {
        method: 'POST',
        body: { json: JSON.stringify(message), channel: HTTP_CHANNEL }
      });
      state.messages.push({ ...message, status: 'SUCCESS', _pending: false });
      renderMessages(true);
      messageInput.value = '';
      sendHint.textContent = '已发送';
      setTimeout(() => { sendHint.textContent = '消息会发送给当前访客'; }, 1600);
      setTimeout(() => loadMessages(thread), 500);
    } catch (error) {
      showToast(error.message, 'error');
      sendHint.textContent = '发送失败，请重试';
    } finally {
      sendButton.disabled = false;
    }
  }

  function startPolling() {
    if (state.pollTimer) clearInterval(state.pollTimer);
    state.pollTimer = setInterval(async () => {
      if (document.hidden) return;
      await loadThreads(false);
      if (state.selected) await loadMessages(state.selected);
    }, 5000);
  }

  function bindEvents() {
    loginForm.addEventListener('submit', (event) => {
      event.preventDefault();
      const pastedToken = normalizeToken($('#loginToken').value);
      if (pastedToken) {
        state.token = pastedToken;
        try { localStorage.setItem(TOKEN_KEY, pastedToken); } catch {}
        bootstrap();
        return;
      }
      login($('#loginUsername').value.trim(), $('#loginPassword').value);
    });

    threadSearch.addEventListener('input', () => {
      state.search = threadSearch.value;
      renderThreads();
    });

    document.querySelectorAll('.filter').forEach((button) => {
      button.addEventListener('click', () => {
        document.querySelectorAll('.filter').forEach((item) => item.classList.remove('active'));
        button.classList.add('active');
        state.filter = button.dataset.filter;
        renderThreads();
      });
    });

    $('#refreshButton').addEventListener('click', async () => {
      await loadThreads(false);
      if (state.selected) await loadMessages(state.selected);
      showToast('会话已刷新');
    });

    $('#logoutButton').addEventListener('click', () => {
      clearSession();
      try { localStorage.removeItem(TOKEN_KEY); } catch {}
      showToast('已退出当前工作台');
    });

    sendButton.addEventListener('click', sendMessage);
    messageInput.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();
        sendMessage();
      }
    });

    $('#callButton').addEventListener('click', () => {
      if (!state.selected) return;
      showToast(`会话 ${state.selected.uid} · ${statusLabel(state.selected.status)}`);
    });

    document.addEventListener('visibilitychange', () => {
      if (!document.hidden && state.token) {
        loadThreads(false);
        if (state.selected) loadMessages(state.selected);
      }
    });
  }

  async function start() {
    bindEvents();
    state.token = normalizeToken(localStorage.getItem(TOKEN_KEY)) || readStoredToken();
    if (!state.token) {
      loginView.hidden = false;
      appView.hidden = true;
      return;
    }
    await bootstrap();
  }

  start();
})();
