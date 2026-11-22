#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从图片里提取颜色（OCR + 颜色字典过滤）"""
import os
import re
import subprocess
import tempfile

from PIL import Image


def ocr_text(img_bytes, psm=6, scale=2):
    """把图片放大后 OCR，返回文本"""
    with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as f:
        p = f.name
    try:
        im = Image.open(__import__("io").BytesIO(img_bytes)).convert("RGB")
        w, h = im.size
        if scale != 1:
            im = im.resize((int(w * scale), int(h * scale)), Image.LANCZOS)
        im.save(p)
        out = subprocess.run(["tesseract", p, "stdout", "-l", "chi_sim+eng",
                              "--psm", str(psm)],
                             capture_output=True, text=True, timeout=120).stdout
        return out
    finally:
        try: os.unlink(p)
        except Exception: pass


SPLIT = re.compile(r"[/／、，,;；|]+")


def clean(tok):
    return re.sub(r"[^\u4e00-\u9fa5A-Za-z0-9\-]", "", tok or "")


def extract_colors(img_bytes, known, full_scan_min=3):
    """返回 (colors, hint_text)。known 是颜色名集合，用来过滤 OCR 噪声"""
    text = ""
    try:
        text = ocr_text(img_bytes, psm=6)
    except Exception:
        pass
    try:
        text += "\n" + ocr_text(img_bytes, psm=11, scale=2)
    except Exception:
        pass

    found = []

    # 1) 优先找「颜色：xxx」这种明确标注
    for m in re.finditer(r"颜\s*色\s*[:：]?\s*([^\n]{0,120})", text):
        seg = m.group(1)
        for tok in SPLIT.split(seg):
            t = clean(tok)
            if t and t in known:
                found.append(t)

    # 2) 标注太少时才全图扫：单图色卡场景需要，多图一色场景不需要
    if len(found) < full_scan_min:
        names = sorted((n for n in known if len(n) >= 2), key=len, reverse=True)
        for n in names:
            if n in text and n not in found:
                found.append(n)

    # 去重保序
    seen, out = set(), []
    for c in found:
        if c not in seen:
            seen.add(c)
            out.append(c)
    return out, text


def extract_colors_multi(images, known):
    """多张图各 OCR 一遍再合并（一张图一个颜色）；单图时允许全图扫"""
    out, per = [], []
    single = len(images) == 1
    for fn, data in images[:9]:
        try:
            got, _ = extract_colors(data, known, full_scan_min=(10 if single else 0))
            if not got:      # 这张图没认出标注，退化成全图扫
                got, _ = extract_colors(data, known, full_scan_min=1)
        except Exception:
            got = []
        per.append((fn, got))
        for c in got:
            if c not in out:
                out.append(c)
    return out, per
