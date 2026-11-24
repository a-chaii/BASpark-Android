#!/bin/bash
# 不依赖 Gradle 的构建脚本：aapt2 + javac + d8 + zipalign + apksigner
# 用法：ANDROID_HOME=/path/to/sdk bash build.sh
set -u

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR" || exit 1

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android}}"
BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
PLATFORM_JAR="$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"

if [ -z "${BT:-}" ] || [ ! -d "$BT" ]; then
    echo "找不到 build-tools，请设置 ANDROID_HOME" >&2
    exit 1
fi
if [ -z "${PLATFORM_JAR:-}" ] || [ ! -f "$PLATFORM_JAR" ]; then
    echo "找不到 platforms/android-*/android.jar" >&2
    exit 1
fi

ZIPALIGN="$BT/zipalign"
[ -x "$ZIPALIGN" ] || ZIPALIGN="$(command -v zipalign || true)"
if [ -z "$ZIPALIGN" ]; then
    echo "找不到 zipalign" >&2
    exit 1
fi

echo "SDK        : $SDK"
echo "build-tools: $BT"
echo "platform   : $PLATFORM_JAR"

rm -rf out
mkdir -p out/gen out/classes out/dex

echo "== aapt2 compile =="
"$BT/aapt2" compile --dir res -o out/res.zip || exit 1

echo "== aapt2 link =="
"$BT/aapt2" link -o out/base.apk -I "$PLATFORM_JAR" \
    --manifest AndroidManifest.xml \
    -R out/res.zip -A assets --auto-add-overlay \
    --java out/gen \
    --min-sdk-version 26 --target-sdk-version 35 \
    --version-code 6 --version-name 0.6 || exit 1

echo "== javac =="
javac -nowarn -encoding UTF-8 --release 11 -cp "$PLATFORM_JAR" -d out/classes \
    out/gen/com/miu/baspark/R.java src/com/miu/baspark/*.java || exit 1

echo "== d8 =="
"$BT/d8" --lib "$PLATFORM_JAR" --min-api 26 --output out/dex \
    $(find out/classes -name '*.class') || exit 1

echo "== package =="
cp out/base.apk out/app.apk
python3 - <<'PY'
import zipfile, shutil, os
src = 'out/app.apk'
dst = 'out/app_dex.apk'
shutil.move(src, dst)
with zipfile.ZipFile(dst, 'r') as zin, zipfile.ZipFile(src, 'w') as zout:
    for it in zin.infolist():
        zout.writestr(it, zin.read(it.filename), it.compress_type)
    zout.write('out/dex/classes.dex', 'classes.dex', compress_type=zipfile.ZIP_DEFLATED)
os.remove(dst)
print('packed', os.path.getsize(src))
PY

echo "== align + sign =="
"$ZIPALIGN" -f 4 out/app.apk out/aligned.apk || exit 1
# 签名：CI 里从 secret 解出固定 keystore，本地没有就用临时 debug key
KEYSTORE="${BASPARK_KEYSTORE:-release.keystore}"
KS_PASS="${BASPARK_KEYSTORE_PASS:-baspark}"
KS_ALIAS="${BASPARK_KEY_ALIAS:-baspark}"

if [ -f "$KEYSTORE" ]; then
    echo "签名: $KEYSTORE (固定签名，可覆盖安装)"
else
    echo "签名: 未找到 $KEYSTORE，回退临时 debug keystore（每个构建都不同，不能覆盖安装）"
    KEYSTORE="debug.keystore"
    if [ ! -f "$KEYSTORE" ]; then
        keytool -genkeypair -keystore "$KEYSTORE" -alias "$KS_ALIAS" \
            -storepass "$KS_PASS" -keypass "$KS_PASS" -keyalg RSA -keysize 2048 \
            -validity 10000 -dname "CN=BASpark Android" >/dev/null 2>&1
    fi
fi

"$BT/apksigner" sign --ks "$KEYSTORE" \
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" --ks-key-alias "$KS_ALIAS" \
    --out out/BASparkMobile.apk out/aligned.apk || exit 1

ls -lh out/BASparkMobile.apk
echo "BUILD OK"