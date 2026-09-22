#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从零生成应用图标：PNG（多尺寸）+ ICO（Windows）+ ICNS（macOS）。

为什么不用 SVG：五种打包格式里有三种**必须要位图**——
  deb/rpm/flatpak → hicolor 的 PNG（软件中心要解码位图）
  MSI             → .ico（ARPPRODUCTICON 只吃 ICO，不吃 PNG）
  macOS DMG       → .icns
而把 SVG 改名成 .png 是假文件（解码会失败）。所以这里直接画位图，不依赖任何
第三方库（Pillow 不可用），也不依赖外部光栅化工具。

图形本身是**占位设计**：TRADEMARK.md 要求分发版与官方 PlayTranslate 图标视觉
可区分，这里从零画了「对话气泡 + 放大镜」，没有沿用上游的贴边 1/4 圆。
公开分发前请换成自己的设计。

用法：make_icons.py <输出目录>
产出：
  icon_<n>.png ×6（16/32/48/64/128/256）
  app.ico
  app.icns
"""
from __future__ import annotations

import math
import os
import struct
import sys
import zlib

# ── 几何工具（有符号距离场，负值 = 内部） ────────────────────────────


def sdf_round_rect(px, py, x0, y0, x1, y1, r):
    cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
    hw, hh = (x1 - x0) / 2.0 - r, (y1 - y0) / 2.0 - r
    qx, qy = abs(px - cx) - hw, abs(py - cy) - hh
    ax, ay = max(qx, 0.0), max(qy, 0.0)
    return math.hypot(ax, ay) + min(max(qx, qy), 0.0) - r


def sdf_circle(px, py, cx, cy, r):
    return math.hypot(px - cx, py - cy) - r


def sdf_segment(px, py, ax, ay, bx, by, r):
    vx, vy = bx - ax, by - ay
    wx, wy = px - ax, py - ay
    L2 = vx * vx + vy * vy
    t = 0.0 if L2 == 0 else max(0.0, min(1.0, (wx * vx + wy * vy) / L2))
    return math.hypot(wx - t * vx, wy - t * vy) - r


def sdf_triangle(px, py, p0, p1, p2):
    """三角形：内部为负。用重心符号法取近似距离（够用）。"""
    def edge(a, b):
        return (px - b[0]) * (a[1] - b[1]) - (a[0] - b[0]) * (py - b[1])
    d0, d1, d2 = edge(p0, p1), edge(p1, p2), edge(p2, p0)
    if (d0 >= 0 and d1 >= 0 and d2 >= 0) or (d0 <= 0 and d1 <= 0 and d2 <= 0):
        return -1.0  # 内部，给个负值即可
    # 外部：到三条边的最小距离
    best = float("inf")
    for a, b in ((p0, p1), (p1, p2), (p2, p0)):
        vx, vy = b[0] - a[0], b[1] - a[1]
        wx, wy = px - a[0], py - a[1]
        L2 = vx * vx + vy * vy
        t = 0.0 if L2 == 0 else max(0.0, min(1.0, (wx * vx + wy * vy) / L2))
        best = min(best, math.hypot(wx - t * vx, wy - t * vy))
    return best


# ── 合成 ─────────────────────────────────────────────────────────────

# 设计坐标系固定 256×256，渲染时按目标尺寸缩放。
C = 256.0


def _sc(v, s):
    return v * s


def sample(px, py, s):
    """
    返回该点的 RGBA（0-255）。

    坐标约定：px/py 与下面所有图形坐标都在**设计空间**（固定 0..256），
    s 只用于超采样的步长换算，**绝不能拿来缩放图形坐标**——
    早先版本在这里乘了一次 s，导致 512 基准图只画出了左上角 1/4 并放大两倍。
    """
    del s  # 显式忽略：图形坐标不做缩放

    def k(v):
        return v

    # 背景：圆角矩形 + 竖向渐变
    d_bg = sdf_round_rect(px, py, k(16), k(16), k(240), k(240), k(52))
    if d_bg > 1.0:
        return (0, 0, 0, 0)
    t = min(1.0, max(0.0, (py - k(16)) / k(224)))
    r = int(0x2F + (0x1D - 0x2F) * t)
    g = int(0x8F + (0x6A - 0x8F) * t)
    b = int(0x86 + (0x63 - 0x86) * t)
    col = [r, g, b, 255]

    def over(rgb, a):
        if a <= 0:
            return
        col[0] = int(col[0] * (1 - a) + rgb[0] * a + 0.5)
        col[1] = int(col[1] * (1 - a) + rgb[1] * a + 0.5)
        col[2] = int(col[2] * (1 - a) + rgb[2] * a + 0.5)
        col[3] = 255

    W = (255, 255, 255)

    # 对话气泡：描边圆角矩形（白，9/256 宽）
    d_box = sdf_round_rect(px, py, k(56), k(66), k(220), k(168), k(20))
    over(W, 1.0 if abs(d_box) < k(4.5) else 0.0)

    # 气泡尾巴（左下角的小尖）
    tri = ((k(96), k(160)), (k(126), k(160)), (k(104), k(196)))
    if sdf_triangle(px, py, *tri) < 0:
        over(W, 0.95)

    # 气泡里的两行「文字」
    if sdf_round_rect(px, py, k(70), k(96), k(146), k(105), k(4.5)) < 0:
        over(W, 0.75)
    if sdf_round_rect(px, py, k(70), k(118), k(118), k(127), k(4.5)) < 0:
        over(W, 0.55)

    # 放大镜：外圈（深色填充 + 白描边）
    d_lens = sdf_circle(px, py, k(158), k(140), k(30))
    if d_lens < 0:
        over((0x1D, 0x6A, 0x63), 1.0)
    if abs(d_lens) < k(4.5):
        over(W, 1.0)
    if sdf_circle(px, py, k(158), k(140), k(17)) < 0:
        over((0x7F, 0xD8, 0xCD), 0.9)
    # 手柄
    if sdf_segment(px, py, k(180), k(162), k(204), k(186), k(6)) < 0:
        over(W, 1.0)

    return tuple(col)


SS = 3  # 超采样 3×3，抗锯齿


def render(size):
    s = size / C
    buf = bytearray()
    inv = 1.0 / (SS * SS)
    for y in range(size):
        buf.append(0)  # 每行的 filter 字节
        for x in range(size):
            acc = [0.0, 0.0, 0.0, 0.0]
            for sy in range(SS):
                for sx in range(SS):
                    px = (x + (sx + 0.5) / SS) / s
                    py = (y + (sy + 0.5) / SS) / s
                    r, g, b, a = sample(px, py, s)
                    af = a / 255.0
                    acc[0] += r * af
                    acc[1] += g * af
                    acc[2] += b * af
                    acc[3] += af
            cov = acc[3] * inv
            if cov <= 1e-4:
                buf += b"\x00\x00\x00\x00"
            else:
                buf += bytes((
                    int(acc[0] * inv / cov + 0.5),
                    int(acc[1] * inv / cov + 0.5),
                    int(acc[2] * inv / cov + 0.5),
                    int(cov * 255 + 0.5),
                ))
    return bytes(buf)


# ── 编码器 ───────────────────────────────────────────────────────────


def _chunk(tag, data):
    return (struct.pack(">I", len(data)) + tag + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))


def png_bytes(size, raw):
    ihdr = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n"
            + _chunk(b"IHDR", ihdr)
            + _chunk(b"IDAT", zlib.compress(raw, 9))
            + _chunk(b"IEND", b""))


def ico_bytes(entries):
    """entries: [(size, png_bytes)]。Vista+ 允许 ICO 内嵌 PNG。"""
    n = len(entries)
    out = struct.pack("<HHH", 0, 1, n)
    offset = 6 + 16 * n
    for size, data in entries:
        w = 0 if size >= 256 else size
        out += struct.pack("<BBBBHHII", w, w, 0, 0, 1, 32, len(data), offset)
        offset += len(data)
    for _, data in entries:
        out += data
    return out


def icns_bytes(entries):
    """entries: [(type, png_bytes)]。ICNS 允许内嵌 PNG。"""
    body = b""
    for typ, data in entries:
        body += typ + struct.pack(">I", 8 + len(data)) + data
    return b"icns" + struct.pack(">I", 8 + len(body)) + body


# ── 主流程 ───────────────────────────────────────────────────────────


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "icons"
    os.makedirs(out, exist_ok=True)

    # 渲染一次 512，再降采样出其余尺寸（比每个尺寸重画快得多）
    base = 512
    print(f"==> 渲染 {base}×{base}（超采样 {SS}×{SS}）")
    raw512 = render(base)
    png512 = png_bytes(base, raw512)

    sizes = [16, 32, 48, 64, 128, 256]
    pngs = {}
    # 先从 512 逐级减半得到 2 的幂尺寸……
    cur, cur_size, cur_raw = 512, base, raw512
    while cur > 16:
        nxt = cur // 2
        nxt_raw = downsample_half(cur_size, cur_raw)
        cur_size, cur_raw, cur = nxt, nxt_raw, nxt
        if nxt in sizes:
            pngs[nxt] = png_bytes(nxt, nxt_raw)
    # ……再直接渲染剩下的非 2 的幂尺寸（hicolor 要 48×48）。
    for s_ in sizes:
        if s_ not in pngs:
            print(f"    直接渲染 {s_}×{s_}")
            pngs[s_] = png_bytes(s_, render(s_))

    for s_ in sizes:
        if pngs.get(s_):
            with open(os.path.join(out, f"icon_{s_}.png"), "wb") as f:
                f.write(pngs[s_])
            print(f"    icon_{s_}.png  {len(pngs[s_]):>6} B")

    ico = ico_bytes([(s_, pngs[s_]) for s_ in (16, 32, 48, 64, 128, 256) if pngs.get(s_)])
    with open(os.path.join(out, "app.ico"), "wb") as f:
        f.write(ico)
    print(f"    app.ico        {len(ico):>6} B")

    icns = icns_bytes([
        (b"ic11", pngs[32]), (b"ic12", pngs[64]), (b"ic07", pngs[128]),
        (b"ic13", pngs[256]), (b"ic08", pngs[256]), (b"ic09", png512),
    ])
    with open(os.path.join(out, "app.icns"), "wb") as f:
        f.write(icns)
    print(f"    app.icns       {len(icns):>6} B")
    print(f"==> {out}")


def downsample_half(size, raw):
    """raw 是带 filter 字节的 RGBA 扫描线，尺寸 size×size。"""
    stride = size * 4 + 1
    half = size // 2
    out = bytearray()
    for y in range(half):
        out.append(0)
        r0 = (2 * y) * stride + 1
        r1 = (2 * y + 1) * stride + 1
        for x in range(half):
            c = [0, 0, 0, 0]
            for dy in (0, 1):
                base = (r0 if dy == 0 else r1) + 2 * x * 4
                for dx in (0, 1):
                    o = base + dx * 4
                    # 预乘 alpha 再平均，避免边缘出现黑边
                    a = raw[o + 3]
                    af = a / 255.0
                    c[0] += raw[o] * af
                    c[1] += raw[o + 1] * af
                    c[2] += raw[o + 2] * af
                    c[3] += af
            cov = c[3] / 4.0
            if cov <= 1e-4:
                out += b"\x00\x00\x00\x00"
            else:
                out += bytes((
                    min(255, int(c[0] / 4.0 / cov + 0.5)),
                    min(255, int(c[1] / 4.0 / cov + 0.5)),
                    min(255, int(c[2] / 4.0 / cov + 0.5)),
                    int(cov * 255 + 0.5),
                ))
    return bytes(out)


if __name__ == "__main__":
    main()
