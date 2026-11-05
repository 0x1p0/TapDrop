# TapDrop

AirDrop-style sharing between an Android phone and a Mac, over your own Wi-Fi, end-to-end encrypted.

- **Tap to send:** touch the top of your phone's screen to the notch, and hold it there until your phone buzzes (about half a second). Whatever is on the phone's screen comes to the Mac: the web page, the original photo or video, the file, the text, or a screenshot. TapDrop only sends when your Mac's notch sees your phone's screen light up and go dark right against it. A hand, a lamp or a tap anywhere else can't send. To fit taps to your phone, calibrate it once: Settings → Tap to Send → Test your tap → Calibrate.
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

Checks: `gradle :core:otpCheck :core:timeCheck` (and `:core:integration` against a running Mac app). Tap to send:
`gradle :core:keyCheck` (builds nothing itself: run `swift build` in `mac/` first; it starts its own test Macs on
ports 47941–47945). It covers the phone's rules, the Mac's detector, every light-key verdict and calibration.

## Install on a phone

On the Mac: menu bar icon → Settings → Phones → Set Up a New Phone…, then scan the QR code with the phone.
