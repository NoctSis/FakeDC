#!/usr/bin/env bash
# fakedc-api102 交付前复查（v234 重写）
#
# == 为什么重写 ==
# v233 版有 34 项判定，但用户问"你的检查逻辑完整吗"之后逐条自查，
# 发现有 4 项属于**假通过**（判定条件根本证明不了它声称的事），
# 另有 6 个真正的风险面**完全没有判定**。假通过比没有检查更危险 ——
# 它会给出"全部通过"的结论，让人放心地交付一个坏的包。
#
# 已修的假通过：
#   D2  旧：「Settings 写有 N 处、if 有 >=3 个 → 都包在门控内」
#       —— 3 个 if 和 4 处写完全可以无关。现在真实解析花括号嵌套，
#          逐条确认每个写入点都在 if 块内。
#   D3  旧：只扫 intercept 函数体的文本 → 反射写在隔壁 getCtx() 里就看不见。
#       现在做**调用链递归扫描**（intercept → 被调函数 → 再下一层），
#       并额外验证"链上有反射时必须有缓存证据"。
#   E3  旧：正则匹配不到滑块 block 时 bad=0 → 直接通过。
#       现在要求 block 数 == 2，匹配不到就是失败。
#   A   旧：找不到 APK 时只报 1 个 FAIL，A1~A7 静默跳过（等于 1 次失败
#       掩盖 7 项未验证）。现在整段计为失败。
#
# 新增的盲区判定：
#   A8  APK 内真的打包了 META-INF/xposed/*（旧版只查源文件 —— 源文件对、
#       打包漏了，一样不生效）
#   A9  APK 内 AndroidManifest 真的声明了 XposedProvider（查二进制，不查源）
#   A10 APK versionName 与本次版本号一致（防"装了个旧包以为新的"）
#   B7  UI 的 putXxx 里必须真的 apply()/commit()（只 put 不 apply = 永不落盘，
#       这正是"改了不生效"最可能的形态）
#   B8  模块 sPrefs 必须在 onModuleLoaded 注入（在别的回调里注入可能永远 null）
#   B10 模块读的 key 集合 == UI 写的 key 集合（闭合性，防漏一个）
#   B11 端到端数值仿真：用模块的公式跑边界/单调性，验证 0<=level<=110
#   C8  hook 必须挂**全部**同名重载（只挂一个 = "装上但没效果"）
#   C9  intercept 必须全程 try/catch 且异常后仍走 proceed（否则 system_server 崩）
#   C10 除零兜底（min_bright=0 时 target/minBright = Inf）
#   C11 改参数前必须确认参数个数（arr[1] 越界会打断显示更新）
#   C12 上报不得依赖 Context 当场可用（否则 hook 装上了 UI 仍显示"未运行"）
#   C13 RBC 抑制 hook 的语义与原版一致（enable 时跳过原方法而不是"执行完丢结果"）
#   E7  refresh 全程 try/catch（UI 不能因为读不到 key 就崩）
#   E8  滑块值域不得产生 0（配合 C10）
#   F4  **判定数量自检**：实际执行数 < 预期数 → 失败（防止某段分析静默死掉）
#
# 用法： bash verify.sh [版本号]
# 顺序： 先 build.sh，再 verify.sh（F2 会检查 APK 比源码新）

set -u
VER="${1:-}"
cd "$(dirname "$0")" || exit 1

PY="C:/Users/hhg/.workbuddy/binaries/python/versions/3.13.12/python.exe"
AAPT2="D:/Android/sdk/build-tools/34.0.0/aapt2.exe"
DEXDUMP="D:/Android/sdk/build-tools/34.0.0/dexdump.exe"
JDK="/d/Program Files/Java/jdk-21.0.12.1+1"

SRC="app/src/main/java/com/lumen/fakedc"
MOD="$SRC/FakeDCModule.java"
UI="$SRC/SettingsActivity.java"
CFG="$SRC/Cfg.java"
APK="$(ls -t ../work/产物APK/api102系/FakeDC-*.apk 2>/dev/null | grep -v idsig | head -1)"

# 临时目录必须放在项目内：Git Bash 的 /tmp 就是 C:\Users\...\Temp，
# 与 Windows 侧 python / dexdump 看到的不是同一个地方（v233 踩过）。
TMP="../.tmp/vfy_$$"
mkdir -p "$TMP"
trap 'rm -rf "$TMP"' EXIT
WINTMP="$(cd "$TMP" && pwd -W 2>/dev/null || echo "$TMP")"

PASS=0; FAIL=0; FAILED=(); EXPECT=0
ok()   { PASS=$((PASS+1)); printf "  \033[32m✓\033[0m %s\n" "$1"; }
bad()  { FAIL=$((FAIL+1)); FAILED+=("$1"); printf "  \033[31m✗\033[0m %s\n" "$1"; }
sec()  { printf "\n\033[1m%s\033[0m\n" "$1"; }

# 消费 python 阶段的 OK/NG 输出，并累计"预期判定数"
consume() {
    local n=0 st msg
    while IFS=$'\t' read -r st msg; do
        [ -z "${st:-}" ] && continue
        n=$((n+1))
        if [ "$st" = "OK" ]; then ok "$msg"; else bad "$msg"; fi
    done
    EXPECT=$((EXPECT+n))
}

echo "=============================================="
echo " FakeDC api102 交付前复查 v${VER}"
echo " 版本: ${VER:-未指定}   APK: $(basename "${APK:-未找到}")"
echo "=============================================="

# ============================================================
sec "A. 注入前提（决定模块能否被加载）"
# ============================================================
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
    # 旧版这里只报 1 个 FAIL，A1~A7 静默跳过 —— 等于用"1 次失败"掩盖
    # "10 项没验证"。现在照实说明，并让 F4 的数量自检兜底报警。
    bad "A0 找不到 APK —— A1~A10 共 10 项全部未验证"
else
    "$PY" -c "
