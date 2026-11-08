#!/usr/bin/env python3
"""生成 FakeDC 的应用图标（自适应图标 前景/背景 位图）。

为什么用脚本画而不是塞一张 PNG：
  1) 可复现 —— 图标是设计资产，二进制 PNG 进仓库就说不清怎么来的；
  2) 要出 5 种密度，手绘 5 张必然不一致；
  3) 图形是纯几何（曲线 + 渐变），代码比手绘更准。

设计意图（用户诉求：「分辨不出是什么软件」）：
  · 核心图形 = **一条从亮到暗的亮度曲线**，直接对应"调光"这个功能；
  · 曲线末端下沉到近黑，直观表达"能压到很暗"；
  · 背景深色 → 与浅色图标在桌面上区分度高（用户用暗色主题）；
  · 不写字（小尺寸下文字必糊），靠形状识别。

输出（Android 自适应图标规范，前景 108dp 画布，安全区中心 72dp）：
  res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_foreground.png
  res/mipmap-{...}/ic_launcher_background.png
"""
import math
import os
import struct
import zlib

OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   'app', 'src', 'main', 'res')

# 自适应图标：108dp 画布。各密度对应 px
DENS = {
    'mdpi': 108, 'hdpi': 162, 'xhdpi': 216, 'xxhdpi': 324, 'xxxhdpi': 432,
}

# ---- 配色（与 Theme.java 的暗色板同源，保持品牌一致）----
BG_INNER = (0x1E, 0x28, 0x38)   # 深蓝灰（比纯黑有质感）
BG_OUTER = (0x0B, 0x0E, 0x13)   # 更深的边缘，做径向渐变
CURVE_HI = (0x90, 0xCA, 0xF9)   # Blue 200 —— Theme.accent()
CURVE_LO = (0xFF, 0xB7, 0x4D)   # Orange 300 —— Theme.orange()
GLOW     = (0x90, 0xCA, 0xF9)

# 设计基准画布（dp）。所有坐标都写在这个尺度上，最后按 s=size/108 缩放。
BASE = 108.0


def lerp(a, b, t):
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


def rgb_to_hsl(r, g, b):
    r, g, b = r / 255.0, g / 255.0, b / 255.0
    mx, mn = max(r, g, b), min(r, g, b)
    l = (mx + mn) / 2.0
    if mx == mn:
        return 0.0, 0.0, l
    d = mx - mn
    s = d / (2.0 - mx - mn) if l > 0.5 else d / (mx + mn)
    if mx == r:
        h = ((g - b) / d + (6 if g < b else 0)) / 6.0
    elif mx == g:
        h = ((b - r) / d + 2) / 6.0
    else:
        h = ((r - g) / d + 4) / 6.0
    return h, s, l


def hsl_to_rgb(h, s, l):
    if s == 0:
        v = int(round(l * 255))
        return (v, v, v)
    q = l * (1 + s) if l < 0.5 else l + s - l * s
    p = 2 * l - q

    def hue(t):
        t = t % 1.0
        if t < 1 / 6.0:
            return p + (q - p) * 6 * t
        if t < 0.5:
            return q
        if t < 2 / 3.0:
            return p + (q - p) * (2 / 3.0 - t) * 6
        return p

    return (int(round(hue(h + 1 / 3.0) * 255)),
            int(round(hue(h) * 255)),
            int(round(hue(h - 1 / 3.0) * 255)))


def hue_lerp(a, b, t):
    """按色相插值（走"短弧"）。

    为什么不用 RGB 直插：蓝(210°) → 橙(35°) 在 RGB 空间的中点必然是
    灰调（R/G/B 三个通道互相抵消）。实测中点 = (187,195,182)，一坨灰绿。
    走短弧 210°→35° 经过 180°(青) / 120°(绿)，中段是鲜亮的青绿，
    「冷 → 暖」的过渡感正好对应"亮度从高到低"。
    """
    h1, s1, l1 = rgb_to_hsl(*a)
    h2, s2, l2 = rgb_to_hsl(*b)
    d = h2 - h1
    if d > 0.5:
        d -= 1.0
    elif d < -0.5:
        d += 1.0
    return hsl_to_rgb((h1 + d * t) % 1.0,
                      s1 + (s2 - s1) * t,
                      l1 + (l2 - l1) * t)


def clamp01(x):
    return 0.0 if x < 0 else (1.0 if x > 1 else x)


def write_png(path, w, h, pixels):
    """pixels: 行列表，每行是 (r,g,b,a) 列表，a 为 0..255。"""
    raw = bytearray()
    for row in pixels:
        raw.append(0)  # filter type 0
        for (r, g, b, a) in row:
            raw += bytes((r, g, b, a))

    def chunk(typ, data):
        c = struct.pack('>I', len(data)) + typ + data
        c += struct.pack('>I', zlib.crc32(typ + data) & 0xFFFFFFFF)
        return c

    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(bytes(raw), 9))
    png += chunk(b'IEND', b'')
    with open(path, 'wb') as f:
        f.write(png)


def make_bg(size):
    """径向渐变背景（自适应图标背景层会被系统裁切，画满即可）。"""
    cx = cy = (size - 1) / 2.0
    maxr = (size / 2.0) * 1.25
    px = []
    for y in range(size):
        row = []
        for x in range(size):
            d = math.hypot(x - cx, y - cy)
            t = clamp01(d / maxr) ** 0.85
            r, g, b = lerp(BG_INNER, BG_OUTER, t)
            row.append((int(r), int(g), int(b), 255))
        px.append(row)
    return px


