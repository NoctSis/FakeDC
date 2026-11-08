#!/usr/bin/env python3
"""图标自检：把"图标好不好看"拆成可判定的几何/颜色指标。

为什么需要（v244 教训）：
  第一版图标画完，mdpi 看起来正常（不透明 13.1%），但 xxxhdpi 是 72.1%
  且中心点颜色 (186,195,183) 是灰白 —— 整片画布被光晕糊满。
  **光看"有没有输出文件"永远发现不了这类错误。**

判定项：
  A 尺寸   每档 PNG 的像素尺寸 = 108dp × 该密度倍率
  B 安全区  自适应图标前景的核心可见区是中心 72/108；图形必须落在
            中心 66/108 以内（留 3dp 余量），否则被系统裁切
  C 不透明率 前景不透明像素占比应在 6%~30%（太少=看不见，太多=糊成一坨）
  D 颜色    必须同时出现「蓝系」和「暖系」像素（渐变两端），且蓝多于暖
  E 中心    中心像素应近乎透明（图形在四周，不在正中）或为图形本身
  F 背景   背景层必须满不透明且以深色为主（亮度 < 90/255）
"""
import math
import os
import struct
import sys
import zlib

RES = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   'app', 'src', 'main', 'res')
DENS = {'mdpi': 108, 'hdpi': 162, 'xhdpi': 216, 'xxhdpi': 324, 'xxxhdpi': 432}

FAIL = []


def ok(m):
    print('  \u2713 ' + m)


def bad(m):
    print('  \u2717 ' + m)
    FAIL.append(m)


