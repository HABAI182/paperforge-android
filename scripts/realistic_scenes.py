"""More realistic top-sheet scenes for PageNet-style training: the cases real photos get wrong.

Adds, on top of page_scenes' geometry, lighting and camera model:
- white-on-white stacks whose top edge shows only as a faint shadow, a thin highlight, or not at all;
- overlapping notebooks with spiral bindings and punched holes;
- a book cover lying on notes, with the page block's edge visible beside the cover;
- folds and creases across the top sheet (straight internal edges that are not the sheet's edge);
- low-contrast photos (haze, compressed range);
- small sheets on busy patterned surfaces (fabric, clutter), with paper cut off at the frame edge as a non-target;
- neighbouring pages beside the top sheet (an open booklet's facing page, the next sheet on the desk) that run
  out of the frame, joined to it by a gutter or a slight overlap;
- a sheet lying on a larger flat object (cardboard box, concrete slab, laptop, board, book, bigger sheet);
- a book cover over a thick page block (book-block): layered page edges, 3-18 px at render scale, toward one
  corner, in the cover's shadow; the label is the cover alone, as the owner's cover photos are annotated;
- a cluttered pale desk (mouse, mug, fruit, magazine) with cables, pens and highlighters lying across the page,
  sometimes on a few other sheets. Crossing objects split the page in the image, never in the label: the label
  is the whole page, as real annotations are.
The label is always the top sheet's own mask. Page textures are Codex's audited public-training textures
(Mendeley x3nm4cxr83, CC BY 4.0; held-out groups and owner photos excluded) and procedural sheets;
backgrounds are COCO photographs, never redistributed.

usage: realistic_scenes.py --textures <public-textures dir> --coco <val2017> --out <dir> --count 30000
"""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import math
import multiprocessing
import random
import time
from pathlib import Path

import cv2
import numpy as np

import page_scenes as scenes

R = scenes.RENDER


def fold(sheet, rng):
    """A crease across the sheet: a light/shade step and a thin line, as a folded page lies."""
    h, w = sheet.shape[:2]
    out = sheet.astype(np.float32)
    for _ in range(rng.choice([1, 1, 2])):
        if rng.random() < 0.7:  # folds mostly run across the middle, parallel to a side
            if rng.random() < 0.5:
                x = w * rng.uniform(0.3, 0.7); p0, p1 = (x + rng.gauss(0, 6), 0), (x + rng.gauss(0, 6), h)
            else:
                y = h * rng.uniform(0.3, 0.7); p0, p1 = (0, y + rng.gauss(0, 6)), (w, y + rng.gauss(0, 6))
        else:
            p0 = (rng.uniform(0, w), 0); p1 = (rng.uniform(0, w), h)
        yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
        side = ((p1[0] - p0[0]) * (yy - p0[1]) - (p1[1] - p0[1]) * (xx - p0[0])) > 0
        gain = rng.uniform(0.9, 1.06)
        out[side] *= gain
        line = np.zeros((h, w), np.float32)
        cv2.line(line, tuple(int(v) for v in p0), tuple(int(v) for v in p1), 1.0, rng.randint(1, 2), cv2.LINE_AA)
        line = cv2.GaussianBlur(line, (0, 0), rng.uniform(0.6, 1.8))
        out += line[..., None] * rng.choice([-1, 1]) * rng.uniform(8, 30)
    return np.clip(out, 0, 255).astype(np.uint8)


def white_paper(shape, rng, base=None):
    tone = base if base is not None else rng.randint(222, 252)
    sheet = np.full(shape, (tone - rng.randint(0, 6), tone - rng.randint(0, 4), tone), np.float32)
    sheet += np.random.default_rng(rng.getrandbits(32)).normal(0, 1.5, shape)
    if rng.random() < 0.6:  # ruled or printed lower sheets show through as faint texture at a distance
        step = rng.randint(22, 34)
        for y in range(rng.randint(20, 50), shape[0] - 8, step):
            cv2.line(sheet, (0, y), (shape[1], y), (tone - 40, tone - 30, tone - 20), 1, cv2.LINE_AA)
    return np.clip(sheet, 0, 255).astype(np.uint8)


