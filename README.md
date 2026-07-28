# Eneverre Android 🎥
This is an Android client for [eneverre-server](https://github.com/matiasdelellis/eneverre-server). 😄

These projects just attempt to consolidate the streams from different manufacturers into a single application.

### Features 🎉
* **Manufacturer independence:** See [eneverre-server](https://github.com/matiasdelellis/eneverre-server) for details.
* **PTZ support:** Maybe, for now only from [Thingino](https://thingino.com/).
* **Privacy Mode:** It's a bit of a lie if you don't use Thingino cameras. With others, this app hides the image, but the camera still transmits Video and Audio.

### Screenshots 😍
Login | Cameras View | Pip Camera | PTZ Camera | Private Camera | Playback
-- | -- | -- | -- | -- | --
![](https://raw.githubusercontent.com/matiasdelellis/eneverre-docs/refs/heads/main/images/android/eneverre-login.png) | ![](https://raw.githubusercontent.com/matiasdelellis/eneverre-docs/refs/heads/main/images/android/cameras-list.png) | ![](https://raw.githubusercontent.com/matiasdelellis/eneverre-docs/refs/heads/main/images/android/pip-camera.png) | ![](https://raw.githubusercontent.com/matiasdelellis/eneverre-docs/refs/heads/main/images/android/ptz-camera.png) | ![](https://raw.githubusercontent.com/matiasdelellis/eneverre-docs/refs/heads/main/images/android/privacy.png) | ![](https://raw.githubusercontent.com/matiasdelellis/eneverre-docs/refs/heads/main/images/android/playback.png)

### Building with GitHub Actions 🤖
You don't need a local Android toolchain to produce a signed release — the
[`Build Signed APK & GitHub Release`](.github/workflows/android-build.yml)
workflow does it for you.

1. **Configure the signing secrets** in your fork's *Settings → Secrets and variables → Actions*:
   * `KEYSTORE_BASE64` — your `keystore.jks`, base64-encoded (`base64 -w0 keystore.jks`).
   * `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` — the keystore/key credentials.
2. **Trigger the build**, either by:
   * pushing a numeric tag (e.g. `git tag 1.2.3 && git push origin 1.2.3`), or
   * running the workflow manually from the *Actions* tab, passing the
     `versionName` you just bumped in `app/build.gradle` (it's validated
     against the built APK so a forgotten bump fails loudly).
3. The workflow builds `arm64`, `armeabi` and `universal` APKs and attaches
   them to a new **GitHub Release**. This generic build has no server baked
   in, so anyone can point it at their own [eneverre-server](https://github.com/matiasdelellis/eneverre-server)
   from the login screen.

> The separate `Publish Update` workflow rebuilds the same code with an
> `API_HOST` baked in and pushes it to your server as an auto-update — it
> only runs when the extra `API_HOST` and `UPDATE_PUBLISH_TOKEN` secrets are set.
