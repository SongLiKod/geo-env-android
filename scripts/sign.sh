#!/usr/bin/env bash
# =============================================================================
# GeoEnv 签名校验 / 重签名脚本
#
# 用法：
#   scripts/sign.sh verify  [apk]        # 校验 APK 签名（默认 release 产物）
#   scripts/sign.sh resign   <apk> [out]  # 用 geoenv-release.jks 重签名 APK
#   scripts/sign.sh info     [apk]        # 打印签名者与证书信息
#
# 说明：
#   - GeoEnv 采用内部 APK 分发（不上架应用商店），统一使用
#     scripts/geoenv-release.jks（alias=geoenv）签名。
#   - 口令默认 geoenv2026，可用项目根目录 keystore.properties 覆盖：
#       storePassword=...
#       keyAlias=geoenv
#       keyPassword=...
#   - 需要 Android SDK build-tools 中的 apksigner / zipalign。
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
KS="$ROOT/scripts/geoenv-release.jks"

# ---- 读取 keystore.properties（与 app/build.gradle.kts 一致） ----
STORE_PASS="geoenv2026"
KEY_ALIAS="geoenv"
KEY_PASS="geoenv2026"
if [[ -f "$ROOT/keystore.properties" ]]; then
  # shellcheck disable=SC1091
  while IFS='=' read -r k v; do
    case "$k" in
      storePassword) STORE_PASS="$v" ;;
      keyAlias)      KEY_ALIAS="$v" ;;
      keyPassword)   KEY_PASS="$v" ;;
    esac
  done < <(grep -E '^(storePassword|keyAlias|keyPassword)=' "$ROOT/keystore.properties" || true)
fi

# ---- 定位 Android SDK build-tools ----
find_tool() {
  local name="$1"
  if command -v "$name" >/dev/null 2>&1; then command -v "$name"; return; fi
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [[ -n "$sdk" && -d "$sdk/build-tools" ]]; then
    local bt
    bt=$(ls -1 "$sdk/build-tools" | sort -V | tail -1)
    if [[ -x "$sdk/build-tools/$bt/$name" ]]; then echo "$sdk/build-tools/$bt/$name"; return; fi
  fi
  return 1
}

APKSIGNER="$(find_tool apksigner || true)"
ZIPALIGN="$(find_tool zipalign || true)"

cmd="${1:-verify}"
apk="${2:-$DEFAULT_APK}"

if [[ ! -f "$apk" ]]; then
  echo "错误：APK 不存在：$apk" >&2
  echo "提示：先执行 scripts/build.sh release" >&2
  exit 1
fi

case "$cmd" in
  verify)
    [[ -n "$APKSIGNER" ]] || { echo "错误：未找到 apksigner（请设置 ANDROID_HOME）" >&2; exit 1; }
    echo "== 签名校验：$apk =="
    "$APKSIGNER" verify --print-certs --verbose "$apk"
    echo
    echo "校验通过：APK 已用预期证书签名。"
    ;;

  info)
    [[ -n "$APKSIGNER" ]] || { echo "错误：未找到 apksigner（请设置 ANDROID_HOME）" >&2; exit 1; }
    "$APKSIGNER" verify --print-certs "$apk"
    ;;

  resign)
    [[ -n "$APKSIGNER" ]] || { echo "错误：未找到 apksigner（请设置 ANDROID_HOME）" >&2; exit 1; }
    [[ -f "$KS" ]] || { echo "错误：缺少签名库 $KS" >&2; exit 1; }
    OUT="${3:-${apk%.apk}-signed.apk}"

    # 1) zipalign（若可用）
    TMP="$OUT"
    if [[ -n "$ZIPALIGN" ]]; then
      TMP="$OUT.align"
      echo "== zipalign =="
      "$ZIPALIGN" -f -p 4 "$apk" "$TMP"
    fi

    # 2) apksigner 签名
    echo "== apksigner sign =="
    "$APKSIGNER" sign \
      --ks "$KS" \
      --ks-key-alias "$KEY_ALIAS" \
      --ks-pass "pass:$STORE_PASS" \
      --key-pass "pass:$KEY_PASS" \
      --out "$OUT" \
      "$TMP"
    [[ "$TMP" == "$OUT" ]] || rm -f "$TMP"

    # 3) 校验
    echo "== 校验 =="
    "$APKSIGNER" verify --print-certs "$OUT"
    echo
    echo "重签名完成：$OUT"
    sha256sum "$OUT"
    ;;

  *)
    echo "用法：$0 verify|info|resign [apk] [out]" >&2
    exit 1
    ;;
esac
