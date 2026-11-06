# TapDrop design system: Lumen

One product on two platforms. Each app should feel native to its platform and built by the same people.
The Mac app is native SwiftUI with Liquid Glass. The Android app is Jetpack Compose on Material 3 Expressive, with
real glass blur. The website (`site/`) follows the same system; its tokens are in `site/src/styles/tokens.css`.

The tokens live in code: `mac/Sources/TapDrop/DesignSystem.swift` (`TD`, `TDFont`, `TDGlass`) and
`android/app/src/main/kotlin/app/tapdrop/ui/Theme.kt` (`Brand`, `Td.colors`, `Td.type`, `TdSpace`, `TdRadius`,
`TdMotion`). This page is the source of truth; change it first.

## Idea

**Light, in the dark.** A tap is a phone's screen lighting up against the notch and going dark, so TapDrop is made of
night and light: near-black, bone white, and one cool glow where they meet. There is no accent hue. Colour comes from
the person's own photos, links and files, which are always the hero. Semantic colours (danger, warning) appear only
where something needs fixing.

The mark is the **Notchline**: the notch's outline traced in one line of light. It's the app icon (the lit line on a
night tile), the Mac's menu bar icon, the horizon on the phone's home screen and the website's logo.

## Color

Brand:

| Token | Hex | Use |
|---|---|---|
| `night` | `#08080A` | The dark background; ink in light mode |
| `graphite` | `#17171A` | Raised dark surfaces |
| `bone` | `#F4F3EF` | Text in dark mode, the accent fill, light backgrounds |
| `ink` | `#0B0B0D` | Text and the accent fill in light mode |
| `light` | `#D6DEFF` | The glow: the cool white of a screen's light. Halos and hero spills only, never a fill |

Neutrals, by role:

| Token | Dark | Light |
|---|---|---|
| `bg` | `#08080A` | `#EFEDE7` |
| `surface` (cards) | `#111114` | `#FFFFFF` |
| `surfaceRaised` | `#17171A` | `#FFFFFF` + shadow |
| `hairline` | white 8% | black 7% |
| `text` | `#F4F3EF` | `#0B0B0D` |
| `textSecondary` | `#A2A2A8` | `#5F5E59` |
| `textTertiary` | `#6E6E75` | `#8C8A84` |
| `accent` (primary fills, selection, progress) | bone | ink |
| `onAccent` | night | bone |

Semantic: **success is bone or ink with the glow, never green.** Danger `#FF7A72` (dark) / `#D9443C` (light) and
warning `#F0B45A` / `#C98A1E` on Android; on the Mac use the system `.red` and `.orange` so they adapt.

Rules:
- Dark mode is the hero, and light mode must be equally finished. Both follow the system unless the person picks
  Light or Dark in Settings.
- Things that are hardware stay hardware in both modes: the notch, the island and the phone are black, and anything
  drawn inside them uses the dark palette (a tick in the island is ink on a bone disc in either mode).
- A "display" (the Test your tap scope, a drawn Mac screen) is night in both modes, so its marks are bone.
- The glow is `light` at 35–55%, heavily blurred, behind hero elements only. In light mode the glow is white on bone.

## Type

| Face | Use |
|---|---|
| **Instrument Serif** (regular and italic) | The big moments: hero lines and screen titles. The last word goes italic, with a full stop ("Ready to *tap.*", "Pair with your *Mac.*") |
| **Geist** (variable) | Everything read: body, rows, buttons, labels |
| **Geist Mono** | Codes, addresses, readings and eyebrows (small capitals, +12–14% tracking: "RECENT", "112 LUX") |

All three are SIL OFL and ship with both apps (Mac: `Resources/Fonts`, registered at launch, falling back to SF;
Android: `res/font`). The wordmark is "Tap" in Geist and "*Drop*" in the serif's italic.

- **Android** (`TdType`): hero 62/60 serif, display 42/44 serif, headline 24/30 Medium, title 19/24 Medium,
  subtitle 16/22 Medium, body 16/24, label 14/20 Medium, caption 12/16, eyebrow Geist Mono 11/14 +14%, mono 16/24,
  reading 26/30 Light with tabular figures. Display sizes track −2%.
- **Mac** (`TDFont`): hero serif 34 (−0.6), title serif 27 (−0.4), then Geist 17 / 15 / 13 / 12 / 11, and Geist Mono 10
  (+1.2) for badges and eyebrows. Use tabular figures for anything that changes (`.monospacedDigit()`,
  `.contentTransition(.numericText())`).
