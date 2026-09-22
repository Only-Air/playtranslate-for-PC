#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
校验 make_icons.py 的产出：PNG / ICO / ICNS 是否真能被解码，图形是否正确。

这是 CI 的冒烟测试。它存在的理由：图标是**打包链路上最容易悄悄坏掉的一环**——
把 SVG 改名成 .png、ICO 里塞错偏移、ICNS 少一个 chunk，这些都不会让构建失败，
只会让软件中心显示空白图标、MSI 装到一半报错。所以这里逐字节验证。

第二类检查是**语义**检查（--probe）：光验证「能解码」不够，
早先的版本因为坐标算错、只画出了左上角 1/4 并放大两倍，文件本身完全合法、
能正常解码。所以还要看四角是否透明、是否有放大镜的浅青色镜片、
不透明像素占比是否落在预期区间。

用法：check_icons.py <图标目录>
"""
from __future__ import annotations

import struct
import sys
import zlib

SIZES = [16, 32, 48, 64, 128, 256]


def parse_png(data, label):
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"{label}: 不是 PNG（magic 不对）")
    pos, idat, w, h = 8, b"", None, None
    while pos < len(data):
        (ln,) = struct.unpack(">I", data[pos:pos + 4])
        tag = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + ln]
        crc = struct.unpack(">I", data[pos + 8 + ln:pos + 12 + ln])[0]
        if crc != (zlib.crc32(tag + body) & 0xFFFFFFFF):
            raise ValueError(f"{label}: {tag.decode()} 的 CRC 不匹配")
        if tag == b"IHDR":
            w, h, depth, ctype = struct.unpack(">IIBB", body[:10])
            if depth != 8 or ctype != 6:
                raise ValueError(f"{label}: 期望 8 位 RGBA，实际 depth={depth} ctype={ctype}")
        elif tag == b"IDAT":
            idat += body
        pos += 12 + ln
    raw = zlib.decompress(idat)
    if len(raw) != h * (w * 4 + 1):
        raise ValueError(f"{label}: 像素数据长度 {len(raw)} != 期望 {h * (w * 4 + 1)}")
    return w, h, raw


def unfilter(w, h, raw):
    """只支持 filter 0（本项目的编码器只产出 0）；否则报错而不是静默出错。"""
    stride = w * 4
    px = bytearray()
    for y in range(h):
        row = raw[y * (stride + 1):(y + 1) * (stride + 1)]
        if row[0] != 0:
            raise ValueError(f"第 {y} 行的 filter 是 {row[0]}，本校验器只支持 0")
        px += row[1:]
    return px


def probe(label, w, h, px):
    """语义检查。返回 (不透明占比, 是否有镜片色, 是否有白色描边)。"""
    def at(x, y):
        o = (y * w + x) * 4
        return px[o], px[o + 1], px[o + 2], px[o + 3]

    opaque = 0
    for i in range(3, len(px), 4):
        if px[i] > 200:
            opaque += 1
    ratio = opaque / (w * h)

    lens = white = 0
    for i in range(0, len(px), 4):
        r, g, b, a = px[i], px[i + 1], px[i + 2], px[i + 3]
        if a > 200:
            if 100 < r < 160 and 190 < g < 240 and 180 < b < 230:
                lens += 1
            if r > 235 and g > 235 and b > 235:
                white += 1

    # 四角必须透明（圆角矩形的外部）
    corners = [at(0, 0), at(w - 1, 0), at(0, h - 1), at(w - 1, h - 1)]
    bad = [c for c in corners if c[3] > 16]

    problems = []
    if bad:
        problems.append(f"四角不透明（{len(bad)}/4）")
    if ratio < 0.55:
        problems.append(f"不透明占比仅 {ratio:.0%}，图形可能被裁剪或过小")
    if ratio > 0.90:
        problems.append(f"不透明占比 {ratio:.0%}，圆角/留白可能丢失")
    # 白描边宽度是 9/256 设计单位，在 16×16 下不足 1 像素，测不出东西是正常的，
    # 所以只对 ≥32 的尺寸做这项检查。
    if w >= 32 and white < w * h * 0.005:
        problems.append("几乎没有白色描边，轮廓可能没画出来")
    if w >= 128 and lens < 50:
        problems.append("找不到放大镜的浅青色镜片，图形可能不完整")

    status = "OK" if not problems else "!! " + "；".join(problems)
    print(f"    {label}: {w}×{h}  不透明 {ratio:>4.0%}  白 {white:>5}  镜片 {lens:>5}  {status}")
    return not problems


def check_ico(path, expect):
    data = open(path, "rb").read()
    reserved, typ, count = struct.unpack("<HHH", data[:6])
    if (reserved, typ) != (0, 1):
        raise ValueError("ICO: 头部不对")
    print(f"    app.ico: {count} 个尺寸")
    seen = []
    for i in range(count):
        off = 6 + 16 * i
        w, h, _c, _r, _p, _b, size, offset = struct.unpack("<BBBBHHII", data[off:off + 16])
        w = w or 256
        blob = data[offset:offset + size]
        pw, ph, _ = parse_png(blob, f"ICO[{i}]")
        if (pw, ph) != (w, w):
            raise ValueError(f"ICO[{i}]: 声明的 {w}×{w} 与实际 {pw}×{ph} 不符")
        if offset + size > len(data):
            raise ValueError(f"ICO[{i}]: 数据越界")
        print(f"      {pw}×{ph} @ 偏移 {offset}（{size} B）  OK")
        seen.append(pw)
    missing = set(expect) - set(seen)
    if missing:
        raise ValueError(f"ICO 缺少尺寸：{sorted(missing)}")


def check_icns(path, expect_types):
    data = open(path, "rb").read()
    if data[:4] != b"icns":
        raise ValueError("ICNS: magic 不对")
    (total,) = struct.unpack(">I", data[4:8])
    if total != len(data):
        raise ValueError(f"ICNS: 声明长度 {total} != 文件长度 {len(data)}")
    pos, found = 8, []
    while pos < len(data):
        typ = data[pos:pos + 4]
        (ln,) = struct.unpack(">I", data[pos + 4:pos + 8])
        if ln < 8 or pos + ln > len(data):
            raise ValueError(f"ICNS: chunk {typ} 长度 {ln} 非法")
        blob = data[pos + 8:pos + ln]
        w, h, _ = parse_png(blob, typ.decode())
        print(f"      {typ.decode()}  {w}×{h}（{ln - 8} B）  OK")
        found.append(typ.decode())
        pos += ln
    missing = set(expect_types) - set(found)
    if missing:
        raise ValueError(f"ICNS 缺少 chunk：{sorted(missing)}")


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "icons"
    ok = True

    print("==> PNG")
    for s in SIZES:
        p = f"{out}/icon_{s}.png"
        w, h, raw = parse_png(open(p, "rb").read(), f"icon_{s}.png")
        if (w, h) != (s, s):
            raise ValueError(f"icon_{s}.png: 尺寸是 {w}×{h}")
        ok &= probe(f"icon_{s}.png", w, h, unfilter(w, h, raw))

    print("==> ICO")
    check_ico(f"{out}/app.ico", SIZES)

    print("==> ICNS")
    check_icns(f"{out}/app.icns", ["ic07", "ic08", "ic09", "ic11", "ic12", "ic13"])

    print()
    if not ok:
        print("图形语义检查未通过（文件合法但内容不对）。")
        sys.exit(1)
    print("全部通过。")


if __name__ == "__main__":
    main()
