# TapDrop site: design notes (Lumen)

## Brief

- **Subject:** TapDrop 2.1, sharing between any Android phone and a Mac. The signature: touch the top of the
  phone's screen to the MacBook's notch, hold it until the phone buzzes, and whatever is on screen lands on the Mac.
- **Audience:** people who carry an Android phone and work on a notched MacBook (designers, developers, students),
  plus anyone the owner shows it to.
- **Job:** a launch page. Explain the tap in three seconds, prove it is real (real screens, the real film), explain
  why it is safe, and give an honest way to get it.
- **Assets:** the app icon, real Mac and Android captures (`video/public/v2/real`, `docs/images`), the 1:15 launch
  film. No invented screens.
- **Constraints:** free and open source (MIT). Downloads come from GitHub Releases; no store badges, quotes, counts or
  prices. Be plain about Gatekeeper and Play Protect. Devices are "Android phone" / "your phone" and "MacBook Air" /
  "your Mac".

## Concepts

| | A. The notch is the door (material) | B. White minus black (formal) | C. What the notch sees (material: the sensor) |
|---|---|---|---|
| Idea | The browser window is your Mac's screen: its top edge has a notch, and everything on the page arrives through it | The whole site is built from two frames, white and black, and only their difference carries content | The page is one long light-sensor reading: scrolling draws the trace, every section is a spike or a dip |
| Anchor | TapDrop's sensor and its island both live in the notch | the light key: white − black is the phone's own light | the real "Test your tap" graph in both apps |
| Layout | menu bar with a notch on every page; content drops out of it | alternating white and black bands | a trace running down the page, sections hang off events |
| Motion verb | morph out of the notch (spring) | flash, then cut to black | draw, spike, settle |
| Signature | the phone touches the notch, the page flashes white then black, the notch opens into the island and the photo drops out | a scrubbed white/black strobe revealing type | the trace spikes as you scroll past each feature |
| Screenshot | a dark phone pressed to the notch with its light spilling round the edges | a white frame with black type | a lux trace with the island above it |
| O / F / Fe / P | 4 / 5 / 4 / 1 | 4 / 4 / 4 / 2 | 3 / 4 / 4 / 2 |

**Pick A.** It is the product's own gesture and nothing else could own it. Steal from B for the transition (every
page change is a white flash, then black) and from C for the live readout in the menu bar and the light-key chart.

## Concept card

```
Idea:        Your browser is your Mac's screen. Its top edge has a notch, and everything arrives through it.
Metaphor:    a MacBook display: menu bar, notch, ambient light sensor, the TapDrop island.
Signature:   the hero tap (sticky stage, one scrubbed timeline): a phone, back to you, presses to the notch;
             the page floods white, cuts to black, the phone buzzes and lifts, the notch morphs into the real
             island and the original photo drops out of it. A sticky stage with one scroll-scrubbed timeline. Reused: hovering a row in "What a tap sends" drops that row's real island
             out of the notch; page transitions are the light key (white, then black, then the page opens
             out of the notch).
Emotion:     the hush of a dark room, then a flash: "oh, it's there".
Motion verb: morph out of the notch. Things grow from a black pill at the top, with the island's spring.
Screenshot:  the eclipse: a dark phone pressed to the notch, light spilling around its edges, "Touch. It's there."
```

## System: Lumen

Lumen is the apps' design system, and the site wears it: light, in the dark. Night is the ground, bone is the ink,
and the only light anywhere is the phone's own, a cool white. No other hue exists on the site (purple and blue
gradients are gone for good); red is kept only for errors in the apps, and the site has none to show.

**Palette**

| Role | Hex | Notes |
|---|---|---|
| Night (ground) | `#08080A` | every scene about light: the tap, the five sends, the originals, the light key |
| Graphite (surface) | `#17171A` | cards, the trace, the phone's body |
| Bone (ink) | `#F4F3EF` | text on night; the primary fill (buttons, the sent mark) |
| Ink | `#0B0B0D` | text on bone (light scheme) and on the white key-on frame |
| Light | `#D6DEFF` | glow only: the phone's spill, the Notchline's halo, the matched fingerprint |
| Black | `#000000` | the notch, the island, the black frame of the light key |
| Muted | `#A2A2A8` on night, `#5F5E59` on bone | 7:1 and 6:1 |

Dark is the default. With `prefers-color-scheme: light`, the reading parts turn bone (`#EFEDE7`) with ink text, while
the scenes about light stay night (`.night`): a dark room is where light can be seen. The white flash of the light
key flips the one global `--ink` on `<html>` (`is-white`), in either scheme.

