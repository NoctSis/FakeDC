#!/bin/bash
# ============================================================
# 把某个版本从「暂存区」提升为「正式版」
#
# 目录约定（2026-11-08 用户定）：
#   bata/产物APK/api102系/   ← build.sh 的默认输出，随便堆，可以整体清掉
#   release/                 ← 只放**当前正式版**，随时能发给别人（仓库根目录下）
#
# 用法：
#   bash release.sh 1.0.3          # 从暂存区搬到 REA/release
#   bash release.sh 1.0.3 --build  # 先重新构建再搬
#
# 为什么要分开：2026-11-08 清理历史包时，把 work/产物APK 整个删了，
# 连当前正式版一起没了。正式版必须放在一个"清理时不会碰"的地方。
# ============================================================
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
VERNAME="${1:-}"
[ -z "$VERNAME" ] && { echo "用法: bash release.sh <versionName> [--build]"; exit 1; }

STAGE="$HERE/../bata/产物APK/api102系"
RELEASE="$HERE/../release"
NAME="FakeDC-${VERNAME}.apk"

# 可选：先构建（版本号取 manifest 里的 versionCode）
if [ "$2" = "--build" ]; then
    VCODE=$(grep -o 'android:versionCode="[0-9]*"' "$HERE/app/src/main/AndroidManifest.xml" \
            | head -1 | sed 's/[^0-9]//g')
    echo "[1/3] 构建 $VERNAME（versionCode=$VCODE）"
    bash "$HERE/build.sh" "$VCODE" "$VERNAME"
else
    echo "[1/3] 跳过构建（要重新构建加 --build）"
fi

SRC="$STAGE/$NAME"
[ -f "$SRC" ] || { echo "!! 暂存区里没有 $NAME"; echo "   先构建： bash build.sh <versionCode> $VERNAME"; exit 1; }

mkdir -p "$RELEASE"

# 只允许一个正式版：先看有没有别的
others=$(ls "$RELEASE"/*.apk 2>/dev/null | grep -v "$NAME" || true)

echo "[2/3] 复制到 REA/release"
cp -f "$SRC" "$RELEASE/$NAME"

echo "[3/3] 校验"
SIZE=$(stat -c%s "$RELEASE/$NAME")
SHA=$(sha256sum "$RELEASE/$NAME" | cut -d' ' -f1)
echo "    文件: $RELEASE/$NAME"
echo "    大小: $SIZE 字节"
echo "    sha256: $SHA"

if [ -n "$others" ]; then
    echo
    echo "    注意：release 目录里还有别的版本，正式版目录建议只留一个："
    echo "$others" | sed 's/^/      /'
fi

echo
echo "完成。这个包就是当前正式版，可以直接安装/发布。"
