from pathlib import Path
from datetime import datetime
import hashlib
import json
import math
import re
import shutil
import xml.etree.ElementTree as ET

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent
WORKSPACE = ROOT.parents[2]
REPOSITORY = ROOT.parents[1]
SOURCE = ROOT / "source"
QA = ROOT / "qa"
SCALE = 2
WIDTH, HEIGHT = 1600, 900
VERSION = "banner-v1"
FONT_DIR = Path("C:/Windows/Fonts")
FONTS = {
    "bold": FONT_DIR / "arialbd.ttf",
    "regular": FONT_DIR / "segoeui.ttf",
    "medium": FONT_DIR / "segoeuib.ttf",
    "light": FONT_DIR / "segoeuil.ttf",
    "chinese": FONT_DIR / "msyh.ttc",
    "mono": FONT_DIR / "consola.ttf",
}
COPY = [
    "DSH Mobile",
    "DeepSeek Harness.",
    "Now in your pocket.",
    "完整 Agent 引擎，在 Android 上运行。",
    "No Root",
    "No Termux",
    "19 Extensions",
]


def font(size, family="regular"):
    return ImageFont.truetype(str(FONTS[family]), round(size * SCALE))


def box(values):
    return tuple(round(value * SCALE) for value in values)


def text(canvas, xy, value, size, fill, family="regular", tracking=0):
    draw = ImageDraw.Draw(canvas)
    if tracking:
        x, y = xy
        for char in value:
            draw.text(box((x, y)), char, font=font(size, family), fill=fill, anchor="lt")
            x += draw.textlength(char, font=font(size, family)) / SCALE + tracking
        return x
    draw.text(box(xy), value, font=font(size, family), fill=fill, anchor="lt")
    return xy[0] + draw.textlength(value, font=font(size, family)) / SCALE


def line(canvas, points, fill, width=1):
    ImageDraw.Draw(canvas).line([box(point) for point in points], fill=fill, width=round(width * SCALE))


def rounded(canvas, rect, radius, fill, outline=None, width=1):
    ImageDraw.Draw(canvas).rounded_rectangle(
        box(rect), round(radius * SCALE), fill=fill, outline=outline, width=round(width * SCALE)
    )


def vector_whale(width, color):
    xml = SOURCE / "ic_launcher_foreground.xml"
    node = ET.parse(xml).getroot().find(".//path")
    data = node.attrib["{http://schemas.android.com/apk/res/android}pathData"]
    tokens = re.findall(r"[A-Za-z]|[-+]?(?:\d*\.\d+|\d+)(?:[eE][-+]?\d+)?", data)
    contours = []
    position = np.array([0.0, 0.0])
    current = []
    index = 0
    command = ""
    last_control = None
    while index < len(tokens):
        if tokens[index].isalpha():
            command = tokens[index]
            index += 1
        op = command.upper()
        relative = command.islower()
        if op == "Z":
            contours.append(np.array(current))
            position = np.array(current[0])
            current = []
            command = ""
            last_control = None
            continue
        count = {"M": 2, "L": 2, "C": 6, "S": 4, "H": 1, "V": 1}[op]
        values = np.array([float(item) for item in tokens[index:index + count]])
        index += count
        if op in ("M", "L"):
            point = values + position if relative else values
            if op == "M" and current:
                contours.append(np.array(current))
                current = []
            current.append(point.copy())
            position = point
            last_control = None
            if op == "M":
                command = "l" if relative else "L"
        elif op in ("C", "S"):
            points = values.reshape((-1, 2))
            if relative:
                points += position
            if op == "C":
                p1, p2, end = points
            else:
                p1 = position if last_control is None else 2 * position - last_control
                p2, end = points
            for t in np.linspace(0, 1, 24)[1:]:
                current.append((1 - t) ** 3 * position + 3 * (1 - t) ** 2 * t * p1 + 3 * (1 - t) * t ** 2 * p2 + t ** 3 * end)
            position = end.copy()
            last_control = p2.copy()
        elif op == "H":
            position = np.array([position[0] + values[0] if relative else values[0], position[1]])
            current.append(position.copy())
            last_control = None
        elif op == "V":
            position = np.array([position[0], position[1] + values[0] if relative else values[0]])
            current.append(position.copy())
            last_control = None
    if current:
        contours.append(np.array(current))
    width_px = round(width * SCALE)
    height_px = round(width_px * 42.2 / 56.6)
    mask = np.zeros((height_px, width_px), np.uint8)
    ratio = width_px / 56.6
    for contour in contours:
        polygon = np.rint(contour * ratio).astype(np.int32)
        shape = np.zeros_like(mask)
        cv2.fillPoly(shape, [polygon], 255, lineType=cv2.LINE_AA)
        mask = cv2.bitwise_xor(mask, shape)
    image = Image.new("RGBA", (width_px, height_px), color)
    image.putalpha(Image.fromarray(mask))
    return image


