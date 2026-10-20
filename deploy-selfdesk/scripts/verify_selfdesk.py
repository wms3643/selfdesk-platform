from __future__ import annotations

import importlib.util
import json
from pathlib import Path


module_path = Path(__file__).with_name("test-selfdesk-chat.py")
spec = importlib.util.spec_from_file_location("selfdesk_chat_test", module_path)
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(module)

token = module.admin_token()
cases = [
    "如何使用 Docker 部署 SelfDesk 系统？",
]
for question in cases:
    result = module.run_case(question, token, "sync")
    print(json.dumps(result, ensure_ascii=False, indent=2))
