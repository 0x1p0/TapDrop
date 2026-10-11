# TapDrop

AirDrop-style sharing between an Android phone and a Mac, over your own Wi-Fi, end-to-end encrypted.

- **Tap to send:** touch your phone to its spot on the Mac (the phone feels the Mac's magnets), or hold it over the notch in a lit room. Whatever is on the phone's screen comes to the Mac: the web page, the original photo or video, the file, the text, or a screenshot.
- **Share both ways:** files, photos, links, text and clipboard, from the Android share sheet or the Mac menu bar.
- **One-time codes:** codes from texts and notifications land on the Mac's clipboard, with a notch popup.
- **Per-app control:** choose what each app sends.

## Requirements

- **Phone:** Android 13 or newer, any brand.
- **Mac:** macOS 15 Sequoia or newer, Apple silicon.
- Both on the same Wi-Fi.

## Layout

- `mac/`: menu bar app (Swift, SwiftUI, Network.framework, CryptoKit).
- `android/app/`: Android app (Kotlin, Jetpack Compose).
- `android/core/`: shared protocol, crypto and client (pure Kotlin, with JVM checks).
- `PROTOCOL.md`: the wire protocol.

## Build

Mac app (builds, bundles the Android APK if built, installs to `/Applications`):

```sh
cd mac && ./scripts/build-app.sh
```

Android app:

```sh
cd android && gradle :app:assembleRelease
```

Release signing reads `android/keystore.properties` (not committed):

```properties
storeFile=tapdrop.keystore
storePassword=…
keyAlias=…
keyPassword=…
```

Without it, builds are signed with the debug key. Keep the same key to update an installed app in place.

Checks: `gradle :core:otpCheck :core:timeCheck` (and `:core:integration` against a running Mac app).

## Install on a phone

On the Mac: menu bar icon → Settings → Phones → Set Up a New Phone…, then scan the QR code with the phone.