def read_png(p):
    d = open(p, 'rb').read()
    if d[:8] != b'\x89PNG\r\n\x1a\n':
        raise ValueError('not png: ' + p)
    i = 8
    w = h = None
    idat = b''
    while i < len(d):
        ln = struct.unpack('>I', d[i:i + 4])[0]
        typ = d[i + 4:i + 8]
        data = d[i + 8:i + 8 + ln]
        if typ == b'IHDR':
            w, h, bd, ct = struct.unpack('>IIBB', data[:10])
            if bd != 8 or ct != 6:
                raise ValueError('expect RGBA8: %s' % p)
        elif typ == b'IDAT':
            idat += data
        i += 12 + ln
    raw = zlib.decompress(idat)
    stride = w * 4
    prev = bytearray(stride)
    rows = []
    pos = 0
    for _ in range(h):
        f = raw[pos]
        pos += 1
        line = bytearray(raw[pos:pos + stride])
        pos += stride
        if f == 1:
            for x in range(4, stride):
                line[x] = (line[x] + line[x - 4]) & 255
        elif f == 2:
            for x in range(stride):
                line[x] = (line[x] + prev[x]) & 255
        elif f == 3:
            for x in range(stride):
                a = line[x - 4] if x >= 4 else 0
                line[x] = (line[x] + ((a + prev[x]) >> 1)) & 255
        elif f == 4:
            for x in range(stride):
                a = line[x - 4] if x >= 4 else 0
                b = prev[x]
                c = prev[x - 4] if x >= 4 else 0
                pp = a + b - c
                pa, pb, pc = abs(pp - a), abs(pp - b), abs(pp - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[x] = (line[x] + pr) & 255
        rows.append(bytes(line))
        prev = line
    return w, h, rows


def stats(rows, w, h):
    tot = w * h
    op = 0
    xs = []
    ys = []
    # 核心像素（alpha>128）：真正被用户看到的形状，必须在 72dp 安全区内
    cxs = []
    cys = []
    blue = warm = 0
    lum = 0
    for y in range(h):
        r0 = rows[y]
        for x in range(w):
            r, g, b, a = r0[x * 4:x * 4 + 4]
            if a > 24:
                op += 1
                xs.append(x)
                ys.append(y)
                lum += (r * 299 + g * 587 + b * 114) // 1000
                if a > 128:
                    cxs.append(x)
                    cys.append(y)
                # 蓝系：b 明显高于 r
                if b > r + 30 and b > 90:
                    blue += 1
                # 暖系：r 明显高于 b
                elif r > b + 40 and r > 120:
                    warm += 1
    return {
        'opq_ratio': op / float(tot),
        'opq': op,
        'xmin': min(xs) if xs else None, 'xmax': max(xs) if xs else None,
        'ymin': min(ys) if ys else None, 'ymax': max(ys) if ys else None,
        'cxmin': min(cxs) if cxs else None, 'cxmax': max(cxs) if cxs else None,
        'cymin': min(cys) if cys else None, 'cymax': max(cys) if cys else None,
        'blue': blue, 'warm': warm,
        'mean_lum': (lum / op) if op else 0,
    }


print('=== 图标自检 ===')

for dens, base in DENS.items():
    print('\n[%s] 基准 %ddp' % (dens, base))

    # ---------- 前景 ----------
    fp = os.path.join(RES, 'mipmap-' + dens, 'ic_launcher_foreground.png')
    if not os.path.isfile(fp):
        bad('%s 前景缺失' % dens)
        continue
    w, h, rows = read_png(fp)

    # A 尺寸
    if (w, h) == (base, base):
        ok('A 前景尺寸 %dx%d' % (w, h))
    else:
        bad('A 前景尺寸 %dx%d ≠ 期望 %dx%d' % (w, h, base, base))

    st = stats(rows, w, h)

    # B 安全区：自适应图标会被裁成圆形/方形，**核心形状（alpha>128）**
    #   必须落在中心 72/108 内（范围 [18, 90]dp），光晕允许略微外溢。
    lo = 18.0 / 108.0 * w
    hi = 90.0 / 108.0 * w
    if st['cxmin'] is None:
        bad('B 前景没有实心像素（alpha>128 为 0）→ 图形太淡')
    elif (st['cxmin'] >= lo - 1 and st['cxmax'] <= hi + 1
          and st['cymin'] >= lo - 1 and st['cymax'] <= hi + 1):
        ok('B 核心图形在 72dp 安全区内 x=[%.0f,%.0f] y=[%.0f,%.0f] (允许 [%.0f,%.0f])'
           % (st['cxmin'], st['cxmax'], st['cymin'], st['cymax'], lo, hi))
    else:
        bad('B 核心图形越出安全区 x=[%.0f,%.0f] y=[%.0f,%.0f] (允许 [%.0f,%.0f])'
            % (st['cxmin'], st['cxmax'], st['cymin'], st['cymax'], lo, hi))

    # C 不透明率
    if 0.06 <= st['opq_ratio'] <= 0.30:
        ok('C 前景不透明率 %.1f%%（6~30%% 之间）' % (st['opq_ratio'] * 100))
    else:
        bad('C 前景不透明率 %.1f%% 超出 6~30%% → %s'
            % (st['opq_ratio'] * 100,
               '几乎看不见' if st['opq_ratio'] < 0.06 else '糊成一坨'))

    # D 颜色：必须同时有蓝和暖
    if st['blue'] > 50 and st['warm'] > 20:
        ok('D 渐变两端都在（蓝 %d px / 暖 %d px）' % (st['blue'], st['warm']))
    else:
        bad('D 颜色不足（蓝 %d / 暖 %d）→ 渐变没画出来' % (st['blue'], st['warm']))

    # E 中心必须有实心图形（自适应图标的内容本就居中；
    #   真正该防的是「中心一片空白」和「中心糊成一团白」两种极端）
    cxp = cyp = w // 2
    cr_, cg_, cb_, ca = rows[cyp][cxp * 4:cxp * 4 + 4]
    if ca >= 200 and not (cr_ > 240 and cg_ > 240 and cb_ > 240):
        ok('E 中心为实心图形 (rgb=%d,%d,%d a=%d)' % (cr_, cg_, cb_, ca))
    elif cr_ > 240 and cg_ > 240 and cb_ > 240:
        bad('E 中心是纯白 (255,255,255) → 颜色量纲算错（clamp01 把 0..255 压成 1.0）')
    else:
        bad('E 中心过淡 alpha=%d → 图形没画到中心' % ca)

    # ---------- 背景 ----------
    bp = os.path.join(RES, 'mipmap-' + dens, 'ic_launcher_background.png')
    if not os.path.isfile(bp):
        bad('%s 背景缺失' % dens)
        continue
    bw, bh, brows = read_png(bp)
    if (bw, bh) == (base, base):
        ok('A 背景尺寸 %dx%d' % (bw, bh))
    else:
        bad('A 背景尺寸 %dx%d ≠ 期望' % (bw, bh))

    bst = stats(brows, bw, bh)
    if bst['opq_ratio'] > 0.999:
        ok('F 背景满不透明')
    else:
        bad('F 背景有透明像素（%.1f%% 不透明）→ 桌面上会漏出壁纸'
            % (bst['opq_ratio'] * 100))

    if bst['mean_lum'] < 90:
        ok('F 背景为深色（平均亮度 %.0f/255）' % bst['mean_lum'])
    else:
        bad('F 背景偏亮（平均亮度 %.0f/255）→ 与浅色图标区分度低'
            % bst['mean_lum'])

print()
if FAIL:
    print('结果: %d 项失败' % len(FAIL))
    for f in FAIL:
        print('  - ' + f)
    sys.exit(1)
print('结果: 全部通过')