- **Copy:** sentence case, short, concrete, warm. Never technical in the main flow. Numbers and diagnostics go
  behind "Details".

## Shape and space

- 4-pt base grid. Standard paddings are 8 / 12 / 16 / 20 / 24 / 32.
- **Mac radii** (continuous corners):

  | Element | Radius |
  |---|---|
  | controls | 8 |
  | rows | 12 |
  | cards | 18 |
  | panel | 26 |
  | island | matches the notch, then grows to its own rounded bottom |
- **Android:**
  - Cards: 28 dp. Sheets: 32 dp. Fields: 16 dp. Chips: 12 dp.
  - Settings groups are segmented: the outer corners are 24 dp, the inner corners 6 dp, with 2 dp gaps between
    items (Android 16 style).
  - Buttons are full pills. Touch targets are at least 48 dp.

## Materials

- **Mac:**
  - On macOS 26 and later, Liquid Glass: `.glassEffect(...)`, `GlassEffectContainer`, and `glassEffectID` morphing
    where content changes shape. On macOS 15, `.ultraThinMaterial` with a 1 px top-edge highlight and a soft shadow.
  - It's all behind one modifier (`.tdGlass(...)`), so screens never branch on the OS version.
  - Big surfaces (the menu bar panel, Settings' sidebar) lay a wash under their content (`TD.panelWash`: bone 86% in
    light, night 80% in dark) so dense text stays legible on any wallpaper. Glass is quiet: no colour tint.
- **Android:**
  - Haze for real backdrop blur on top bars, bottom sheets and floating toolbars.
  - Cards are solid tonal surfaces with a hairline, so lists stay crisp and fast.
- **Lit controls, never flat white.** The accent button is lit: bone with a soft top-to-bottom falloff, a rim and a
  faint glow of the notch's light in dark mode; ink with a sheen and a soft shadow in light mode. Neutral buttons are
  raised: a gentle gradient and a rim that catches the light (Mac: `LitCapsule`; Android: `litFill`, `litHalo`,
  `raisedFill`).

## Motion

Motion has to feel physical, never decorative. Everything that changes state animates, and nothing loops forever
except live indicators.

| Spring | Response | Damping | Use |
|---|---|---|---|
| standard | 0.38 | 0.86 | Most state changes |
| emphasized | 0.5 | 0.72 | Arrivals and celebrations |
| island | 0.42 | 0.78 | The notch island growing and shrinking (Mac) |
| snappy | | 0.62 | Presses and toggles (Android) |
| gentle | | 1.0 | Ambient changes: glows, backdrops, no overshoot |

- The Notchline blooms when a tap counts, and live dots breathe. Light arrives and leaves; it never blinks.
- **Haptics:** Mac `NSHapticFeedbackManager` on accept and confirm; Android `Haptics.kt` on every meaningful change.
- **Reduced motion:** honour it everywhere by swapping springs for quick fades (Mac 0.2 s, Android 140 ms).

## Iconography

- Mac: SF Symbols, hierarchical rendering, medium weight. Android: `Icons.Rounded.*` throughout; don't mix styles.
- Icons are monochrome (text colour on a quiet well). The only coloured things on screen are the person's content.
- The Notchline is the brand mark: always one line, never filled, never with a second colour.

## Components (same names on both platforms)

| Component | What it is |
|---|---|
| `Notchline` | The mark: lit (core line over a blurred halo) on dark, ink with a white halo on bone. |
| `GlassCard` | The standard container (glass on Mac, tonal card on Android). |
| `StatusPill` | A live dot plus a label. Live: a bone (or ink) dot with the light's halo and a soft pulse; searching: an orbiting arc; offline: grey; error: the danger colour. |
| `DeviceHero` | An illustration of the other device (a phone or a MacBook with its notch), with the link drawn as light between them. |
| `ActionTile` | An icon well, a title and a subtitle. Press scale 0.97, with haptics. |
| `SettingsGroup` / `SettingsRow` | An icon well, title, optional subtitle, and a trailing control or chevron. |
| `PrimaryButton` / `SecondaryButton` | Lit accent (bone in dark, ink in light) / raised neutral. Both are pills. |
| `ProgressRing`, `CheckBurst` | Transfer progress, and success: the ring becomes a check that draws itself, in `onAccent` on the accent. |
| `Island` (Mac notch HUD) and `Pill` (Android overlay) | The same anatomy: leading visual (thumbnail, icon or ring), title, subtitle, trailing action. They grow out of the notch or camera hole as pure black so they look like hardware. |

