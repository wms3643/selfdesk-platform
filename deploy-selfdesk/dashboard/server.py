"""
SelfDesk 运营仪表盘 - 后端聚合服务
平台未提供统计接口(/api/v1/statistic、/api/v1/report 均不存在)，
这里改用 6 个查询接口做服务端聚合，再供前端渲染。

启动: python server.py   (默认 127.0.0.1:8002)
"""
from __future__ import annotations

import collections
import datetime as dt
import json
import os
import pathlib
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

import uvicorn
from fastapi import FastAPI, Header, HTTPException, Query
from fastapi.responses import FileResponse

HERE = pathlib.Path(__file__).resolve().parent
UPSTREAM = os.environ.get("SELFDESK_BASE", "http://127.0.0.1:9003").rstrip("/")
PORT = int(os.environ.get("DASHBOARD_PORT", "8002"))
ENV_FILE = HERE.parent / ".env.local"
APP_LOG = HERE.parent / "runtime" / "logs" / "app.log"
USAGE_FILE = HERE.parent / "runtime" / "ai-usage.json"
DEEPSEEK_KEY_FILE = HERE.parent / ".deepseek-key"

app = FastAPI(title="SelfDesk Dashboard")

# 服务端自动登录缓存：仅供本机 127.0.0.1 的仪表盘免密使用
_TOKEN = {"value": None, "exp": 0.0}
_TOKEN_TTL = 6 * 3600


def read_admin_password() -> str | None:
    """密码来源优先环境变量，其次 .env.local；不在代码里硬编码。"""
    env_pwd = os.environ.get("SELFDESK_ADMIN_PASSWORD")
    if env_pwd:
        return env_pwd
    try:
        for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
            if line.startswith("BYTEDESK_ADMIN_PASSWORD="):
                return line.split("=", 1)[1].strip().strip('"').strip("'")
    except OSError:
        pass
    return None


def service_token(force: bool = False) -> str | None:
    """用管理员账号在服务端静默登录，供仪表盘免密访问。"""
    now = time.time()
    if not force and _TOKEN["value"] and now < _TOKEN["exp"]:
        return _TOKEN["value"]
    pwd = read_admin_password()
    if not pwd:
        return None
    try:
        res = upstream("/auth/v1/login", "POST", {
            "username": "admin@email.com",
            "password": pwd,
            "platform": "BYTEDESK",
            "channel": "WEB",
        })
    except HTTPException:
        return None
    token = (res.get("data") or {}).get("accessToken")
    if token:
        _TOKEN["value"] = token
        _TOKEN["exp"] = now + _TOKEN_TTL
    return token

STATUS_LABEL = {
    "NEW": "待接入",
    "ROBOTING": "机器人接待",
    "QUEUING": "排队中",
    "CHATTING": "人工接待",
    "OFFLINE": "访客离线",
    "CLOSED": "已结束",
}
ACTIVE_STATUS = {"NEW", "ROBOTING", "QUEUING", "CHATTING"}

CHANNEL_LABEL = {
    "WEB_VISITOR": "网页访客",
    "WEB_FLOAT": "网页悬浮窗",
    "WEB_CHAT": "网页聊天",
    "APP": "App",
    "WECHAT": "微信",
    "SYSTEM": "系统",
}


def upstream(path: str, method: str = "GET", body=None, token: str | None = None,
             query: dict | None = None, timeout: int = 25):
    url = UPSTREAM + path
    if query:
        url += "?" + urllib.parse.urlencode(query)
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "ignore")[:200]
        raise HTTPException(status_code=exc.code, detail=detail or f"upstream {exc.code}")
    except urllib.error.URLError as exc:
        raise HTTPException(status_code=502, detail=f"无法连接 SelfDesk 应用 ({UPSTREAM}): {exc.reason}")


def fetch_list(path: str, token: str, size: int = 300) -> list:
    data = upstream(path, token=token, query={"pageSize": size, "pageNumber": 0})
    payload = data.get("data") or {}
    if isinstance(payload, list):
        return payload
    return payload.get("content") or []


