# TapDrop design system

One product on two platforms. Each app should feel native to its platform and built by the same people.
The Mac app is native SwiftUI with Liquid Glass. The Android app uses Material 3 Expressive with real glass blur.

## Idea

TapDrop is about light. A tap only counts when the Mac's notch sees the phone's light. The visual language
follows from that: deep, quiet surfaces, luminous accents, and motion that feels physical (springs, momentum,
glow). Everything else steps back so the content (photos, links, codes) is the hero.

## Color

Brand ("Aurora"). Keep these exact values; they match the app icon and the demo video.

| Token | Hex | Use |
|---|---|---|
| `aurora.blue` | `#409EFF` | gradient start, info |
| `aurora.violet` | `#8561FF` | primary accent, focus, selection |
| `aurora.orchid` | `#F26BD9` | gradient end, highlights (sparingly) |
| `aurora.gradient` | blue → violet (→ orchid for hero glow only) | primary buttons, hero glyphs, progress |

Neutrals:

| Token | Dark | Light |
|---|---|---|
| `bg` | `#0A0A0F` | `#F4F4F8` |
| `surface` (cards) | `#15151C` | `#FFFFFF` |
| `surfaceRaised` | `#1C1C25` | `#FFFFFF` + shadow |
| `hairline` | white 8% | black 7% |
| `text` | `#F5F5FA` | `#111118` |
| `textSecondary` | `#A0A2B4` | `#5E6173` |
| `textTertiary` | `#6B6D80` | `#8E90A2` |

Semantic: `success #2ED17A`, `warning #FFB020`, `danger #FF5A5F`. On the Mac use the system colors
(`.green`, `.orange`, `.red`) so they adapt to the appearance.

Rules:
- Dark mode is the hero, and light mode must be equally finished.
- The gradient is an accent: at most one gradient element per view, plus the hero glow.
- Glow is violet at 35–45% alpha, heavily blurred, and goes behind hero elements only.

## Type

- **Mac:** SF Pro (system).
  - Titles are semibold with slightly negative tracking: −0.3 at 20pt, −0.5 at 28pt.
  - Use `.rounded` with `.monospacedDigit()` for numbers, codes and sizes.
  - Use `.contentTransition(.numericText())` wherever a number changes.
- **Android:** Geist, bundled in `res/font` (variable font, SIL OFL), for all text. Use Geist Mono for codes.

  | Style | Size / line | Weight | Tracking |
  |---|---|---|---|
  | display | 34/40 | Bold | −1% |
  | headline | 26/32 | SemiBold | −0.5% |
  | title | 20/26 | SemiBold | |
  | body | 16/24 | Regular | |
  | label | 14/20 | Medium | |
  | caption | 12/16 | Medium | |
- **Copy:** sentence case, short, concrete, warm. Never technical in the main flow. Numbers and diagnostics
  go behind "Details".

## Shape and space

- 4-pt base grid. Standard paddings are 8 / 12 / 16 / 20 / 24 / 32.
- **Mac radii** (continuous corners):

  | Element | Radius |
  |---|---|
  | controls | 8 |
  | rows | 12 |
  | cards | 18 |
  | panel | 26 |
  | island | matches the notch, then a capsule |
- **Android:**
  - Cards: 28 dp.
  - Settings groups are segmented: the outer corners are 24 dp, the inner corners 6 dp, with 2 dp gaps between
    items (Android 16 style).
  - Buttons are full pills; chips are 12 dp.
  - Touch targets are at least 48 dp.

## Materials

- **Mac:**
  - On macOS 26 and later, use Liquid Glass: `.glassEffect(...)`, `GlassEffectContainer`, `.buttonStyle(.glass)`
    and `.buttonStyle(.glassProminent)`, and `glassEffectID` morphing where content changes shape.
  - On macOS 15, fall back to `.ultraThinMaterial` with a 1 px top-edge highlight (white 10%) and a soft shadow.
  - Wrap this in one modifier (`.tdGlass(...)`) so screens never branch on the OS version themselves.
- **Android:**
  - Use Haze for real backdrop blur on top app bars, bottom sheets and floating toolbars.
  - Cards are solid tonal surfaces with a hairline border in dark mode, so lists stay crisp and fast.

## Motion

Motion has to feel physical, never decorative. Everything that changes state animates, and nothing loops forever
except live indicators.

- **Mac:**
  - Springs:

    | Spring | Response | Damping |
    |---|---|---|
    | standard | 0.38 | 0.86 |
    | emphasized | 0.5 | 0.72 |
    | island | 0.42 | 0.78 |
  - SF Symbol effects: bounce on success, pulse for live, iterative `variableColor` while searching, and
    `.replace` transitions between symbols.
- **Android:**
  - `MotionScheme.expressive()`, with spring specs throughout.
  - Shape morphing (`MaterialShapes` with graphics-shapes) for the hero status shape.
  - The expressive `LoadingIndicator` while searching.
  - Shared-element transitions between home and detail pages, and predictive back.
- **Haptics:**
  - Mac: `NSHapticFeedbackManager` on accept and confirm.
  - Android: `Haptics.kt` on every meaningful state change.
- **Reduced motion:** honour it everywhere by swapping springs for quick fades.

## Iconography

- Mac: SF Symbols with hierarchical rendering, medium weight.
- Android: `Icons.Rounded.*` throughout. Don't mix in other styles.
- Icon chips are a 28–36 pt rounded square or a Material shape, holding a tinted or gradient symbol.

## Components (same names on both platforms)

| Component | What it is |
|---|---|
| `AuroraBackdrop` | A slow, low-contrast animated mesh gradient (Mac: `MeshGradient`; Android: drifting radial blobs). Hero areas only. |
| `GlassCard` | The standard container (glass on Mac, tonal card on Android). |
| `StatusPill` | A live dot plus a label. Live = green with a soft pulse; searching = amber with an animated arc; offline = gray; error = red. |
| `DeviceHero` | An illustration of the other device (phone or MacBook with its notch), with the link state drawn as light between them. |
| `ActionTile` | An icon chip, a title and a subtitle. Press scale 0.97, with haptics. |
| `SettingsGroup` / `SettingsRow` | An icon chip, title, optional subtitle, and a trailing control or chevron. |
| `PrimaryButton` | Gradient fill (Android) or `glassProminent` tinted violet (Mac). `SecondaryButton` is glass (Mac) or tonal (Android). |
| `ProgressRing`, `CheckBurst` | Transfer progress, and success: the ring morphs to a check that draws itself, with a short glow burst. |
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