def cubic_bezier(p0, p1, p2, p3, t):
    """标准三次贝塞尔（之前误写成折线插值，导致曲线是折的）。"""
    mt = 1.0 - t
    a = mt * mt * mt
    b = 3 * mt * mt * t
    c = 3 * mt * t * t
    d = t * t * t
    return (a * p0[0] + b * p1[0] + c * p2[0] + d * p3[0],
            a * p0[1] + b * p1[1] + c * p2[1] + d * p3[1])


def rrect_cov(x, y, cx, cy, hw, hh, r, aa):
    """圆角矩形的覆盖率（SDF，带抗锯齿）。hw/hh = 半宽/半高，r = 圆角半径。"""
    qx = abs(x - cx) - (hw - r)
    qy = abs(y - cy) - (hh - r)
    d = math.hypot(max(qx, 0.0), max(qy, 0.0)) + min(max(qx, qy), 0.0) - r
    return clamp01(0.5 - d / aa)


def make_fg(size):
    """前景：**5 根递减的竖条**（亮度档位）+ 底部对齐，表示"亮度被卡在下限之上"。

    为什么从「一条曲线」换成「一排竖条」（v277，用户提出与某同类模块图标相似）：
      · 曲线 = 亮度随滑块衰减，语义对，但"一条曲线"是同类应用的高频母题，
        桌面上容易和别家撞脸；
      · 竖条 = **档位**，更贴这个模块实际干的事（把面板钉在某一档上，不低于它）；
      · 配色不用彩虹：蓝→绿→橙在 5 根上会变成"信号格"，显得杂。
        改为「前 4 根蓝色逐档变暗 + 最后一根橙色」，橙色那根正好标记**下限**，
        正好对应这个模块干的事（亮度不低于某档）；
      · 5 根（奇数）让中心那根正好压在画布正中 —— 自适应图标裁成圆形后重心稳，
        且 check_icon 的「中心必须是实心图形」一项天然满足。

    参数都是 108dp 基准坐标，最后按 size/108 缩放。
    """
    s = size / BASE
    cx = cy = (size - 1) / 2.0

    N = 5                  # 根数（奇数 → 中间那根在正中）
    W = 8.0                # 条宽 dp
    G = 4.0                # 间距 dp
    BOTTOM = 84.0          # 底对齐线 dp
    HEIGHTS = [58.0, 50.0, 42.0, 34.0, 26.0]   # 左高右低：亮度档位递减
    R = 2.5                # 圆角半径 dp
    AA = 0.9               # 抗锯齿带宽 dp
    GLOW_W = 4.5           # 光晕外扩 dp
    GLOW_A = 0.20          # 光晕最大不透明度

    total = N * W + (N - 1) * G
    left = 54.0 - total / 2.0

    bars = []
    for i, h in enumerate(HEIGHTS):
        bx = left + i * (W + G) + W / 2.0          # 中心 x
        by = BOTTOM - h / 2.0                      # 中心 y
        # 前 N-1 根：蓝色逐档变暗（亮度递减）；最后一根用橙色，标记"下限"
        if i == N - 1:
            col = CURVE_LO
        else:
            col = lerp(CURVE_HI, (0x35, 0x5C, 0x8A), i / float(N - 2))
        bars.append((bx, by, W / 2.0, h / 2.0, col))

    px = []
    for y in range(size):
        row = []
        dy = (y - cy) / s + BASE / 2.0
        for x in range(size):
            dx = (x - cx) / s + BASE / 2.0

            cov = 0.0
            col = (0, 0, 0)
            dist = 1e9
            for (bx, by, hw, hh, c) in bars:
                cv = rrect_cov(dx, dy, bx, by, hw, hh, R, AA)
                if cv > cov:
                    cov = cv
                    col = c
                # 到条形边缘的距离（用于光晕）
                dq = math.hypot(max(abs(dx - bx) - hw, 0.0),
                                max(abs(dy - by) - hh, 0.0))
                if dq < dist:
                    dist = dq

            glow = clamp01((GLOW_W - dist) / GLOW_W) ** 1.6 * GLOW_A

            if cov < 0.003 and glow < 0.003:
                row.append((0, 0, 0, 0))
                continue

            cr, cg, cb = col
            gr, gg, gb = GLOW

            # 预乘 alpha：光晕在下，条形在上
            pr, pg, pb, pa = gr * glow, gg * glow, gb * glow, glow
            nr = cr * cov + pr * (1.0 - cov)
            ng = cg * cov + pg * (1.0 - cov)
            nb = cb * cov + pb * (1.0 - cov)
            na = cov + pa * (1.0 - cov)

            if na <= 0.001:
                row.append((0, 0, 0, 0))
                continue

            # 注意量纲：配色是 0..255，clamp 到 255，**不能** clamp01
            row.append((int(max(0, min(255, nr / na))),
                        int(max(0, min(255, ng / na))),
                        int(max(0, min(255, nb / na))),
                        int(clamp01(na) * 255)))
        px.append(row)
    return px


def main():
    for dens, size in DENS.items():
        d = os.path.join(OUT, 'mipmap-' + dens)
        os.makedirs(d, exist_ok=True)
        write_png(os.path.join(d, 'ic_launcher_background.png'),
                  size, size, make_bg(size))
        write_png(os.path.join(d, 'ic_launcher_foreground.png'),
                  size, size, make_fg(size))
        print('  %-8s %dx%d' % (dens, size, size))
    print('图标已生成到', OUT)


if __name__ == '__main__':
    main()