def parse_day(value: str | None) -> str | None:
    if not value:
        return None
    return value[:10]


def pct(part: int, total: int) -> float:
    if not total:
        return 0.0
    return round(part * 100.0 / total, 1)


@app.get("/")
def index():
    return FileResponse(HERE / "index.html", media_type="text/html; charset=utf-8")


@app.post("/api/login")
def login(payload: dict):
    result = upstream("/auth/v1/login", "POST", {
        "username": payload.get("username", ""),
        "password": payload.get("password", ""),
        "platform": "BYTEDESK",
        "channel": "WEB",
    })
    if not result.get("data"):
        raise HTTPException(status_code=401, detail=result.get("message") or "登录失败")
    return result


# ── AI token 用量与费用 ──────────────────────────────────────
USAGE_LOG_PAT = re.compile(
    r"(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})[^\n]*?"
    r"Published AI token usage event: provider=([\w-]+), model=([\w.-]+), "
    r"tokens=(\d+)\+(\d+)=(\d+), success=(\w+), responseTime=(\d+)ms"
)

# DeepSeek 官方定价（USD / 1M tokens），顺序为 (非高峰, 高峰)
# 高峰 = 周一至周五 UTC 01:00-04:00 与 06:00-10:00
PRICING_USD = {
    "deepseek-flash": {"in_miss": (0.15, 0.30), "out": (0.60, 1.20)},
    "deepseek-v4-pro": {"in_miss": (0.66, 1.32), "out": (1.98, 3.96)},
}
MODEL_ALIAS = {"deepseek-chat": "deepseek-flash", "deepseek-reasoner": "deepseek-v4-pro"}
USD_CNY = 7.2

_USAGE_LOCK = threading.Lock()
_USAGE_STATE = {"offset": 0, "size": 0, "records": [], "seen": set(), "loaded": False}


def _is_peak(ts: dt.datetime) -> bool:
    u = ts - dt.timedelta(hours=8)  # 本地 UTC+8 -> UTC
    if u.weekday() >= 5:
        return False
    return (1 <= u.hour < 4) or (6 <= u.hour < 10)


def _cost_usd(model: str, prompt: int, completion: int, ts: dt.datetime) -> float:
    key = MODEL_ALIAS.get(model, model)
    price = PRICING_USD.get(key, PRICING_USD["deepseek-flash"])
    idx = 1 if _is_peak(ts) else 0
    return prompt / 1e6 * price["in_miss"][idx] + completion / 1e6 * price["out"][idx]


def _load_usage() -> None:
    if _USAGE_STATE["loaded"]:
        return
    if USAGE_FILE.exists():
        try:
            data = json.loads(USAGE_FILE.read_text(encoding="utf-8"))
            for rec in data.get("records", []):
                key = (rec["ts"], rec["prompt"], rec["completion"])
                if key not in _USAGE_STATE["seen"]:
                    _USAGE_STATE["seen"].add(key)
                    _USAGE_STATE["records"].append(rec)
        except (OSError, ValueError):
            pass
    _USAGE_STATE["loaded"] = True


def _save_usage() -> None:
    tmp = USAGE_FILE.with_suffix(".tmp")
    try:
        tmp.write_text(json.dumps({"records": _USAGE_STATE["records"]}, ensure_ascii=False), encoding="utf-8")
        tmp.replace(USAGE_FILE)
    except OSError:
        pass


def refresh_usage() -> None:
    """增量读取应用日志，累积 token 用量；日志被截断时自动从头重扫。"""
    with _USAGE_LOCK:
        _load_usage()
        if not APP_LOG.exists():
            return
        size = APP_LOG.stat().st_size
        offset = _USAGE_STATE["offset"]
        if size < offset or size < _USAGE_STATE["size"]:
            offset = 0
        changed = False
        with APP_LOG.open("r", encoding="utf-8", errors="ignore") as fh:
            fh.seek(offset)
            for line in fh:
                if "Published AI token usage event" not in line:
                    continue
                m = USAGE_LOG_PAT.search(line)
                if not m:
                    continue
                ts_s, provider, model, p, c, total, ok, ms = m.groups()
                key = (ts_s, int(p), int(c))
                if key in _USAGE_STATE["seen"]:
                    continue
                _USAGE_STATE["seen"].add(key)
                _USAGE_STATE["records"].append({
                    "ts": ts_s, "provider": provider, "model": model,
                    "prompt": int(p), "completion": int(c), "total": int(total),
                    "ok": ok == "true", "ms": int(ms),
                })
                changed = True
            _USAGE_STATE["offset"] = fh.tell()
        _USAGE_STATE["size"] = size
        if changed:
            _save_usage()


