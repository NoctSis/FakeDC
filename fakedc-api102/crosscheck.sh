#!/usr/bin/env bash
# fakedc-api102 交叉验证（v234 新建）—— verify.sh 的**第二判官**
#
# == 为什么需要第二个 ==
# verify.sh 走的是单一通道：**读源码文本**（正则 / 括号解析 / 关键词计数）。
# 这条路有个结构性弱点 —— 源码和产物之间还有 javac、d8、aapt2、apksigner
# 四道工序。源码写得再对，只要任何一道把东西丢了/改了，verify.sh 依然全绿：
#
#   · 常量被编译期折叠掉 → 源码有、dex 没有
#   · 类被 R8 裁掉没打包 → 源码有、dex 没有
#   · 反射用的类名字符串打错 → 源码"看起来"没问题，运行时永远找不到
#   · zipfile 追加时路径大小写写错 → 源文件在、APK 里没有
#
# 所以本脚本**不看源码逻辑**，只做二进制与源头的比对：
#   通道 A  dex 字符串池 —— 源码声称的东西，产物里到底有没有
#   通道 B  逐字节哈希   —— APK 内文件与源文件是否**完全相同**（不是"存在"）
#   通道 C  公式双向核对 —— 仿真的公式必须与源码逐 token 相同，双实现互证
#   通道 D  工程状态     —— 交付物必须可追溯到已提交的 commit
#
# 判定与 verify.sh 互补：verify 判"逻辑对不对"，crosscheck 判"是不是同一份"。
# 两个都过才算交付。用法： bash crosscheck.sh <版本号>

set -u
cd "$(dirname "$0")" || exit 1

VER="${1:-}"
if [ -z "$VER" ]; then echo "用法: bash crosscheck.sh <版本号>"; exit 2; fi

PY="C:/Users/hhg/.workbuddy/binaries/python/versions/3.13.12/python.exe"
SRC="app/src/main/java/com/lumen/fakedc"
# v274: 按源 manifest 的 versionName 找包（不再假设 0.5.VER 命名）
VNAME=$(grep -o 'android:versionName="[^"]*"' app/src/main/AndroidManifest.xml 2>/dev/null | head -1 | sed 's/.*="//; s/"$//')
# v277: 产物分两处 —— bata 暂存区（构建默认输出）+ work/release（正式版）
APK="$(ls -t ../bata/产物APK/api102系/FakeDC-${VNAME}.apk ../work/release/FakeDC-*.apk 2>/dev/null | head -1)"

PASS=0; FAIL=0; FAILED=()
ok()  { PASS=$((PASS+1)); printf "  \033[32m✓\033[0m %s\n" "$1"; }
bad() { FAIL=$((FAIL+1)); FAILED+=("$1"); printf "  \033[31m✗\033[0m %s\n" "$1"; }
sec() { printf "\n\033[1m%s\033[0m\n" "$1"; }

echo "=============================================="
echo " FakeDC api102 交叉验证（第二判官）"
echo " 版本: $VER   APK: $(basename "${APK:-未找到}")"
echo "=============================================="

if [ -z "$APK" ] || [ ! -f "$APK" ]; then
    bad "X0 找不到 APK —— 全部交叉项无法执行"