def background():
    yy, xx = np.mgrid[0:HEIGHT, 0:WIDTH].astype(np.float32)
    rgb = np.empty((HEIGHT, WIDTH, 3), dtype=np.float32)
    rgb[:] = (5, 9, 20)
    for cx, cy, rx, ry, intensity, tint in [
        (1280, 420, 410, 400, .62, (8, 46, 150)),
        (1030, 870, 560, 155, .35, (10, 66, 128)),
        (1500, 120, 285, 260, .32, (10, 55, 180)),
        (260, 390, 560, 470, .16, (12, 24, 42)),
    ]:
        falloff = np.exp(-((xx - cx) / rx) ** 2 - ((yy - cy) / ry) ** 2) * intensity
        rgb += falloff[..., None] * np.array(tint)
    angle = math.radians(-30)
    dx, dy = xx - 1292, yy - 459
    local_x = dx * math.cos(angle) + dy * math.sin(angle)
    local_y = -dx * math.sin(angle) + dy * math.cos(angle)
    radius = np.sqrt((local_x / 351) ** 2 + (local_y / 248) ** 2)
    distance = (radius - 1) * 248
    azimuth = np.arctan2(local_y / 248, local_x / 351)
    glow = np.exp(-(distance / 47) ** 2) * .30
    rgb += glow[..., None] * np.array([10, 69, 170])
    alpha = np.clip((25 - np.abs(distance)) / 1.5, 0, 1)
    normal = distance / 24
    highlight = np.exp(-((normal + .72) / .11) ** 2)
    bevel = np.exp(-((normal - .75) / .17) ** 2)
    face = np.clip(1 - normal ** 2, 0, 1)
    lighting = .22 + .78 * (np.sin(azimuth - .9) + 1) / 2
    band = np.empty_like(rgb)
    band[:] = (4, 16, 54)
    band += face[..., None] * np.array([10, 32, 95]) * lighting[..., None]
    band += highlight[..., None] * np.array([85, 157, 195]) * lighting[..., None]
    band += bevel[..., None] * np.array([3, 42, 120])
    rgb = rgb * (1 - alpha[..., None]) + band * alpha[..., None]
    quiet = np.clip((xx - 760) / 180, 0, 1)
    base = np.empty_like(rgb)
    base[:] = (5, 9, 20)
    left_glow = np.exp(-((xx - 500) / 800) ** 2 - ((yy - 430) / 450) ** 2)
    base += left_glow[..., None] * np.array([2, 4, 8])
    rgb = base * (1 - quiet[..., None]) + rgb * quiet[..., None]
    noise = np.random.default_rng(47).normal(0, .32, (HEIGHT, WIDTH))
    rgb += noise[..., None]
    image = Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8))
    image.save(SOURCE / "deep-blue-current.png")
    return image.resize((WIDTH * SCALE, HEIGHT * SCALE), Image.Resampling.LANCZOS).convert("RGBA")