_BALANCE = {"at": 0.0, "data": None}


def deepseek_balance() -> dict:
    now = time.time()
    if _BALANCE["data"] and now - _BALANCE["at"] < 300:
        return _BALANCE["data"]
    result = {"available": False}
    try:
        key = DEEPSEEK_KEY_FILE.read_text(encoding="utf-8").strip()
        if key:
            req = urllib.request.Request("https://api.deepseek.com/user/balance")
            req.add_header("Authorization", "Bearer " + key)
            with urllib.request.urlopen(req, timeout=8) as resp:
                raw = json.loads(resp.read().decode("utf-8"))
            infos = raw.get("balance_infos") or []
            if infos:
                result = {
                    "available": bool(raw.get("is_available")),
                    "currency": infos[0].get("currency", "CNY"),
                    "total": float(infos[0].get("total_balance") or 0),
                    "toppedUp": float(infos[0].get("topped_up_balance") or 0),
                    "granted": float(infos[0].get("granted_balance") or 0),
                }
    except Exception:
        result = {"available": False}
    _BALANCE["data"] = result
    _BALANCE["at"] = now
    return result


def ai_usage(days: int) -> dict:
    refresh_usage()
    records = list(_USAGE_STATE["records"])
    today = dt.date.today().isoformat()

    def bucket(rows: list) -> dict:
        prompt = sum(r["prompt"] for r in rows)
        completion = sum(r["completion"] for r in rows)
        cost = 0.0
        for r in rows:
            try:
                ts = dt.datetime.strptime(r["ts"], "%Y-%m-%d %H:%M:%S")
            except ValueError:
                continue
            cost += _cost_usd(r["model"], r["prompt"], r["completion"], ts)
        return {
            "calls": len(rows),
            "prompt": prompt,
            "completion": completion,
            "tokens": prompt + completion,
            "costCny": round(cost * USD_CNY, 6),
        }

    start = dt.date.today() - dt.timedelta(days=days - 1)
    trend_map = {(start + dt.timedelta(days=i)).isoformat(): [] for i in range(days)}
    for r in records:
        d = r["ts"][:10]
        if d in trend_map:
            trend_map[d].append(r)

    model_rows = []
    for name, _count in collections.Counter(r["model"] for r in records).most_common():
        sub = [r for r in records if r["model"] == name]
        row = bucket(sub)
        row["model"] = name
        model_rows.append(row)

    ok_rows = [r for r in records if r["ok"]]
    return {
        "records": len(records),
        "today": bucket([r for r in records if r["ts"][:10] == today]),
        "total": bucket(records),
        "trend": [dict(date=d, **bucket(rows)) for d, rows in trend_map.items()],
        "models": model_rows,
        "avgResponseMs": round(sum(r["ms"] for r in records) / len(records)) if records else 0,
        "successRate": round(len(ok_rows) * 100 / len(records), 1) if records else 100.0,
        "balance": deepseek_balance(),
        "pricing": {
            "usdCny": USD_CNY,
            "model": "deepseek-flash",
            "inputMissPerM": list(PRICING_USD["deepseek-flash"]["in_miss"]),
            "outputPerM": list(PRICING_USD["deepseek-flash"]["out"]),
            "note": "按官方单价估算；输入按缓存未命中计（实际可能更低）；高峰为周一至周五 UTC 01-04、06-10",
        },
    }


