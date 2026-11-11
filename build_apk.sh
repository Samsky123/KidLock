#!/usr/bin/env bash
# 一键编译 KidLock（macOS / Linux 通用）
#
# 依赖：
#   - JDK 17（JAVA_HOME 指向它，或系统默认 java 就是 17）
#   - Android SDK（ANDROID_HOME 或 ANDROID_SDK_ROOT 指向它，需含 platform-34 / build-tools 34）
#
# 用法：
#   ./build_apk.sh                    # 编译 debug APK
#   ./build_apk.sh --offline          # 离线编译（依赖已缓存时）
#   ./build_apk.sh assembleRelease    # 编译 release（需 keystore.properties）
set -euo pipefail

cd "$(dirname "$0")"

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  echo "JAVA_HOME=$JAVA_HOME"
  "$JAVA_HOME/bin/java" -version 2>&1 | head -1
else
  echo "JAVA_HOME 未设置，使用系统 java：$(command -v java || true)"
fi

if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  echo "错误：请设置 ANDROID_HOME（或 ANDROID_SDK_ROOT）指向 Android SDK 目录。" >&2
  echo "例如：export ANDROID_HOME=\$HOME/Library/Android/sdk" >&2
  exit 1
fi
echo "ANDROID_HOME=${ANDROID_HOME:-$ANDROID_SDK_ROOT}"

if [ -x "./gradlew" ]; then
  GRADLE="./gradlew"
elif command -v gradle >/dev/null 2>&1; then
  GRADLE="gradle"
else
  echo "错误：未找到 Gradle。请用 Android Studio 打开本工程，或安装 Gradle 8.9。" >&2
  exit 1
fi

TASK="${1:-assembleDebug}"
if [ "$TASK" = "--offline" ]; then
  TASK="assembleDebug"
  set -- "$TASK" --offline
fi

"$GRADLE" "$@"

echo "==== APK 输出 ===="
find app/build/outputs/apk -name '*.apk' -maxdepth 4 2>/dev/null | while read -r apk; do
  ls -lh "$apk"
done