def spiral(scene, quad, side, rng):
    """A spiral binding along one side of a placed sheet: coils just outside, holes just inside."""
    a, b = quad[side], quad[(side + 1) % 4]
    d = (b - a) / max(1.0, float(np.linalg.norm(b - a)))
    n = np.float32([d[1], -d[0]])  # outward for place()'s clockwise order
    count = rng.randint(10, 22)
    colour = rng.choice([(70, 70, 75), (150, 150, 155), (30, 30, 30), (190, 190, 195)])
    for k in range(count):
        p = a + (b - a) * ((k + 0.5) / count)
        hole = p - n * rng.uniform(5, 9)
        cv2.circle(scene, tuple(int(v) for v in hole), rng.randint(2, 4), (40, 40, 45), -1, cv2.LINE_AA)
        tip = p + n * rng.uniform(6, 12)
        cv2.ellipse(scene, tuple(int(v) for v in (hole + tip) / 2), (int(np.linalg.norm(tip - hole) / 2) + 2, 3),
                    math.degrees(math.atan2(n[1], n[0])), 0, 360, colour, 2, cv2.LINE_AA)


def subtle_edge(scene, a, rng):
    """The top sheet's edge on paper like it: a faint soft shadow, a thin highlight, or nothing."""
    kind = rng.choice(["shadow", "shadow", "highlight", "none"])
    if kind == "none":
        return
    ring = cv2.morphologyEx(a, cv2.MORPH_GRADIENT, np.ones((3, 3), np.uint8))
    if kind == "shadow":
        dx, dy = rng.randint(-3, 3), rng.randint(-1, 4)
        cast = cv2.GaussianBlur(cv2.warpAffine(a, np.float32([[1, 0, dx], [0, 1, dy]]), (R, R)), (0, 0), rng.uniform(0.7, 2.5))
        scene *= (1 - (cast * (1 - a))[..., None] * rng.uniform(0.03, 0.15))
    else:
        scene += cv2.GaussianBlur(ring, (0, 0), 0.7)[..., None] * rng.uniform(3, 12)


def bend(sheet, rng):
    """page_scenes' curl only sometimes: real loose sheets are mostly flat or gently bent, not wavy."""
    if rng.random() < 0.6:
        h, w = sheet.shape[:2]
        pad = int(0.08 * max(h, w))
        canvas = cv2.copyMakeBorder(sheet, pad, pad, pad, pad, cv2.BORDER_CONSTANT, value=(0, 0, 0))
        alpha = cv2.copyMakeBorder(np.full((h, w), 255, np.uint8), pad, pad, pad, pad, cv2.BORDER_CONSTANT, value=0)
        return canvas, alpha, pad
    return scenes.curl(sheet, rng)