**Type:** Instrument Serif for display, with the last word in its italic, as the apps title things ("Ready to
*tap.*"): the action sharp, the arrival fluid. Geist for UI and body (17 px / 1.55). Geist Mono for eyebrows and
measurements (lux, scores, sizes), uppercase with 0.14em tracking. Display tracking −0.028em, leading 0.86.

**The mark:** the Notchline, the notch's outline traced in one line of light (the app icon). It is the brand tile in
the menu bar, the favicon and the OG image, and it lives under the site's own notch, brightening with the light the
notch reads.

**Grid:** 12 columns, gutter `clamp(16px, 2.2vw, 40px)`. Section rhythm: hero (sticky), sends (pinned), originals
(pinned), numbers (open, big serif figures), light key (sticky narrative), more (gallery wall), film (full bleed),
privacy (tight), get (airy), footer (wordmark).

**Shape:** surfaces 28 px (the apps' cards), controls are pills, the notch has 12 px bottom corners.

**Motion:** one ease family (expo out for entrances, power2 in-out for scrubs, a soft spring for the island).
Primary buttons light up from the notch's side (the key-on fill, bone on night, ink on bone), nav labels roll,
inline links keep a hairline underline that rises into a band.

## Never a screenshot (the new centrepiece)

The site's job grew: besides the tap, it has to show why TapDrop sends the original, not a screenshot. One pinned
figure (`src/components/Originals.astro`, `src/motion/originals.ts`), one variable (scroll progress), five beats:
the receipt on screen; the screen dissolving into its 16 × 16 grid of light; the library of fingerprints lit by a
passing scan, ranked 0.959 against 0.537; the 48 × 48 look up close, 0.99; the original sent, about a second. Every
score is the real correlation of the grids drawn (`scripts/originals.mjs`, tuned to a real run), so the figure is
honest down to its numbers. Below 768 px and under reduced motion it is a still sequence of the same grids. Videos
follow: a grid every half second, a scan that settles on the matching moment. Then the cost, in big serif numbers:
0.45 KB a photo, 30 KB a minute of video, 38 MB for 29,674 photos and 12.7 hours of video.

## Composition plan

```
HERO (scene, sticky 540svh)            SENDS (pinned stage, five taps, 975svh)
┌──────────── menu bar ▀▀notch▀▀ ───┐  ┌────────── ▀▀island▀▀ ─────────────┐
│ meta          ▐ phone ▌           │  │ The real     ▐phone▌   ┌window──┐ │
│               ▐ back  ▌ corona    │  │ thing, not a ▐front▌   │ result │ │
│               ▐       ▌           │  │ picture.     ▐/back▌   │ lands  │ │
│ TOUCH.                IT'S THERE. │  │ 01 ━━━━ 02 03 04 05     caption   │
│ lede · CTA                        │  └───────────────────────────────────┘
└───────────────────────────────────┘
NEVER A SCREENSHOT (pinned, 5 beats)   WHAT IT COSTS (open, numbers as type)
┌steps─┬─phone──┬─library / up close / sent─┐  ┌ Small enough to forget. ───────┐
│01 ━━ │▐rcpt▌→ │ ▦▦▦▦▦▦▦  1 IMG…143116 .959 │  │ 0.45 KB │ 30 KB │ 38 MB         │
│02 03 │▐grid▌  │ ▦▦▣▦▦▦▦  2 IMG…142950 .537 │  │ 15 min ── for all 29,674 photos │
└──────┴────────┴───────────────────────────┘  └────────────────────────────────┘
LIGHT KEY (sticky narrative)           MORE (gallery wall, real screens)
┌──────────────┬────────────────────┐  ┌──────┬────┬─────────┐
│ trace (sticky)│ 1 touch            │  │ code │    │ share   │
│ room──┐  ┌┐   │ 2 white            │  │      │clip│ sheet   │
│       └──┘└── │ 3 black            │  └──────┴────┴─────────┘
│ can't send ×5 │ 4 buzz             │  FILM (full bleed, poster, play)
└──────────────┴────────────────────┘  PRIVACY (statement + 4 facts)
GET IT (requirements as three big lines, then the honest path)    FOOTER (wordmark under the notch)
```

**Sends, the centrepiece.** One pinned stage; scroll progress p over five stretches gives (item i, phase t), and
every visual is computed from them on each tick (`src/motion/tapsends.ts`), so no speed or direction can leave a
layer half-done. Per tap: the phone shows the item's real screen (photos and videos zoomed to the picture), turns
round (screen to the Mac, back to you), presses to the notch; the light key floods white from the notch then cuts
to black; it buzzes and is lifted off; the island blooms out of the notch with the item's real capture; the real
result flies out of the island's thumbnail and lands as a window (the page in a browser, the photo, the video
playing, the PDF under its own name, the copied words). Earlier windows step back and dim. The index (01 to 05)
shows progress and jumps to an item's result. Reduced motion: a still five-row grid, phone screen beside island
and result. Phone: the same stage stacked, index as five numbered steps.

**Every page arrives through the notch.** Inner pages and the 404 open with an island under the notch carrying a
status in the apps' words: "Light key passed", "MacBook Air, connected, ready to tap", "Nothing received".

**Idle.** Home: the hero phone's light breathes and leans to the pointer, the menu bar keeps reading the room, the
trace's head wobbles. Inner pages stay still at rest by decision (they are for reading); the menu bar readout and
the trace's scanning head are their only live parts.

Inner pages: `/how-it-works/` (the light key in full: the round, the math, verdicts, finding the original, privacy),
`/get-started/` (requirements, the two setup films inline at Parts 1 and 2, every step, troubleshooting). 404: the notch with nothing in it, "Nothing landed here."

## Genericness test

- A generic launch page would put a purple gradient blob behind a centered phone mockup facing the viewer. Changed:
  the ground is black, the phone shows its back (the screen faces the Mac, as the app's own illustration demands), and
  the only colored light is the light that phone emits.
- Three feature cards became a gallery wall of real captures at different sizes.
- A fake "Download" button became an honest requirements block and the build-from-source path.
- Mono for every label became mono only for real measurements.
