#!/usr/bin/env bash
# 手工构建 APK（不依赖 Gradle / Android Studio）
#
# 依赖：JDK 17 + Android build-tools 34 + platform android.jar
# 用法：bash build_apk.sh [debug|release]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
MODE="${1:-debug}"
VERSION_FILE="$ROOT/../version.txt"
read -r VERSION_CODE VERSION_NAME < "$VERSION_FILE"
SDK="${ANDROID_SDK:-C:/Users/bejay/android-build/sdk}"
JAVA_HOME="${JAVA_HOME:-C:/Users/bejay/android-build/jdk-17.0.2}"
BT="$SDK/build-tools/34.0.0"
PLATFORM="${ANDROID_JAR:-$SDK/platforms/android-35/android.jar}"
SRC="$ROOT/app/src/main"
OUT="$ROOT/build-manual"
KEYSTORE="$ROOT/debug.keystore"

export JAVA_HOME
# Git Bash 的 PATH 需要 POSIX 路径，Windows 风格路径加进去命令找不到
if command -v cygpath >/dev/null 2>&1; then
  PATH="$(cygpath -u "$JAVA_HOME/bin"):$(cygpath -u "$BT"):$PATH"
else
  PATH="$JAVA_HOME/bin:$BT:$PATH"
fi
export PATH

AAPT2="$BT/aapt2.exe"
ZIPALIGN="$BT/zipalign.exe"
APKSIGNER="$BT/apksigner.bat"
D8="$BT/d8.bat"

die() { echo "!! $*" >&2; exit 1; }

[ -x "$AAPT2" ] || die "找不到 aapt2: $AAPT2"
[ -f "$PLATFORM" ] || die "找不到 android.jar: $PLATFORM"
command -v javac >/dev/null || die "找不到 javac（JAVA_HOME=$JAVA_HOME）"

rm -rf "$OUT"
mkdir -p "$OUT/compiled" "$OUT/classes" "$OUT/dex"

echo "== 0/6 生成课程库 =="
python "$ROOT/../tools/gen_courses.py" "$ROOT/../courses/recommended.json" \
  "$SRC/java/com/xq/treadmill/CourseLibrary.java"

echo "== 0.5/6 协议单测 =="
mkdir -p "$OUT/proto"
javac -encoding UTF-8 -d "$OUT/proto" \
  "$SRC/java/com/xq/treadmill/TreadmillClient.java" \
  "$ROOT/../tools/ProtoSelfTest.java" > "$OUT/proto.log" 2>&1 \
  || { iconv -f gbk -t utf-8 "$OUT/proto.log" 2>/dev/null | head -20; die "协议单测编译失败"; }
java -Dfile.encoding=UTF-8 -cp "$(cygpath -m "$OUT/proto")" com.xq.treadmill.ProtoSelfTest \
  || die "协议单测未通过"

echo "== 1/6 编译资源 =="
"$AAPT2" compile --dir "$SRC/res" -o "$OUT/compiled.zip"

echo "== 2/6 链接资源与清单 =="
# AGP 用 namespace 注入包名，独立 aapt2 需要清单里显式有 package；这里生成临时清单
mkdir -p "$OUT/manifest"
# debug 包打开 debuggable，便于 run-as 注入测试数据；release 包保持关闭
if [ "$MODE" = "debug" ]; then
  sed -e '0,/<manifest /s//<manifest package="com.xq.treadmill" /' \
      -e '0,/<application/s//<application android:debuggable="true" /' \
    "$SRC/AndroidManifest.xml" > "$OUT/manifest/AndroidManifest.xml"
else
  sed '0,/<manifest /s//<manifest package="com.xq.treadmill" /' \
    "$SRC/AndroidManifest.xml" > "$OUT/manifest/AndroidManifest.xml"
fi
"$AAPT2" link \
  -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest "$OUT/manifest/AndroidManifest.xml" \
  --min-sdk-version 21 \
  --target-sdk-version 34 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  -R "$OUT/compiled.zip" \
  --auto-add-overlay

