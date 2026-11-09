"""Generates the training scenes for PaperForge PageNet. Imports no ML framework.

Procedural sheets are composited over COCO val2017 photos (backgrounds only, never redistributed) or
procedural surfaces. The generator concentrates on what generic detectors get wrong: stacked sheets,
a larger document under the page, a neighbouring page across a gutter, and fingers over the edge.
"""

from __future__ import annotations

import argparse
import json
import math
import multiprocessing
import random
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

INPUT = 320
RENDER = 512


# ---------------------------------------------------------------- sheets


def scribble(sheet, rng, x, y, height, color, thick):
    """One handwritten-looking word: a jittered baseline with loops."""
    letters = rng.randint(2, 9)
    px = x
    for _ in range(letters):
        width = rng.uniform(0.35, 0.8) * height
        count = rng.randint(3, 6)
        pts = [(px + width * i / (count - 1) + rng.gauss(0, width * 0.15), y - rng.uniform(0, height) * (i % 2) + rng.gauss(0, height * 0.08)) for i in range(count)]
        cv2.polylines(sheet, [np.array(pts, np.int32)], False, color, thick, cv2.LINE_AA)
        px += width * 1.05
    return px


FONT = Path(__file__).resolve().parents[1] / "app/src/main/assets/fonts/PaperForgeCJK-Regular.ttf"
LATIN = "the of and to in a is that for it as was with be by on not he this are or his from at which but have an had they you were their one all we can her has there been if more when will would who so no"
_fonts: dict = {}


def _font(size: int):
    if size not in _fonts:
        _fonts[size] = ImageFont.truetype(str(FONT), size)
    return _fonts[size]


def _words(rng, count: int) -> str:
    if rng.random() < 0.5:
        return "".join(chr(rng.randint(0x4E00, 0x7000)) for _ in range(count))
    return " ".join(rng.choice(LATIN.split()) for _ in range(count))