def phone(canvas, source_path, crop, center, width, rotation, accent):
    screenshot = Image.open(source_path).convert("RGB").crop(crop)
    screen_width = width - 18
    screen_height = screen_width * screenshot.height / screenshot.width
    height = screen_height + 32
    pad = 12
    image = Image.new("RGBA", box((width + pad * 2, height + pad * 2)), (0, 0, 0, 0))
    rect = (pad, pad, pad + width, pad + height)
    rounded(image, rect, 38, (28, 39, 62), (91, 117, 151), 1.2)
    rounded(image, (pad + 2, pad + 2, pad + width - 2, pad + height - 2), 36, (7, 11, 19), (55, 71, 97), .8)
    rounded(image, (pad + 6, pad + 7, pad + width - 6, pad + height - 7), 32, (18, 24, 33))
    line(image, [(pad + 1, pad + 56), (pad + 1, pad + height - 56)], (112, 149, 191), .7)
    line(image, [(pad + width - 1, pad + 55), (pad + width - 1, pad + height - 56)], (21, 49, 92), .9)
    for y, length in [(126, 34), (180, 54)]:
        rounded(image, (pad - 2, pad + y, pad + 1, pad + y + length), 1, (72, 91, 120))
    rounded(image, (pad + width - 1, pad + 165, pad + width + 2, pad + 235), 1, (47, 64, 91))
    screen_size = box((screen_width, screen_height))
    screen = screenshot.resize(screen_size, Image.Resampling.LANCZOS).convert("RGBA")
    mask = Image.new("L", screen_size)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, screen_size[0] - 1, screen_size[1] - 1), 25 * SCALE, fill=255)
    screen.putalpha(mask)
    image.alpha_composite(screen, box((pad + 9, pad + 13)))
    camera_x = pad + width / 2
    ImageDraw.Draw(image).ellipse(box((camera_x - 4, pad + 19, camera_x + 4, pad + 27)), fill=(2, 4, 9), outline=(30, 36, 50), width=SCALE)
    ImageDraw.Draw(image).ellipse(box((camera_x - 1.2, pad + 21, camera_x + .4, pad + 22.6)), fill=(18, 53, 83))
    rounded(image, (camera_x - 35, pad + height - 15, camera_x + 35, pad + height - 12), 1.5, (125, 137, 150))
    rotated = image.rotate(rotation, Image.Resampling.BICUBIC, expand=True)
    alpha = rotated.getchannel("A")
    shadow_image = Image.new("RGBA", rotated.size, (0, 2, 10, 255))
    shadow_image.putalpha(alpha.point(lambda value: round(value * .78)))
    shadow_pad = 100 * SCALE
    large = Image.new("RGBA", (rotated.width + shadow_pad * 2, rotated.height + shadow_pad * 2))
    large.alpha_composite(shadow_image, (shadow_pad, shadow_pad))
    large = large.filter(ImageFilter.GaussianBlur(29 * SCALE))
    px = round(center[0] * SCALE - rotated.width / 2)
    py = round(center[1] * SCALE - rotated.height / 2)
    canvas.alpha_composite(large, (px - shadow_pad + 18 * SCALE, py - shadow_pad + 30 * SCALE))
    halo = Image.new("RGBA", large.size, accent)
    halo_mask = Image.new("L", large.size)
    halo_mask.paste(alpha.point(lambda value: round(value * .18)), (shadow_pad, shadow_pad))
    halo.putalpha(halo_mask.filter(ImageFilter.GaussianBlur(13 * SCALE)))
    canvas.alpha_composite(halo, (px - shadow_pad, py - shadow_pad))
    canvas.alpha_composite(rotated, (px, py))
    return [px / SCALE + pad, py / SCALE + pad, (px + rotated.width) / SCALE - pad, (py + rotated.height) / SCALE - pad]


def pill(canvas, x, y, label, icon, width):
    rounded(canvas, (x, y, x + width, y + 43), 10, (15, 25, 43), (41, 57, 82), .8)
    color = (109, 181, 245)
    if icon == "check":
        line(canvas, [(x + 16, y + 22), (x + 20, y + 26), (x + 27, y + 17)], color, 1.8)
    elif icon == "terminal":
        line(canvas, [(x + 15, y + 16), (x + 21, y + 21.5), (x + 15, y + 27)], color, 1.6)
        line(canvas, [(x + 23, y + 27), (x + 29, y + 27)], color, 1.6)
    elif icon == "grid":
        for dx in (0, 7):
            for dy in (0, 7):
                rounded(canvas, (x + 16 + dx, y + 16 + dy, x + 21 + dx, y + 21 + dy), .8, color)
    text(canvas, (x + 39, y + 12), label, 17, (224, 231, 243), "medium")


