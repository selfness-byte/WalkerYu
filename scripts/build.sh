#!/bin/zsh
# APK 빌드 (외장하드 M 의 JDK/SDK/Gradle 사용). 사용법: zsh scripts/build.sh [--install]
set -e
if [ ! -d /Volumes/MDev ]; then
  hdiutil attach /Volumes/M/DevTools/MDev.sparsebundle
fi
source /Volumes/M/DevTools/env.sh
PROJECT=/Volumes/M/Android_Transit_Map_Widget_20261022
cd "$PROJECT"
pkill -f "KotlinCompileDaemon" 2>/dev/null || true
pkill -f "GradleDaemon" 2>/dev/null || true
dot_clean -m "$PROJECT" 2>/dev/null || true
rm -rf "$NRM_BUILD_ROOT/GyeonggiTransitMap/app/tmp/kotlin-classes" 2>/dev/null || true
gradle --offline --no-daemon --project-cache-dir /Volumes/MDev/project-cache/GyeonggiTransitMap :app:assembleDebug --console=plain
mkdir -p "$PROJECT/dist"
cp "$NRM_BUILD_ROOT/GyeonggiTransitMap/app/outputs/apk/debug/app-debug.apk" "$PROJECT/dist/GyeonggiTransitMap-debug.apk"
cp "$NRM_BUILD_ROOT/GyeonggiTransitMap/app/outputs/apk/debug/app-debug.apk" "$PROJECT/dist/WalkerYu-debug.apk"
cp "$NRM_BUILD_ROOT/GyeonggiTransitMap/app/outputs/apk/debug/app-debug.apk" "$PROJECT/dist/WalkerYu-1.0.0.apk"
cp "$NRM_BUILD_ROOT/GyeonggiTransitMap/app/outputs/apk/debug/app-debug.apk" "$PROJECT/dist/WalkerYu-1.0.3.apk"
echo "APK: $PROJECT/dist/WalkerYu-1.0.3.apk"
if [ "$1" = "--install" ]; then
  adb install -r "$PROJECT/dist/WalkerYu-1.0.3.apk"
  adb shell am start -n com.mj.transitmap/.ui.MainActivity
fi
