# IRMono.apk goes here

The prebuilt APK is not committed to git (see `.gitignore`). To assemble the flashable module
yourself, place `IRMono.apk` in this folder, then zip the contents of `magisk-module/`
(with `module.prop` at the zip root).

Get `IRMono.apk` either way:
- Download it from the [Releases](../../../../releases) page, **or**
- Build it from [`app/`](../../../../app/) — see `app/BUILD.md`.

The ready-made `IRMono-magisk-module-vX.Y.zip` in Releases already contains the APK in this path.
