from __future__ import annotations

import argparse
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


BASE_URL = "http://127.0.0.1:9003"
LOCAL_DIR = Path(__file__).resolve().parent
ADMIN_USERNAME = "admin@email.com"
ORG_UID = "df_org_uid"
WORKGROUP_UID = "df_wg_uid"


def request_json(
    method: str,
    path: str,
    payload: dict | None = None,
    token: str | None = None,
    timeout: int = 120,
) -> dict:
    data = None
    headers = {"Accept": "application/json"}
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(
        BASE_URL + path,
        data=data,
        headers=headers,
        method=method,
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            body = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {exc.code}: {body[:1000]}") from exc
    return json.loads(body)


def read_admin_password() -> str:
    env_path = LOCAL_DIR / ".env.local"
    for line in env_path.read_text(encoding="utf-8").splitlines():
        if line.startswith("BYTEDESK_ADMIN_PASSWORD="):
            return line.split("=", 1)[1].strip().strip('"').strip("'")
    raise RuntimeError("BYTEDESK_ADMIN_PASSWORD is missing from .env.local")


def admin_token() -> str:
    result = request_json(
        "POST",
        "/auth/v1/login",
        {
            "username": ADMIN_USERNAME,
            "password": read_admin_password(),
            "platform": "BYTEDESK",
            "channel": "WEB",
        },
        timeout=30,
    )
    if result.get("code") != 200:
        raise RuntimeError(f"admin login failed: {result.get('message')}")
    return result["data"]["accessToken"]


def run_case(question: str, token: str, transport: str) -> dict:
    suffix = str(time.time_ns())
    visitor_uid = f"auto_test_{suffix}"
    init = request_json(
        "POST",
        "/visitor/api/v1/init",
        {
            "orgUid": ORG_UID,
            "visitorUid": visitor_uid,
            "nickname": "自动功能测试",
            "channel": "WEB_FLOAT",
            "client": "WEB_FLOAT",
            "lang": "ZH_CN",
            "status": "ONLINE",
        },
        timeout=30,
    )
    if init.get("code") != 200:
        raise RuntimeError(f"visitor init failed: {init.get('message')}")

    visitor = init["data"]
    thread_result = request_json(
        "POST",
        "/visitor/api/v1/thread",
        {
            "orgUid": ORG_UID,
            "visitorUid": visitor_uid,
            "uid": visitor["uid"],
            "sid": WORKGROUP_UID,
            "type": "WORKGROUP",
            "channel": "WEB_FLOAT",
            "client": "WEB_FLOAT",
            "lang": "ZH_CN",
            "forceNewThread": True,
            "forceAgent": False,
            "callType": "TEXT",
        },
        timeout=60,
    )
    if thread_result.get("code") != 200:
        raise RuntimeError(f"thread request failed: {thread_result.get('message')}")

    thread = thread_result["data"]["thread"]
    message = {
        "uid": f"msg_{suffix}",
        "type": "TEXT",
        "content": question,
        "status": "SENDING",
        "channel": "WEB_FLOAT",
        "thread": {
            "uid": thread["uid"],
            "topic": thread["topic"],
            "type": "WORKGROUP",
            "status": "CHATTING",
            "channel": "WEB_FLOAT",
        },
        "user": {
            "uid": visitor["uid"],
            "visitorUid": visitor_uid,
            "nickname": "自动功能测试",
            "type": "VISITOR",
        },
    }
    message_json = json.dumps(message, ensure_ascii=False)
    if transport == "sync":
        reply = request_json(
            "POST",
            "/visitor/api/v1/message/sync",
            {"json": message_json},
            timeout=150,
        )
        if reply.get("code") != 200:
            raise RuntimeError(f"chat failed: {reply.get('message')}")
        reply_data = reply.get("data") or {}
    else:
        req = urllib.request.Request(
            BASE_URL + "/visitor/api/v1/message/sse",
            data=message_json.encode("utf-8"),
            headers={"Content-Type": "text/plain; charset=utf-8", "Accept": "text/event-stream"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=180) as response:
            response.read()
        time.sleep(2)
        query = urllib.parse.urlencode({
            "orgUid": ORG_UID,
            "threadUid": thread["uid"],
            "pageNumber": 0,
            "pageSize": 100,
            "sortBy": "createdAt",
            "sortDirection": "ascend",
        })
        messages = request_json("GET", f"/visitor/api/v1/message/thread/uid?{query}", timeout=30)
        rows = (messages.get("data") or {}).get("content") or []
        robot_rows = [row for row in rows if row.get("type") == "ROBOT"]
        reply_data = robot_rows[-1] if robot_rows else {}

    time.sleep(1.2)
    query = urllib.parse.urlencode({"uid": thread["uid"], "orgUid": ORG_UID})
    current = request_json("GET", f"/api/v1/thread/query/uid?{query}", token=token, timeout=30)
    current_data = current.get("data") or {}
    return {
        "question": question,
        "visitorUid": visitor_uid,
        "threadUid": thread["uid"],
        "initialThreadStatus": thread_result["data"]["thread"].get("status"),
        "replyType": reply_data.get("type"),
        "replyContent": reply_data.get("content"),
        "replyExtra": reply_data.get("extra"),
        "transport": transport,
        "currentThreadStatus": current_data.get("status"),
        "transferStatus": current_data.get("transferStatus"),
        "currentRobotUid": (current_data.get("robot") or {}).get("uid")
        if isinstance(current_data.get("robot"), dict)
        else None,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sse", action="store_true")
    parser.add_argument("question", nargs="+")
    args = parser.parse_args()
    question = " ".join(args.question)
    token = admin_token()
    result = run_case(question, token, "sse" if args.sse else "sync")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