def main():
    SOURCE.mkdir(exist_ok=True)
    QA.mkdir(exist_ok=True)
    assets = [
        (WORKSPACE / "_build_v128/emu35-toolbar.png", "ui-workspace.png"),
        (WORKSPACE / "_build_v128/emu44-tools.png", "ui-extensions.png"),
        (REPOSITORY / "app/src/main/res/drawable/ic_launcher_foreground.xml", "ic_launcher_foreground.xml"),
    ]
    for source, name in assets:
        assert source.is_file(), source
        target = SOURCE / name
        if not target.exists():
            shutil.copy2(source, target)
    for value in FONTS.values():
        assert value.is_file(), value
    canvas = background()
    whale = vector_whale(43, (208, 228, 255))
    canvas.alpha_composite(whale, box((98, 85)))
    text(canvas, (154, 93), "DEEPSEEK HARNESS FOR ANDROID", 14, (147, 171, 205), "medium", tracking=2)
    title_right = text(canvas, (94, 231), "DSH", 129, (242, 247, 255), "bold")
    title_mask = Image.new("L", canvas.size)
    ImageDraw.Draw(title_mask).text(box((title_right + 22, 231)), "Mobile", font=font(129, "bold"), fill=255, anchor="lt")
    gradient = np.zeros((canvas.height, canvas.width, 4), dtype=np.uint8)
    fraction = np.linspace(0, 1, canvas.height)[:, None]
    for channel, (a, b) in enumerate(zip((131, 185, 255), (49, 122, 236))):
        gradient[..., channel] = (a * (1 - fraction) + b * fraction).astype(np.uint8)
    gradient[..., 3] = np.array(title_mask)
    canvas.alpha_composite(Image.fromarray(gradient))
    line(canvas, [(100, 390), (137, 390)], (83, 155, 249), 2.5)
    text(canvas, (98, 423), "DeepSeek Harness.", 38, (224, 232, 246), "regular")
    text(canvas, (98, 473), "Now in your pocket.", 38, (224, 232, 246), "regular")
    text(canvas, (100, 546), "完整 Agent 引擎，在 Android 上运行。", 22, (146, 162, 189), "chinese")
    pill(canvas, 100, 604, "No Root", "check", 128)
    pill(canvas, 239, 604, "No Termux", "terminal", 151)
    pill(canvas, 401, 604, "19 Extensions", "grid", 181)
    text(canvas, (100, 678), "代码 · 文件 · Shell · 网页预览", 17, (111, 133, 165), "chinese")
    phone(canvas, SOURCE / "ui-extensions.png", (0, 120, 1080, 2330), (1058, 433), 288, 10, (34, 112, 208, 255))
    phone(canvas, SOURCE / "ui-workspace.png", (0, 235, 1080, 2330), (1285, 460), 348, -8, (50, 122, 225, 255))
    badge_x, badge_y = 1350, 638
    rounded(canvas, (badge_x, badge_y, badge_x + 161, badge_y + 65), 12, (10, 24, 46, 246), (59, 92, 135), .8)
    ImageDraw.Draw(canvas).ellipse(box((badge_x + 16, badge_y + 16, badge_x + 22, badge_y + 22)), fill=(82, 215, 173))
    text(canvas, (badge_x + 30, badge_y + 13), "ENGINE READY", 10.5, (141, 204, 196), "medium", tracking=1)
    text(canvas, (badge_x + 16, badge_y + 35), "127.0.0.1:3080", 14, (204, 223, 249), "mono")
    text(canvas, (1050, 812), "REAL APP. REAL CAPABILITY.", 10, (86, 120, 166), "medium", tracking=1.5)
    line(canvas, [(100, 770), (720, 770)], (34, 49, 71), .7)
    text(canvas, (100, 798), "github.com / Soodok / Deepseek-Harness-Local-Android", 12, (116, 139, 172), "regular")
    text(canvas, (100, 824), "OPEN SOURCE  /  MIT  /  COMMUNITY PROJECT", 9.5, (82, 108, 144), "medium", tracking=1.3)
    image = canvas.convert("RGB")
    image.save(ROOT / "dsh-mobile-github@2x.png", optimize=True)
    small = image.resize((WIDTH, HEIGHT), Image.Resampling.LANCZOS)
    small.save(ROOT / "dsh-mobile-github.png", optimize=True)
    small.save(ROOT / "dsh-mobile-github.webp", quality=95, method=6)
    social = image.crop(box((0, 50, 1600, 850))).resize((1280, 640), Image.Resampling.LANCZOS)
    social.save(ROOT / "dsh-mobile-social.png", optimize=True)
    small.resize((800, 450), Image.Resampling.LANCZOS).save(QA / "github-readme-800.png")
    social.resize((640, 320), Image.Resampling.LANCZOS).save(QA / "social-preview-640.png")
    sources = [{
        "file": str((SOURCE / name).relative_to(ROOT)).replace("\\", "/"),
        "origin": str(source).replace("\\", "/"),
        "sha256": hashlib.sha256((SOURCE / name).read_bytes()).hexdigest(),
    } for source, name in assets]
    sources.append({
        "file": "source/deep-blue-current.png",
        "origin": "Procedural local studio background; render_banner.py",
        "sha256": hashlib.sha256((SOURCE / "deep-blue-current.png").read_bytes()).hexdigest(),
    })
    manifest = {
        "version": VERSION,
        "built_at": datetime.now().astimezone().isoformat(timespec="seconds"),
        "copy": COPY,
        "sources": sources,
        "fonts": {key: str(value).replace("\\", "/") for key, value in FONTS.items()},
        "model_inference": "Configured API service",
        "ui_processing": "Real screenshots, scale and status/toolbar crop only. No generated UI content.",
        "safe_bounds": [
            {"name": "brand", "box": [94, 84, 745, 117]},
            {"name": "title", "box": [94, 229, 824, 366]},
            {"name": "claims", "box": [98, 423, 770, 707]},
            {"name": "engine_status", "box": [1350, 638, 1511, 703]},
            {"name": "footer", "box": [100, 795, 720, 840]},
        ],
        "outputs": ["dsh-mobile-github.png", "dsh-mobile-github@2x.png", "dsh-mobile-github.webp", "dsh-mobile-social.png"],
        "timeline": [{"version": VERSION, "time": datetime.now().astimezone().isoformat(timespec="seconds"), "change": "Initial dark navy banner with vector logo and real UI phone mockups."}],
    }
    (ROOT / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Rendered {VERSION}: 1600x900 / 3200x1800 / 1280x640")


if __name__ == "__main__":
    main()