## The tap, in words

Only the phone's screen counts: it lights up white against the notch, then goes dark, and the notch has to see both.
Use these words on both platforms (shorten for small surfaces, keep the voice: plain, calm, second person, no jargon).

- **Gesture:** "Touch the top of your phone's screen to the notch."
- **Hold:** "Hold it there until your phone buzzes." (About half a second; the phone buzzes when the tap counts.)
- **Why it's safe:** "TapDrop only sends when your Mac's notch sees your phone's screen light up and go dark right
  against it. A hand, a lamp or a tap anywhere else can't send."
- **When a tap doesn't count:**

  | Reason | Words |
  |---|---|
  | `not-seen`, `no-light-sent` | "Your Mac didn't see your screen. Press the top of the screen flat on the notch." |
  | `stayed-lit`, `no-key-off` | "Keep your phone on the notch until it buzzes." |
  | `facing`, `screen-only` | "Turn your phone around: its screen goes against the notch." |
  | `other-phone`, `late`, `gone`, `busy`, `cooldown` | "That tap didn't count. Try again." |
  | `calibrating` | Not an error: one of the calibration holds. |
- **Calibration** (Mac: Settings → Tap to Send → Test your tap): "Touch the top of your phone's screen to the notch
  and hold it until it buzzes," three times; then "Android phone reads 1,480 lux on the notch. Taps now need 740." with
  Reset.
- **Never say:** "the back of your phone works too", torch, flashlight, magnet, spot, "hold over the notch".
- **Drawing the gesture:** show what the person sees from their seat: the phone's back (camera bar) at the notch,
  with its screen's light spilling around the edges onto the Mac's display (the screen faces the Mac). Never draw the
  phone's screen facing the viewer for the tap. Android's `HowToTapIllustration` (the back-facing variant) is the
  reference.

## Updates, in words

Both apps check GitHub for a new release once a day and say so quietly; nothing about the person is sent. Same
voice as everywhere else: plain, short, second person.

- **Found one:** "TapDrop 2.1.1 is available", with "You have 2.1.0." under it and **What's new** (the release notes:
  headings, bullets, bold, code and links; links open in the browser).
- **Actions:** **Update Now** (primary), **Later**, **Skip This Version** (quiet).
- **Under way:** "Downloading… 42%" (with the sizes and **Cancel**), "Checking the download…", "Restarting TapDrop…",
  and "TapDrop restarts when the transfer finishes…" when a transfer is running (never cut one short).
- **Status:** "Checking…", "You're up to date" ("TapDrop 2.1.0 is the newest version · Checked today at 9:41."),
  "Not checked yet", "You skipped TapDrop 2.1.1" ("Check for Updates shows it again."), "Couldn't check for updates".
- **When it can't update itself:** say why in one sentence and offer **Download from GitHub** (the release page), plus
  **Try Again** when trying again could help.

  | Why | Words |
  |---|---|
  | Offline | "Couldn't reach GitHub. Check your internet connection and try again." |
  | Rate limited | "GitHub is limiting checks from your network right now. Try again in an hour." |
  | Checksum | "The download didn't match its checksum, so nothing was installed." |
  | Signed differently (a copy built or signed elsewhere) | "This copy of TapDrop is signed differently from the release, so it can't update itself." |
  | Ad hoc / unsigned copy | "This copy of TapDrop isn't signed like the releases, so it can't update itself." |
  | Can't write where it is | "TapDrop doesn't have permission to replace itself in Applications." |
  | Run from a download | "Move TapDrop to your Applications folder and open it from there to update it." |
  | Didn't take after restarting | "The update didn't finish, so TapDrop is still on 2.1.0." |
- **Where (Mac):** Settings → About (the Updates group: status, What's new, the "Check for updates automatically"
  switch), **Check for Updates…** in the TapDrop menu, an **Update available** row at the foot of the menu bar panel
  ("TapDrop 2.1.1 · See what's new"), and one notch notice per version ("TapDrop 2.1.1 is available" · "See what's new
  in Settings" · **Details**), only while the island is idle and never with a sound.

## Quality bar

Every screen ships with:
- dark and light appearance;
- empty, loading and error states;
- hover and focus states (Mac);
- accessibility labels;
- Dynamic Type or large-font tolerance with no clipped text;
- reduced motion.

No placeholder copy, no misaligned baselines, no default-looking controls.