def typeset(sheet, rng, ink, backgrounds):
    """Real type in a page layout: columns, headings, and photos like a magazine or a book."""
    h, w = sheet.shape[:2]
    image = Image.fromarray(sheet)
    draw = ImageDraw.Draw(image)
    columns = rng.choice([1, 1, 1, 2, 2, 3])
    margin = rng.randint(10, 40)
    gap = rng.randint(8, 22)
    width = (w - 2 * margin - gap * (columns - 1)) // columns
    size = rng.randint(9, 16)
    font = _font(size)
    y = margin
    if rng.random() < 0.6:
        heading = _font(rng.randint(20, 44))
        colour = ink if rng.random() < 0.6 else (rng.randint(0, 220), rng.randint(0, 200), rng.randint(0, 220))
        draw.text((margin, y), _words(rng, rng.randint(2, 6)), font=heading, fill=colour)
        y += int(heading.size * 1.5)
    if rng.random() < 0.25:  # a coloured masthead band, common on flyers and magazines
        band = rng.randint(14, 70)
        draw.rectangle((0, 0, w, band), fill=(rng.randint(0, 255), rng.randint(0, 255), rng.randint(0, 255)))
        y += band
    for column in range(columns):
        x = margin + column * (width + gap)
        cursor = y
        while cursor < h - margin - size:
            if backgrounds and rng.random() < 0.05:
                bh = rng.randint(h // 10, h // 3)
                photo = cv2.resize(rng.choice(backgrounds), (width, bh))
                image.paste(Image.fromarray(photo[:, :, ::-1]), (x, cursor))
                cursor += bh + 6
                continue
            chars = max(2, int(width / size * rng.uniform(0.75, 1.0)))
            draw.text((x, cursor), _words(rng, chars if rng.random() < 0.5 else chars // 4), font=font, fill=ink)
            cursor += int(size * rng.uniform(1.25, 1.7))
            if rng.random() < 0.08:
                cursor += size
    return np.array(image)


def crumple(sheet, rng):
    """Fold and crease shading across a sheet; real folded paper is full of soft brightness steps."""
    h, w = sheet.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    gain = np.ones((h, w), np.float32)
    for _ in range(rng.randint(1, 4)):
        angle = rng.uniform(0, math.pi)
        distance = (xx - rng.uniform(0, w)) * math.cos(angle) + (yy - rng.uniform(0, h)) * math.sin(angle)
        gain *= 1 + rng.uniform(-0.2, 0.2) * np.tanh(distance / rng.uniform(1, 14))
    return np.clip(sheet.astype(np.float32) * gain[..., None], 0, 255).astype(np.uint8)


REAL_SHEETS: list = []


def make_sheet(rng, backgrounds):
    # The project's own notebook and book pages, so the network sees real handwriting and print.
    if REAL_SHEETS and rng.random() < 0.3:
        sheet = rng.choice(REAL_SHEETS)
        h, w = sheet.shape[:2]
        keep = rng.uniform(0.7, 1.0)
        ch, cw = int(h * keep), int(w * keep)
        y, x = rng.randint(0, h - ch), rng.randint(0, w - cw)
        target = rng.randint(460, 720)
        sheet = cv2.resize(sheet[y:y + ch, x:x + cw], (int(target * cw / ch), target), interpolation=cv2.INTER_AREA)
        if rng.random() < 0.5:
            sheet = sheet[:, ::-1]
        gain = np.float32([rng.uniform(0.86, 1.08) for _ in range(3)])
        return np.clip(sheet.astype(np.float32) * gain, 0, 255).astype(np.uint8)
    aspect = rng.choice([1.414, 1.414, 1.3, 1.5, 1.2, 1.7, 2.2, 0.75])
    h = rng.randint(440, 700)
    w = int(h / aspect)
    tone = rng.choice(["white", "white", "cream", "cream", "grey", "dark"])
    base = {"white": (246, 247, 247), "cream": (205, 228, 238), "grey": (205, 208, 210), "dark": (42, 38, 36)}[tone]
    base = tuple(int(c + rng.randint(-10, 8)) for c in base)
    sheet = np.full((h, w, 3), base, np.uint8)
    ink = (225, 225, 225) if tone == "dark" else (rng.randint(10, 80), rng.randint(10, 60), rng.randint(10, 60))
    kind = rng.choice(["lined", "lined", "lined", "print", "print", "mixed", "blank", "colour"])
    if kind == "lined":
        step = rng.randint(22, 34)
        rule = (rng.randint(140, 190), rng.randint(130, 170), rng.randint(120, 160))
        for y in range(rng.randint(30, 60), h - 8, step):
            cv2.line(sheet, (rng.randint(0, 3), y), (w - rng.randint(0, 3), y + rng.randint(-1, 1)), rule, 1, cv2.LINE_AA)
        for y in range(rng.randint(40, 80), h - 10, step):
            x = rng.randint(8, 50)
            while x < w - 30 and rng.random() > 0.08:
                x = scribble(sheet, rng, x, y - 3, rng.uniform(10, 20), ink, rng.choice([1, 1, 2, 2, 3])) + rng.randint(8, 40)
    elif kind in ("print", "mixed") and rng.random() < 0.85:
        sheet = typeset(sheet, rng, ink, backgrounds)
    elif kind in ("print", "mixed"):
        size = rng.uniform(0.3, 0.55)
        for y in range(rng.randint(24, 50), h - 16, int(34 * size) + rng.randint(4, 9)):
            text = "".join(rng.choice("abcdefghijklmnopqrstuvwxyz 0123456789 ()=;") for _ in range(int(w / (15 * size))))
            cv2.putText(sheet, text, (rng.randint(10, 26), y), cv2.FONT_HERSHEY_SIMPLEX, size, ink, 1, cv2.LINE_AA)
    if kind in ("mixed", "colour") and backgrounds:
        for _ in range(rng.randint(1, 3)):
            bw, bh = rng.randint(w // 4, int(w * 0.8)), rng.randint(h // 8, h // 3)
            x0, y0 = rng.randint(0, w - bw), rng.randint(0, h - bh)
            sheet[y0:y0 + bh, x0:x0 + bw] = cv2.resize(rng.choice(backgrounds), (bw, bh))
    if rng.random() < 0.45:
        sheet = crumple(sheet, rng)
    # Faint show-through and paper grain.
    noise = cv2.GaussianBlur(np.random.default_rng(rng.getrandbits(32)).normal(0, 1, (h // 4 + 1, w // 4 + 1)).astype(np.float32), (0, 0), 2)
    grain = cv2.resize(noise, (w, h))[..., None] * rng.uniform(1, 6)
    return np.clip(sheet.astype(np.float32) + grain, 0, 255).astype(np.uint8)


def curl(sheet, rng):
    """Bend a sheet in-plane so its edges stop being straight lines; returns image and alpha."""
    h, w = sheet.shape[:2]
    pad = int(0.08 * max(h, w))
    canvas = cv2.copyMakeBorder(sheet, pad, pad, pad, pad, cv2.BORDER_CONSTANT, value=(0, 0, 0))
    alpha = cv2.copyMakeBorder(np.full((h, w), 255, np.uint8), pad, pad, pad, pad, cv2.BORDER_CONSTANT, value=0)
    ch, cw = canvas.shape[:2]
    ys, xs = np.mgrid[0:ch, 0:cw].astype(np.float32)
    strength = rng.choice([0, 0, 0.5, 1, 1.5]) * rng.uniform(2, 0.035 * h)
    px, py = rng.uniform(0, 6.28), rng.uniform(0, 6.28)
    fx, fy = rng.uniform(0.6, 2.2), rng.uniform(0.6, 2.2)
    map_x = xs + strength * np.sin(ys / ch * fy * 6.28 + py).astype(np.float32)
    map_y = ys + strength * np.sin(xs / cw * fx * 6.28 + px).astype(np.float32)
    return cv2.remap(canvas, map_x, map_y, cv2.INTER_LINEAR), cv2.remap(alpha, map_x, map_y, cv2.INTER_LINEAR), pad


def place(rng, sheet_shape, area):
    """A destination quad for a sheet covering roughly `area` of the frame, with perspective."""
    h, w = sheet_shape[:2]
    scale = math.sqrt(area * RENDER * RENDER / (w * h))
    hw, hh = w * scale / 2, h * scale / 2
    angle = math.radians(rng.choice([rng.gauss(0, 7), rng.gauss(0, 7), rng.uniform(-35, 35), rng.uniform(-180, 180)]) if rng.random() < 0.9 else rng.choice([90, -90, 180]))
    cx = RENDER / 2 + rng.gauss(0, 0.09) * RENDER
    cy = RENDER / 2 + rng.gauss(0, 0.09) * RENDER
    corners = []
    tilt = rng.uniform(0, 0.13)
    for sx, sy in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
        jx = sx * hw * (1 + rng.uniform(-tilt, tilt))
        jy = sy * hh * (1 + rng.uniform(-tilt, tilt))
        corners.append((cx + jx * math.cos(angle) - jy * math.sin(angle), cy + jx * math.sin(angle) + jy * math.cos(angle)))
    return np.float32(corners)


def paste(scene, sheet, alpha, pad, quad, rng, shadow=True):
    h, w = sheet.shape[:2]
    src = np.float32([[pad, pad], [w - pad, pad], [w - pad, h - pad], [pad, h - pad]])
    matrix = cv2.getPerspectiveTransform(src, quad)
    image = cv2.warpPerspective(sheet, matrix, (RENDER, RENDER), flags=cv2.INTER_AREA)
    a = cv2.warpPerspective(alpha, matrix, (RENDER, RENDER), flags=cv2.INTER_LINEAR).astype(np.float32) / 255
    if shadow:
        dx, dy = rng.randint(-5, 5), rng.randint(-3, 7)
        cast = cv2.GaussianBlur(cv2.warpAffine(a, np.float32([[1, 0, dx], [0, 1, dy]]), (RENDER, RENDER)), (0, 0), rng.uniform(1.5, 7))
        scene *= (1 - cast[..., None] * rng.uniform(0.2, 0.6))
    scene[:] = scene * (1 - a[..., None]) + image.astype(np.float32) * a[..., None]
    return a, matrix


def background(rng, photos):
    if photos and rng.random() < 0.8:
        p = rng.choice(photos)
        h, w = p.shape[:2]
        size = rng.randint(int(min(h, w) * 0.5), min(h, w))
        y, x = rng.randint(0, h - size), rng.randint(0, w - size)
        return cv2.resize(p[y:y + size, x:x + size], (RENDER, RENDER)).astype(np.float32)
    colour = np.float32([rng.randint(20, 235) for _ in range(3)])
    gen = np.random.default_rng(rng.getrandbits(32))
    long_grain = rng.random() < 0.5
    tex = gen.normal(0, 1, (RENDER // 4, 8) if long_grain else (RENDER // 8, RENDER // 8)).astype(np.float32)
    tex = cv2.resize(cv2.GaussianBlur(tex, (0, 0), 1), (RENDER, RENDER), interpolation=cv2.INTER_CUBIC)
    return np.clip(colour + tex[..., None] * rng.uniform(4, 28), 0, 255)


def distractors(scene, rng):
    for _ in range(rng.choice([0, 0, 1, 2, 3])):
        x, y = rng.randint(-60, RENDER), rng.randint(-60, RENDER)
        w, h = rng.randint(40, 320), rng.randint(30, 320)
        colour = tuple(float(rng.randint(5, 240)) for _ in range(3))
        cv2.rectangle(scene, (x, y), (x + w, y + h), colour, -1, cv2.LINE_AA)
        if rng.random() < 0.4:  # keyboard-like grid of keys
            for gy in range(y, y + h, 26):
                for gx in range(x, x + w, 26):
                    cv2.rectangle(scene, (gx + 3, gy + 3), (gx + 22, gy + 22), tuple(c * 1.6 + 25 for c in colour), -1)


def fingers(scene, rng, quad):
    if rng.random() > 0.22:
        return
    skin = np.float32([rng.randint(110, 175), rng.randint(140, 190), rng.randint(190, 240)])
    i = rng.randint(0, 3)
    a, b = quad[i], quad[(i + 1) % 4]
    start = rng.uniform(0.15, 0.75)
    normal = np.float32([-(b - a)[1], (b - a)[0]])
    normal /= max(1.0, float(np.linalg.norm(normal)))
    overlay = scene.copy()
    for k in range(rng.randint(1, 4)):
        p = a + (b - a) * (start + 0.09 * k)
        centre = p + normal * rng.uniform(-14, 26)
        tip = centre + normal * rng.uniform(-60, -20)
        cv2.line(overlay, tuple(int(v) for v in centre), tuple(int(v) for v in tip), skin.tolist(), rng.randint(16, 30), cv2.LINE_AA)
        cv2.circle(overlay, tuple(int(v) for v in centre), rng.randint(9, 15), (skin * 1.05).clip(0, 255).tolist(), -1, cv2.LINE_AA)
    scene[:] = overlay


def lighting(scene, rng):
    gen = np.random.default_rng(rng.getrandbits(32))
    yy, xx = np.mgrid[0:RENDER, 0:RENDER].astype(np.float32) / RENDER
    gain = 1 + rng.uniform(-0.35, 0.35) * (xx - 0.5) + rng.uniform(-0.35, 0.35) * (yy - 0.5)
    if rng.random() < 0.65:  # hard-edged cast shadow, like a hand or a phone
        points = gen.uniform(-0.2, 1.2, (rng.randint(3, 6), 2)) * RENDER
        hull = cv2.convexHull(points.astype(np.int32))
        mask = np.zeros((RENDER, RENDER), np.float32)
        cv2.fillConvexPoly(mask, hull, 1.0)
        gain *= 1 - cv2.GaussianBlur(mask, (0, 0), rng.uniform(1.5, 22)) * rng.uniform(0.15, 0.6)
    tint = np.float32([rng.uniform(0.86, 1.12), rng.uniform(0.94, 1.05), rng.uniform(0.86, 1.14)])
    scene *= (gain * rng.uniform(0.6, 1.2))[..., None] * tint


def camera(scene, rng):
    image = np.clip(scene, 0, 255).astype(np.uint8)
    if rng.random() < 0.5:
        image = cv2.GaussianBlur(image, (0, 0), rng.uniform(0.4, 1.6))
    if rng.random() < 0.5:
        noise = np.random.default_rng(rng.getrandbits(32)).normal(0, rng.uniform(1, 8), image.shape).astype(np.float32)
        image = np.clip(image.astype(np.float32) + noise, 0, 255).astype(np.uint8)
    if rng.random() < 0.6:
        ok, data = cv2.imencode(".jpg", image, [cv2.IMWRITE_JPEG_QUALITY, rng.randint(35, 92)])
        image = cv2.imdecode(data, cv2.IMREAD_COLOR)
    return image


def scene_sample(rng, photos, pool):
    scene = background(rng, photos)
    distractors(scene, rng)
    top = rng.choice(pool)
    area = rng.choice([rng.uniform(0.05, 0.3), rng.uniform(0.05, 0.3), rng.uniform(0.15, 0.92), rng.uniform(0.45, 0.92)])
    quad = place(rng, top.shape, area)
    case = rng.choice(["plain", "stack", "stack", "under", "under", "gutter"])
    sheet, alpha, pad = curl(top, rng)
    if case == "stack":
        # A pile: lower sheets poke out by small offsets along one or two sides.
        direction = np.float32([rng.choice([-1, -1, 1, 0]), rng.choice([0, 0, 1, -1])])
        for k in range(rng.randint(1, 5), 0, -1):
            under, under_alpha, under_pad = curl(rng.choice(pool), rng)
            offset = direction * k * rng.uniform(1.5, 9) + np.float32([rng.gauss(0, 1.2), rng.gauss(0, 1.2)])
            paste(scene, cv2.resize(under, (sheet.shape[1], sheet.shape[0])), cv2.resize(under_alpha, (sheet.shape[1], sheet.shape[0])), pad, quad + offset, rng, shadow=False)
    elif case == "under":
        # A larger document under the page, showing past one or more edges.
        under, under_alpha, _ = curl(rng.choice(pool), rng)
        centre = quad.mean(0)
        grown = centre + (quad - centre) * rng.uniform(1.15, 1.8) + np.float32([rng.gauss(0, 40), rng.gauss(0, 40)])
        paste(scene, cv2.resize(under, (sheet.shape[1], sheet.shape[0])), cv2.resize(under_alpha, (sheet.shape[1], sheet.shape[0])), pad, grown, rng)
    elif case == "gutter":
        # The facing page of an open book, across a dark gutter.
        other, other_alpha, _ = curl(rng.choice(pool), rng)
        side = quad[1] - quad[0]
        paste(scene, cv2.resize(other, (sheet.shape[1], sheet.shape[0])), cv2.resize(other_alpha, (sheet.shape[1], sheet.shape[0])), pad, quad + side * rng.choice([-1.0, 1.0]) * rng.uniform(0.98, 1.03), rng, shadow=False)
    a, _ = paste(scene, sheet, alpha, pad, quad, rng)
    fingers(scene, rng, quad)
    lighting(scene, rng)
    image = camera(scene, rng)
    mask = (a > 0.5).astype(np.uint8)
    return image, mask


def _chunk(job) -> int:
    start, count, seed, coco, total, out, real = job
    cv2.setNumThreads(0)
    rng = random.Random(seed)
    if real and not REAL_SHEETS:
        REAL_SHEETS.extend(cv2.imread(str(path)) for path in sorted(Path(real).glob("*.png")))
    photos = []
    if coco:
        files = sorted(Path(coco).glob("*.jpg"))
        for path in rng.sample(files, min(70, len(files))):
            image = cv2.imread(str(path))
            if image is not None and min(image.shape[:2]) >= 300:
                photos.append(image)
    pool = [make_sheet(rng, photos) for _ in range(60)]
    images = np.memmap(Path(out) / "images.bin", dtype=np.uint8, mode="r+", shape=(total, INPUT, INPUT, 3))
    masks = np.memmap(Path(out) / "masks.bin", dtype=np.uint8, mode="r+", shape=(total, INPUT // 2, INPUT // 2))
    for i in range(start, start + count):
        image, mask = scene_sample(rng, photos, pool)
        images[i] = cv2.cvtColor(cv2.resize(image, (INPUT, INPUT), interpolation=cv2.INTER_AREA), cv2.COLOR_BGR2RGB)
        masks[i] = cv2.resize(mask, (INPUT // 2, INPUT // 2), interpolation=cv2.INTER_NEAREST)
        if i % 12 == 0:
            pool[rng.randrange(len(pool))] = make_sheet(rng, photos)
    images.flush()
    masks.flush()
    return count


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--coco", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--count", type=int, default=60000)
    parser.add_argument("--processes", type=int, default=24)
    parser.add_argument("--chunk", type=int, default=1500)
    parser.add_argument("--seed", type=int, default=1000)
    parser.add_argument("--real", type=Path, help="directory of the project's own page images, flat and cropped")
    parser.add_argument("--preview", action="store_true")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    if args.preview:
        rng = random.Random(3)
        files = sorted(args.coco.glob("*.jpg"))[:60] if args.coco else []
        photos = [cv2.imread(str(p)) for p in files]
        pool = [make_sheet(rng, photos) for _ in range(40)]
        tiles = []
        for _ in range(24):
            image, mask = scene_sample(rng, photos, pool)
            contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            cv2.drawContours(image, contours, -1, (0, 230, 0), 1, cv2.LINE_AA)
            tiles.append(cv2.resize(image, (256, 256)))
        cv2.imwrite(str(args.out / "scenes.jpg"), np.vstack([np.hstack(tiles[i:i + 6]) for i in range(0, 24, 6)]))
        return
    for name, shape in (("images.bin", (args.count, INPUT, INPUT, 3)), ("masks.bin", (args.count, INPUT // 2, INPUT // 2))):
        np.memmap(args.out / name, dtype=np.uint8, mode="w+", shape=shape).flush()
    jobs = [(s, min(args.chunk, args.count - s), args.seed + i, str(args.coco) if args.coco else None, args.count, str(args.out), str(args.real) if args.real else None) for i, s in enumerate(range(0, args.count, args.chunk))]
    done = 0
    with multiprocessing.Pool(args.processes) as workers:
        for count in workers.imap_unordered(_chunk, jobs):
            done += count
            print(f"{done}/{args.count}", flush=True)
    (args.out / "scenes.json").write_text(json.dumps({"count": args.count, "input": INPUT}), encoding="utf-8")
    print("GENERATED", flush=True)


if __name__ == "__main__":
    main()
