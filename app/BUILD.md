# Building IRMono from source (no Gradle)

The app is a single Activity with no dependencies, so it builds with just the Android SDK
command-line tools. This is handy on a machine without a full Android Studio / Gradle setup.

## Prerequisites

- **JDK 17** (Temurin/Adoptium is fine).
- Android SDK with:
  - `build-tools` 34 or 35 (provides `aapt2`, `d8`, `zipalign`, `apksigner`),
  - a `platform` whose `android.jar` has API 33+ (e.g. `platforms/android-35/android.jar`).
- A debug keystore to sign with. Create one once:

  ```sh
  keytool -genkeypair -v -keystore debug.keystore -storepass android -keypass android \
          -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
          -dname "CN=Android Debug,O=Android,C=US"
  ```

## Two gotchas (both cost real time)

1. **`JAVA_HOME` must point at a 64-bit JDK.** If it points at a 32-bit JDK 8, `d8` dies with
   `Could not reserve enough space for 1048576KB object heap`. Set `JAVA_HOME` to the JDK 17 above.
2. **Don't mix `-bootclasspath` with `-target`.** `javac` refuses it
   (`-boot-class-path not allowed with -target 11`). Use `--release 11 -classpath android.jar`
   instead — that is the modern, correct way to compile against the platform jar.

## Steps

Adjust the three variables to your paths.

```sh
SDK="$HOME/Android/Sdk"            # your Android SDK
BT="$SDK/build-tools/35.0.0"       # build-tools dir
AJAR="$SDK/platforms/android-35/android.jar"

# 1. Compile resources + manifest into a base APK (no resources here, just the manifest)
"$BT/aapt2" link -o base.apk -I "$AJAR" --manifest AndroidManifest.xml \
    --min-sdk-version 29 --target-sdk-version 33

# 2. Compile Java against the platform jar
mkdir -p classes
javac --release 11 -classpath "$AJAR" -d classes \
    src/com/irmono/cam/MainActivity.java

# 3. Dex it
"$BT/d8" --min-api 29 --output . classes/com/irmono/cam/*.class

# 4. Put classes.dex into the APK
jar uf base.apk classes.dex          # or: zip -j base.apk classes.dex

# 5. Align, then sign with the debug key
"$BT/zipalign" -f 4 base.apk irmono-aligned.apk
"$BT/apksigner" sign --ks debug.keystore \
    --ks-pass pass:android --key-pass pass:android \
    --out IRMono.apk irmono-aligned.apk

"$BT/apksigner" verify IRMono.apk && echo OK
```

The resulting `IRMono.apk` is what goes into the Magisk module at
`system/priv-app/IRMono/IRMono.apk`.

> Installing this APK normally (`pm install` / tapping it) will run the app, but it will **not** see
> `cameraId 4` — only a privileged install grants `SYSTEM_CAMERA`. Use the Magisk module.