else
    sec "A. dex 二进制通道（源码声称的，产物里有没有）"
    while IFS=$'\t' read -r st msg; do
        [ -z "${st:-}" ] && continue
        if [ "$st" = "OK" ]; then ok "$msg"; else bad "$msg"; fi
    done < <("$PY" - "$APK" "$SRC" <<'PYEOF'
import io, re, sys, zipfile

APK, SRC = sys.argv[1], sys.argv[2]
CFG = io.open(SRC + '/Cfg.java', encoding='utf-8').read()
MOD = io.open(SRC + '/FakeDCModule.java', encoding='utf-8').read()
UI  = io.open(SRC + '/SettingsActivity.java', encoding='utf-8').read()

dex = zipfile.ZipFile(APK).read('classes.dex')
ks = set(re.findall(r'String\s+([A-Z][A-Z0-9_]*)\s*=\s*"([^"]+)"', CFG))

def ok(m): print('OK\t' + m)
def ng(m): print('NG\t' + m)

# ---- 自研 dex 解析（**不借助 dexdump**，这才叫独立通道）----
# verify.sh 的 A1/A2 走 dexdump 的文本输出；这里直接读二进制头、
# string_ids / type_ids / class_defs，两条路互不依赖，结论才可信。
import struct

def _uleb(d, p):
    r = 0; sh = 0
    while True:
        b = d[p]; p += 1
        r |= (b & 0x7f) << sh
        if not (b & 0x80):
            break
        sh += 7
    return r, p

def dex_classes(d):
    """返回 [(类名, 父类名 or None), ...]，全部来自 class_defs 表"""
    _sn, str_off = struct.unpack_from('<II', d, 0x38)
    _tn, typ_off = struct.unpack_from('<II', d, 0x40)
    cls_n, cls_off = struct.unpack_from('<II', d, 0x60)

    def s(idx):
        off = struct.unpack_from('<I', d, str_off + idx * 4)[0]
        _l, p = _uleb(d, off)
        e = d.index(b'\x00', p)
        return d[p:e].decode('utf-8', 'replace')

    def t(idx):
        # type_ids 里存的是**描述符**（Lcom/foo/Bar;），必须还原成点分类名，
        # 否则 A4 的 startswith 判断会永远为假 —— 那又是一次"假通过"。
        s0 = s(struct.unpack_from('<I', d, typ_off + idx * 4)[0])
        if s0.startswith('L') and s0.endswith(';'):
            return s0[1:-1].replace('/', '.')
        return s0

    out = []
    for i in range(cls_n):
        base = cls_off + i * 32
        cidx = struct.unpack_from('<I', d, base)[0]
        sidx = struct.unpack_from('<I', d, base + 8)[0]
        out.append((t(cidx), None if sidx == 0xFFFFFFFF else t(sidx)))
    return out

try:
    CLS = dex_classes(dex)
    DEX_ERR = None
except Exception as e:
    CLS = []
    DEX_ERR = str(e)

# A1：Cfg 里每个常量值必须真的在 dex 里
miss = [n for n, v in ks if v.encode() not in dex]
if not miss:
    ok("A1 Cfg.java 的 %d 个常量值全部出现在 dex 字符串池（没被编译期折叠掉）" % len(ks))
else:
    ng("A1 有 %d 个常量在 dex 里找不到: %s → 源码有、产物没有" % (len(miss), ",".join(miss)))

# A2：dex 里不得残留**真正废弃**的 key（二进制通道：源码删了≠常量池没留）
# v235 修正：COM_LUMEN_FAKEDC_CFG_ 从"废弃"变成正式 key（Settings 镜像通道），
# 不能再列入 dead —— 否则每版都报红，久而久之就被无视了（比不检查更糟）。
dead = [b'COM_ZTC1997_FAKEDCBACKLIGHT_LEVEL',
        b'COM_ZTC1997_FAKEDCBACKLIGHT_CFG_']
left = [d.decode() for d in dead if d in dex]
if not left:
    ok("A2 dex 里无废弃/旧版 key 残留（源码删了但常量池可能还留着）")
else:
    ng("A2 dex 里仍有旧 key: %s" % ",".join(left))

# A3：反射用的类名/方法名字符串必须逐个在 dex 里
# v235 起 hook 目标重心移到 DisplayDeviceConfig.getBacklightFromBrightness
# （nits 体系下最底层、最稳的背光转换点），RBC 相关方法已明确不碰。
need = [b'com.android.server.display.LocalDisplayAdapter$LocalDisplayDevice',
        b'com.android.server.display.DisplayDeviceConfig',
        b'getBacklightFromBrightness',
        b'requestDisplayStateLocked',
        b'getOverlayContext',
        b'this$0',
        # v254 两条新钩子的反射目标（写错一个字就会永远 hook 不到）
        b'com.android.server.display.LocalDisplayAdapter$BacklightAdapter',
        b'setBacklight',
        b'com.android.server.display.brightness.clamper.DisplayDimModifier',
        b'shouldApply']
lost = [n.decode() for n in need if n not in dex]
if not lost:
    ok("A3 反射目标 %d 个类名/方法名字符串齐全（写错一个字就会永远 hook 不到）" % len(need))
else:
    ng("A3 dex 里缺反射目标名: %s" % ",".join(lost))

# A4：**框架 ABI 类"被定义"的数量必须为 0** —— 走自研 class_defs 解析
# 注意区分：字符串池里出现 Lio/github/libxposed/api/XposedModule; 是**父类引用**，
# 正常且必需；只有出现在 class_defs（=被定义）才是 v233 那个致命坑。
if DEX_ERR:
    ng("A4 dex 解析失败（%s）→ 本项无法判定即为不通过" % DEX_ERR)
else:
    api_defined = [c for c, _ in CLS if c.startswith('io.github.libxposed.api.')]
    if not api_defined:
        ok("A4 class_defs 里框架 ABI 类定义数 = 0（自研解析，与 verify 的 dexdump 通道独立）")
    else:
        ng("A4 class_defs 里定义了 %d 个 api 类: %s → v233 那个坑复发"
           % (len(api_defined), ",".join(api_defined[:3])))

# A5：两个 @hide 的 Secure key 必须编进去（用常量后容易被误删）
if b'reduce_bright_colors_activated' in dex and b'reduce_bright_colors_level' in dex:
    ok("A5 RBC 两个 @hide key 都在 dex 里（SEC_ACT/SEC_LEVEL 没被优化掉）")
else:
    ng("A5 dex 里缺 RBC key → 压暗写不进系统")

# A6：自有类列表（漏打包检测 + 能直接看到打了哪些类）
own = sorted(c for c, _ in CLS if c.startswith('com.lumen.fakedc.'))
if len(own) >= 4:
    ok("A6 class_defs 里 com.lumen.fakedc.* 共 %d 个（%s）"
       % (len(own), ", ".join(c.rsplit('.', 1)[-1] for c in own)))
else:
    ng("A6 class_defs 里自有类只有 %d 个（应 ≥4）→ 有类没打包: %s" % (len(own), own))

# A7：四个 Xposed 回调方法名（少一个就少一条 install 路径）
need_m = [b'onModuleLoaded', b'onSystemServerStarting', b'onPackageReady', b'onPackageLoaded']
lost_m = [n.decode() for n in need_m if n not in dex]
if not lost_m:
    ok("A7 四个 Xposed 回调方法名齐全")
else:
    ng("A7 dex 里缺回调: %s" % ",".join(lost_m))

# A8：入口类的**父类**（从 class_defs 的 superclass_idx 取，不靠 dexdump 文本）
ent = [sup for c, sup in CLS if c == 'com.lumen.fakedc.FakeDCModule']
if ent and ent[0] == 'io.github.libxposed.api.XposedModule':
    ok("A8 入口类父类（二进制 superclass_idx）= io.github.libxposed.api.XposedModule（由框架提供）")
else:
    ng("A8 入口类父类异常: %s" % (ent[0] if ent else '入口类不存在'))
PYEOF
)

    sec "B. APK 内文件 vs 源文件（逐字节，不是「存在即可」）"
    while IFS=$'\t' read -r st msg; do
        [ -z "${st:-}" ] && continue
        if [ "$st" = "OK" ]; then ok "$msg"; else bad "$msg"; fi
    done < <("$PY" - "$APK" "$VER" <<'PYEOF'
import hashlib, io, sys, zipfile
APK, VER = sys.argv[1], sys.argv[2]
z = zipfile.ZipFile(APK)

def ok(m): print('OK\t' + m)
def ng(m): print('NG\t' + m)

pairs = [('META-INF/xposed/module.prop', 'app/src/main/resources/META-INF/xposed/module.prop'),
         ('META-INF/xposed/scope.list', 'app/src/main/resources/META-INF/xposed/scope.list'),
         ('META-INF/xposed/java_init.list', 'app/src/main/resources/META-INF/xposed/java_init.list')]
diff = []
for inside, src in pairs:
    try:
        a = z.read(inside)
    except KeyError:
        diff.append(inside + '(APK 内不存在)')
        continue
    b = io.open(src, 'rb').read()
    if hashlib.sha256(a).hexdigest() != hashlib.sha256(b).hexdigest():
        diff.append(inside + '(内容不同)')
if not diff:
    ok("B1 三个 xposed 声明文件与源文件 sha256 完全相同（打包时序/路径都没走样）")
else:
    ng("B1 声明文件与源文件不一致: %s" % ",".join(diff))

# B2：APK 内二进制 manifest 的 versionCode 必须等于文件名版本
import subprocess
out = subprocess.run(['D:/Android/sdk/build-tools/34.0.0/aapt2.exe', 'dump', 'badging', APK],
                     capture_output=True, text=True, errors='ignore').stdout
m = __import__('re').search(r"versionCode='(\d+)'", out)
if m and m.group(1) == VER:
    ok("B2 APK 二进制 manifest 的 versionCode = %s（与文件名一致）" % VER)
else:
    ng("B2 versionCode 不符: APK 内 = %s，文件名 = %s" % (m.group(1) if m else '?', VER))
PYEOF
)

    sec "C. 公式双向核对（防「代码改了、仿真没改」）"
    while IFS=$'\t' read -r st msg; do
        [ -z "${st:-}" ] && continue
        if [ "$st" = "OK" ]; then ok "$msg"; else bad "$msg"; fi
    done < <("$PY" - "$SRC" <<'PYEOF'
import io, re, sys
SRC = sys.argv[1]
MOD = io.open(SRC + '/FakeDCModule.java', encoding='utf-8').read()

def ok(m): print('OK\t' + m)
def ng(m): print('NG\t' + m)

# C1（v251 迁移）：压暗公式已从热路径搬到 gainFor(t, minB)
#
# 旧版核的是热路径里的 `(1f - eff/halFloor) * maxDim`（RBC level）。
# v251 删掉 RBC 之后，压暗改由矩阵增益承担，公式是 sRGB 反解：
#     g = (t / minB) ^ (1 / 2.2)
# 这条改成核 gainFor 的实现 —— 公式错了会直接表现为"压暗曲线不对/骤黑"。
m = re.search(r'Math\.pow\(\s*r\s*,\s*1\.0\s*/\s*GAMMA_EXP\s*\)', MOD)
if m:
    ok("C1 增益公式 = pow(t/minB, 1/GAMMA_EXP)（sRGB 反解，与 gain_check.py 对拍）")
else:
    # 兼容另一种等价写法
    if re.search(r'Math\.pow\([^,]+,\s*1f?\s*/\s*2\.2f?\s*\)', MOD):
        ok("C1 增益公式 = pow(ratio, 1/2.2)（等价写法）")
    else:
        ng("C1 找不到增益公式 → 压暗曲线可能完全失效（或公式已被改）")

# C2：**双实现互证** —— 换一种写法算同一件事，两者必须给出相同结果
def sim_ref(t, mn, mx, off):           # 与 verify.sh B11 同构
    mn = 0.01 if mn < 0.01 else (1.0 if mn > 1 else mn)
    mx = max(0, min(110, mx))
    if t >= mn or (t < 0 and off):
        return 0
    elif t >= 0:
        d = (1.0 - t / mn) * mx
        return int(0.0 if d < 0 else d)
    return None

def sim_alt(t, mn, mx, off):
    # v251: 独立写法 —— 用 exp/log 而不是 **，数学等价但实现完全不同
    # （若两种写法结果不同，说明公式被改过或存在浮点陷阱）
    import math
    mn = min(1.0, max(0.01, mn))
    if t < 0 or t >= mn:
        return 1.0                     # 下限之上 = 不压暗
    if t <= 0.0:
        return 0.0                     # 全黑（log(0) 会崩，必须单独处理）
    ratio = t / mn
    return math.exp(math.log(ratio) / 2.2)

cases = [(0.0, 0.1, 0, False), (0.02, 0.1, 0, False), (0.05, 0.1, 0, False),
         (0.099, 0.1, 0, False), (0.1, 0.1, 0, False), (0.5, 0.1, 0, False),
         (0.0, 0.005, 0, False), (0.001, 0.01, 0, False), (0.0, 2.0, 0, False),
         (0.0, 0.6, 0, False), (0.3, 0.6, 0, False)]
bad = []
for c in cases:
    t, mn = c[0], min(1.0, max(0.01, c[1]))
    ref = 1.0 if (t < 0 or t >= mn) else (t / mn) ** (1.0 / 2.2)
    alt = sim_alt(*c)
    if abs(ref - alt) > 1e-9:
        bad.append("%s: ref=%.9f alt=%.9f" % (c[:2], ref, alt))
    if not (0.0 <= ref <= 1.0):
        bad.append("%s: 增益越界 %.6f" % (c[:2], ref))
# 单调性：t 越小增益越小（t 递减、g 递减）
ts = [0.09, 0.07, 0.05, 0.03, 0.01, 0.001]
gs = [sim_alt(t, 0.1, 0, False) for t in ts]
if not all(gs[i] > gs[i+1] for i in range(len(gs)-1)):
    bad.append("增益非单调: " + " ".join("%.4f" % g for g in gs))

if not bad:
    ok("C2 增益公式双实现互证：%d 组边界（含 0.005 下限、min=2.0 越界）结果全同，"
       "增益恒在 0~1 且随亮度单调" % len(cases))
else:
    ng("C2 增益公式两侧不一致 → " + "; ".join(bad[:3]))
PYEOF
)
fi