echo "== 3/6 编译 Java =="
find "$SRC/java" -name "*.java" | while read -r f; do cygpath -m "$f"; done > "$OUT/sources.txt"
if ! javac -encoding UTF-8 -source 8 -target 8 \
  -bootclasspath "$PLATFORM" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1; then
  echo "-- javac 报错 --"
  iconv -f gbk -t utf-8 "$OUT/javac.log" 2>/dev/null | grep -v "^注:" | head -40 \
    || head -40 "$OUT/javac.log"
  die "Java 编译失败"
fi

[ -f "$OUT/classes/com/xq/treadmill/MainActivity.class" ] || die "javac 没有产出 MainActivity.class"

echo "== 4/6 dex =="
find "$OUT/classes" -name "*.class" | while read -r f; do cygpath -m "$f"; done > "$OUT/classes.txt"
"$D8" --min-api 21 --lib "$PLATFORM" --output "$OUT/dex" @"$OUT/classes.txt"
[ -f "$OUT/dex/classes.dex" ] || die "d8 没有产出 classes.dex"

echo "== 5/6 打包 =="
OUT_WIN="$(cygpath -m "$OUT" 2>/dev/null || echo "$OUT")"
ASSETS_WIN="$(cygpath -m "$SRC/assets" 2>/dev/null || echo "$SRC/assets")"
python - "$OUT_WIN" "$ASSETS_WIN" <<'PY'
import sys, zipfile, os
out, assets = sys.argv[1], sys.argv[2]
apk = os.path.join(out, "base.apk")
dex = os.path.join(out, "dex", "classes.dex")
n = 0
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
    if os.path.isdir(assets):
        for root, dirs, files in os.walk(assets):
            for name in files:
                full = os.path.join(root, name)
                rel = os.path.relpath(full, assets).replace(os.sep, "/")
                # mp3 本身已压缩，用 STORED 避免二次压缩
                z.write(full, "assets/" + rel, compress_type=zipfile.ZIP_STORED)
                n += 1
print("   已写入 classes.dex (%d KB) + %d 个 assets" % (os.path.getsize(dex) // 1024, n))
PY
"$ZIPALIGN" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "== 6/6 签名（$MODE, v$VERSION_NAME/$VERSION_CODE） =="
if [ "$MODE" = "release" ]; then
  PROPS="$ROOT/../keystore.properties"
  [ -f "$PROPS" ] || die "缺少 keystore.properties，无法打发布包"
  # 读私有发布证书配置（文件不入版本库）
  get_prop() { sed -n "s/^$1=//p" "$PROPS"; }
  REL_KS="$(get_prop storeFile)"
  REL_ALIAS="$(get_prop keyAlias)"
  REL_STORE_PASS="$(get_prop storePassword)"
  REL_KEY_PASS="$(get_prop keyPassword)"
  case "$REL_KS" in
    /*) : ;;
    *) REL_KS="$(dirname "$PROPS")/$REL_KS" ;;
  esac
  TARGET="$ROOT/app-release.apk"
  "$APKSIGNER" sign \
    --ks "$REL_KS" --ks-key-alias "$REL_ALIAS" \
    --ks-pass "pass:$REL_STORE_PASS" --key-pass "pass:$REL_KEY_PASS" \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "$TARGET" "$OUT/aligned.apk"
else
  if [ ! -f "$KEYSTORE" ]; then
    echo "   生成调试签名 …"
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
      -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
  fi
  TARGET="$ROOT/app-debug.apk"
  "$APKSIGNER" sign \
    --ks "$KEYSTORE" --ks-key-alias androiddebugkey \
    --ks-pass pass:android --key-pass pass:android \
    --out "$TARGET" "$OUT/aligned.apk"
fi
"$APKSIGNER" verify --min-sdk-version 21 --print-certs "$TARGET" | head -5

echo
echo "完成: $TARGET"
ls -lh "$TARGET"