def build_summary(days: int, token: str) -> dict:
    threads = fetch_list("/api/v1/thread/query/org", token)
    visitors = fetch_list("/api/v1/visitor/query/org", token)
    users = fetch_list("/api/v1/user/query/org", token)
    workgroups = fetch_list("/api/v1/workgroup/query/org", token)
    tickets = fetch_list("/api/v1/ticket/query/org", token)
    kbases = fetch_list("/api/v1/kbase/query/org", token)

    today = dt.date.today().isoformat()

    msg_all = sum(int(t.get("allMessageCount") or 0) for t in threads)
    msg_visitor = sum(int(t.get("visitorMessageCount") or 0) for t in threads)
    msg_robot = sum(int(t.get("robotMessageCount") or 0) for t in threads)
    msg_agent = sum(int(t.get("agentMessageCount") or 0) for t in threads)
    transfers = sum(1 for t in threads if t.get("robotToAgent"))

    status_counter = collections.Counter(t.get("status") or "UNKNOWN" for t in threads)
    channel_counter = collections.Counter(t.get("channel") or "UNKNOWN" for t in threads)

    # 近 N 天趋势（补齐没有数据的日期）
    start = dt.date.today() - dt.timedelta(days=days - 1)
    buckets = {}
    for i in range(days):
        d = (start + dt.timedelta(days=i)).isoformat()
        buckets[d] = {"date": d, "total": 0, "closed": 0, "transfer": 0, "messages": 0}
    for t in threads:
        d = parse_day(t.get("createdAt"))
        if d in buckets:
            buckets[d]["total"] += 1
            buckets[d]["messages"] += int(t.get("allMessageCount") or 0)
            if (t.get("status") or "") == "CLOSED":
                buckets[d]["closed"] += 1
            if t.get("robotToAgent"):
                buckets[d]["transfer"] += 1

    # 工作组负载
    wg_threads = collections.Counter()
    for t in threads:
        wg = (t.get("workgroupProtobuf") or {}).get("uid")
        if wg:
            wg_threads[wg] += 1
    wg_rows = []
    for w in workgroups:
        uid = w.get("uid")
        name = w.get("nickname") or ""
        if name.startswith("i18n."):
            name = "默认工作组"
        wg_rows.append({
            "uid": uid,
            "name": name,
            "threads": wg_threads.get(uid, 0),
            "available": int(w.get("availableAgentCount") or 0),
            "offline": int(w.get("offlineAgentCount") or 0),
            "status": w.get("status"),
        })
    # 多个工作组的 nickname 可能是同一个 i18n 占位符，按出现顺序编号区分
    dup = collections.Counter(r["name"] for r in wg_rows)
    seq: dict[str, int] = {}
    for r in wg_rows:
        if dup[r["name"]] > 1:
            seq[r["name"]] = seq.get(r["name"], 0) + 1
            r["name"] = f'{r["name"]} #{seq[r["name"]]}'
    wg_rows.sort(key=lambda r: r["threads"], reverse=True)

    online_visitors = sum(1 for v in visitors if (v.get("status") or "") == "ONLINE")
    kbase_articles = sum(int(k.get("articleCount") or 0) for k in kbases)

    last_activity = max((t.get("updatedAt") for t in threads if t.get("updatedAt")), default="")

    kpi = {
        "threads": len(threads),
        "threadsToday": sum(1 for t in threads if parse_day(t.get("createdAt")) == today),
        "active": sum(1 for t in threads if (t.get("status") or "") in ACTIVE_STATUS),
        "closed": status_counter.get("CLOSED", 0),
        "messages": msg_all,
        "visitorMessages": msg_visitor,
        "robotMessages": msg_robot,
        "agentMessages": msg_agent,
        "transfers": transfers,
        "transferRate": pct(transfers, len(threads)),
        "avgMessages": round(msg_all / len(threads), 1) if threads else 0,
        "visitors": len(visitors),
        "visitorsOnline": online_visitors,
        "agents": len(users),
        "workgroups": len(workgroups),
        "tickets": len(tickets),
        "kbaseArticles": kbase_articles,
    }

    status_rows = [
        {"key": k, "label": STATUS_LABEL.get(k, k), "count": v,
         "percent": pct(v, len(threads))}
        for k, v in status_counter.most_common()
    ]
    channel_rows = [
        {"key": k, "label": CHANNEL_LABEL.get(k, k), "count": v,
         "percent": pct(v, len(threads))}
        for k, v in channel_counter.most_common()
    ]

    recent = []
    for t in sorted(threads, key=lambda x: x.get("updatedAt") or "", reverse=True)[:9]:
        topic = t.get("topic") or ""
        visitor = (t.get("user") or {}).get("nickname") or ""
        if not visitor or str(visitor).startswith(("df_", "i18n.")):
            raw_uid = str((t.get("user") or {}).get("uid") or t.get("userUid") or "")
            digits = "".join(ch for ch in raw_uid if ch.isdigit())
            visitor = f"访客 #{digits[-4:]}" if digits else "匿名访客"
        recent.append({
            "uid": t.get("uid"),
            "visitor": visitor,
            "workgroup": "默认工作组" if str((t.get("workgroupProtobuf") or {}).get("nickname", "")).startswith("i18n.")
                        else (t.get("workgroupProtobuf") or {}).get("nickname") or "-",
            "status": t.get("status"),
            "statusLabel": STATUS_LABEL.get(t.get("status"), t.get("status")),
            "messages": int(t.get("allMessageCount") or 0),
            "updatedAt": t.get("updatedAt"),
            "topic": topic.split("/")[-1] if topic else "",
            "transfer": bool(t.get("robotToAgent")),
        })

    health = []
    if kbase_articles == 0:
        health.append({"level": "warn", "title": "知识库没有任何文章",
                       "detail": f"{len(kbases)} 个知识库合计 0 篇，机器人只能靠通用能力作答"})
    if kpi["agentMessages"] == 0:
        health.append({"level": "warn", "title": "尚无坐席回复",
                       "detail": "全部会话由机器人接待，人工接待链路还没跑通业务"})
    avail = sum(r["available"] for r in wg_rows)
    if avail == 0:
        health.append({"level": "warn", "title": "无在线可用坐席",
                       "detail": "所有工作组的可用坐席数为 0，转人工会进入排队"})
    if kpi["active"] > 0:
        health.append({"level": "info", "title": f'{kpi["active"]} 个会话进行中',
                       "detail": "含机器人接待、排队与人工接待状态"})
    if kpi["tickets"] == 0:
        health.append({"level": "info", "title": "工单模块为空",
                       "detail": "还没有创建过工单，可从 /ticket/ 页面体验"})
    if not health:
        health.append({"level": "ok", "title": "各项指标正常", "detail": "没有发现异常"})

    return {
        "generatedAt": dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "upstream": UPSTREAM,
        "lastActivity": last_activity,
        "kpi": kpi,
        "ai": ai_usage(days),
        "trend": [buckets[(start + dt.timedelta(days=i)).isoformat()] for i in range(days)],
        "status": status_rows,
        "channels": channel_rows,
        "workgroups": wg_rows,
        "recent": recent,
        "health": health,
        "org": {"name": "MyCompany", "uid": "df_org_uid"},
    }


@app.get("/api/summary")
def summary(days: int = Query(7, ge=1, le=90), x_token: str = Header(default="")):
    # 优先使用浏览器带来的令牌；没有就用服务端静默登录的令牌（免密）
    token = x_token or service_token()
    if not token:
        raise HTTPException(status_code=401,
                            detail="自动登录失败：请检查 .env.local 中的 BYTEDESK_ADMIN_PASSWORD")
    try:
        return build_summary(days, token)
    except HTTPException as exc:
        if exc.status_code == 401:
            fresh = service_token(force=True)
            if fresh and fresh != token:
                return build_summary(days, fresh)
        raise


@app.get("/api/session")
def session():
    """供前端判断是否可免密进入。"""
    token = service_token()
    return {"autoLogin": bool(token), "upstream": UPSTREAM}


if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=PORT, log_level="warning")