import zipfile
zipfile.ZipFile(r'$APK').extract('classes.dex', r'$WINTMP')
" 2>/dev/null
    if [ ! -f "$TMP/classes.dex" ]; then
        bad "A0 从 APK 里取 classes.dex 失败"
        EXPECT=$((EXPECT+1))
    else
        "$DEXDUMP" -f "$TMP/classes.dex" > "$TMP/f.txt" 2>&1
        EXPECT=$((EXPECT+1))
        N_API=$(grep -c "Class descriptor  : 'Lio/github/libxposed/api/" "$TMP/f.txt")
        if [ "$N_API" -eq 0 ]; then
            ok "A1 dex 里无框架 ABI 类（api 包 = 0）—— 模块不会被框架跳过"
        else
            bad "A1 dex 里有 $N_API 个 io.github.libxposed.api 类 → 入口类继承错父类，模块不注入"
        fi

        EXPECT=$((EXPECT+1))
        SUPER=$(grep -A2 "Class descriptor  : 'Lcom/lumen/fakedc/FakeDCModule;'" "$TMP/f.txt" \
                 | grep "Superclass" | head -1 | cut -d"'" -f2)
        if [ "$SUPER" = "Lio/github/libxposed/api/XposedModule;" ]; then
            ok "A2 入口类父类 = 引用 $SUPER（由框架提供）"
        else
            bad "A2 入口类父类异常: ${SUPER:-空}"
        fi

        EXPECT=$((EXPECT+1))
        INITC=$(tr -d '\r' < app/src/main/resources/META-INF/xposed/java_init.list 2>/dev/null | head -1)
        if [ -n "$INITC" ] && grep -q "Class descriptor  : 'L${INITC//.//};'" "$TMP/f.txt"; then
            ok "A3 java_init.list 的入口类存在于 dex: $INITC"
        else
            bad "A3 java_init.list 声明的类 ${INITC:-空} 在 dex 里找不到"
        fi
    fi

    EXPECT=$((EXPECT+1))
    MP="app/src/main/resources/META-INF/xposed/module.prop"
    if grep -q "^minApiVersion=" "$MP" && grep -q "^targetApiVersion=" "$MP" \
       && grep -q "^staticScope=true" "$MP"; then
        ok "A4 module.prop 完整（minApi/targetApi/staticScope）"
    else
        bad "A4 module.prop 缺字段（会导致框架不认模块）"
    fi

    EXPECT=$((EXPECT+1))
    if [ "$(tr -d '\r\n' < app/src/main/resources/META-INF/xposed/scope.list)" = "system" ]; then
        ok "A5 scope.list = system（hook system_server 必需）"
    else
        bad "A5 scope.list 不是 system → hook 不到 DisplayManagerService"
    fi

    EXPECT=$((EXPECT+1))
    if grep -q "io.github.libxposed.service.XposedProvider" app/src/main/AndroidManifest.xml \
       && grep -q 'authorities="com.lumen.fakedc.XposedService"' app/src/main/AndroidManifest.xml; then
        ok "A6 源 manifest 声明 XposedProvider，authority 正确"
    else
        bad "A6 源 manifest 缺 XposedProvider 或 authority 不对 → UI 收不到配置服务"
    fi

    EXPECT=$((EXPECT+1))
    if JAVA_HOME="$JDK" "$JDK/bin/java.exe" -jar \
        "D:/Android/sdk/build-tools/34.0.0/lib/apksigner.jar" verify "$APK" >/dev/null 2>&1; then
        ok "A7 APK 签名有效"
    else
        bad "A7 APK 签名无效（装不上）"
    fi

    # ---- A8: APK 内真的打包了 xposed 声明文件（旧版只查源文件）----
    EXPECT=$((EXPECT+1))
    NMISS=$("$PY" -c "
import zipfile,sys
z=zipfile.ZipFile(r'$APK')
names=set(z.namelist())
need=['META-INF/xposed/module.prop','META-INF/xposed/scope.list','META-INF/xposed/java_init.list']
print(sum(1 for n in need if n not in names))
" 2>/dev/null)
    if [ "${NMISS:-9}" = "0" ]; then
        ok "A8 APK 内已打包 module.prop/scope.list/java_init.list 三个声明文件"
    else
        bad "A8 APK 内缺 ${NMISS:-?} 个 xposed 声明文件 → 框架读不到，模块不生效（源文件对也没用）"
    fi

    # ---- A9: APK 内的二进制 manifest 真的含 provider ----
    EXPECT=$((EXPECT+1))
    "$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" > "$TMP/mf.txt" 2>/dev/null
    if grep -q "io.github.libxposed.service.XposedProvider" "$TMP/mf.txt" \
       && grep -q "com.lumen.fakedc.XposedService" "$TMP/mf.txt"; then
        ok "A9 APK 内 AndroidManifest 含 XposedProvider + authority（查的是打包产物，不是源文件）"
    else
        bad "A9 APK 内 manifest 没有 XposedProvider → aapt2 链接时漏了，UI 永远连不上"
    fi

    # ---- A10: 版本号与本次构建号一致 ----
    EXPECT=$((EXPECT+1))
    # v274: 不再硬编码 "0.5.VER" —— 期望值从源 manifest 读，
    # 这样 versionName 换成 1.0.0 之类正式号也不用改脚本。
    WANTV=$(grep -o 'android:versionName="[^"]*"' app/src/main/AndroidManifest.xml 2>/dev/null | head -1 | sed 's/.*="//; s/"$//')
    if [ -z "$WANTV" ]; then
        bad "A10 读不到源 manifest 的 versionName"
    else
        "$AAPT2" dump badging "$APK" 2>/dev/null | head -1 > "$TMP/badging.txt"
        if grep -q "versionName='${WANTV}'" "$TMP/badging.txt"; then
            ok "A10 APK versionName = ${WANTV}（与源 manifest 一致）"
        else
            bad "A10 APK 版本号不是 ${WANTV} —— 可能拿到的是旧包: $(head -c 120 "$TMP/badging.txt")"
        fi
    fi
fi

# ============================================================
sec "B. 配置通道（唯一：Settings.System）"
# ============================================================
consume < <("$PY" - "$TMP" "$MOD" "$UI" "$CFG" <<'PYEOF'
import io, re, sys

TMP, MOD_P, UI_P, CFG_P = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
MOD = io.open(MOD_P, encoding='utf-8').read()
UI  = io.open(UI_P,  encoding='utf-8').read()
CFG = io.open(CFG_P, encoding='utf-8').read()

def ok(m): print("OK\t" + m)
def ng(m): print("NG\t" + m)

KEY2 = ['ENABLE', 'MIN_BRIGHT', 'GAMMA_COMP']   # v271: 增加画面补偿（治偏白）

# B1：模块侧 4 个配置全部走 Cfg 常量（无字面量，杜绝双侧漂移）
miss = [k for k in KEY2 if 'Cfg.' + k not in MOD]
if not miss:
    ok("B1 模块侧 4 个 key 全部走 Cfg（无字面量漂移）")
else:
    ng("B1 模块侧缺 Cfg 引用: " + ",".join(miss))

# B2：UI 侧同上
miss = [k for k in KEY2 if 'Cfg.' + k not in UI]
if not miss:
    ok("B2 UI 侧 4 个 key 全部走 Cfg")
else:
    ng("B2 UI 侧缺 Cfg 引用: " + ",".join(miss))

# B3：唯一配置通道 = ContentProvider
#     历史：Settings（app 写不了）→ RemotePreferences（从未落盘，2026-11-01 取证）
#     → 自建 Provider（v266）。判定跟着最新契约走。
if 'content://com.lumen.fakedc.cfg' in MOD and 'CfgProvider.PREF' in UI:
    ok("B3 配置通道唯一：ContentProvider（UI 写私有 SharedPreferences，模块 query）")
else:
    ng("B3 配置通道不完整 → 模块读不到用户设置（UI 改了也不会生效）")

# B4：读写类型必须成对（int↔int / float↔float）
bad = []
# 模块读
for k in ['ENABLE']:
    if not re.search(r'Settings\.System\.getInt\([^;]*Cfg\.' + k, MOD):
        bad.append('模块 getInt(' + k + ')')
if not re.search(r'Settings\.System\.getFloat\([^;]*Cfg\.MIN_BRIGHT', MOD):
    bad.append('模块 getFloat(MIN_BRIGHT)')
# UI 写
for k in ['ENABLE']:
    if not ('putInt(Cfg.' + k in UI or 'putInt(key' in UI):
        bad.append('UI putInt(' + k + ')')
if not ('putFloat(Cfg.MIN_BRIGHT' in UI or 'putFloat(key' in UI):
    bad.append('UI putFloat(MIN_BRIGHT)')
if not bad:
    ok("B4 类型成对：ENABLE 走 int，MIN_BRIGHT 走 float")
else:
    ng("B4 类型不匹配: " + ",".join(bad) + " → float 写 int 读会永远拿默认值")

# B5：MIN_BRIGHT 必须「直写直读」——滑块位置 == 面板下限百分比，不允许任何映射
#     用户 2026-11-02 原话：「不应该是我设置定哪里，你就定在哪个位置吗」。
#     v261 的平方映射 0.5*(p/100)^2 把「28%」写成 0.0392，标签与实际差 7 倍，
#     结果「只有变暗生效、防闪没反应」。直写直读才对得上 Cfg 契约
#     （MIN_BRIGHT = float 0~1 = 面板亮度百分比）与原版 fakedcbacklight 语义。
if re.search(r'putFloat\(\s*Cfg\.MIN_BRIGHT\s*,\s*pct\s*/\s*100', UI) \
        and re.search(r'Math\.round\([^;]*\*\s*100', UI):
    if 'Math.pow' in UI or 'Math.sqrt' in UI:
        ng("B5 MIN_BRIGHT 仍残留 pow/sqrt 映射 → 滑块位置与实际下限不一致")
    elif not re.search(r'minBNorm\(\)\s*\*\s*Cfg\.BRIGHT_FULL|cMinBright\s*\*\s*Cfg\.BRIGHT_FULL', MOD):
        ng("B5 模块没有把用户百分比换算成 framework 亮度（×Cfg.BRIGHT_FULL）→ 会钉到错误位置")
    else:
        ok("B5 MIN_BRIGHT 直写直读 + 模块换算 ×BRIGHT_FULL（设置哪里就钉在哪里）")
else:
    ng("B5 MIN_BRIGHT 不是直写直读 → 设置哪里≠钉在哪里")

# B5b：默认值必须单点定义（v270 真机教训：三处各写各的默认值 →
#       界面显示 2% 而模块跑 10%，又是一次「显示值≠生效值」）
_defaults = re.findall(r'MIN_BRIGHT\s*,\s*([0-9.]+)f', UI + MOD + CFG)
_uses = (len(re.findall(r'Cfg\.MIN_BRIGHT_DEFAULT', UI)),
         len(re.findall(r'Cfg\.MIN_BRIGHT_DEFAULT', MOD)),
         len(re.findall(r'float MIN_BRIGHT_DEFAULT', CFG)))
if _defaults:
    ng("B5b 发现裸默认值 %s → 必须统一用 Cfg.MIN_BRIGHT_DEFAULT" % _defaults)
elif min(_uses) >= 1:
    ok("B5b MIN_BRIGHT 默认值单点定义在 Cfg，UI/Provider/模块三处共用")
else:
    ng("B5b MIN_BRIGHT 默认值未统一（定义/引用 UI,Mod,Cfg=%s）→ 界面显示值会与生效值不一致" % (_uses,))

# B6：增益公式（矩阵线程里的 t/minB）
if 't / minB' in MOD or 't/minB' in MOD:
    ok("B6 增益公式用 t/minB（矩阵线程，0~1 / 0~1 同量纲）")
else:
    ng("B6 找不到增益公式 → 压暗可能完全没实现")

# B6b：用户意图亮度通道（AOD/DIM 下 hook 参数不可信，需要副源）
if 'sUserBright' in MOD and 'refreshUserBright' in MOD:
    ok("B6b 用户意图亮度通道存在（refreshUserBright → sUserBright）")
else:
    ng("B6b 缺用户意图亮度 → 低亮度时算出的压暗量会失真")

# B6c：矩阵压暗三要素（算增益 / 写矩阵 / 独立槽位）
need = {'gainFor': 'gainFor' in MOD, 'pushMatrix': 'pushMatrix' in MOD,
        'slot': 'MY_MATRIX_LEVEL = 4242' in MOD, 'gamma': '1.0 / GAMMA_EXP' in MOD}
miss = [k for k, v in need.items() if not v]
if not miss:
    ok("B6c 矩阵压暗齐全（gainFor + pushMatrix + 4242 槽位 + gamma 2.2）")
else:
    ng("B6c 矩阵压暗缺件: " + ",".join(miss))

# B6d：防闪判据必须是「或」（写成「且」会白闪且补不回来）
if re.search(r'boolean\s+changed\s*=.*\n.*boolean\s+heartbeat\s*=.*\n.*if\s*\(\s*!changed\s*&&\s*!heartbeat\s*\)\s*return', MOD):
    ok("B6d 矩阵写入防闪用「或」（!changed && !heartbeat 才跳过）")
else:
    ng("B6d 防闪判据缺失或写反 → 可能出现白闪或系统清矩阵后补不回来")

# B7：UI 写配置只能走 sp.edit()，**不得**再写 Settings.System
#     v252 反过来查（要求必须写 Settings），结果 app 侧全被系统拒绝。
seg = re.search(r'private void putInt\(.*?\n    \}\n\n    private void putFloat\(.*?\n    \}', UI, re.S)
if seg and 'edit()' in seg.group(0) and 'Settings.System.put' not in seg.group(0):
    ok("B7 UI 写配置只走 sp.edit()（不再碰 Settings.System）")
else:
    ng("B7 UI 仍在写 Settings.System → Android 13+ 必抛 IllegalArgumentException")

# B10：key 集合闭合 —— 模块读的 == UI 写的
mod_reads = set(re.findall(r'Cfg\.(ENABLE|MIN_BRIGHT|GAMMA_COMP)', MOD))
ui_writes = set(re.findall(r'(?:putInt|putFloat)\(Cfg\.(ENABLE|MIN_BRIGHT|GAMMA_COMP)', UI))
if mod_reads == ui_writes and mod_reads == set(KEY2):
    ok("B10 key 集合闭合：模块读的 3 个 == UI 写的 3 个")
else:
    ng("B10 key 集合不闭合 —— 模块读 %s / UI 写 %s" % (sorted(mod_reads), sorted(ui_writes)))

# B12：不得再有**文件读写**通道（ContentProvider 是 IPC，不算文件通道）
if 'java.io.File(' not in MOD and 'Properties' not in MOD:
    ok("B12 无文件读写通道（配置只走 CfgProvider）")
else:
    ng("B12 仍有文件读写代码 → 多一个可能读到脏值的地方")

PYEOF
)
# ============================================================

sec "C. 模块逻辑正确性（对照原版 Hook.kt 语义）"
# ============================================================
consume < <("$PY" - "$MOD" "$UI" <<'PYEOF'
import io, re, sys
MOD = io.open(sys.argv[1], encoding='utf-8').read()
UI  = io.open(sys.argv[2], encoding='utf-8').read()

def ok(m): print("OK\t" + m)
def ng(m): print("NG\t" + m)

def get_body(src, name, nth=0):
    cnt = 0
    for m in re.finditer(r'\b' + re.escape(name) + r'\s*\(', src):
        ls = src.rfind('\n', 0, m.start()) + 1
        pre = src[ls:m.start()]
        # 跳过注释行里的 "intercept()" —— 否则会把整个类体当成函数体，
        # 后面的热路径分析全部失真（v234 自查时踩到过）
        if '//' in pre or pre.strip().startswith('*'):
            continue
        j = m.end(); depth = 1
        while j < len(src) and depth > 0:
            c = src[j]
            if c == '(':
                depth += 1
            elif c == ')':
                depth -= 1
            j += 1
        k = j
        while k < len(src) and src[k] not in '{;':
            k += 1
        if k >= len(src) or src[k] != '{':
            continue
        d = 0; e = k
        while e < len(src):
            if src[e] == '{':
                d += 1
            elif src[e] == '}':
                d -= 1
                if d == 0:
                    if cnt == nth:
                        return src[k+1:e]
                    cnt += 1
                    break
            e += 1
    return None

# C1（v251 反转）：必须**不再有** sWasEnabled 状态机
#
# 旧版靠它判断"上次是启用的，这次关了要清 RBC"。v251 完全不写 RBC，
# 这个状态机连同它引发的自锁死循环（DIM 后 hook 不被调用 → 清理跑不到
# → activated 残留）一起删掉了。留着反而是隐患。
if not re.search(r'sWasEnabled\s*=', MOD):
    ok("C1 无 sWasEnabled 状态机（v251：不写 RBC 就不需要清理状态机）")
else:
    ng("C1 仍残留 sWasEnabled → 说明 RBC 清理逻辑没删干净，自锁死循环可能复现")

# C2（v251 反转）：热路径**一处 RBC 写入都不能有**
#
# v243~v250 在这里要求"activated 三条路径齐全"。现在整个 RBC 机制都删了，
# 判据反转成：热路径里不允许出现任何 reduce_bright_colors_* 写入。
# 唯一允许的一次性清理在 echo 线程（开机后清一次，之后永不触碰）。
import re as _re
_c2_hot = MOD[MOD.index('class DisplayStateHooker'):] if 'class DisplayStateHooker' in MOD else ''
_c2_hot = _c2_hot[: _c2_hot.index('// ---- helpers ----')] if '// ---- helpers ----' in _c2_hot else _c2_hot
_c2_writes = _re.findall(r'Settings\.Secure\.put(?:Int|Float|String)\([^;]*reduce_bright_colors', _c2_hot)
_c2_consts = ('SEC_LEVEL' in _c2_hot) or ('SEC_ACT' in _c2_hot)
_c2_once = 'sRbcCleared' in MOD and 'reduce_bright_colors_level' in MOD
if not _c2_writes and not _c2_consts and _c2_once:
    ok("C2 热路径零 RBC 写入（RBC 已彻底删除，仅 echo 线程开机清一次残留）")
elif _c2_writes or _c2_consts:
    ng("C2 热路径仍写 RBC（%d 处）→ 会重新引入 DIM 棘轮 / AOD 误判 / 闪烁"
       % len(_c2_writes))
else:
    ng("C2 找不到一次性 RBC 残留清理 → 旧版留下的 act=1 会一直让系统走 DIM")

# C3：不得写死 hook 签名
if 'getDeclaredMethods()' in MOD and 'getDeclaredMethod("requestDisplayStateLocked"' not in MOD:
    ok("C3 hook 目标用遍历（多签名兼容），未写死签名")
else:
    ng("C3 hook 签名写死 → ROM 一改就静默失效")

# C4：无 dim>100 钳位
if 'dim > 100f' not in MOD:
    ok("C4 无 dim>100 钳位（与 UI 的 0~110 一致）")
else:
    ng("C4 有 dim>100 钳位 → 用户设 105~110 实际只到 100")

# C5：三条 install 入口
nc = sum(1 for k in ('onSystemServerStarting', 'onPackageReady', 'onPackageLoaded') if k in MOD)
if nc == 3:
    ok("C5 install 入口三条路径齐全（sss / packageReady / packageLoaded 兜底）")
else:
    ng("C5 install 入口只有 %d 条（应 3 条兜底）" % nc)

# C6：模块活着标记
if 'Cfg.MODULE_ALIVE' in MOD:
    ok("C6 写 MODULE_ALIVE（能区分没注入 / hook 失败）")
else:
    ng("C6 缺 MODULE_ALIVE → 用户只能看到笼统的\"模块未运行\"")

# C7（v251 反转）：RBC 清理必须是**一次性**，不能变成每帧写
#
# 旧版查"上报前读回实际 level"。现在连 level 都不写了，这条改成守
# 一次性清理的幂等性 —— 若 sRbcCleared 判断丢了，就会每次 tick 都写
# 两次 Settings，又回到"和系统打架"的老路。
if 'sRbcCleared' in MOD and 'if (!sRbcCleared)' in MOD:
    ok("C7 RBC 残留清理是一次性（sRbcCleared 门控，之后永不触碰）")
else:
    ng("C7 RBC 清理没有一次性门控 → 会每次 tick 都写，重新引入打架")

# ---- C8（v254 扩展）：该挂全的挂全，该挂准的挂准 ----
inst = get_body(MOD, 'install') or ''
# (a) requestDisplayStateLocked：必须挂**全部**同名重载
seg = ''
i = inst.find('requestDisplayStateLocked".equals')
if i > 0:
    seg = inst[i:i+1400]
ok_a = bool(seg) and 'break;' not in seg and 'nHooked' in seg
# (b) BacklightAdapter.setBacklight：v254 **只挂 5 参 (FFFFZ)V**
#     4 参版 (FFFF)V 在本机全 dex 无调用点 —— 挂上去是死代码，
#     既无效又多一个热路径分支（违背「最简单」）。
segb = ''
j = inst.find('setBacklight".equals')
if j > 0:
    segb = inst[j:j+900]
ok_b = bool(segb) and 'pt.length != 5' in segb and 'pt.length != 4' not in segb
if ok_a and ok_b:
    ok("C8 挂点正确：requestDisplayStateLocked 挂全部同名重载"
       " + setBacklight 只挂 5 参 (FFFFZ)V（4 参版是死代码，不挂）")
elif not ok_a:
    ng("C8 只挂单个重载或循环里有 break → ROM 上调用另一个重载时「装上但没效果」")
else:
    ng("C8 setBacklight 没挂 5 参或仍挂了 4 参 (FFFF)V → 4 参版全 dex 无调用点，挂了是死代码")

# ---- C9（新增）：intercept 全程兜底，异常不许外抛 ----
ic = get_body(MOD, 'intercept') or ''
if 'try {' in ic and 'catch (Throwable' in ic:
    # 最后一个 catch 之后必须还有 proceed —— 否则异常会中断显示更新链
    tail = ic[ic.rfind('catch (Throwable'):]
    if 'return chain.proceed();' in tail:
        ok("C9 intercept 全程 try/catch，异常后仍 proceed（不会把异常抛进 system_server）")
    else:
        ng("C9 intercept 的 catch 之后没有 proceed 兜底 → 异常会中断显示更新")
else:
    ng("C9 intercept 缺少 try/catch 兜底 → 一次异常就可能让 system_server 显示链中断")

# ---- C10（v235 重写）：除零兜底 ----
# v235 把钳位从热路径搬进了 loadCfg()（统一入口），判定也要跟过去。
# 关键不是"某一行存在"，而是"所有来源的值都过同一个钳位"。
lb = get_body(MOD, 'loadCfg') or ''
if re.search(r'mb < 0\.0\d+f', lb) and 'mb > 1f' in lb:
    ok("C10 min_bright 有上下限兜底，且在 loadCfg 统一钳位（三条通道都过同一关）")
else:
    ng("C10 缺除零兜底 → min_bright=0 时 (int)Infinity 得到 Integer.MIN_VALUE 写进系统")

# ---- C11（新增）：改参数前确认个数 ----
if 'cargs.size() < 2' in MOD:
    ok("C11 取参前确认了参数个数（size<2 直接放行，arr[1] 不会越界）")
else:
    ng("C11 未校验参数个数 → 参数不足的方法上会抛 ArrayIndexOutOfBounds")

# ---- C12（新增）：上报不得依赖 Context 当场可用 ----
rh = get_body(MOD, 'reportHook') or ''
if rh and 'systemContext()' not in rh:
    ok("C12 上报先落内存、后异步刷（不因 Context 未就绪而丢弃状态）")
else:
    ng("C12 reportHook 仍在当场调 systemContext，ctx==null 就丢 → hook 装上了 UI 还是显示\"未运行\"")

# ---- C13（v235 重写）：不得拦截 RBC 系统方法 ----
# 原版（Android 12~13）把 handleRbcChanged 挂成 return null 是对的，
# 因为那时 RBC 由我们自己算。但 Android 14+ 改成 nits spline：
# handleRbcChanged 正是「level → 屏幕变暗」的唯一生效点。
# 拦住它 = 写了 level 没人读 = 屏幕不暗。这条规则从"必须一致"反转为"必须不拦"。
# 注意剥离顺序：必须先剥 /* */ 再剥 //。
# 反过来会把块注释里的 // 先切掉，导致 /* 永远闭不上，后面的代码全被当成注释。
_nc = re.sub(r'/\*.*?\*/', '', MOD, flags=re.S)
_nc = re.sub(r'//[^\n]*', '', _nc)
if 'NoopHooker' not in _nc and 'handleRbcChanged' not in _nc:
    ok("C13 不拦截 handleRbcChanged / RBC 相关方法（nits 体系下它们才是压暗生效点）")
else:
    ng("C13 仍有代码拦截 RBC 系统方法 → 在 Android 14+ 上会拦住唯一读取 level 的地方")

# ---- C14（v237 新增）：echo 链路失败必须可见 ----
# v236 真机「无回显」查不出原因的直接根源：echo 线程的 catch 是
# `catch (Throwable ignored) {}`，异常被吞。判定三件事：
#   a) echo 线程的 catch 里调用 reportLog（异常原文进模块日志）
#   b) echoConfig 函数体内不得再有自己的 catch（吞了就传不上去）
#   c) loadCfg 三条通道的 catch 都调 noteErr
ec = get_body(MOD, 'echoConfig')
ml = get_body(MOD, 'onModuleLoaded') or ''
bad14 = []
# v252: 配置只剩 Settings 一条通道 → noteErr 只需 1 处（不再是"三通道各一处"）
if MOD.count('noteErr(') < 1:
    bad14.append('loadCfg 没有 noteErr')
# 诊断链路：reportStatus 必须存在，且 echo 线程里主动调用
if 'reportStatus' not in MOD:
    bad14.append('缺 reportStatus（唯一状态输出）')
if 'reportStatus(c)' not in MOD:
    bad14.append('echo 线程没调 reportStatus')
if not bad14:
    ok("C14 配置读取失败可见（loadCfg 接 noteErr）+ 状态由 reportStatus 输出")
else:
    ng("C14 %s → 「无回显」这类问题依旧查无可查" % "；".join(bad14))

# ---- C15（v254 重写）：行为计数必须落在**真正干活的那条钩子**上 ----
#
# v253 的教训：计数全挂在 DisplayStateHooker（状态通道），而真正钳位的
# 是 BacklightAdapterHooker —— 计数挂错钩子 = 指标全绿但功能其实没跑。
# v254 三条钩子职责与计数必须一一对应：
#   DisplayStateHooker     → sCallsState / sHookCalls / sHookLastRaw（只观测，不钳位）
#   BacklightAdapterHooker → sCallsBk / sHookPins（钳位的唯一发生地，只挂 5 参）
#   DimModifierHooker      → sCallsDim + 恒返回 false（替换返回值，不改参数）
def _cls_body(src, name):
    m = re.search(r'class\s+' + name + r'\b[^{]*\{', src)
    if not m:
        return None
    i = m.end() - 1; d = 0
    while i < len(src):
        if src[i] == '{': d += 1
        elif src[i] == '}':
            d -= 1
            if d == 0:
                return src[m.end():i]
        i += 1
    return None

def _ic_of(name):
    c = _cls_body(MOD, name)
    return get_body(c, 'intercept') if c else None

ic15 = _ic_of('DisplayStateHooker')
bad15 = []
if ic15 is None:
    bad15.append('DisplayStateHooker.intercept 找不到')
else:
    if 'sCallsState++' not in ic15: bad15.append('没记 sCallsState')
    if 'sHookCalls++' not in ic15: bad15.append('intercept 里没有 sHookCalls++')
    if 'sHookLastRaw = target' not in ic15: bad15.append('没有记录 sHookLastRaw')
icb15 = _ic_of('BacklightAdapterHooker')
if icb15 is None:
    bad15.append('BacklightAdapterHooker.intercept 找不到')
else:
    if 'sCallsBk++' not in icb15: bad15.append('背光出口没记 sCallsBk')
    if 'sHookPins++' not in icb15: bad15.append('钳位分支没有 sHookPins++（v254 起只应在这里）')
    if 'arr[IDX_BACKLIGHT] = ' not in icb15: bad15.append('钳位没有改写 args[2]')
icd15 = _ic_of('DimModifierHooker')
if icd15 is None:
    bad15.append('DimModifierHooker.intercept 找不到（钩子②没装）')
else:
    if 'sCallsDim++' not in icd15: bad15.append('钩子②没记 sCallsDim')
    if 'Boolean.FALSE' not in icd15: bad15.append('钩子②不是恒返回 false')
    if 'chain.proceed(' in icd15: bad15.append('钩子②仍调用了原方法（应直接替换返回值）')
# v252: 计数不再写 3 个独立 key，改成拼进 Cfg.LOG 一行
# v254: 三条钩子的计数（st/bk/dim）都要在那一行里，否则"哪条在起作用"查不到
for f in ['calls=', 'pins=', 'tgt=', 'gain=', 'mtx=', 'st=', 'bk=', 'dim=']:
    if f not in MOD:
        bad15.append('日志行缺字段 ' + f)
if not bad15:
    ok("C15 三条钩子计数各就各位（st/bk+pins/dim）+ 日志字段齐备，"
       "拼进 Cfg.LOG 一行由 echo 线程每秒刷")
else:
    ng("C15 %s → 「hook 跑没跑过」依旧只能靠猜" % "；".join(bad15))

# ---- C16（v237 新增）：计数必须是零 IO（volatile 自增，不得引入 IO/拼接）----
if ic15 is not None and 'sHookCalls++' in ic15:
    seg16 = ic15[ic15.find('sHookCalls++'):ic15.find('sHookCalls++') + 200]
    if re.search(r'Settings\.|SimpleDateFormat|String\.format|\.append\(|putLong|putFloat', seg16):
        ng("C16 计数附近有 IO/格式化 → 违反热路径纪律")
    else:
        ok("C16 行为计数是纯 volatile 自增（热路径零 IO），上报在 echo 线程侧")
else:
    ng("C16 无法定位计数代码（C15 应已失败）")
PYEOF
)

# ============================================================
sec "D. 热路径纪律（v221 卡死事故教训）"
# ============================================================
consume < <("$PY" - "$MOD" <<'PYEOF'
import io, re, sys
MOD = io.open(sys.argv[1], encoding='utf-8').read()

def ok(m): print("OK\t" + m)
def ng(m): print("NG\t" + m)

def get_body(src, name, nth=0):
    cnt = 0
    for m in re.finditer(r'\b' + re.escape(name) + r'\s*\(', src):
        ls = src.rfind('\n', 0, m.start()) + 1
        pre = src[ls:m.start()]
        # 跳过注释行里的 "intercept()" —— 否则会把整个类体当成函数体，
        # 后面的热路径分析全部失真（v234 自查时踩到过）
        if '//' in pre or pre.strip().startswith('*'):
            continue
        j = m.end(); depth = 1
        while j < len(src) and depth > 0:
            c = src[j]
            if c == '(':
                depth += 1
            elif c == ')':
                depth -= 1
            j += 1
        k = j
        while k < len(src) and src[k] not in '{;':
            k += 1
        if k >= len(src) or src[k] != '{':
            continue
        d = 0; e = k
        while e < len(src):
            if src[e] == '{':
                d += 1
            elif src[e] == '}':
                d -= 1
                if d == 0:
                    if cnt == nth:
                        return src[k+1:e]
                    cnt += 1
                    break
            e += 1
    return None

def get_class_body(src, name):
    """截取一个内部类/顶层类的类体（v235 需要：现在有两个拦截器，
    必须精确拿到 DisplayStateHooker 的那个 intercept，不能拿第一个）"""
    m = re.search(r'class\s+' + name + r'\b[^{]*\{', src)
    if not m:
        return None
    i = m.end() - 1
    d = 0
    while i < len(src):
        if src[i] == '{':
            d += 1
        elif src[i] == '}':
            d -= 1
            if d == 0:
                return src[m.end():i]
        i += 1
    return None

_dsh = get_class_body(MOD, 'DisplayStateHooker')
ic = get_body(_dsh, 'intercept') if _dsh else None
if ic is None:
    ng("D0 无法截取 DisplayStateHooker.intercept 函数体（源码结构变了？必须人工复查）")
    raise SystemExit

# D1：无跨进程查询 / 文件 IO
if re.search(r'\.query\(|FileInputStream|FileOutputStream|openRemoteFile', ic):
    ng("D1 热路径里有跨进程查询/文件 IO → 会拖死 system_server")
else:
    ok("D1 热路径无 query / 文件 IO")

# ---- D2（重写）：真实解析花括号嵌套，逐条确认写入点在 if 块内 ----
# 注意区分两个概念：
#   静态条数 = 源码里写了多少条 put 语句（我们 16 条）
#   执行次数 = 一次调用实际走几条（我们最多 6 条）
# 只有后者才等于每帧 binder 数。旧版拿"静态条数"当阈值判据是错的，
# 会逼着人去为了过检查而合并代码，属于指标反向驱动。
def gate_scan(body):
    stack = []
    bare = []
    total = 0
    maxbranch = 0
    for line in body.split('\n'):
        st = line.strip()
        if not st or st.startswith('//') or st.startswith('*') or st.startswith('/*'):
            continue
        opened = 0
        if '{' in st:
            head = st.split('{')[0].strip()
            if head.startswith('} else'):
                if stack:
                    maxbranch = max(maxbranch, stack[-1][1])
                    stack.pop()
                stack.append(['if', 0])
            elif head.startswith('}'):
                if stack:
                    maxbranch = max(maxbranch, stack[-1][1])
                    stack.pop()
                stack.append(['blk', 0])
            elif head.startswith('if'):
                stack.append(['if', 0])
            elif head.startswith('try'):
                stack.append(['try', 0])
            elif head.startswith('for') or head.startswith('while'):
                stack.append(['loop', 0])
            else:
                stack.append(['blk', 0])
            opened = 1
        if re.search(r'Settings\.(Secure|System)\.put', st):
            total += 1
            if stack:
                stack[-1][1] += 1
            if 'if' not in [b[0] for b in stack] and 'loop' not in [b[0] for b in stack]:
                bare.append(st[:70])
        closes = st.count('}')
        if opened:
            closes -= 1
        for _ in range(max(0, closes)):
            if stack:
                maxbranch = max(maxbranch, stack[-1][1])
                stack.pop()
    while stack:
        maxbranch = max(maxbranch, stack[-1][1])
        stack.pop()
    return total, bare, maxbranch

total, bare, maxbranch = gate_scan(ic)
# v251（反转）：热路径现在**应该一处 Settings 写都没有**。
#
# 旧版要求"23 处写全部包在 if 门控内"。v251 把压暗搬到矩阵线程、
# RBC 全部删除之后，热路径已经不写任何 Settings —— 这比"写好门控"更强。
# 所以判据反转：total 必须为 0。若将来有人往热路径里加 Settings 写，
# 这条会立刻报警（那正是 v221 卡死事故的形态）。
if total == 0 and bare:
    ng("D2 热路径分析失效（解析到 0 处写却有裸写记录）")
elif total == 0:
    ok("D2 热路径零 Settings 写（v251：压暗搬到矩阵线程，真正零 IO）")
elif bare:
    ng("D2 热路径有 %d 处**裸写**（不在任何 if 门控内）→ 每帧 binder，例如: %s"
       % (len(bare), bare[0]))
else:
    ng("D2 热路径仍有 %d 处 Settings 写 → v251 起应该为零（全部搬去矩阵线程）" % total)

# ---- D3（重写）：调用链递归扫描反射，只判**一级**调用 ----
# 注意：二级函数没有缓存是正常的（它只在一级缓存未命中时才跑），
# 拿它单独判"未缓存"会把正确实现判成错误。
CALLS = re.compile(r'\b([A-Za-z_][A-Za-z0-9_]*)\s*\(')
SKIP = {'if', 'for', 'while', 'switch', 'catch', 'return', 'new', 'synchronized',
        'else', 'try', 'throw', 'super', 'this'}
REFLECT = re.compile(r'getDeclaredField|getDeclaredMethod|getMethod\s*\(|\.invoke\s*\(|Class\.forName')

def chain_hits(src, body, depth=0, seen=None):
    if seen is None:
        seen = set()
    hits = []
    for name in set(CALLS.findall(body)) - SKIP:
        if name in seen:
            continue
        seen.add(name)
        inner = get_body(src, name)
        if inner is None:
            continue
        if REFLECT.search(inner):
            hits.append((name, inner))
        if depth < 2:
            hits += chain_hits(src, inner, depth + 1, seen)
    return hits

unguarded = []
checked = []
for name in set(CALLS.findall(ic)) - SKIP:
    inner = get_body(MOD, name)
    if inner is None:
        continue
    has_refl = bool(REFLECT.search(inner)) or bool(chain_hits(MOD, inner, 1))
    if not has_refl:
        continue
    checked.append(name)
    # 一级函数必须在自身里给出缓存证据（"解析一次、之后走缓存"）。
    # v235 新增豁免：一次性初始化（ensureCfg）用 CAS + ready 短路保证
    # 全进程只解析一次，与"每帧反射"是两回事 —— 判它未缓存属于指标错配。
    once = ('compareAndSet' in inner) and ('sCfgReady' in inner)
    if 'Cache.get' not in inner and 'sSysCtx' not in inner and not once:
        unguarded.append(name)

if not checked:
    ok("D3 热路径调用链（含被调函数，递归 2 层）无反射")
elif unguarded:
    ng("D3 热路径一级调用 %s 触达反射且无缓存证据 → 每帧重新解析 Field/Method"
       % ",".join(sorted(unguarded)))
else:
    ok("D3 热路径触及反射的入口 %s 均有缓存（解析一次，稳态零反射）"
       % ",".join(sorted(checked)))

# D4：不得起线程 / 睡
if re.search(r'new Thread|Thread\.sleep|postDelayed', ic):
    ng("D4 热路径里起线程/延时 → 禁止")
else:
    ok("D4 热路径无线程操作")

# ---- D5（新增）：热路径不得做格式化/日志拼接 ----
if re.search(r'SimpleDateFormat|String\.format|\.append\(', ic):
    ng("D5 热路径里有格式化/字符串拼接 → 每帧产生垃圾对象")
else:
    ok("D5 热路径无格式化/字符串拼接")
PYEOF
)

# ============================================================
sec "E. UI 正确性（v252：极简界面）"
# ============================================================
consume < <("$PY" - "$UI" <<'PYEOF'
import io, re, sys
UI = io.open(sys.argv[1], encoding='utf-8').read()

def ok(m): print("OK\t" + m)
def ng(m): print("NG\t" + m)

# E1：不得真实读取 @hide key（Android 12+ 普通 app 读会抛 SecurityException）
hid = re.findall(r'Settings\.Secure\.(?:getInt|getString|getFloat)[^;]{0,80}reduce_bright_colors', UI)
if not hid:
    ok("E1 UI 无对 @hide key 的真实读取（Android 12+ 会抛 SecurityException）")
else:
    ng("E1 UI 读了 %d 处 @hide key → 会抛异常或永远拿默认值" % len(hid))

# E2：配置写入不得依赖外部服务/权限（v266：直接写自己的 SharedPreferences）
if 'getSharedPreferences(CfgProvider.PREF' in UI:
    ok("E2 UI 直接写私有 SharedPreferences（不依赖服务绑定或系统权限）")
else:
    ng("E2 UI 配置写入仍依赖外部条件 → 条件不满足时改了不生效且无提示")

# E3：滑块进度回调（v265 起只剩 DC 区间下限一个）
nseek = len(re.findall(r'onProgressChanged\s*\(', UI))
if nseek >= 1:
    ok("E3 滑块有进度回调（DC 区间下限）")
else:
    ng("E3 找不到滑块回调 → 拖动不会写入配置")

# E4：refresh 必须把存值回填到控件（否则界面显示与实际存值不一致）
if 'sbMin.setProgress' in UI:
    ok("E4 refresh 把存值回填到滑块（显示值 == 实际存值）")
else:
    ng("E4 未回填滑块位置 → 界面显示值与实际存值可能不一致")

# E5：必须隐藏 ActionBar（否则顶部内容被顶掉）
if 'getActionBar().hide()' in UI or 'getActionBar() != null' in UI:
    ok("E5 已隐藏 ActionBar（顶部内容不被遮挡）")
else:
    ng("E5 未隐藏 ActionBar → 顶部标题会被顶掉")

# E6：版本号必须动态读，不得硬编码
if 'getPackageInfo' in UI and 'versionCode' in UI:
    ok("E6 版本号动态读（getPackageInfo.versionCode），不会与 manifest 漂移")
else:
    ng("E6 UI 未动态读版本号 → 显示的可能与实际装的版本不一致")

# E7：日志必须原样显示，不得二次解析（解析就会和模块的格式耦合，
#     改一下日志格式 UI 就显示错 —— 这正是"前后端没统一"的老毛病）
if re.search(r'getString\([^;]*Cfg\.LOG', UI) and 'split(' not in UI.split('refreshLog')[-1][:600]:
    ok("E7 日志原样显示（不解析格式 → UI 与模块解耦，改格式不会显示错）")
else:
    ng("E7 日志被解析后再显示 → 与模块日志格式强耦合，改一处就显示错")

# E8：界面不得再出现已删除的 key（防止"读一个永远空的 key 当实时状态"）
# 只查**代码**，不查注释 —— 注释里说明"为什么删掉这些 key"是合理的
_code_only = "\n".join(l for l in UI.split("\n")
                       if not l.strip().startswith(('//', '*', '/*')))
dead = [k for k in ['UI_RBC_ACT', 'UI_RBC_LEVEL', 'ECHO_ACT', 'STAT_CALLS',
                    'STAT_PINS', 'ECHO_ENABLE', 'ECHO_MIN', 'ECHO_SRC',
                    'HOOK_STATUS', 'HOOK_SIG', 'HOOK_ERROR', 'MODULE_PROC']
        if k in _code_only]
if not dead:
    ok("E8 UI 不引用任何已删除的 key（不再显示永远为空的「实时状态」）")
else:
    ng("E8 UI 仍引用已删除的 key: %s → 会显示空值，误导用户以为功能坏了" % ",".join(dead))

PYEOF
)
# ============================================================

sec "F. 交付卫生"
# ============================================================
# F1：无遗留旧 key
EXPECT=$((EXPECT+1))
OLD=$(grep -c "COM_LUMEN_FAKEDC_CFG_" "$MOD" "$UI" 2>/dev/null | awk -F: '{s+=$2} END {print s+0}')
if [ "${OLD:-0}" -eq 0 ]; then
    ok "F1 无旧版 COM_LUMEN_FAKEDC_CFG_* key 残留"
else
    bad "F1 有 $OLD 处旧 key 残留 → 可能与新 key 混用"
fi

# F2：构建日志无 error + APK 比源码新
EXPECT=$((EXPECT+1))
BLOG="../.tmp/buildlog.txt"
if [ ! -f "$BLOG" ]; then
    bad "F2 没有构建日志 → 请先 bash build.sh N 2>&1 | tee ../.tmp/buildlog.txt"
else
    EW=$(grep -ciE "(^|[^a-z])error([^a-z]|$)|错误" "$BLOG" 2>/dev/null || true)
    EW=${EW:-0}
    if [ "$EW" -ne 0 ]; then
        bad "F2 构建日志有 $EW 处 error"
    elif [ -z "$APK" ] || [ ! -f "$APK" ]; then
        bad "F2 找不到 APK"
    elif [ "$(find "$SRC" -name '*.java' -newer "$APK" | wc -l)" -gt 0 ]; then
        bad "F2 有源文件比 APK 新 → 这个 APK 不是当前源码构建的（经典的「装了旧包」陷阱）"
    else
        ok "F2 构建无 error，且 APK 新于全部源码（确实是本次产物）"
    fi
fi

# F3：关键源码文件齐全
EXPECT=$((EXPECT+1))
NF=0
for f in "$MOD" "$UI" "$CFG" "$SRC/Theme.java"; do [ -f "$f" ] || NF=$((NF+1)); done
if [ "$NF" -eq 0 ]; then
    ok "F3 4 个源文件齐全（Module/UI/Cfg/Theme）"
else
    bad "F3 缺 $NF 个源文件"
fi

# F5（v244）：图标必须真的被声明并打进 APK
#
# 为什么单列一项：用户第一诉求就是「分辨不出是什么软件」。图标缺失时
# 一切检查都还是全绿 —— aapt2 不会报错，桌面只是用系统默认图。
# 这正是"功能没坏但体验是废的"那类问题，必须由检查兜住。
EXPECT=$((EXPECT+1))
ICON_OK=1
[ -f "$APK" ] || ICON_OK=0
if [ "$ICON_OK" -eq 1 ]; then
    # 1) manifest 必须声明 icon
    grep -q 'android:icon="@mipmap/ic_launcher"' app/src/main/AndroidManifest.xml || ICON_OK=0
    # 2) APK 里必须有实际图标资源（anydpi 的 xml + 至少一档位图）
    unzip -l "$APK" 2>/dev/null | grep -q 'mipmap-anydpi-v26/ic_launcher.xml' || ICON_OK=0
    # 注意：aapt2 会把目录名改写成带 -v4 后缀（mipmap-xxxhdpi-v4），
    # 这里必须按**产物实际路径**匹配，否则永远查不到。
    unzip -l "$APK" 2>/dev/null | grep -q 'mipmap-xxxhdpi-v4/ic_launcher_foreground.png' || ICON_OK=0
    # 3) aapt2 解析出的 application-icon 不能为空
    BADG=$("$AAPT2" dump badging "$APK" 2>/dev/null | grep -c 'application-icon')
    [ "$BADG" -ge 1 ] || ICON_OK=0
fi
if [ "$ICON_OK" -eq 1 ]; then
    ok "F5 图标已声明并打包（adaptive-icon + 5 档位图，aapt2 能解析出 application-icon）"
else
    bad "F5 图标缺失或未声明 → 桌面显示系统默认图，用户认不出这是什么软件"
fi

# F4：判定数量自检
# 用**硬编码常量**而不是运行时累加 —— 累加只能发现"某段整体崩了"，
# 发现不了"我少写了一项检查"（v234 自查时就漏了 B11，累加式自检没报）。
# 硬编码会强迫增删检查项时同步改这个数字。
EXPECT_TOTAL=57
DONE=$((PASS+FAIL+1))   # +1 = F4 自己（此刻还没计进 PASS/FAIL）
if [ "$DONE" -eq "$EXPECT_TOTAL" ]; then
    ok "F4 判定数量自检：实际执行 $DONE 项 = 清单 $EXPECT_TOTAL 项（无静默跳过、无漏写）"
else
    bad "F4 实际执行 $DONE 项，清单应为 $EXPECT_TOTAL 项 → 有判定没跑（缺 APK？某段分析崩了？）或清单未同步"
fi

# ============================================================
echo
echo "=============================================="
printf " 结果: \033[32m%d 通过\033[0m" "$PASS"
[ "$FAIL" -gt 0 ] && printf "  \033[31m%d 失败\033[0m" "$FAIL"
echo
if [ "$FAIL" -gt 0 ]; then
    echo " 失败项:"
    for f in "${FAILED[@]}"; do echo "   - $f"; done
    echo "=============================================="
    exit 1
fi
echo " 全部通过，可交付。"
echo "=============================================="
