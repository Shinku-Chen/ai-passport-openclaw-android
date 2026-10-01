#!/usr/bin/env python3
"""从定稿图标生成安卓全套图标资源(app/src/main/res 下的 mipmap*、drawable)。

用法::

    python tools/make_android_icons.py                # 用仓库内母版
    python tools/make_android_icons.py <母版.png>     # 用指定的 2048x2048 RGB 母版

输入:2048x2048 RGB 母版(带浅暖色背景的完整插画)。默认取仓库内的原件
`docs/design/app-icon-source.png`(2048x2048、256 色调色板,视觉上与原始生成图一致;
完整无损版为 AI 生成稿,未入库)。

输出:

  - `mipmap-anydpi-v26/ic_launcher.xml` + `ic_launcher_round.xml`(自适应图标)
  - `drawable/ic_launcher_background.png`(432x432,母版模糊后的暖色底,无接缝)
  - `mipmap-xxxhdpi/ic_launcher_foreground.png`(432x432,母版缩到安全区内,透明底)
  - `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png`(48/72/96/144/192)
  - `mipmap-{同上}/ic_launcher_round.png`(同上,圆形遮罩)

设计取舍:

  - 自适应图标的前景按 108dp 画布、内容缩到约 78%(约 84dp),
    这样圆形/方形遮罩只裁到背景,不会切到设备主体;
  - 背景用母版高斯模糊后的暖色底,与母版自身渐变几乎一致,前景贴上去没有硬边。
"""
from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

REPO = Path(__file__).resolve().parents[1]
DEFAULT_MASTER = REPO / "docs" / "design" / "app-icon-source.png"
RES = REPO / "app" / "src" / "main" / "res"

ADAPTIVE = 432          # 108dp @ xxxhdpi
FORE_RATIO = 0.78       # 前景里母版的占比(其余留作遮罩余量;0.78 让设备完整落在安全区内)
LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def main() -> int:
    master_path = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else DEFAULT_MASTER
    if not master_path.is_file():
        print(f"母版不存在: {master_path}")
        return 1
    if not RES.is_dir():
        print(f"资源目录不存在: {RES}")
        return 1

    master = Image.open(master_path).convert("RGB")
    side = min(master.size)
    master = master.crop(((master.width - side) // 2, (master.height - side) // 2,
                          (master.width - side) // 2 + side, (master.height - side) // 2 + side))

    # 1) 自适应前景:母版缩到 78%,居中放在透明画布上
    fg = Image.new("RGBA", (ADAPTIVE, ADAPTIVE), (0, 0, 0, 0))
    inner = int(ADAPTIVE * FORE_RATIO)
    fg.paste(master.resize((inner, inner), Image.LANCZOS),
             ((ADAPTIVE - inner) // 2, (ADAPTIVE - inner) // 2))
    (RES / "mipmap-xxxhdpi").mkdir(parents=True, exist_ok=True)
    fg.save(RES / "mipmap-xxxhdpi" / "ic_launcher_foreground.png", "PNG", optimize=True)

    # 2) 自适应背景:母版放大后高斯模糊,作为无接缝暖色底
    bg = master.resize((ADAPTIVE, ADAPTIVE), Image.LANCZOS).filter(ImageFilter.GaussianBlur(46))
    (RES / "drawable").mkdir(parents=True, exist_ok=True)
    bg.save(RES / "drawable" / "ic_launcher_background.png", "PNG", optimize=True)

    # 3) 各密度传统图标(方版 + 圆版)
    for density, px in LEGACY.items():
        d = RES / f"mipmap-{density}"
        d.mkdir(parents=True, exist_ok=True)
        square = master.resize((px, px), Image.LANCZOS)
        square.save(d / "ic_launcher.png", "PNG", optimize=True)

        big = 4  # 先放大再画圆再缩,边缘更平滑
        mask = Image.new("L", (px * big, px * big), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, px * big - 1, px * big - 1), fill=255)
        mask = mask.resize((px, px), Image.LANCZOS)
        round_icon = square.convert("RGBA")
        round_icon.putalpha(mask)
        round_icon.save(d / "ic_launcher_round.png", "PNG", optimize=True)

    # 4) 自适应图标 XML
    anydpi = RES / "mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)
    xml = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""
    (anydpi / "ic_launcher.xml").write_text(xml, encoding="utf-8", newline="\n")
    (anydpi / "ic_launcher_round.xml").write_text(xml, encoding="utf-8", newline="\n")

    print(f"母版: {master_path.relative_to(REPO) if master_path.is_relative_to(REPO) else master_path}")
    print("已生成:")
    for p in sorted(RES.rglob("ic_launcher*")):
        print(f"  {p.relative_to(RES)}  {p.stat().st_size} B")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
