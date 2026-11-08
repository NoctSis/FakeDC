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


def make_fg(size):
    """前景：一条从左上（亮）下沉到右下（暗）的调光曲线 + 起点圆点。

    合成方式（这是上一版画错的地方）：
      用 **预乘 alpha** 正确叠加「光晕」与「线条」，再统一转回直通 alpha。
      上一版把 glow 当颜色乘数却没参与 alpha，结果整片画布被写成半透明
      灰白（xxxhdpi 下 41821 个 alpha=255 的像素，图标糊成一坨）。
    """
    s = size / BASE
    cx = cy = (size - 1) / 2.0

    # 曲线控制点（108dp 坐标系）—— 形如亮度随滑块下降而衰减
    #
    # v244.1 构图修正（用 img2ascii.py 把图标转成字符网格后才发现）：
    #   首版 P0=(27,33) P3=(81,75)，实际只占画布 36%~84% 横向、25%~74% 纵向，
    #   而且起点太靠左上 —— 圆形裁切后整条曲线挤在左上到右中的窄条里，
    #   右侧和下方大片空白，桌面小图下看起来"缩在角落"。
    #   现在让曲线贯穿安全区（x 24→86, y 28→82），重心回到中心。
    # v244.2 加大落差（字符网格显示首版倾角只有约 32°，圆形裁切下像横杠，
    #   缺少"亮度沉下去"的动势）。改成 24°→82°，倾角约 40°，起点更高、末端更低。
    P0 = (24.0, 24.0)      # 起点：亮（更高）
    P1 = (42.0, 30.0)
    P2 = (54.0, 60.0)
    P3 = (86.0, 82.0)      # 末端：沉到低（更低）
    dot_c = P0
    dot_r = 5.2            # dp

    N = 240
    samples = [cubic_bezier(P0, P1, P2, P3, i / (N - 1.0)) for i in range(N)]

    half = 3.0             # dp，线半宽
    aa = 1.0               # dp，抗锯齿带宽
    glow_w = 6.5           # dp，光晕外扩
    glow_a = 0.34          # 光晕最大不透明度

    px = []
    for y in range(size):
        row = []
        dy = (y - cy) / s + BASE / 2.0
        for x in range(size):
            dx = (x - cx) / s + BASE / 2.0

            # ---- 到曲线的最短距离（dp）----
            best = 1e9
            for (sx, sy) in samples:
                d2 = (dx - sx) ** 2 + (dy - sy) ** 2
                if d2 < best:
                    best = d2
            dist = math.sqrt(best)

            # ---- 到起点圆点的距离（dp）----
            dist_dot = math.hypot(dx - dot_c[0], dy - dot_c[1])

            # 线覆盖率：dist < half 内实心，之后 aa 宽度内衰减
            cov_line = clamp01((half + aa - dist) / aa)
            # 圆点覆盖率
            cov_dot = clamp01((dot_r + aa - dist_dot) / aa)
            # 取两者并集（圆点压在曲线起点上）
            cov = max(cov_line, cov_dot)

            # 光晕：只在离图形较近处存在，且中心更亮
            gd = min(dist, dist_dot)
            glow = clamp01((half + glow_w - gd) / glow_w) ** 1.6 * glow_a

            if cov < 0.003 and glow < 0.003:
                row.append((0, 0, 0, 0))
                continue

            # 沿线渐变：起点蓝 → 末端橙（用参数进度而不是 dy，更贴曲线）
            prog = clamp01(((dx - P0[0]) ** 2 + (dy - P0[1]) ** 2) ** 0.5
                           / math.hypot(P3[0] - P0[0], P3[1] - P0[1]))
            cr, cg, cb = hue_lerp(CURVE_HI, CURVE_LO, prog ** 1.35)

            # 圆点固定用高亮色（它是"当前亮度"标记）
            if cov_dot > cov_line:
                cr, cg, cb = CURVE_HI

            # ---- 预乘 alpha 合成：光晕在下，线条在上 ----
            gr, gg, gb = GLOW
            pr = gr * glow
            pg = gg * glow
            pb = gb * glow
            pa = glow

            # 线条 over 光晕
            nr = cr * cov + pr * (1.0 - cov)
            ng = cg * cov + pg * (1.0 - cov)
            nb = cb * cov + pb * (1.0 - cov)
            na = cov + pa * (1.0 - cov)

            if na <= 0.001:
                row.append((0, 0, 0, 0))
                continue

            # 预乘结果已是「最终色 × alpha」，转回直通 alpha 需除以 na。
            # 注意：nr/ng/nb 是 **0..255** 量级（配色表就是 0..255），
            # 所以这里只能 clamp 到 255，绝不能 clamp01 —— 上一版就栽在这，
            # 144 被压成 1.0 再乘 255 = 纯白，整个图标变成一团白。
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
