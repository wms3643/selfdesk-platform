from __future__ import annotations

import shutil
import json
import re
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


def font(size: int) -> ImageFont.FreeTypeFont:
    candidates = [
        Path(r"C:\Windows\Fonts\arialbd.ttf"),
        Path(r"C:\Windows\Fonts\segoeuib.ttf"),
        Path(r"C:\Windows\Fonts\calibrib.ttf"),
    ]
    for path in candidates:
        if path.exists():
            return ImageFont.truetype(str(path), size)
    return ImageFont.load_default()


def build_icon(static_root: Path) -> None:
    size = 512
    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    gradient = Image.new("RGBA", (size, size))
    pixels = gradient.load()
    start = (11, 18, 32)
    end = (15, 118, 110)
    for y in range(size):
        for x in range(size):
            ratio = (x + y) / (2 * (size - 1))
            pixels[x, y] = (
                round(start[0] + (end[0] - start[0]) * ratio),
                round(start[1] + (end[1] - start[1]) * ratio),
                round(start[2] + (end[2] - start[2]) * ratio),
                255,
            )
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size - 1, size - 1), radius=116, fill=255)
    image.alpha_composite(Image.composite(gradient, Image.new("RGBA", image.size), mask))

    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((116, 98, 396, 348), radius=54, fill="white")
    draw.polygon([(205, 338), (142, 420), (258, 358)], fill="white")
    draw.ellipse((365, 95, 416, 146), fill="#2DD4BF")
    draw.text((184, 136), "S", font=font(188), fill="#0F766E", stroke_width=2)

    image.save(static_root / "logo.png", optimize=True)
    image.save(static_root / "logo_round.png", optimize=True)
    image.save(static_root / "chat" / "logo.png", optimize=True)
    image.resize((512, 512), Image.Resampling.LANCZOS).save(static_root / "chat" / "logo512.png", optimize=True)
    image.resize((192, 192), Image.Resampling.LANCZOS).save(static_root / "chat" / "logo192.png", optimize=True)
    image.save(static_root / "favicon.ico", sizes=[(16, 16), (32, 32), (48, 48), (64, 64)])
    image.save(static_root / "chat" / "favicon.ico", sizes=[(16, 16), (32, 32), (48, 48), (64, 64)])

    source_svg = static_root / "selfdesk-logo.svg"
    for target in [
        static_root / "logo.svg",
        static_root / "logo-light.svg",
        static_root / "logo-dark.svg",
        static_root / "chat" / "logo.svg",
    ]:
        shutil.copyfile(source_svg, target)

    for favicon in static_root.rglob("favicon.ico"):
        if favicon.parent.name == "chat" or favicon.resolve() == (static_root / "favicon.ico").resolve():
            continue
        shutil.copyfile(static_root / "favicon.ico", favicon)

    for manifest in list(static_root.rglob("manifest*.json")) + list(static_root.rglob("*.webmanifest")):
        try:
            data = json.loads(manifest.read_text(encoding="utf-8"))
        except Exception:
            continue
        for icon in data.get("icons") or []:
            src = icon.get("src")
            if not src:
                continue
            icon_path = (manifest.parent / src.lstrip("/")).resolve()
            try:
                icon_path.relative_to(static_root.resolve())
            except ValueError:
                continue
            icon_path.parent.mkdir(parents=True, exist_ok=True)
            if icon_path.suffix.lower() == ".svg":
                shutil.copyfile(source_svg, icon_path)
                continue
            sizes = icon.get("sizes") or "192x192"
            match = re.search(r"(\d+)x(\d+)", sizes)
            edge = int(match.group(1)) if match else 192
            edge = max(16, min(edge, 1024))
            image.resize((edge, edge), Image.Resampling.LANCZOS).save(icon_path, optimize=True)


if __name__ == "__main__":
    import sys

    if len(sys.argv) != 2:
        raise SystemExit("usage: generate_selfdesk_assets.py <static-root>")
    build_icon(Path(sys.argv[1]))
