"""Builds a photo-like OCR evaluation set by degrading rendered text.

  PYTHONPATH=<LiteRT deps with numpy + Pillow> python3.12 tools/make_eval_set.py <output dir>

Each case is `<category>__<n>.jpg|png` with its ground truth in `<category>__<n>.txt` (one line of
text per line). HostPipelineTest.ocrEvaluation (env RECOGNITION_EVAL_DIR) scores a directory of such
pairs per category. The set is generated, not committed: it is deterministic (fixed seeds).
"""
import math, sys
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
only = set(sys.argv[2:])

ASSET = "/System/Library/AssetsV2/com_apple_MobileAsset_Font8"
FONTS = {
    "hei": ("/System/Library/Fonts/STHeiti Light.ttc", 0),
    "heibold": ("/System/Library/Fonts/STHeiti Medium.ttc", 0),
    "song": ("/System/Library/Fonts/Supplemental/Songti.ttc", None),
    "kai": (f"{ASSET}/88d6cc32a907955efa1d014207889413890573be.asset/AssetData/Kaiti.ttc", None),
    "pingfang": (f"{ASSET}/86ba2c91f017a3749571a82f2c6d890ac7ffb2fb.asset/AssetData/PingFang.ttc", None),
}
TEXTS = [
    ["深圳是一座年轻的现代化城市", "这里有很多高楼和美丽的公园", "每天都有人来这里工作和生活", "我们喜欢在海边散步看日落", "欢迎你来深圳旅游"],
    ["今天的天气非常好", "我想和朋友一起去喝早茶", "听说那家餐厅的点心很有名", "虾饺烧卖肠粉样样都好吃", "吃完以后再去书店买几本书"],
    ["请问地铁站怎么走", "一直往前走到红绿灯向右转", "大约需要十分钟就可以到达", "如果下雨可以坐公共汽车", "谢谢你的帮助再见"],
    ["学习中文需要每天练习", "先学会拼音再认识汉字", "多听多说多读多写进步很快", "遇到不懂的词语就查字典", "坚持一年就能看懂报纸"],
    ["注意安全小心地滑", "禁止吸烟请勿大声喧哗", "出口在左边洗手间在右边", "营业时间上午九点到晚上十点", "本店商品明码标价"],
    ["香港的夜景十分迷人", "維多利亞港兩岸燈火通明", "遊客可以乘坐天星小輪過海", "山頂纜車已經有一百多年歷史", "茶餐廳的奶茶和菠蘿包最受歡迎"],
]
SIGNS = [["小心台阶", "注意安全"], ["欢迎光临", "营业中"], ["禁止停车", "违者拖走"], ["深圳北站", "出发层"], ["牛肉面", "十八元"], ["文明城市", "你我共建"]]


def font(name, size, bold=False):
    path, index = FONTS[name]
    if index is None:
        # Pick the regular (or bold) Simplified face of the collection.
        want = "Bold" if bold else "Regular"
        index = 0
        for i in range(12):
            try:
                f = ImageFont.truetype(path, size, index=i)
            except OSError:
                break
            family, style = f.getname()
            if ("SC" in family or "Kaiti" in family) and style == want:
                index = i
                break
    return ImageFont.truetype(path, size, index=index)


def render(lines, fname="hei", size=36, fg=(20, 20, 20), bg=(250, 250, 250), spacing=1.7, margin=50, bold=False):
    f = font(fname, size, bold)
    width = int(max(f.getlength(l) for l in lines)) + 2 * margin
    image = Image.new("RGB", (width, int(len(lines) * size * spacing) + 2 * margin - int(size * (spacing - 1))), bg)
    d = ImageDraw.Draw(image)
    for i, line in enumerate(lines):
        d.text((margin, margin + i * size * spacing), line, font=f, fill=fg)
    return image


def homography(src, dst):
    a = []
    for (x, y), (u, v) in zip(src, dst):
        a.append([x, y, 1, 0, 0, 0, -u * x, -u * y])
        a.append([0, 0, 0, x, y, 1, -v * x, -v * y])
    return np.linalg.solve(np.array(a, float), np.array(dst, float).reshape(8))


