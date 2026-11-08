#!/bin/bash
# ============================================================
# fakedc-api102 构建脚本（新 API 102 主力线，v213 起）
#
# 用法：bash build.sh [版本号]        默认 213
# 产物：../work/产物APK/api102系/FakeDC-<versionName>.apk
#
# 管线：javac → d8 → aapt2 compile/link → zipfile 追加 → zipalign → apksigner
#
# ⚠️ 两个踩过的坑（改这个脚本前必读）：
#   1) aapt2 的 -A 参数会把文件塞进 assets/ 且路径带反斜杠，
#      LSPosed 读不到 META-INF/xposed/* → 模块直接不生效。
#      必须用 zipfile 把 classes.dex 与 META-INF/xposed/* 追加到 APK 根目录。
#   2) 版本号写在 app/src/main/AndroidManifest.xml（versionCode/versionName），
#      本脚本不自动改 —— 升版本要先改 manifest。
# ============================================================
set -e

# 脚本所在目录（后面所有相对路径都基于它，不依赖调用者的 cwd）
HERE="$(cd "$(dirname "$0")" && pwd)"

VER="${1:-213}"
# v274: 第二个参数可指定 versionName（如 1.0.0），默认 0.5.VER
VNAME="${2:-0.5.${VER}}"
# 工具链路径：可用环境变量覆盖（默认值是本机布局，别人在自己机器上请按需设置）
JDK="${JDK_PATH:-/d/Program Files/Java/jdk-21.0.12.1+1}"
AJ="${ANDROID_JAR:-D:/Android/sdk/platforms/android-37/android.jar}"
BT="${BUILD_TOOLS:-D:/Android/sdk/build-tools/34.0.0}"
R8="${R8_JAR:-D:/Android/sdk/cmdline-tools/latest/lib/r8.jar}"
for f in "$JDK/bin/javac.exe" "$AJ" "$BT/aapt2.exe" "$R8"; do
    [ -f "$f" ] || { echo "!! 找不到构建依赖: $f"; echo "   用 JDK_PATH / ANDROID_JAR / BUILD_TOOLS / R8_JAR 指定路径"; exit 1; }
done
# v276: 签名可用环境变量覆盖（开源后别人没有我们的密钥，私钥也不该进公开仓库）：
#   FDKS=/path/to/my.jks  FDKSPASS=******  bash build.sh 276 1.0.2
# 默认按本地开发布局找密钥（fakedc-api102/../fakedc-legacy/）；找不到就生成 debug 密钥。
KS="${FDKS:-$HERE/../fakedc-legacy/fakedc-release.jks}"
KSPASS="${FDKSPASS:-fakedc2026}"
if [ ! -f "$KS" ]; then
    # 没有密钥也要能编译：自动生成一个 debug 密钥（只够本地装，不能当正式发布签名）。
    echo "!! 找不到签名密钥: $KS"
    echo "   自动生成 debug 密钥（仅本地测试；正式发布请用 FDKS/FDKSPASS 指定自己的密钥）"
    KS="$HERE/.keystore/debug.jks"
    KSPASS="fakedcdebug"
    mkdir -p "$HERE/.keystore"
    if [ ! -f "$KS" ]; then
        "$JDK/bin/keytool" -genkeypair -v -keystore "$KS" -alias fakedc -keyalg RSA \
            -keysize 2048 -validity 36500 -storepass "$KSPASS" -keypass "$KSPASS" \
            -dname "CN=FakeDC Debug" > /dev/null 2>&1 || { echo "keytool 失败，退出"; exit 1; }
        echo "   已生成: $KS"
    fi
fi

PKG="com.lumen.fakedc"

cd "$HERE"
python -c "import shutil,os;shutil.rmtree('out',ignore_errors=True)"
mkdir -p out

# ============================================================
# v244: 生成 BuildInfo.java（版本身份，用户明确要求的能力）
#
# 为什么必须自动生成而不是手写常量：
#   手写 = 总有一天忘记改 = 界面显示的版本与 dex 实际版本不符，
#   而这个特性的全部价值就在于「能分辨装的是哪个版本」。
#   legacy 系一直是 build.sh 注入，api102 从 v213 起丢了这套。
#
# 机制：HOOK_VER 编译进模块 → 运行时写 Settings → UI 与
#   PackageInfo.versionCode 比对 → 不一致就提示「重启后生效」。
# ============================================================
BUILD_DATE="$(date +%Y-%m-%d)"
cat > app/src/main/java/com/lumen/fakedc/BuildInfo.java <<EOF
package com.lumen.fakedc;

/**
 * 本文件由 build.sh 自动生成，请勿手改。
 * 构建时间：${BUILD_DATE}
 */