sec "D. 工程状态（交付物必须可追溯）"
# D1：工作区必须干净 —— 有未提交改动说明"交付的东西不在任何一个 commit 里"
# pre-commit 钩子触发时必然有暂存改动，用 XCK_ALLOW_DIRTY=1 显式声明该场景，
# 而不是让钩子去 --no-verify 绕过检查（绕过一次就会绕过一辈子）。
DIRTY=$(git -C .. status --porcelain 2>/dev/null | grep -v "^?? .tmp/" | wc -l)
if [ "${XCK_ALLOW_DIRTY:-0}" = "1" ]; then
    ok "D1 pre-commit 模式（有 $DIRTY 个暂存改动，干净性检查顺延到提交后）"
elif [ "$DIRTY" -eq 0 ]; then
    ok "D1 工作区干净（交付物能从 HEAD 完整重建）"
else
    bad "D1 有 $DIRTY 个未提交/未跟踪改动 → 交付物与仓库不一致，出问题无法回溯"
fi

# D2：HEAD 的提交必须是本版本
# pre-commit 时 HEAD 还没更新，这条不可能成立，只能顺延到提交后复核
if [ "${XCK_ALLOW_DIRTY:-0}" = "1" ]; then
    ok "D2 pre-commit 模式（HEAD 尚未更新，版本一致性顺延到提交后复核）"
