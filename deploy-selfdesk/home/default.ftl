<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>SelfDesk 智能客服平台</title>
    <link rel="icon" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'%3E%3Crect width='32' height='32' rx='9' fill='%230E7A68'/%3E%3Cpath d='M10 12h12a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2h-6l-4 3v-3h-2a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2z' fill='%23fff'/%3E%3C/svg%3E">
    <style>
        :root {
            --bg: #F1F4F3;
            --panel: #ffffff;
            --ink: #0E1513;
            --ink-2: #48524E;
            --ink-3: #7C8781;
            --line: #E3E8E6;
            --primary: #0E7A68;
            --primary-soft: #E7F3F0;
        }
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            min-height: 100vh;
            background:
                radial-gradient(circle at 12% 8%, rgba(14,122,104,.07), transparent 34%),
                radial-gradient(circle at 88% 92%, rgba(109,90,230,.05), transparent 30%),
                var(--bg);
            color: var(--ink);
            font-family: "Inter", "Segoe UI", system-ui, -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif;
            -webkit-font-smoothing: antialiased;
            display: flex;
            justify-content: center;
            padding: 56px 24px 40px;
        }
        .wrap { width: 100%; max-width: 960px; }
        header { display: flex; align-items: center; gap: 14px; margin-bottom: 40px; }
        .mark {
            width: 44px; height: 44px; border-radius: 13px; background: var(--primary);
            display: grid; place-items: center; box-shadow: 0 10px 24px -14px rgba(14,122,104,.9);
        }
        .brand { font-size: 21px; font-weight: 600; letter-spacing: -.01em; }
        .tag { color: var(--ink-3); font-size: 13px; margin-top: 3px; }
        h1 { font-size: 34px; font-weight: 600; letter-spacing: -.025em; line-height: 1.25; }
        .lede { margin-top: 14px; color: var(--ink-2); font-size: 15px; line-height: 1.7; max-width: 660px; }
        .lede b { color: var(--ink); font-weight: 600; }
        .grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; margin-top: 34px; }
        a.card {
            display: block; text-decoration: none; color: inherit;
            background: var(--panel); border: 1px solid var(--line); border-radius: 16px;
            padding: 22px 20px 20px; transition: .16s ease;
            box-shadow: 0 18px 40px -34px rgba(14,21,19,.5);
        }
        a.card:hover { border-color: #C9D8D3; transform: translateY(-2px); box-shadow: 0 22px 44px -30px rgba(14,21,19,.45); }
        .ico {
            width: 34px; height: 34px; border-radius: 10px; background: var(--primary-soft);
            display: grid; place-items: center; margin-bottom: 14px;
        }
        .card h3 { font-size: 15px; font-weight: 600; }
        .card p { margin-top: 6px; font-size: 12.5px; color: var(--ink-3); line-height: 1.6; }
        .card .go { margin-top: 14px; font-size: 12px; color: var(--primary); font-weight: 500; }
        .sub { margin-top: 30px; }
        .sub-title { font-size: 12px; color: var(--ink-3); letter-spacing: .08em; text-transform: uppercase; margin-bottom: 12px; }
        .chips { display: flex; flex-wrap: wrap; gap: 8px; }
        .chips a {
            text-decoration: none; color: var(--ink-2); font-size: 12.5px;
            background: #FAFBFB; border: 1px solid var(--line); border-radius: 999px; padding: 7px 14px;
        }
        .chips a:hover { color: var(--primary); border-color: #CFE6E0; background: var(--primary-soft); }
        footer {
            margin-top: 44px; padding-top: 20px; border-top: 1px solid var(--line);
            display: flex; justify-content: space-between; flex-wrap: wrap; gap: 10px;
            font-size: 11.5px; color: var(--ink-3); letter-spacing: .02em;
        }
        .hint { margin-top: 18px; font-size: 12px; color: var(--ink-3); }
        @media (max-width: 760px) {
            body { padding: 32px 18px; }
            h1 { font-size: 26px; }
            .grid { grid-template-columns: 1fr; }
        }
    </style>
</head>
<body>
<div class="wrap">
    <header>
        <div class="mark">
            <svg width="23" height="23" viewBox="0 0 32 32" fill="none">
                <path d="M9 11h14a2.5 2.5 0 0 1 2.5 2.5v7A2.5 2.5 0 0 1 23 23h-6.2l-4.6 3.4V23H9a2.5 2.5 0 0 1-2.5-2.5v-7A2.5 2.5 0 0 1 9 11z" fill="#fff"/>
            </svg>
        </div>
        <div>
            <div class="brand">SelfDesk</div>
            <div class="tag">企业级智能客服平台</div>
        </div>
    </header>

    <h1>多渠道接入、坐席协同、知识库问答</h1>
    <p class="lede">
        面向在线客服场景的完整系统：<b>访客会话</b>、<b>坐席工作台</b>、<b>知识库 RAG</b>、<b>工单与 CRM</b>、<b>运营分析</b>。<br>AI 侧有两套可切换实现：Spring AI 直连，或 Python + LangGraph 编排（意图分流、工具调用、转人工挂起恢复）。
    </p>

    <div class="grid">
        <a class="card" href="/chat/">
            <div class="ico">
                <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="#0E7A68" stroke-width="1.8" stroke-linecap="round">
                    <path d="M21 11.5a8.4 8.4 0 0 1-9 8.4 8.9 8.9 0 0 1-3.3-.6L3 21l1.7-5a8.4 8.4 0 0 1 8.3-12.9 8.4 8.4 0 0 1 8 8.4z"/>
                </svg>
            </div>
            <h3>访客端</h3>
            <p>模拟用户在网页里发起咨询，由 AI 先回答，答不了转人工。</p>
            <div class="go">打开 /chat/ →</div>
        </a>

        <a class="card" href="/console/">
            <div class="ico">
                <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="#0E7A68" stroke-width="1.8" stroke-linecap="round">
                    <rect x="3" y="4" width="18" height="14" rx="2"/><path d="M8 21h8M12 18v3"/>
                </svg>
            </div>
            <h3>客服工作台</h3>
            <p>坐席侧：会话列表、接管、回复、结束，以及实时运营看板。</p>
            <div class="go">打开 /console/ →</div>
        </a>

        <a class="card" href="/admin">
            <div class="ico">
                <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="#0E7A68" stroke-width="1.8" stroke-linecap="round">
                    <rect x="3" y="3" width="7" height="7" rx="1.6"/><rect x="14" y="3" width="7" height="7" rx="1.6"/>
                    <rect x="3" y="14" width="7" height="7" rx="1.6"/><rect x="14" y="14" width="7" height="7" rx="1.6"/>
                </svg>
            </div>
            <h3>管理后台</h3>
            <p>组织与成员、角色权限、机器人配置、模型与渠道管理。</p>
            <div class="go">打开 /admin →</div>
        </a>
    </div>

    <div class="sub">
        <div class="sub-title">更多模块</div>
        <div class="chips">
            <a href="/kbase/">知识库</a>
            <a href="/ticket/">工单</a>
            <a href="/workflow/">工作流</a>
            <a href="/kanban/">看板</a>
            <a href="/voc/">客户之声</a>
            <a href="/meet/">会议</a>
            <a href="/office/">办公</a>
            <a href="/webrtc/">音视频</a>
        </div>
    </div>

    <p class="hint">演示环境已开启本机免密：打开管理后台会直接进入，不需要输入账号密码。</p>

    <footer>
        <span>JAVA 21 · SPRING BOOT · SPRING AI · LANGGRAPH · POSTGRESQL · ELASTICSEARCH</span>
        <span>AGPL-3.0</span>
    </footer>
</div>
</body>
</html>
