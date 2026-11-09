# oneplus8pro-ir-camera — unlock the hidden color-filter / IR sensor (cameraId 4)

> 🇷🇺 **[Русская версия →](README.ru.md)** · 🇬🇧 English (this file).

Unlock and shoot with the **5 MP "color filter" sensor** on the OnePlus 8 Pro (IN2020) — the
fourth rear camera that OnePlus marketed as **"Photochrom"** and then disabled with an OTA in 2020.

This repo ships a tiny privileged camera app (**IRMono**) and a **flashable Magisk module** that
lets the app open that sensor directly (`cameraId 4`), show a live preview, save full-resolution
**2592×1944** stills and **record video**. Unlike the stock "Photochrom" filter — which is real-time blending (RTB) that
just tints the **main** sensor's image — this reads the dedicated sensor itself, with its own
viewpoint and characteristic cold blue/false-color response. A TV-remote IR LED, invisible to the
eye, lights up brightly through it.

> Verified on **OnePlus 8 Pro IN2020, Chinese ColorOS 13.1.0.187** (build `IN2020_11.F.73`,
> Android 13). The sensor hardware is the same on all 8 Pro variants, so it is very likely to work
> on global OxygenOS too, but that is **untested** — see [Compatibility](#compatibility).

---

## Why a privileged app is needed

On Android 10+ the extra physical sensors of a multi-camera device are hidden behind the
`SYSTEM_CAMERA` permission. A normally-installed app calling `getCameraIdList()` only sees the four
"logical" cameras (ids `0–3`); it never sees `cameraId 4`. `SYSTEM_CAMERA` is a
`signature|privileged` permission, so the only way an ordinary (non-platform-signed) app can hold it
is to be installed as a **privileged app** (`/system/priv-app`) with an allow-list entry. That is
exactly what the Magisk module does — no firmware patching, fully reversible.

## How it differs from stock "Photochrom"

The stock camera's Photochrom is **RTB (real-time blending)**: it keeps shooting on the **main**
48 MP sensor and just blends in a tint, so the geometry, viewpoint and EXIF are the main lens'.
This project opens the **dedicated** color-filter sensor (`cameraId 4`) on its own — its real
2592×1944 frame, its own slightly shifted viewpoint, and the raw cold-blue false-color response.

## Install (recommended: flashable Magisk module)

**Requirements**
- OnePlus 8 Pro with an **unlocked bootloader** and **Magisk** (v20.4+).
- Reboot access. ~20 KB of space.

**Steps**
1. Download `IRMono-magisk-module-vX.Y.zip` from the [Releases](../../releases) page.
2. Magisk app → **Modules** → **Install from storage** → pick the zip.
3. **Reboot.** (The system only scans `priv-app` and grants `SYSTEM_CAMERA` at boot.)
4. Open **"IR Camera"** from the app drawer. Tap **Allow** when it asks for the Camera permission.
5. The preview opens on `cam 4` (label top-left shows `cam 4   2592×1944`).
   - **СНЯТЬ / SHOOT** — save a still to `DCIM/IRMono/`.
   - **ВИДЕО / VIDEO** — record a clip to `DCIM/IRMono/` (tap again to stop; mic audio if granted).
   - **СЕНСОР / SENSOR ▸** — cycle through the other sensors.

**Uninstall:** Magisk app → Modules → toggle *IR Camera* off (or delete) → reboot. Nothing in the
system partition is touched; the overlay simply disappears.

### Alternative: manual install over adb (no flashing)

If you prefer to place the files by hand (device rooted, `adb` + `su` working):

```sh
adb push IRMono.apk /sdcard/Download/IRMono.apk
adb push privapp-permissions-irmono.xml /sdcard/Download/

adb shell su -c '
  MOD=/data/adb/modules/irmono
  mkdir -p $MOD/system/priv-app/IRMono $MOD/system/etc/permissions
  cp /sdcard/Download/IRMono.apk $MOD/system/priv-app/IRMono/IRMono.apk
  cp /sdcard/Download/privapp-permissions-irmono.xml $MOD/system/etc/permissions/
  # module.prop
  printf "id=irmono\nname=IR Camera\nversion=1.0\nversionCode=1\nauthor=you\ndescription=IR camera\n" > $MOD/module.prop
  chown -R 0:0 $MOD
  chcon -R u:object_r:system_file:s0 $MOD/system
'
adb reboot
# after boot, optionally pre-grant the runtime permission (otherwise the app just asks on launch):
adb shell su -c 'pm grant com.irmono.cam android.permission.CAMERA'
```

## How it works

Two small pieces:

**1. The allow-list** (`privapp-permissions-irmono.xml`, placed in `/system/etc/permissions/`):

```xml
<permissions>
    <privapp-permissions package="com.irmono.cam">
        <permission name="android.permission.SYSTEM_CAMERA"/>
    </privapp-permissions>
</permissions>
```

Because the APK sits in `/system/priv-app`, PackageManager reads this file at boot and grants
`SYSTEM_CAMERA` — after which `getCameraIdList()` returns the hidden sensors too.

**2. The app** (`com.irmono.cam`, ~16 KB, plain Camera2, no dependencies):
- `getCameraIdList()` now includes `4` (and the other `SYSTEM_CAMERA` ids). The app prefers `cameraId 4`.
- Opens it, runs a `TextureView` preview, and on **SHOOT** captures a JPEG at the sensor's max size
  (2592×1944) via an `ImageReader`, saving through `MediaStore` to `DCIM/IRMono/`.

### Sensor map (OnePlus 8 Pro, from the engineering-camera logs)

| cameraId | sensor                         |
|---------:|--------------------------------|
| 0        | main (48 MP, Sony IMX689)      |
| 1        | front                          |
| 2        | ultra-wide                     |
| 3        | telephoto                      |
| **4**    | **5 MP color-filter / "Photochrom"** |

`cameraId 4` is the sensor this project targets.

## Build from source

The app is deliberately buildable with just the Android SDK command-line tools (no Gradle).
Full source is under [`app/`](app/): `MainActivity.java`, `AndroidManifest.xml`.

Outline (JDK 17, SDK build-tools 34/35, `android.jar` from platform 35):

```
aapt2 link  -o base.apk --manifest AndroidManifest.xml -I android.jar --java gen
javac --release 11 -classpath android.jar -d classes <sources>
d8 --output . classes/**/*.class
# add classes.dex into base.apk, then:
zipalign -f 4 base.apk irmono-aligned.apk
apksigner sign --ks debug.keystore ... irmono-aligned.apk   # -> IRMono.apk
```

See [`app/BUILD.md`](app/BUILD.md) for the exact commands and the two gotchas that bite
(32-bit `JAVA_HOME` breaks `d8`; `-bootclasspath` vs `--release`).

## Compatibility

- **Confirmed:** OnePlus 8 Pro **IN2020**, ColorOS 13.1.0.187 CN.
- **Likely but untested:** other 8 Pro SKUs (IN2010/IN2015/IN2017/IN2019) and global OxygenOS —
  same hardware, same Camera2 HAL. The sensor-id mapping could differ; if `cameraId 4` isn't the
  color-filter sensor on your build, use the **SENSOR ▸** button to find the one that shows the
  cold-blue false-color image, and note which id it is.
- **Will not help** phones without this fourth sensor (e.g. OnePlus 8, 8T, Nord).

## Caveats

- Requires an unlocked bootloader + root. Unlocking wipes the device; rooting voids warranty.
- Fully reversible: the module adds an overlay only. Disable it in Magisk and reboot to undo.
- An OTA update can break root — restore your stock `boot.img` before updating, then re-patch.
- The app is signed with a throwaway debug key. Rebuild from source if you don't want to trust the
  prebuilt APK — that is exactly why the source is here.

## Credits

Code written by Claude (Anthropic); the device owner set the goal, reverse-engineered the camera
behavior together with it, and tested every build on the real phone.

## License

MIT — see [LICENSE](LICENSE). The app code is original; the Magisk `update-binary` is the standard
template from the Magisk project.