public final class BuildInfo {
    /** 与 APK 的 versionCode 同步 */
    public static final int HOOK_VER = ${VER};
    /** 构建日期 */
    public static final String DATE = "${BUILD_DATE}";
    private BuildInfo() {}
}
EOF
echo "    BuildInfo.java 已生成 HOOK_VER=${VER} DATE=${BUILD_DATE}"

echo "[1/6] javac（stub + 源码 + libxposed service）"
# v225: UI 要走官方 XposedService → Remote Preferences，需要 libxposed:service
# 依赖在 libs/service-102.0.0.aar（Maven Central 下载），classes.jar 已解出。
# v225.1: 官方依赖链是 **service -> interface**（service 的 POM 里 runtime 依赖）。
# IXposedService / IXposedService$Stub / HookedProcess 等 15 个类都在
# **interface** 库里 —— 少了它，XposedProvider.call() 运行时会
# NoClassDefFoundError（v225 初版的真 bug，已修）。
LIBSVC="libs/service-classes.jar"
LIBIFC="libs/interface-classes.jar"
# v276: 两个 jar 不再入仓（二进制 + 上游 Apache-2.0 产物），首次构建自动从 Maven Central 取。
#   离线环境可手工放好 libs/service-classes.jar 与 libs/interface-classes.jar 再构建。
LIBAAR_VER="102.0.0"
ensure_lib() {
    _name="$1"
    _jar="libs/${_name}-classes.jar"
    _aar="libs/${_name}-${LIBAAR_VER}.aar"
    [ -f "$_jar" ] && return 0
    mkdir -p libs
    if [ ! -f "$_aar" ]; then
        echo "   拉取 libxposed ${_name} ${LIBAAR_VER} ..."
        curl -fsSL --max-time 90 -o "$_aar" \
            "https://repo1.maven.org/maven2/io/github/libxposed/${_name}/${LIBAAR_VER}/${_name}-${LIBAAR_VER}.aar" \
            || { echo "!! 下载失败: ${_name}（离线环境请手工放到 ${_aar}）"; return 1; }
    fi
    python - "$_aar" "$_jar" <<'EOP'
import sys, zipfile
aar, jar = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(aar) as z, open(jar, "wb") as f:
    f.write(z.read("classes.jar"))
EOP
    [ -f "$_jar" ] || { echo "!! 解包失败: $_aar"; return 1; }
}
ensure_lib service || exit 1
ensure_lib interface || exit 1
for f in "$LIBSVC" "$LIBIFC"; do
    if [ ! -f "$f" ]; then
        echo "!! 缺少 $f（libxposed 官方依赖），构建中止"; exit 1
    fi
done
"$JDK/bin/javac.exe" -nowarn -encoding UTF-8 -source 11 -target 11 \
    -cp "$AJ;$LIBSVC;$LIBIFC" -d out/classes \
    $(find stub -name '*.java') $(find app/src/main/java -name '*.java')
# 把两个官方库的 class 也放进 out/classes，稍后一起 d8
(cd out/classes && unzip -o -q "../../$LIBSVC" && unzip -o -q "../../$LIBIFC")

# ============================================================
# v233 致命修复（在下面 d8 处生效）：**api 包不进 dex** ⚠️
#
# 根因（2026-10-27 回归定位，对比 v217 与 v232 的 dex）：
#   v217（能用）dex 里 14 个类，**全部是 com.lumen.fakedc.***，没有任何
#   io.github.libxposed.* 类 —— 那些由框架在运行时提供。
#   v225 起我把整个 out/classes 打进 dex，把框架 ABI 类复制了一份进模块，
#   其中 **io.github.libxposed.api.XposedModule 是模块入口类的父类**。
#
#   后果：FakeDCModule extends XposedModule 继承的是"模块自带那份"，
#   框架检查 instanceof（它自己那份）失败 -> **模块被静默跳过，完全不被注入**。
#   表现：LSPosed 里启用了、作用域是 system，但 hook 一个都没装、日志一行没有。
#
# 所以 d8 的输入排除 api 包（用 --classpath 只解析不打包）；
# service 包保留 —— UI 是普通 app，需要它们收框架发来的 binder。
# ============================================================
echo "    service + interface 库 class 已解入 out/classes（api 包将不进 dex）"

echo "[2/6] d8 转 dex"
# v225: 连同 libxposed service 的 class 一起 d8（UI 需要它的 API）
# v233: d8 输入**排除 api 包**（只解析不打包）。
# 做法：先把 api 的 class 拷到一个只作 classpath 的目录，再让 d8 用它解析
# 父类（XposedModule 等），但输入文件里排除 api 目录 —— 这样 dex 里不会带
# 框架契约类，模块入口类才会正确继承框架那一份。
python -c "import shutil;shutil.rmtree('out/apicp',ignore_errors=True)"; mkdir -p out/apicp
if [ -d out/classes/io/github/libxposed/api ]; then
    cp -r out/classes/io/github/libxposed/api out/apicp/api
