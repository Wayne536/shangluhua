#!/bin/bash
# 入库助手构建脚本：在仓库根目录直接产出 build/app.apk
# 依赖：JDK 17+、Android build-tools 29、API 30 android.jar、R8、debug.keystore
set -e
cd "$(dirname "$0")"

SDK=${ANDROID_SDK:-/usr/lib/android-sdk}
BT=${BUILD_TOOLS:-$SDK/build-tools/29.0.3}
AJ=${ANDROID_JAR:-$(find /root/workspace/platform30 -name android.jar 2>/dev/null | head -1)}
R8=${R8_JAR:-/root/workspace/r8.jar}
KS=${KEYSTORE:-/root/workspace/debug.keystore}
KS_PASS=${KS_PASS:-android}

for f in "$BT/aapt2" "$AJ" "$R8" "$KS"; do
  [ -e "$f" ] || { echo "缺少依赖: $f"; exit 1; }
done

rm -rf build
mkdir -p build/gen build/classes build/dex build/libs_jars

python3 - <<'PY'
import glob, os, zipfile
for aar in glob.glob("libs/*.aar"):
    name = os.path.basename(aar)[:-4]
    out = os.path.join("build/libs_jars", name)
    os.makedirs(out, exist_ok=True)
    zipfile.ZipFile(aar).extractall(out)
    print("解包", name)
PY

echo "[1/5] 资源与清单"
$BT/aapt2 compile --dir res -o build/res.zip
$BT/aapt2 link -o build/base.apk -I "$AJ" --manifest AndroidManifest.xml \
  build/res.zip -A assets --min-sdk-version 24 --target-sdk-version 30 --java build/gen

echo "[2/5] 编译 Java"
CP="$AJ:$(find build/libs_jars -name classes.jar | tr '\n' ':')"
javac -source 8 -target 8 -encoding UTF-8 -classpath "$CP" -d build/classes \
  $(find src build/gen -name '*.java') > build/javac.log 2>&1 || {
    echo "编译失败:"; grep -E "error" build/javac.log | head -20; exit 1; }
test -f build/classes/com/slh/app/MainActivity.class || { echo "没有产出 MainActivity.class"; exit 1; }

echo "[3/5] dex"
java -cp "$R8" com.android.tools.r8.D8 --lib "$AJ" --min-api 24 --output build/dex \
  $(find build/classes -name '*.class') \
  $(find build/libs_jars -name classes.jar)

echo "[4/5] 打包"
cd build
cp base.apk unsigned.apk
python3 - <<'PY'
import glob, zipfile
z = zipfile.ZipFile('unsigned.apk', 'a')
for f in glob.glob('dex/*.dex'):
    z.write(f, f.split('/')[-1])
import os
for so in sorted(glob.glob(os.path.join('..', 'libs_so', '*', '*.so'))):
    abi = os.path.basename(os.path.dirname(so))
    z.write(so, 'lib/%s/%s' % (abi, os.path.basename(so)))
z.close()
PY
$BT/zipalign -f 4 unsigned.apk aligned.apk

echo "[5/5] 签名"
$BT/apksigner sign --ks "$KS" --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KS_PASS" --out app.apk aligned.apk >/dev/null
echo "OK: $(pwd)/app.apk"