def perspective(image, yaw=0.0, pitch=0.0, fill=(250, 250, 250)):
    """Views the page as a plane turned by yaw/pitch degrees in front of a camera."""
    w, h = image.size
    f = 1.4 * max(w, h)
    cy, sy, cp, sp = math.cos(math.radians(yaw)), math.sin(math.radians(yaw)), math.cos(math.radians(pitch)), math.sin(math.radians(pitch))
    corners = []
    for x, y in [(-w / 2, -h / 2), (w / 2, -h / 2), (w / 2, h / 2), (-w / 2, h / 2)]:
        x1, z1 = x * cy, x * sy
        y2, z2 = y * cp - z1 * sp, y * sp + z1 * cp
        k = f / (f + z2)
        corners.append((x1 * k, y2 * k))
    xs, ys = [c[0] for c in corners], [c[1] for c in corners]
    pad = 30
    ow, oh = int(max(xs) - min(xs)) + 2 * pad, int(max(ys) - min(ys)) + 2 * pad
    dst = [(x - min(xs) + pad, y - min(ys) + pad) for x, y in corners]
    coefficients = homography(dst, [(0, 0), (w, 0), (w, h), (0, h)])
    return image.transform((ow, oh), Image.Transform.PERSPECTIVE, tuple(coefficients), Image.Resampling.BICUBIC, fillcolor=fill)


def motion(image, length, angle=0.0):
    a = np.asarray(image).astype(np.float32)
    acc = np.zeros_like(a)
    for i in range(length):
        t = i - (length - 1) / 2
        dx, dy = int(round(t * math.cos(math.radians(angle)))), int(round(t * math.sin(math.radians(angle))))
        acc += np.roll(np.roll(a, dx, axis=1), dy, axis=0)
    return Image.fromarray(np.clip(acc / length, 0, 255).astype(np.uint8))


def noise(image, sigma, seed):
    a = np.asarray(image).astype(np.float32)
    a += np.random.default_rng(seed).normal(0, sigma, a.shape)
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))


def lighting(image, seed, shadow=True):
    a = np.asarray(image).astype(np.float32)
    h, w, _ = a.shape
    rng = np.random.default_rng(seed)
    gx, gy = np.meshgrid(np.linspace(0, 1, w), np.linspace(0, 1, h))
    angle = rng.uniform(0, 2 * math.pi)
    ramp = (gx - .5) * math.cos(angle) + (gy - .5) * math.sin(angle)
    gain = 0.78 + 0.45 * (ramp - ramp.min()) / (ramp.max() - ramp.min())
    if shadow:
        # A soft-edged cast shadow across part of the page.
        edge = gx * math.cos(angle + 1.1) + gy * math.sin(angle + 1.1) - rng.uniform(.2, .6)
        gain *= 1 - 0.42 / (1 + np.exp(-edge * 40))
    return Image.fromarray(np.clip(a * gain[..., None], 0, 255).astype(np.uint8))


def texture(size, seed, base=(214, 196, 160)):
    rng = np.random.default_rng(seed)
    w, h = size
    small = rng.normal(0, 1, (h // 12 + 2, w // 12 + 2))
    low = np.asarray(Image.fromarray(((small - small.min()) / (small.max() - small.min()) * 255).astype(np.uint8)).resize((w, h), Image.Resampling.BICUBIC)).astype(np.float32) / 255
    grain = rng.normal(0, 9, (h, w))
    stripes = 10 * np.sin(np.arange(h)[:, None] / rng.uniform(2.5, 6) + low * 6)
    a = np.array(base, np.float32)[None, None, :] + (low[..., None] - .5) * 55 + grain[..., None] + stripes[..., None]
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))


def on_texture(lines, seed, fname="hei", size=36, fg=(30, 24, 20)):
    page = render(lines, fname, size, fg=(0, 0, 0), bg=(255, 255, 255))
    alpha = Image.eval(page.convert("L"), lambda v: 255 - v)
    tex = texture(page.size, seed)
    tex.paste(Image.new("RGB", page.size, fg), (0, 0), alpha)
    return tex


def save(category, n, image, lines, jpeg=None):
    if only and category not in only:
        return
    name = f"{category}__{n}"
    if jpeg is None:
        image.save(out / f"{name}.png", optimize=True)
    else:
        image.convert("RGB").save(out / f"{name}.jpg", quality=jpeg)
    (out / f"{name}.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")


