#!/bin/bash
# 一键编译 KidLock debug APK（无需 Android Studio）
# 用法：bash build_apk.sh   或带参数：bash build_apk.sh --offline
set -e
TOOL=/home/user/android-build/.toolchain
export JAVA_HOME="$TOOL/jdk-17.0.13+11/Contents/Home"
export ANDROID_HOME="$TOOL/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

echo "JAVA_HOME=$JAVA_HOME"
"$JAVA_HOME/bin/java" -version 2>&1 | head -1
echo "ANDROID_HOME=$ANDROID_HOME"

cd "$(dirname "$0")"
GRADLE="$TOOL/gradle-8.9/bin/gradle"
if [ ! -x "$GRADLE" ]; then
  echo "未找到 gradle，请先按 README 准备工具链"; exit 1
fi
"$GRADLE" assembleDebug "$@"
echo "==== APK 输出 ===="
ls -lh app/build/outputs/apk/debug/*.apk 2>/dev/null