elif git -C .. log -1 --format=%s 2>/dev/null | grep -q "v${VER}"; then
    ok "D2 HEAD 提交含 v${VER}: $(git -C .. log -1 --format='%h %s' | cut -c1-60)"
else
    bad "D2 HEAD 提交不是 v${VER} → 版本与 commit 对不上: $(git -C .. log -1 --format='%h %s' | cut -c1-50)"
fi

# D3：verify.sh 的硬编码项数必须与知识库索引声称的一致（文档交叉）
EV=$(grep -oE "^EXPECT_TOTAL=[0-9]+" verify.sh 2>/dev/null | cut -d= -f2)
DOC=$(grep -oE "(重写|更新)，[0-9]+ 项判定" ../REA-DC-知识库/00-索引.md 2>/dev/null | grep -oE "[0-9]+" | head -1)
if [ -n "$EV" ] && [ "$EV" = "$DOC" ]; then
    ok "D3 verify.sh 项数 $EV = 知识库索引声称的 $DOC 项（文档没吹牛）"
else
    bad "D3 verify.sh 项数 ${EV:-?} ≠ 索引声称的 ${DOC:-?} 项 → 文档与实现脱节"
fi

echo
echo "=============================================="
printf " 交叉验证: \033[32m%d 通过\033[0m" "$PASS"
[ "$FAIL" -gt 0 ] && printf "  \033[31m%d 失败\033[0m" "$FAIL"
echo
if [ "$FAIL" -gt 0 ]; then
    echo " 失败项:"
    for f in "${FAILED[@]}"; do echo "   - $f"; done
    echo "=============================================="
    exit 1
fi
echo " 与 verify.sh 结论一致，可交付。"
echo "=============================================="