STYLES = [("hei", 36), ("song", 38), ("heibold", 34), ("kai", 40), ("pingfang", 36), ("hei", 30)]
for n, lines in enumerate(TEXTS):
    fname, size = STYLES[n]
    if n == 5:
        fname = "hei"  # the traditional text needs a face with traditional glyphs
    page = render(lines, fname, size)
    save("clean", n, page, lines)
    save("perspective10", n, perspective(page, yaw=10 if n % 2 else -10, pitch=5), lines)
    save("perspective25", n, perspective(page, yaw=25 if n % 2 else -25, pitch=8 if n % 3 else -8), lines)
    save("rotation5", n, page.rotate(5 if n % 2 else -5, expand=True, fillcolor=(250, 250, 250), resample=Image.Resampling.BICUBIC), lines)
    save("rotation15", n, page.rotate(15 if n % 2 else -15, expand=True, fillcolor=(250, 250, 250), resample=Image.Resampling.BICUBIC), lines)
    save("defocus1.5", n, page.filter(ImageFilter.GaussianBlur(1.5)), lines)
    save("defocus3", n, page.filter(ImageFilter.GaussianBlur(3)), lines)
    save("motion9", n, motion(page, 9, 0 if n % 2 else 30), lines)
    save("motion15", n, motion(page, 15, 0 if n % 2 else 60), lines)
    save("jpeg30", n, page, lines, jpeg=30)
    save("jpeg10", n, page, lines, jpeg=10)
    save("noise15", n, noise(page, 15, n), lines)
    save("noise30", n, noise(page, 30, n), lines)
    save("lighting", n, lighting(page, n), lines)
    low = render(lines, fname, size, fg=(138, 138, 138), bg=(186, 186, 186))
    save("lowcontrast", n, noise(low, 4, n), lines)
    colours = [((200, 30, 30), (255, 255, 255)), ((20, 70, 160), (255, 255, 255)), ((250, 210, 40), (20, 20, 20)),
               ((20, 110, 60), (250, 230, 90)), ((235, 120, 30), (255, 255, 255)), ((120, 40, 130), (250, 240, 200))]
    bg, fg = colours[n]
    save("colourpanel", n, render(lines, fname, size, fg=fg, bg=bg), lines)
    save("texture", n, on_texture(lines, n, fname, size), lines)
    dark = render(lines, fname, size, fg=(235, 235, 235), bg=(24, 24, 28))
    save("lightondark", n, dark, lines)
    # A dark panel on a light wall: the page corners are light but the text is light on dark.
    wall = Image.new("RGB", (dark.width + 240, dark.height + 200), (228, 224, 214))
    wall.paste(dark, (120, 100))
    save("darkpanel", n, wall, lines)
    save("bold", n, render(lines, "heibold", size + 6), lines)
    if n != 5:
        save("serif", n, render(lines, "song", size), lines)
        save("kaiti", n, render(lines, "kai", size + 2), lines)
        save("songbold", n, render(lines, "song", size, bold=True), lines)
    # Small text in a 12 MP frame: the page occupies a corner of a 4000 x 3000 photo.
    frame = Image.new("RGB", (4000, 3000), (236, 232, 224))
    small = render(lines, fname, 30 if n % 2 else 40)
    frame.paste(small, (300 + 200 * n, 400 + 150 * n))
    save("small12mp", n, frame, lines, jpeg=88)
    photo = perspective(lighting(page, n + 20), yaw=12 if n % 2 else -12, pitch=6)
    photo = photo.rotate(4 if n % 2 else -3, expand=True, fillcolor=(205, 205, 205), resample=Image.Resampling.BICUBIC)
    photo = noise(photo.filter(ImageFilter.GaussianBlur(1.0)), 8, n)
    save("photo", n, photo, lines, jpeg=60)

for n, lines in enumerate(SIGNS):
    bg, fg = [((190, 20, 20), (255, 235, 120)), ((255, 255, 255), (200, 20, 20)), ((20, 60, 150), (255, 255, 255)),
              ((30, 30, 30), (255, 200, 40)), ((250, 240, 200), (120, 30, 20)), ((15, 120, 70), (255, 255, 255))][n]
    sign = render(lines, "heibold" if n % 2 else "kai", 110, fg=fg, bg=bg, spacing=1.35, margin=70, bold=True)
    wall = Image.new("RGB", (sign.width + 300, sign.height + 260), (150, 150, 146))
    wall.paste(sign, (150, 130))
    wall = lighting(wall, n + 40, shadow=n % 2 == 0)
    save("sign", n, noise(perspective(wall, yaw=14 if n % 2 else -14, pitch=6, fill=(150, 150, 146)), 6, n), lines, jpeg=75)
print("evaluation set written to", out)
