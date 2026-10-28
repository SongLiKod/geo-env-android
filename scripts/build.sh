#!/usr/bin/env bash
# =============================================================================
# GeoEnv 打包脚本 —— 构建 debug / release APK
#
# 用法：
#   scripts/build.sh              # 构建 release（签名）APK
#   scripts/build.sh debug        # 构建 debug APK
#   scripts/build.sh release      # 构建 release（签名）APK（同默认）
#   scripts/build.sh clean        # 先 clean 再构建 release
#
# 环境变量（可选）：
#   JAVA_HOME     JDK 17 路径（默认取系统 java）
#   ANDROID_HOME  Android SDK 路径（默认取 $ANDROID_SDK_ROOT）
#   GRADLE_HOME   Gradle 安装路径；否则用项目自带 wrapper / 系统 gradle
#
# 产物：
#   app/build/outputs/apk/debug/app-debug.apk
#   app/build/outputs/apk/release/app-release.apk   （已用 scripts/geoenv-release.jks 签名）
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

TARGET="${1:-release}"

# ---- 工具链 ----
JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
export JAVA_HOME
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -n "$ANDROID_HOME" ]]; then export ANDROID_HOME; fi

if [[ -n "${GRADLE_HOME:-}" && -x "$GRADLE_HOME/bin/gradle" ]]; then
  GRADLE="$GRADLE_HOME/bin/gradle"
elif [[ -x "$ROOT/gradlew" ]]; then
  GRADLE="$ROOT/gradlew"
elif command -v gradle >/dev/null 2>&1; then
  GRADLE="$(command -v gradle)"
else
  echo "错误：未找到 gradle。请设置 GRADLE_HOME 或提供 ./gradlew" >&2
  exit 1
fi

echo "== GeoEnv build =="
echo "  root    : $ROOT"
echo "  java    : $JAVA_HOME"
echo "  android : ${ANDROID_HOME:-<未设置>}"
echo "  gradle  : $GRADLE"
echo "  target  : $TARGET"
echo

# ---- clean ----
if [[ "$TARGET" == "clean" ]]; then
  "$GRADLE" -p "$ROOT" clean
  TARGET=release
fi

# ---- 构建 ----
case "$TARGET" in
  debug)
    "$GRADLE" -p "$ROOT" :app:assembleDebug --console=plain
    APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
    ;;
  release)
    KS="$ROOT/scripts/geoenv-release.jks"
    if [[ ! -f "$KS" ]]; then
      echo "错误：缺少签名库 $KS" >&2
      exit 1
    fi
    echo "签名库：$KS（alias=geoenv，可用 keystore.properties 覆盖口令）"
    "$GRADLE" -p "$ROOT" :app:assembleRelease --console=plain
    APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
    ;;
  *)
    echo "未知目标：$TARGET（可用 debug | release | clean）" >&2
    exit 1
    ;;
esac

# ---- 产物信息 ----
if [[ -f "$APK" ]]; then
  SIZE=$(du -h "$APK" | cut -f1)
  SHA=$(sha256sum "$APK" | cut -d' ' -f1)
  echo
  echo "构建成功："
  echo "  路径   : $APK"
  echo "  大小   : $SIZE"
  echo "  sha256 : $SHA"
else
  echo "错误：未生成 APK：$APK" >&2
  exit 1
fi
