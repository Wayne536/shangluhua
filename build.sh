#!/bin/bash
set -e
SDK=/usr/lib/android-sdk
BT=$SDK/build-tools/29.0.3
AJ=$(find /root/workspace/platform30 -name android.jar | head -1)
R8=/root/workspace/r8.jar
cd /root/workspace/slhapp
rm -rf build libs_jars && mkdir -p build/gen build/classes build/dex libs_jars
python3 - <<'PY2'
import zipfile, glob, os
for a in glob.glob("/root/workspace/libs/*.aar"):
    n = os.path.basename(a)[:-4]
    out = os.path.join("libs_jars", n)
    os.makedirs(out, exist_ok=True)
    zipfile.ZipFile(a).extractall(out)
    print("解包", n)
PY2
echo "[1/5] 资源与清单"
$BT/aapt2 compile --dir res -o build/res.zip
$BT/aapt2 link -o build/base.apk -I $AJ --manifest AndroidManifest.xml \
  build/res.zip --min-sdk-version 24 --target-sdk-version 30 --java build/gen
echo "[2/5] 编译 Java"
CP=$AJ:$(find libs_jars -name classes.jar | tr '\n' ':'):$(find /root/workspace/proxylibs -name '*.jar' | tr '\n' ':')
javac -source 8 -target 8 -classpath "$AJ:$CP" -d build/classes \
  $(find src build/gen -name '*.java') > build/javac.log 2>&1
if [ $? -ne 0 ]; then echo "编译失败:"; grep -E "error" build/javac.log | head -10; exit 1; fi
test -f build/classes/com/slh/app/MainActivity.class || { echo "没有产出 MainActivity.class"; exit 1; }
echo "[3/5] dex"
java -cp $R8 com.android.tools.r8.D8 --lib $AJ --min-api 24 --output build/dex \
  $(find build/classes -name '*.class') $(find libs_jars -name classes.jar) $(find /root/workspace/proxylibs -name '*.jar')
echo "[4/5] 打包"
cd build && cp base.apk unsigned.apk
python3 -c "
import zipfile
z=zipfile.ZipFile('unsigned.apk','a')
import glob
for f in glob.glob('dex/*.dex'): z.write(f, f.split('/')[-1])
z.close()"
$BT/zipalign -f 4 unsigned.apk aligned.apk
echo "[5/5] 签名"
$BT/apksigner sign --ks /root/workspace/debug.keystore --ks-pass pass:android \
  --key-pass pass:android --out app.apk aligned.apk
echo "OK: $(pwd)/app.apk"
