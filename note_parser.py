#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""微信笔记 -> 商陆花货品档案 解析器

输入：一条微信笔记的纯文本（可带换行、可被微信自动折行）
输出：{款号, 名称, 颜色[], 尺码[]} 的 JSON

用法：
    python3 note_parser.py 笔记.txt
    python3 note_parser.py < 笔记.txt
    python3 note_parser.py --json 笔记.txt
"""
import json
import re
import sys

# 【款号】 【款式】 【颜色】 【尺码】 这类标签
TAG_RE = re.compile(r"[【\[]\s*([^】\]]{1,8}?)\s*[】\]]\s*[:：]?")
# 允许的全角/半角圆括号标签：(款号) 款号:
PAREN_TAG_RE = re.compile(r"[（(]\s*([^）)]{1,6}?)\s*[）)]\s*[:：]?")
# 裸标签：行首直接写「款号：」「颜色:」
BARE_TAG_RE = re.compile(
    r"(?:(?<=^)|(?<=\n))[ \t]*(款号|货号|款式|品名|名称|颜色|色号|尺码|尺寸|码数|成分|面料)"
    r"[ \t]*[:：]?[ \t]*", re.M)

ALIASES = {
    "款号": "style_no", "货号": "style_no", "货号款号": "style_no",
    "款式": "name", "品名": "name", "名称": "name", "款名": "name",
    "颜色": "colors", "色号": "colors", "颜色名称": "colors",
    "尺码": "sizes", "尺寸": "sizes", "码数": "sizes",
    "成分": "ignore", "面料": "ignore", "价格": "ignore", "进价": "ignore",
}

SEP_RE = re.compile(r"[/／、，,;；|]+")
# 只有当整段里一个显式分隔符都没有时，才把换行当成分隔符
NEWLINE_ONLY_RE = re.compile(r"[\n\r]+")


def split_values(raw: str, space_sep: bool = False):
    """按 / 、 ， 等分隔符切分，去掉空项和首尾空白，保持原顺序去重。

    微信会自动折行，换行可能插在词中间（如「芭比\\n粉」），
    所以只要文本里有显式分隔符，就先把所有空白删掉再切。
    尺码这类可能用空格分隔（100 110 120），space_sep=True。
    """
    if SEP_RE.search(raw):
        raw = re.sub(r"\s+", "", raw)
        chunks = SEP_RE.split(raw)
    elif space_sep and re.search(r"\S\s+\S", raw):
        chunks = re.split(r"\s+", raw.strip())
    else:
        chunks = NEWLINE_ONLY_RE.split(raw)
    parts = [re.sub(r"\s+", "", p).strip(" 　·•-—") for p in chunks]
    out, seen = [], set()
    for p in parts:
        if not p:
            continue
        if p in seen:
            continue
        seen.add(p)
        out.append(p)
    return out




SIZE_ALIAS = {
    "s": "S", "m": "M", "l": "L", "xl": "XL", "xxl": "2XL", "xxxl": "3XL",
    "2xl": "2XL", "3xl": "3XL", "4xl": "4XL", "5xl": "5XL",
    "均": "均码", "均码": "均码", "f": "均码", "free": "均码",
}


def normalize_sizes(sizes):
    """把 sml 这种粘连写法拆开，并统一大小写"""
    out = []
    for s in sizes:
        t = s.strip()
        if not t:
            continue
        low = t.lower()
        if low in SIZE_ALIAS:
            out.append(SIZE_ALIAS[low])
            continue
        # 纯字母且长度 2~6，如 sml / xsml / smlx
        if re.fullmatch(r"[smlxSMLX]{2,6}", t):
            out.extend(ch.upper() for ch in t)
            continue
        out.append(t)
    # 去重保序
    seen, res = set(), []
    for x in out:
        if x not in seen:
            seen.add(x)
            res.append(x)
    return res


def parse(text: str) -> dict:
    text = text.replace("\u3000", " ").replace("\xa0", " ")

    # 1) 找出所有标签的位置（三套写法都收进来）
    marks = []
    for rx in (TAG_RE, PAREN_TAG_RE, BARE_TAG_RE):
        for m in rx.finditer(text):
            marks.append((m.start(), m.end(), m.group(1)))
    if not marks:
        raise ValueError("没找到【款号】【颜色】这类标签，请检查文本格式")
    marks.sort()
    # 去掉被前一个标签包住的重复命中（【款号】里的「款号」）
    tidy = []
    for mk in marks:
        if tidy and mk[0] < tidy[-1][1]:
            continue
        tidy.append(mk)
    marks = tidy

    # 2) 按标签切段，后一个标签之前的内容全归前一个标签
    fields = {}
    for i, (start, end, label) in enumerate(marks):
        stop = marks[i + 1][0] if i + 1 < len(marks) else len(text)
        raw = text[end:stop]
        key = ALIASES.get(label.strip())
        if not key or key == "ignore":
            continue
        if key in fields and key != "ignore":
            fields[key] += " " + raw   # 同名标签出现两次就拼接
        else:
            fields[key] = raw

    def one(key):
        v = fields.get(key, "")
        # 折叠换行和多余空格（微信折行会插换行）
        v = re.sub(r"\s+", "", v)
        return v.strip(" /／、，,;；")

    result = {
        "款号": one("style_no"),
        "名称": one("name"),
        "颜色": split_values(fields.get("colors", "")),
        "尺码": split_values(fields.get("sizes", ""), space_sep=True),
    }
    # 「款号：8806全羊毛加厚三色均码」→ 款号 8806，名称取后半段
    if not result["名称"]:
        m = re.match(r"^([A-Za-z0-9][A-Za-z0-9\-]*?)([\u4e00-\u9fa5].*)$", result["款号"])
        if m:
            result["款号"] = m.group(1)
            result["名称"] = m.group(2)
    result["尺码"] = normalize_sizes(result["尺码"])
    if not result["尺码"]:
        result["尺码"] = ["均码"]
    return result


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("-")]
    if args:
        with open(args[0], encoding="utf-8") as f:
            text = f.read()
    else:
        text = sys.stdin.read()

    data = parse(text)
    print(json.dumps(data, ensure_ascii=False, indent=2))
    print(
        f"\n款号 {data['款号']} | 颜色 {len(data['颜色'])} 个 | "
        f"尺码 {len(data['尺码'])} 个 | 预计 {len(data['颜色']) * len(data['尺码'])} 个 SKU",
        file=sys.stderr,
    )


if __name__ == "__main__":
    main()
