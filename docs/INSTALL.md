# Install TapDrop, step by step

TapDrop is free and open source. This guide takes you from nothing to your first tap. It covers every click and
every warning macOS and Android show along the way, and why they appear. It takes about ten minutes.

- [What you need](#what-you-need)
- [Part 1. The Mac app](#part-1-the-mac-app)
- [Part 2. The Android app](#part-2-the-android-app)
- [Part 3. Pair your phone with your Mac](#part-3-pair-your-phone-with-your-mac)
- [Part 4. Turn on tap to send](#part-4-turn-on-tap-to-send)
- [Part 5. Optional: get the most out of TapDrop](#part-5-optional-get-the-most-out-of-tapdrop)
- [Part 6. Your first tap](#part-6-your-first-tap)
- [Everyday use](#everyday-use)
- [Updating](#updating)
- [Troubleshooting](#troubleshooting)
- [Uninstalling](#uninstalling)
- [Building it yourself](#building-it-yourself)

## What you need

- **A Mac with a notch** (MacBook Air or MacBook Pro with Apple silicon), on **macOS 15 Sequoia or newer**. A Mac
  without a notch can still share files, links, the clipboard and codes, but it can't do tap to send.
- **An Android phone on Android 13 or newer**, any brand.
- **Both on the same Wi‑Fi.** A guest network or a network with "client isolation" keeps them apart.

## Part 1. The Mac app

### 1. Download it

1. Download **[TapDrop.dmg](https://github.com/0x1p0/TapDrop/releases/latest/download/TapDrop.dmg)** on your Mac. It's always the latest version.
2. Open it from your **Downloads** folder. A TapDrop window opens.
3. Drag **TapDrop** onto **Applications** in that window.
4. Eject the TapDrop disk: click the eject button next to it in Finder's sidebar.

> Prefer to build it from the source yourself? See [Building it yourself](#building-it-yourself). A copy you build
> on your own Mac opens without the warning in the next step.

### 2. Open it the first time

TapDrop is free, so it isn't notarized by Apple (that costs a yearly fee). The first time you open it, macOS stops
it once. You allow it once, and it opens normally after that.

1. Double-click **TapDrop** in Applications.
2. macOS says *"TapDrop" Not Opened: Apple could not verify "TapDrop" is free of malware…*. Click **Done**, not
   Move to Trash.
3. Open **System Settings → Privacy & Security**.
4. Scroll down to **Security**. You'll see *"TapDrop" was blocked to protect your Mac.* Click **Open Anyway**.
5. Confirm with Touch ID or your password, then click **Open Anyway** once more.

<details>
<summary><b>Or do it in Terminal</b></summary>

This removes the "downloaded from the internet" flag, which is what makes macOS ask:

```sh
xattr -dr com.apple.quarantine /Applications/TapDrop.app
open /Applications/TapDrop.app
```
</details>

### 3. Allow your local network

macOS asks: *Allow "TapDrop" to find devices on local networks?* Click **Allow**. That's how your phone and Mac
reach each other over your Wi‑Fi.

> Clicked Don't Allow? Open **System Settings → Privacy & Security → Local Network** and switch **TapDrop** on.

### 4. Find it in the menu bar

TapDrop lives in the menu bar, at the top right of your screen: the **((·))** icon. Click it to open the panel.

- It isn't in the Dock or ⌘‑Tab unless one of its windows (Settings, Set Up a Phone) is open. Closing the window
  keeps TapDrop running in the menu bar.
- It opens at login. To change that: menu bar icon → **⚙︎ Settings** → **General** → **Open at login**. If it
  says it needs approval, click **Allow…** and switch TapDrop on under **Login Items & Extensions**.
- The first time, a **Set Up a Phone** window opens by itself. Keep it open for Part 2. You can always get back
  to it from the menu bar icon → **⚙︎ Settings** → **Phones** → **Set Up a New Phone**.

## Part 2. The Android app

Your Mac hands the Android app to your phone over your Wi‑Fi. You can also download it from GitHub.

### 1. Download it to your phone

**From your Mac (easiest):**

1. On the Mac, the **Set Up a Phone** window shows **Step 1 of 3, Install TapDrop on your phone** with a QR code.
2. On your phone, open the **Camera** app, point it at the QR code and tap the link that appears.
3. A **Get TapDrop** page opens, served by your Mac. It shows the version you're installing. Tap
   **Download TapDrop**.

The Mac shows *Opened on your phone* and then *Downloaded. Open TapDrop.apk on your phone.*

**Or directly:** on your phone, download **[TapDrop.apk](https://github.com/0x1p0/TapDrop/releases/latest/download/TapDrop.apk)**. It's always the latest version.

> Chrome may say *File can't be downloaded securely*: your Mac's page is plain http on your own Wi‑Fi. Tap **Keep**.
> If it warns that *this type of file can harm your device*, tap **Download anyway**.

### 2. Install it

1. Open the downloaded file. Tap the *TapDrop.apk* download notification, or open the **Files** app →
   **Downloads** → **TapDrop.apk**.
2. Android says *For your security, your phone currently isn't allowed to install unknown apps from this source.*
   Tap **Settings**, switch on **Allow from this source**, then go back.
3. Tap **Install**.

### 3. If Google Play Protect stops it

Play Protect warns about apps it hasn't seen from the Play Store. TapDrop isn't on the Play Store, so you may see
one of these:

- **"Unsafe app blocked"** or **"Play Protect doesn't recognise this app"**: tap **More details**, then
  **Install anyway**.
- **"Send app for a security scan?"**: either choice is fine. **Don't send** skips it.

If it's blocked with no **Install anyway** button, pause Play Protect's scanning for the install:

1. Open the **Play Store** app and tap your **profile picture** (top right).
2. Tap **Play Protect**, then **⚙︎** (top right).
3. Switch off **Scan apps with Play Protect**, and confirm.
4. Install TapDrop (step 2 above).
5. Go back and switch **Scan apps with Play Protect** on again. TapDrop keeps working with it on.

<details>
<summary><b>Samsung: Auto Blocker</b></summary>

Samsung phones (One UI 6 and newer) can block every app that doesn't come from an app store. If nothing happens
when you open the APK, go to **Settings → Security and privacy → Auto Blocker**, switch it off, install TapDrop,
then switch it back on.
</details>

<details>
<summary><b>Xiaomi, Redmi and POCO</b></summary>

MIUI and HyperOS scan the app first. Wait for the scan, then tap **Continue installation** or **Install anyway**.
</details>

<details>
<summary><b>Installing from a computer with adb</b></summary>

With USB debugging on: `adb install TapDrop.apk`
</details>

## Part 3. Pair your phone with your Mac

Pairing happens once. It exchanges encryption keys, and you compare a code like you would with Bluetooth.

1. On your phone, open **TapDrop** and tap **Pair with your Mac**.
2. It shows *Looking for Macs…* and then your Mac by name. Tap it.
3. Your phone and your Mac each show a **six‑digit code**. Check that they match.
4. On the Mac, click **Pair**.

The Mac's setup window moves on to **Step 3 of 3**, and your phone shows your Mac as connected.

> **Your Mac isn't listed?** Tap **Can't see your Mac?**, then **Edit address**. Type the address shown under the QR
> code in the Mac's **Set Up a Phone** window (like `192.168.1.20:47914`) and tap **Connect**. If that fails too, see
> [The phone can't find the Mac](#the-phone-cant-find-the-mac).

## Part 4. Turn on tap to send

Tap to send needs TapDrop's **accessibility service**. That's how it can see what's on your screen at the moment
you tap. It only looks when a tap passes, and what it sends goes only to your Mac, encrypted, over your Wi‑Fi.

On your phone's TapDrop home screen, find **Tap your Mac to send** and tap **Turn on tap to send**. A checklist
walks you through these steps. Each one has a button that opens the right screen.

### 1. Try TapDrop in Accessibility first (only if Android restricts it)

Android 13 and newer restrict accessibility for apps installed from a file, and only offer the way to allow it once
you've tried. If the checklist shows **Try TapDrop in Accessibility**:

1. Tap **Open**. If you land on a list, TapDrop is under **Downloaded apps** (some phones: **Installed apps**).
2. Tap **TapDrop — tap your Mac to send**. Android says it's restricted: *App was denied access* or, on some
   versions, *Restricted setting*. Tap **Close** (or **OK**).
3. Go back to TapDrop.

### 2. Allow restricted settings

1. Tap **App info** next to **Allow restricted settings**.
2. Tap **⋮** (the three dots, top right). It's there now, after step 1.
3. Tap **Allow restricted settings** and confirm with your PIN, pattern or fingerprint.
4. Go back to TapDrop.

### 3. Turn on TapDrop in Accessibility

1. Tap **Open** next to **Turn on TapDrop in Accessibility**.
2. Tap **TapDrop — tap your Mac to send** and switch on **Use TapDrop**.
3. Android asks *Allow TapDrop to have full control of your device?* Tap **Allow**.
4. Go back to TapDrop.

### 4. Let it run in the background (Samsung, Xiaomi, OnePlus, OPPO, vivo, Huawei and others)

Some brands stop apps in the background to save battery, which turns tap to send off. If the checklist shows
**Let it run in the background**, tap **Allow**, then allow it in the dialog. You can also do it by hand: **App
info → Battery → Unrestricted**. **Settings → Compatibility** in TapDrop lists the extra steps for your brand.

When every step is ticked, you'll see **You're all set**.

## Part 5. Optional: get the most out of TapDrop

On the phone's home screen, open **Get the most out of TapDrop**. Each permission is optional:

| Permission | What it does | What to tap |
|---|---|---|
| **Original photos & videos** | A tap on a photo or video sends the original file, not a screenshot | **Allow** → **Allow all**. If you pick only some photos, TapDrop can't find the rest. |
| **Codes from texts** | One-time codes from text messages appear in the notch and land on your Mac's clipboard | **Allow** |
| **Any file** | A tap on a PDF, ZIP or document in Files or a chat sends the file itself | **Allow** → switch on **Allow access to manage all files** |
| **Run in the background** | Keeps tap to send alive on brands that stop apps (only shown where it matters) | **Allow** |

## Part 6. Your first tap

1. Open anything on your phone: a web page, a photo, a PDF.
2. Hold the phone **screen toward the Mac's display** and touch the **top edge of its screen** to the **notch**.
3. Keep it there until the phone **buzzes**. That takes about half a second. The screen flashes white, then black,
   right against the notch.
4. Whatever was on screen lands on the Mac:
   - web pages open in your browser;
   - photos, videos and files go to **Downloads**;
   - text goes to the clipboard.

**Practice first, if you like:** on the phone, **Settings → Test your tap**; on the Mac, menu bar icon →
**⚙︎ Settings** → **Tap to Send** → **Test your tap**. Both show a live graph of what the notch sees and say in plain
words why a tap did or didn't count. **Calibrate** measures your phone's brightness up front, though TapDrop also
learns it by itself as you tap.

> **Pick before sending** (phone **Settings**) freezes the screen for a moment after the tap. Tap one photo or drag
> over an area to send just that part.

## Everyday use

- **Phone → Mac:** tap the notch, or use the share sheet: **Share → Send to Mac**.
- **Mac → phone:** drag files onto the menu bar icon, or open the panel and click **Send Files** or
  **Send Clipboard**. Your phone picks them up.
- **One-time codes:** they slide out of the notch and are copied, ready to paste.
- **Where things go:** menu bar icon → **⚙︎ Settings** → **General** → **Received files**.

## Updating

TapDrop checks for a new version once a day and tells you what's new. You can turn this off in Settings.

**Mac:** when there's an update, the menu bar panel shows **Update available**. Open it (or **⚙︎ Settings** →
**About**) to read what's new, then click **Update Now**. TapDrop downloads the new version, checks it, replaces
itself and reopens. To check right away: **⚙︎ Settings** → **About** → **Check for Updates…**.

**Phone:** you get a notification, and TapDrop's home screen shows the new version. Tap **Update**. The first time,
Android asks you to let TapDrop install apps: switch on **Allow from this source**, go back, then tap **Update**
on the system's prompt. TapDrop stays paired, and tap to send stays on.

<details>
<summary><b>Updating by hand</b></summary>

- **Mac:** download the new **[TapDrop.dmg](https://github.com/0x1p0/TapDrop/releases/latest/download/TapDrop.dmg)**. Quit TapDrop (**⚙︎ Settings** → **Quit TapDrop**),
  open the DMG, drag **TapDrop** onto **Applications** and choose **Replace**. If macOS asks again, repeat
  [Open it the first time](#2-open-it-the-first-time).
- **Phone:** install the new **[TapDrop.apk](https://github.com/0x1p0/TapDrop/releases/latest/download/TapDrop.apk)**, or scan the code in **Set Up a New
  Phone** on the Mac. It installs over the old one.
- If your phone can't connect after a Mac update, see
  [The phone can't find the Mac](#the-phone-cant-find-the-mac).
</details>

## Troubleshooting

### The phone can't find the Mac

- Both must be on the **same Wi‑Fi**, not a guest network.
- On the Mac, open **System Settings → Privacy & Security → Local Network**, switch **TapDrop off, then on**. This
  fixes most cases, especially after an update. The switch can show on while macOS is still blocking it.
- Still nothing? Quit and reopen TapDrop, then try **Can't see your Mac?** on the phone with the Mac's address.

### The setup page won't open on the phone, or says "too many retries"

Same fix: switch **Local Network** off and on for TapDrop on the Mac, then scan again. Make sure the phone isn't
using mobile data instead of Wi‑Fi.

### Taps don't count

- Use the **screen side** of the phone, its top edge, flat on the notch, and hold until the buzz.
- Open **Test your tap** (Part 6). The verdict says what happened. *Turn your phone around* means the back was facing
  the notch.
- Very bright sun on the notch, or a phone dimmed because it's hot, can make taps harder. Try **Calibrate**.

### "Tap to send stopped" on the phone

Android stopped the accessibility service, usually to save battery. Open TapDrop and tap the banner, or switch TapDrop
off and on in **Settings → Accessibility**. Then do [Let it run in the background](#4-let-it-run-in-the-background-samsung-xiaomi-oneplus-oppo-vivo-huawei-and-others).

### A tap sent a screenshot instead of the photo, video or file

- Allow **Original photos & videos** and **Any file** (Part 5).
- Videos are fingerprinted in the background while the phone is unlocked and cool. **Settings → Videos for tap** on
  the phone shows how many are ready.
- Streaming apps (YouTube, Instagram, Netflix) don't keep videos as files, so they always send a screenshot.

### "App not installed" on the phone

An app with the same name but a different signature is already installed (for example, one you built yourself).
Uninstall the old TapDrop first, then install, then pair again.

## Uninstalling

- **Mac:** Quit TapDrop, drag **TapDrop.app** from Applications to the Bin. To remove its settings and history too,
  delete `~/Library/Application Support/TapDrop`.
- **Phone:** long-press **TapDrop** → **App info** → **Uninstall**.

## Building it yourself

Everything is in this repository. A copy you build yourself opens without Gatekeeper's warning.

**Mac** (Xcode 16 or newer, or its Command Line Tools: `xcode-select --install`):

```sh
git clone https://github.com/0x1p0/TapDrop.git
cd TapDrop/mac
./scripts/build-app.sh          # builds, installs to /Applications and opens it
```

**Android** (JDK 17 and the Android SDK, e.g. from Android Studio):

```sh
cd TapDrop/android
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
```

Build the Android app **before** the Mac app if you want the Mac's setup page to offer it: `build-app.sh` bundles
`app-release.apk` when it exists. Release builds and signing are covered in the [README](../README.md#build-from-source).