def busy(rng, photos):
    """A strongly patterned surface: printed fabric, a tiled photo crop, or stripes."""
    kind = rng.choice(["tile", "stripes", "blobs"])
    gen = np.random.default_rng(rng.getrandbits(32))
    if kind == "tile" and photos:
        p = rng.choice(photos)
        size = rng.randint(40, 140)
        y, x = rng.randint(0, p.shape[0] - size), rng.randint(0, p.shape[1] - size)
        tile = p[y:y + size, x:x + size]
        reps = R // size + 2
        out = np.tile(tile, (reps, reps, 1))[:R, :R].astype(np.float32)
    elif kind == "stripes":
        out = np.zeros((R, R, 3), np.float32)
        colours = [np.float32([rng.randint(0, 255) for _ in range(3)]) for _ in range(rng.randint(2, 4))]
        yy, xx = np.mgrid[0:R, 0:R].astype(np.float32)
        ang = rng.uniform(0, math.pi)
        t = (xx * math.cos(ang) + yy * math.sin(ang)) / rng.uniform(6, 30)
        idx = (np.floor(t).astype(int)) % len(colours)
        for k, c in enumerate(colours):
            out[idx == k] = c
    else:
        out = np.zeros((R, R, 3), np.float32) + np.float32([rng.randint(0, 255) for _ in range(3)])
        for _ in range(rng.randint(40, 160)):
            c = tuple(float(v) for v in gen.integers(0, 256, 3))
            cv2.ellipse(out, (rng.randint(0, R), rng.randint(0, R)), (rng.randint(3, 18), rng.randint(3, 18)),
                        rng.uniform(0, 180), 0, 360, c, -1, cv2.LINE_AA)
    if rng.random() < 0.6:  # folds in the cloth
        warp = cv2.GaussianBlur(gen.normal(0, 1, (R // 16, R // 16)).astype(np.float32), (0, 0), 1)
        warp = cv2.resize(warp, (R, R), interpolation=cv2.INTER_CUBIC)
        out *= (1 + warp[..., None] * rng.uniform(0.05, 0.25))
    out = cv2.warpPerspective(out, cv2.getPerspectiveTransform(
        np.float32([[0, 0], [R, 0], [R, R], [0, R]]),
        np.float32([[rng.uniform(-60, 30), rng.uniform(-60, 30)], [R + rng.uniform(-30, 60), rng.uniform(-60, 30)],
                    [R + rng.uniform(-30, 60), R + rng.uniform(-30, 60)], [rng.uniform(-60, 30), R + rng.uniform(-30, 60)]])),
        (R, R), borderMode=cv2.BORDER_REFLECT)
    return np.clip(out, 0, 255)


def edge_paper(scene, rng, pool):
    """A sheet or newspaper mostly outside the frame: paper that is not the document."""
    other = rng.choice(pool)
    o, oa, opad = bend(other, rng)
    q = scenes.place(rng, other.shape, rng.uniform(0.1, 0.4))
    side = rng.randrange(4)
    shift = [np.float32([0, -1]), np.float32([1, 0]), np.float32([0, 1]), np.float32([-1, 0])][side]
    centre = q.mean(0)
    target = np.float32([R / 2, R / 2]) + shift * R * rng.uniform(0.5, 0.75)
    scenes.paste(scene, o, oa, opad, q - centre + target, rng, shadow=rng.random() < 0.5)


def support(rng, pool, h, w):
    """A larger flat rectangular object a sheet can lie on: not the document, though it is the bigger rectangle."""
    kind = rng.choice(["cardboard", "concrete", "laptop", "board", "book", "sheet"])
    gen = np.random.default_rng(rng.getrandbits(32))
    if kind == "book":
        return scenes.cover(rng, h, w)
    if kind == "sheet":
        return cv2.resize(rng.choice(pool), (w, h))
    base = {"cardboard": (rng.randint(60, 110), rng.randint(120, 160), rng.randint(165, 205)),
            "concrete": (rng.randint(110, 175),) * 3,
            "laptop": (rng.choice([rng.randint(30, 70), rng.randint(165, 210)]),) * 3,
            "board": (rng.randint(40, 120), rng.randint(80, 150), rng.randint(120, 200))}[kind]
    out = np.full((h, w, 3), base, np.float32)
    grain = gen.normal(0, 1, (h, w)).astype(np.float32)
    if kind == "concrete":
        out += cv2.GaussianBlur(grain, (0, 0), 1.2)[..., None] * rng.uniform(8, 20)
        for _ in range(rng.randint(0, 4)):
            cv2.circle(out, (rng.randint(0, w), rng.randint(0, h)), rng.randint(4, 10), (40.0, 40.0, 40.0), -1, cv2.LINE_AA)
    elif kind == "board":
        out += cv2.resize(gen.normal(0, 1, (h, 6)).astype(np.float32), (w, h))[..., None] * rng.uniform(10, 30)
    elif kind == "cardboard":
        out += cv2.GaussianBlur(grain, (0, 0), 2)[..., None] * 6
        if rng.random() < 0.6:  # the box's flap seam and tape
            y = rng.randint(h // 3, 2 * h // 3)
            cv2.line(out, (0, y), (w, y), tuple(float(v) * 0.6 for v in base), rng.randint(2, 5), cv2.LINE_AA)
            if rng.random() < 0.5:
                cv2.rectangle(out, (0, y - rng.randint(15, 30)), (w, y + rng.randint(15, 30)), (float(rng.randint(150, 210)),) * 3, -1)
        if rng.random() < 0.6:  # OpenCV draws text on 8-bit images only
            out = np.clip(out, 0, 255).astype(np.uint8)
            cv2.putText(out, rng.choice(["25 X 15", "FRAGILE", "THIS SIDE UP", "21H"]), (rng.randint(10, w // 2), rng.randint(40, h - 10)),
                        cv2.FONT_HERSHEY_SIMPLEX, rng.uniform(1, 2.5), (40.0, 40.0, 40.0), rng.randint(2, 5), cv2.LINE_AA)
            out = out.astype(np.float32)
    else:  # laptop: keyboard grid or a plain lid
        out += cv2.GaussianBlur(grain, (0, 0), 1)[..., None] * 3
        if rng.random() < 0.5:
            key = rng.randint(18, 34)
            for y in range(h // 2, h - key, key + 4):
                for x in range(8, w - key, key + 4):
                    cv2.rectangle(out, (x, y), (x + key, y + key), tuple(float(v) * 0.7 for v in out[0, 0]), -1)
    return np.clip(out, 0, 255).astype(np.uint8)


def crossers(scene, rng, quad, count=None):
    """Long thin objects lying across the sheet: cables (smooth dark curves with a soft shadow) and pens or
    highlighters. They break the page into pieces in the image; the label stays the whole page."""
    centre = quad.mean(0)
    size = float(np.linalg.norm(quad[2] - quad[0]))
    for _ in range(count if count is not None else rng.choice([1, 1, 2, 3])):
        if rng.random() < 0.55:  # cable: a smooth curve through the page, often running off the frame
            ang = rng.uniform(0, math.pi)
            d = np.float32([math.cos(ang), math.sin(ang)])
            n = np.float32([-d[1], d[0]])
            through = centre + n * rng.uniform(-0.35, 0.35) * size
            bend = rng.uniform(-0.25, 0.25) * size
            pts = np.int32([through + d * t * size * rng.uniform(0.95, 1.05) + n * bend * (1 - t * t)
                            for t in np.linspace(-1.2, 1.2, 40)])
            width = rng.randint(3, 9)
            colour = rng.choice([(25, 25, 28), (40, 40, 45), (210, 210, 215), (60, 60, 70)])
            shade = np.zeros(scene.shape[:2], np.float32)
            cv2.polylines(shade, [pts + np.int32([rng.randint(1, 4), rng.randint(2, 6)])], False, 1.0, width + 2, cv2.LINE_AA)
            scene *= 1 - cv2.GaussianBlur(shade, (0, 0), 2.5)[..., None] * rng.uniform(0.15, 0.35)
            cv2.polylines(scene, [pts], False, colour, width, cv2.LINE_AA)
        else:  # pen, marker or highlighter lying across the page
            ang = rng.uniform(0, math.pi)
            d = np.float32([math.cos(ang), math.sin(ang)])
            start = centre + np.float32([rng.uniform(-0.3, 0.3), rng.uniform(-0.3, 0.3)]) * size - d * rng.uniform(0.1, 0.3) * size
            end = start + d * rng.uniform(0.2, 0.45) * size
            width = rng.randint(6, 16)
            body = rng.choice([(40, 40, 45), (230, 232, 235), (60, 220, 230), (60, 200, 120), (40, 120, 230),
                               tuple(float(rng.randint(30, 230)) for _ in range(3))])
            p0, p1 = tuple(int(v) for v in start), tuple(int(v) for v in end)
            cv2.line(scene, tuple(int(v) for v in start + np.float32([2, 4])), tuple(int(v) for v in end + np.float32([2, 4])),
                     (90, 90, 90), width + 2, cv2.LINE_AA)
            cv2.line(scene, p0, p1, body, width, cv2.LINE_AA)
            cv2.line(scene, p0, p1, tuple(min(255.0, c * 1.25 + 30) for c in body), max(1, width // 4), cv2.LINE_AA)
            cap = end + d * rng.uniform(0.04, 0.1) * size
            cv2.line(scene, p1, tuple(int(v) for v in cap), tuple(c * 0.6 for c in body), width + 2, cv2.LINE_AA)


def desk_objects(scene, rng, quad):
    """Mouse, mug, fruit and a magazine around (rarely touching) the sheet on a pale desk."""
    size = float(np.linalg.norm(quad[2] - quad[0]))
    for _ in range(rng.randint(1, 4)):
        kind = rng.choice(["mouse", "mug", "fruit", "magazine"])
        c = (rng.uniform(0, R), rng.uniform(0, R))
        if kind == "magazine":
            w, h = size * rng.uniform(0.4, 0.7), size * rng.uniform(0.5, 0.9)
            box = cv2.boxPoints(((c[0], c[1]), (w, h), rng.uniform(0, 90)))
            cv2.fillPoly(scene, [np.int32(box)], tuple(float(rng.randint(20, 200)) for _ in range(3)), cv2.LINE_AA)
            for _ in range(rng.randint(2, 6)):  # cover print
                spot = box.mean(0) + np.float32([rng.gauss(0, w / 4), rng.gauss(0, h / 4)])
                cv2.circle(scene, tuple(int(v) for v in spot), rng.randint(4, 20),
                           tuple(float(rng.randint(0, 255)) for _ in range(3)), -1, cv2.LINE_AA)
        elif kind == "mug":
            r = int(size * rng.uniform(0.1, 0.18))
            cv2.circle(scene, (int(c[0]) + 4, int(c[1]) + 6), r, (150, 150, 150), -1, cv2.LINE_AA)
            cv2.circle(scene, (int(c[0]), int(c[1])), r, (235, 235, 238), -1, cv2.LINE_AA)
            cv2.circle(scene, (int(c[0]), int(c[1])), int(r * 0.8), (60, 70, 80), -1, cv2.LINE_AA)
        elif kind == "fruit":
            cv2.circle(scene, (int(c[0]), int(c[1])), int(size * rng.uniform(0.04, 0.08)), (30, 110, 230), -1, cv2.LINE_AA)
        else:
            axes = (int(size * rng.uniform(0.06, 0.09)), int(size * rng.uniform(0.1, 0.14)))
            cv2.ellipse(scene, (int(c[0]), int(c[1])), axes, rng.uniform(0, 180), 0, 360, (30, 30, 35), -1, cv2.LINE_AA)


CASES = ["white-stack", "white-stack", "notebooks", "book", "plain-fold", "white-desk"]
CROSSERS = False  # crossing objects on other cases; off for sets made before 2026-11-13 (keeps their rebuild exact)


def compositor(rng, photos, pool, textures):
    case = rng.choice(CASES)
    scene = busy(rng, photos) if (case == "small-clutter" and rng.random() < 0.6) else scenes.background(rng, photos)
    if case in ("white-desk", "cluttered-desk") or (case == "white-stack" and rng.random() < 0.4):
        tone = rng.randint(200, 248)
        scene = np.full((R, R, 3), tone, np.float32) + np.random.default_rng(rng.getrandbits(32)).normal(0, 2, (R, R, 3))
    else:
        scenes.distractors(scene, rng)
    if case in ("book", "book-block"):
        top = scenes.cover(rng, rng.randint(440, 640), rng.randint(320, 460))
    elif case in ("white-stack", "white-desk"):
        top = rng.choice(textures) if (textures and rng.random() < 0.5) else white_paper((rng.randint(460, 700), rng.randint(320, 500), 3), rng)
    else:
        top = rng.choice(pool)
    if case not in ("book", "book-block") and rng.random() < 0.5:
        top = fold(top, rng)
    area = rng.choice([rng.uniform(0.15, 0.5), rng.uniform(0.3, 0.85), rng.uniform(0.45, 0.9)])
    if case == "small-clutter":
        area = rng.uniform(0.04, 0.25)
    elif case == "spread":
        area = rng.uniform(0.35, 0.8)
    elif case == "on-object":
        area = rng.uniform(0.05, 0.35)
    elif case == "cluttered-desk":
        area = rng.uniform(0.06, 0.35)
    elif case == "book-block":
        area = rng.uniform(0.25, 0.85)
    quad = scenes.place(rng, top.shape, area)
    sheet, alpha, pad = bend(top, rng)
    if case == "white-stack":
        base = int(np.median(top)) if rng.random() < 0.7 else None
        direction = np.float32([rng.choice([-1, 1, 0]), rng.choice([-1, 1, 0])])
        pivot = quad[rng.randrange(4)]
        for k in range(rng.randint(1, 4), 0, -1):
            under = white_paper(top.shape, rng, base=None if base is None else int(np.clip(base + rng.randint(-5, 5), 180, 255)))
            if rng.random() < 0.3:
                under = fold(under, rng)
            u, ua, _ = bend(under, rng)
            offset = direction * k * rng.uniform(2, 14) + np.float32([rng.gauss(0, 2), rng.gauss(0, 2)])
            ang = math.radians(k * rng.uniform(0, 4) * rng.choice([-1, 1]))
            rot = np.float32([[math.cos(ang), -math.sin(ang)], [math.sin(ang), math.cos(ang)]])
            placed = (quad - pivot) @ rot.T + pivot + offset
            scenes.paste(scene, cv2.resize(u, (sheet.shape[1], sheet.shape[0])), cv2.resize(ua, (sheet.shape[1], sheet.shape[0])), pad, placed, rng, shadow=rng.random() < 0.3)
        a, _ = scenes.paste(scene, sheet, alpha, pad, quad, rng, shadow=False)
        subtle_edge(scene, a, rng)
    elif case == "notebooks":
        # A second notebook page overlapping the top one from a side, bound by its own spiral.
        under = rng.choice(pool)
        u, ua, upad = bend(under, rng)
        shift = (quad[1] - quad[0]) * rng.uniform(0.3, 0.8) * rng.choice([-1, 1]) + (quad[3] - quad[0]) * rng.uniform(-0.25, 0.25)
        placed = quad + shift
        scenes.paste(scene, cv2.resize(u, (sheet.shape[1], sheet.shape[0])), cv2.resize(ua, (sheet.shape[1], sheet.shape[0])), pad, placed, rng)
        if rng.random() < 0.7:
            spiral(scene, placed, rng.randrange(4), rng)
        a, _ = scenes.paste(scene, sheet, alpha, pad, quad, rng, shadow=rng.random() < 0.6)
        if rng.random() < 0.6:
            spiral(scene, quad, rng.randrange(4), rng)
    elif case == "book":
        notes = rng.choice(pool)
        n, na, _ = bend(notes, rng)
        centre = quad.mean(0)
        grown = centre + (quad - centre) * rng.uniform(1.2, 1.9) + np.float32([rng.gauss(0, 30), rng.gauss(0, 30)])
        scenes.paste(scene, cv2.resize(n, (sheet.shape[1], sheet.shape[0])), cv2.resize(na, (sheet.shape[1], sheet.shape[0])), pad, grown, rng)
        # The page block: cream edges a few pixels beside two adjacent sides of the cover.
        block = scenes.paste(np.zeros_like(scene), np.full_like(sheet, 1), alpha, pad, quad + np.float32([rng.uniform(2, 6), rng.uniform(2, 6)]), rng, shadow=False)[0]
        cream = np.float32([rng.randint(200, 235), rng.randint(215, 240), rng.randint(225, 248)])
        scene[:] = scene * (1 - block[..., None]) + cream * block[..., None]
        a, _ = scenes.paste(scene, sheet, alpha, pad, quad, rng)
    elif case == "book-block":
        if rng.random() < 0.5:  # lying on notes or another book
            notes = rng.choice(pool)
            n, na, _ = bend(notes, rng)
            centre = quad.mean(0)
            grown = centre + (quad - centre) * rng.uniform(1.15, 1.8) + np.float32([rng.gauss(0, 30), rng.gauss(0, 30)])
            scenes.paste(scene, cv2.resize(n, (sheet.shape[1], sheet.shape[0])), cv2.resize(na, (sheet.shape[1], sheet.shape[0])), pad, grown, rng)
        corner = quad[rng.randrange(4)] - quad.mean(0)
        direction = corner / max(1.0, float(np.linalg.norm(corner)))
        direction = direction + np.float32([rng.gauss(0, 0.2), rng.gauss(0, 0.2)])
        thick = rng.uniform(3, 18)
        cream = np.float32([rng.randint(195, 235), rng.randint(210, 240), rng.randint(220, 248)])
        layers = max(2, int(thick / 1.5))
        for k, t in enumerate(np.linspace(thick, 0.5, layers)):  # far pages first: striped page edges
            block = scenes.paste(np.zeros_like(scene), np.full_like(sheet, 1), alpha, pad, np.float32(quad + direction * t), rng, shadow=False)[0]
            tone = cream * (rng.uniform(0.86, 0.95) if k % 2 else rng.uniform(0.96, 1.02))
            scene[:] = scene * (1 - block[..., None]) + tone * block[..., None]
        a, _ = scenes.paste(scene, sheet, alpha, pad, quad, rng, shadow=rng.random() < 0.7)
    elif case == "small-clutter":
        if rng.random() < 0.6:
            edge_paper(scene, rng, pool)
        a, _ = scenes.paste(scene, sheet, alpha, pad, quad, rng, shadow=rng.random() < 0.5)
    elif case == "cluttered-desk":
        if rng.random() < 0.5:  # a few loose sheets under the page, offset like a casual pile
            for _ in range(rng.randint(1, 3)):
                u, ua, _ = bend(white_paper(top.shape, rng), rng)
                ang = math.radians(rng.uniform(-12, 12))
                rot = np.float32([[math.cos(ang), -math.sin(ang)], [math.sin(ang), math.cos(ang)]])
                c = quad.mean(0)
                placed = (quad - c) @ rot.T + c + np.float32([rng.gauss(0, 12), rng.gauss(0, 12)])
                scenes.paste(scene, cv2.resize(u, (sheet.shape[1], sheet.shape[0])), cv2.resize(ua, (sheet.shape[1], sheet.shape[0])),
                             pad, placed, rng, shadow=rng.random() < 0.4)
        desk_objects(scene, rng, quad)
        a, _ = scenes.paste(scene, sheet, alpha, pad, quad, rng, shadow=rng.random() < 0.5)
        subtle_edge(scene, a, rng)
        crossers(scene, rng, quad)
    elif case == "on-object":
        grow = rng.uniform(1.3, 2.4)
        centre = quad.mean(0)
        big = centre + (quad - centre) * np.float32([grow, grow * rng.uniform(0.8, 1.25)]) + np.float32([rng.gauss(0, 25), rng.gauss(0, 25)])
        ang = math.radians(rng.gauss(0, 8))
        rot = np.float32([[math.cos(ang), -math.sin(ang)], [math.sin(ang), math.cos(ang)]])
        big = (big - centre) @ rot.T + centre
        obj = support(rng, pool, rng.randint(400, 640), rng.randint(320, 520))
        oa = np.full(obj.shape[:2], 255, np.uint8)
        scenes.paste(scene, obj, oa, 0, big, rng, shadow=rng.random() < 0.6)
        if rng.random() < 0.3:
            edge_paper(scene, rng, pool)
        a, _ = scenes.paste(scene, sheet, alpha, pad, quad, rng, shadow=rng.random() < 0.6)
    elif case == "spread":
        # The facing page or the next sheet across one side: same paper, a gutter or a slight overlap, cut by the frame.
        side = rng.randrange(4)
        across = quad[side] - quad[side - 1]
        placed = quad + across * (1 + rng.uniform(-0.05, 0.03))
        placed = placed + (quad[(side + 1) % 4] - quad[side]) * rng.uniform(-0.08, 0.08)
        under = top[:, ::-1].copy() if rng.random() < 0.5 else rng.choice(pool)
        u, ua, _ = bend(under, rng)
        scenes.paste(scene, cv2.resize(u, (sheet.shape[1], sheet.shape[0])), cv2.resize(ua, (sheet.shape[1], sheet.shape[0])), pad, placed, rng, shadow=False)
        a, _ = scenes.paste(scene, sheet, alpha, pad, quad, rng, shadow=rng.random() < 0.4)
        if rng.random() < 0.6:  # the gutter: a soft dark line along the shared side
            line = np.zeros((R, R), np.float32)
            p0, p1 = quad[side], quad[(side + 1) % 4]
            cv2.line(line, tuple(int(v) for v in p0), tuple(int(v) for v in p1), 1.0, rng.randint(2, 6), cv2.LINE_AA)
            line = cv2.GaussianBlur(line, (0, 0), rng.uniform(1.5, 5))
            scene *= (1 - line[..., None] * rng.uniform(0.1, 0.45))
        if rng.random() < 0.4:
            spiral(scene, quad, side, rng)
    else:  # plain-fold, white-desk: one sheet, folded, often on a pale surface
        a, _ = scenes.paste(scene, sheet, alpha, pad, quad, rng, shadow=rng.random() < 0.5)
        if case == "white-desk":
            subtle_edge(scene, a, rng)
    scenes.pens(scene, rng, quad)
    if case != "cluttered-desk" and CROSSERS and rng.random() < 0.25:
        crossers(scene, rng, quad, count=rng.choice([1, 1, 2]))
    scenes.fingers(scene, rng, quad)
    scenes.lighting(scene, rng)
    if rng.random() < 0.3:  # haze / low contrast
        mean = scene.mean((0, 1), keepdims=True)
        scene[:] = mean + (scene - mean) * rng.uniform(0.5, 0.85) + rng.uniform(0, 25)
    image = scenes.camera(scene, rng)
    return image, (a > 0.5).astype(np.uint8), case


def chunk(job, *, start_hard=0, limit=None, images=None, masks=None):
    """Scenes [first, last) of a set. Everything is derived from (seed, first), so chunks are independent of
    worker scheduling. start_hard is page_scenes.HARD while the chunk's sheet pool is built: 0 for sets made
    after 2026-11-12; for older sets the value a worker happened to carry over (see regen_table.json)."""
    root, count, first, last, seed, texture_paths, coco, cases = job[:8]
    if len(job) > 8:
        start_hard = job[8]
    global CROSSERS
    CROSSERS = len(job) > 9 and bool(job[9])
    global CASES
    CASES = cases
    scenes.HARD = start_hard
    cv2.setNumThreads(1)
    rng = random.Random(seed + first * 131)
    textures = [cv2.imread(p) for p in rng.sample(texture_paths, min(60, len(texture_paths)))]
    textures = [t for t in textures if t is not None]
    scenes.REAL_SHEETS = []
    candidates = sorted(Path(coco).glob("*.jpg"))
    photos = [cv2.imread(str(p)) for p in rng.sample(candidates, min(35, len(candidates)))]
    photos = [p for p in photos if p is not None]
    pool = [rng.choice(textures) if rng.random() < 0.4 else scenes.make_sheet(rng, photos) for _ in range(40)]
    if images is None:
        images = np.memmap(Path(root) / "images.bin", mode="r+", dtype=np.uint8, shape=(count, 320, 320, 3))
        masks = np.memmap(Path(root) / "masks.bin", mode="r+", dtype=np.uint8, shape=(count, 160, 160))
    cases = {}
    for i in range(first, last if limit is None else min(last, first + limit)):
        scenes.HARD = rng.choice([0, 1, 2])
        image, mask, case = compositor(rng, photos, pool, textures)
        images[i] = cv2.cvtColor(cv2.resize(image, (320, 320), interpolation=cv2.INTER_AREA), cv2.COLOR_BGR2RGB)
        masks[i] = cv2.resize(mask, (160, 160), interpolation=cv2.INTER_NEAREST)
        cases[case] = cases.get(case, 0) + 1
        if i % 13 == 0:
            pool[rng.randrange(len(pool))] = rng.choice(textures) if rng.random() < 0.4 else scenes.make_sheet(rng, photos)
        if i < 16 and limit is None:
            preview = image.copy()
            contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            cv2.drawContours(preview, contours, -1, (0, 0, 255), 2)
            cv2.imwrite(str(Path(root) / f"label-{i:02d}-{case}.jpg"), preview)
    images.flush(); masks.flush()
    return cases


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--textures", type=Path, required=True, help="public-textures folder with manifest.json")
    parser.add_argument("--coco", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--count", type=int, default=30000)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--seed", type=int, default=20261112)
    parser.add_argument("--cases", nargs="+", default=None, help="case list to draw from (repeat a name to weight it)")
    parser.add_argument("--crossers", action="store_true", help="also lay cables/pens across the page in 25 percent of other cases")
    parser.add_argument("--regen-table", type=Path, help="regen_table.json of a set made before 2026-11-12, to rebuild it exactly")
    args = parser.parse_args()
    root = args.out.resolve()
    if (root / "scenes.json").exists():
        raise ValueError("Completed scene sets are immutable; use a new output directory")
    root.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((args.textures / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("publicValidationUsed") or manifest.get("ownerPhotosUsed"):
        raise ValueError("Texture pool must exclude held-out and owner photos")
    paths = [r["texture"] for r in manifest["records"]]
    for name, dtype, shape in [("images", np.uint8, (args.count, 320, 320, 3)), ("masks", np.uint8, (args.count, 160, 160))]:
        np.memmap(root / f"{name}.bin", mode="w+", dtype=dtype, shape=shape).flush()
    for script in (Path(__file__), Path(__file__).with_name("page_scenes.py")):
        (root / ("used-" + script.name)).write_bytes(script.read_bytes())
    started = time.perf_counter()
    case_list = args.cases or CASES
    start = {}
    if args.regen_table:
        table = json.loads(args.regen_table.read_text(encoding="utf-8"))
        start = {int(k): v for k, v in table["startHard"].items()}
    jobs = [(str(root), args.count, i, min(i + 100, args.count), args.seed, paths, str(args.coco), case_list, start.get(i, 0))
            for i in range(0, args.count, 100)]
    if args.crossers:
        jobs = [j + (True,) for j in jobs]
    totals = {}
    with concurrent.futures.ProcessPoolExecutor(max_workers=args.workers) as pool:
        done = 0
        for cases in pool.map(chunk, jobs):
            for k, v in cases.items():
                totals[k] = totals.get(k, 0) + v
            done += sum(cases.values())
            if done % 2000 < 100:
                print(json.dumps({"completed": done, "total": args.count, "seconds": round(time.perf_counter() - started)}), flush=True)
    (root / "scenes.json").write_text(json.dumps({
        "count": args.count, "input": 320, "mask": 160, "seed": args.seed, "cases": totals, "caseList": case_list, "crossers": bool(args.crossers),
        "textures": "Mendeley x3nm4cxr83 (CC BY 4.0) public-training textures + procedural sheets",
        "texturesManifestSplit": manifest.get("splitSha256"),
        "publicValidationUsed": False, "ownerPhotosUsed": False,
        "seconds": round(time.perf_counter() - started),
    }, indent=2), encoding="utf-8")


if __name__ == "__main__":
    multiprocessing.freeze_support()
    main()