fi
"$JDK/bin/java.exe" -cp "$R8" com.android.tools.r8.D8 \
    --min-api 26 --lib "$AJ" --classpath out/apicp --output out \
    $(find out/classes -name '*.class' -not -path '*/libxposed/api/*')

echo "[3/6] aapt2 编译资源 + 链接 manifest"
# v234: **版本号唯一来源 = 命令行参数**。
# 之前 manifest 里的 versionCode/versionName 是手改的，结果 build.sh 234 产出的
# 文件名是 FakeDC-0.5.234.apk，manifest 里却还是 233 —— 升级安装时版本号不递增、
# UI 显示的版本与文件名对不上。verify.sh 的 A10 抓到了这个漂移。
if sed -i -E "s/android:versionCode=\"[0-9]+\"/android:versionCode=\"${VER}\"/; s/android:versionName=\"[^\"]*\"/android:versionName=\"${VNAME}\"/" app/src/main/AndroidManifest.xml; then
    echo "    版本号已同步 -> ${VER} / ${VNAME}"
else
    echo "    !! 版本号同步失败（aapt2 可能仍用旧版本号）"
fi
"$BT/aapt2.exe" compile --dir app/src/main/res -o out/res.zip
"$BT/aapt2.exe" link -o out/base.apk -I "$AJ" \
    --manifest app/src/main/AndroidManifest.xml \
    --min-sdk-version 26 --target-sdk-version 36 out/res.zip

echo "[4/6] 追加 classes.dex 与 META-INF/xposed/*（必须用 zipfile，见文件头坑1）"
PY="C:/Users/hhg/.workbuddy/binaries/python/versions/3.13.12/python.exe"
"$PY" - "$PKG" << 'PYEOF'
import os, sys, zipfile, shutil
pkg = sys.argv[1]
shutil.copy('out/base.apk', 'out/final-unsigned.apk')
with zipfile.ZipFile('out/final-unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    z.write('out/classes.dex', 'classes.dex')
    for f in ['java_init.list', 'module.prop', 'scope.list']:
        z.write(os.path.join('app/src/main/resources/META-INF/xposed', f),
                'META-INF/xposed/' + f)
print("    已追加 dex 与 3 个声明文件")
PYEOF

echo "[5/6] zipalign"
"$BT/zipalign.exe" -f -p 4 out/final-unsigned.apk out/final-aligned.apk

echo "[6/6] apksigner 签名"
export JAVA_HOME="$JDK"
mkdir -p "../work/产物APK/api102系"
"$BT/apksigner.bat" sign \
    --ks "$KS" --ks-pass pass:"$KSPASS" --key-pass pass:"$KSPASS" \
    --ks-key-alias fakedc \
    --out "../work/产物APK/api102系/FakeDC-${VNAME}.apk" out/final-aligned.apk

echo
echo "=== 自检 ==="
"$BT/apksigner.bat" verify "../work/产物APK/api102系/FakeDC-${VNAME}.apk" \
    && echo "签名: OK"
echo -n "scope.list: "; unzip -p "../work/产物APK/api102系/FakeDC-${VNAME}.apk" META-INF/xposed/scope.list
echo -n "java_init : "; unzip -p "../work/产物APK/api102系/FakeDC-${VNAME}.apk" META-INF/xposed/java_init.list
echo "--- module.prop / scope.list（v215 起：静态作用域 + system）---"
unzip -p "../work/产物APK/api102系/FakeDC-${VNAME}.apk" META-INF/xposed/module.prop
# 本 LSPosed 分支（lin.xposed 2.4.1）守护进程只认 system 作为系统框架标识：
# 写 android 静默无效（勾了不注入），必须写 system（设备上其他模块均为 system）。
if [ "$(unzip -p "../work/产物APK/api102系/FakeDC-${VNAME}.apk" META-INF/xposed/scope.list | tr -d '[:space:]')" != "system" ]; then
    echo "!! scope.list 必须是 system（android 在本分支静默无效），构建中止"; exit 1
fi
# 静态作用域：装好即按推荐作用域注入，不需要用户去列表里勾
if unzip -p "../work/产物APK/api102系/FakeDC-${VNAME}.apk" META-INF/xposed/module.prop | grep -q "staticScope=false"; then
    echo "!! staticScope=false 需要用户手动勾作用域，本次要求 true，构建中止"; exit 1
fi
echo -n "dex 大小 : "; unzip -l "../work/产物APK/api102系/FakeDC-${VNAME}.apk" classes.dex | tail -1 | awk '{print $1" 字节"}'

# 清中间产物，只留 APK
python -c "import os;[os.remove(f) for f in ['out/classes.dex','out/res.zip','out/base.apk','out/final-unsigned.apk','out/final-aligned.apk'] if os.path.exists(f)];import shutil;shutil.rmtree('out/classes',ignore_errors=True)"
echo
echo "完成: ../work/产物APK/api102系/FakeDC-${VNAME}.apk"
