"""把 SelfDesk 定制层打进 bytedesk fat jar（可重复执行）。

做四件事：
1. 用本仓库的 console/ 覆盖 jar 内 `static/console/{index.html,styles.css}`
2. 写入 `static/selfdesk-brand.js` 与 `static/selfdesk-admin-dash.js`
3. 在 `static/admin/index.html` 里补一行 brand.js 的 <script>，并把 theme-color 换成品牌色
4. 如果本地存在 `.selfdesk-autologin.json`（已 gitignore），把账号密码注入 jar 内的 brand.js，
   实现本机免密；没有这个文件就保持正常登录页。

用法：
    python scripts/patch_jar.py
    SELFDESK_JAR=D:\\path\\to\\bytedesk-starter.jar python scripts/patch_jar.py
"""
from __future__ import annotations

import json
import os
import pathlib
import shutil
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
JAR = pathlib.Path(
    os.environ.get("SELFDESK_JAR")
    or str(ROOT.parent / "starter" / "target" / "bytedesk-starter.jar")
)
PREFIX = "BOOT-INF/classes/static/"
ADMIN_INDEX = PREFIX + "admin/index.html"
BRAND_JS = PREFIX + "selfdesk-brand.js"
DASH_JS = PREFIX + "selfdesk-admin-dash.js"

TPL = "BOOT-INF/classes/templates/"
SYNC = {
    TPL + "ftl/default.ftl": ROOT / "home" / "default.ftl",
    PREFIX + "console/index.html": ROOT / "console" / "index.html",
    PREFIX + "console/styles.css": ROOT / "console" / "styles.css",
}
ASSETS = {
    BRAND_JS: ROOT / "admin-patch" / "selfdesk-brand.js",
    DASH_JS: ROOT / "admin-patch" / "selfdesk-admin-dash.js",
}

INJECT = '<script src="/selfdesk-brand.js" defer></script>'


def autologin_prefix() -> str:
    """注入免密配置。

    优先级：
    1. 本地 `.selfdesk-autologin.json`（已 gitignore，仓库里永远没有明文）
    2. 演示模式（默认开启）：用 SELFDESK_DEMO_USERNAME / SELFDESK_DEMO_PASSWORD，
       与容器里的 BYTEDESK_ADMIN_PASSWORD 保持一致
    3. SELFDESK_DEMO_AUTOLOGIN=0 关闭，回到正常登录页
    """
    cfg_file = ROOT / ".selfdesk-autologin.json"
    if cfg_file.exists():
        try:
            cfg = json.loads(cfg_file.read_text(encoding="utf-8"))
        except Exception as exc:  # noqa: BLE001
            raise SystemExit(f".selfdesk-autologin.json 解析失败：{exc}")
        if not cfg.get("username") or not cfg.get("password"):
            raise SystemExit(".selfdesk-autologin.json 需要 username 与 password")
        print("  · 检测到 .selfdesk-autologin.json，将注入本机免密配置")
        return "window.__SELFDESK_AUTOLOGIN__=" + json.dumps(cfg, ensure_ascii=False) + ";\n"

    demo = os.environ.get("SELFDESK_DEMO_AUTOLOGIN", "1").strip().lower()
    if demo in ("0", "false", "off", "no"):
        print("  · 演示免密已关闭（SELFDESK_DEMO_AUTOLOGIN=0），保留正常登录页")
        return ""

    cfg = {
        "username": os.environ.get("SELFDESK_DEMO_USERNAME", "admin@email.com"),
        "password": os.environ.get("SELFDESK_DEMO_PASSWORD", "SelfDesk#2026"),
    }
    print(
        "  · 演示免密已启用："
        + cfg["username"]
        + "（设 SELFDESK_DEMO_AUTOLOGIN=0 可关闭）"
    )
    return "window.__SELFDESK_AUTOLOGIN__=" + json.dumps(cfg, ensure_ascii=False) + ";\n"


def main() -> None:
    if not JAR.exists():
        raise SystemExit(
            f"找不到 jar：{JAR}\n"
            "先用 SELFDESK_JAR 指定路径，或把上游 bytedesk 构建到默认位置。"
        )

    brand = ASSETS[BRAND_JS].read_text(encoding="utf-8")
    brand = autologin_prefix() + brand
    dash = ASSETS[DASH_JS].read_text(encoding="utf-8")

    tmp = JAR.with_suffix(".jar.new")
    stats = {"synced": 0, "assets": 0, "html": 0}

    with zipfile.ZipFile(JAR) as zin, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            name = item.filename
            data = zin.read(name)

            if name in SYNC:
                zout.writestr(item, SYNC[name].read_bytes())
                stats["synced"] += 1
                continue

            if name == BRAND_JS:
                zout.writestr(item, brand.encode("utf-8"))
                stats["assets"] += 1
                continue

            if name == DASH_JS:
                zout.writestr(item, dash.encode("utf-8"))
                stats["assets"] += 1
                continue

            if name == ADMIN_INDEX:
                html = data.decode("utf-8")
                if "selfdesk-brand.js" not in html:
                    if "</body>" not in html:
                        raise SystemExit("admin/index.html 里没有 </body>，无法注入")
                    html = html.replace("</body>", "  " + INJECT + "\n</body>", 1)
                html = html.replace('content="#1677ff"', 'content="#0E7A68"')
                zout.writestr(item, html.encode("utf-8"))
                stats["html"] += 1
                continue

            zout.writestr(item, data)

    backup = JAR.with_suffix(".jar.bak-selfdesk")
    if not backup.exists():
        shutil.copy2(JAR, backup)
        print(f"  · 原 jar 已备份：{backup.name}")

    tmp.replace(JAR)
    print(
        f"完成：页面同步 {stats['synced']} 个 / 脚本 {stats['assets']} 个 / HTML {stats['html']} 个，"
        f"jar 现在 {JAR.stat().st_size / 1048576:.1f} MB"
    )


if __name__ == "__main__":
    main()
