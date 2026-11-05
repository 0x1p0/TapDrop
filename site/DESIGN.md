# TapDrop launch site: design notes

## Brief

- **Subject:** TapDrop 2.0, sharing between any Android phone and a Mac. The signature: touch the top of the
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

## System

**Palette** (the brand's dark neutrals; white and black are events, the aurora is only ever light):

| Role | Hex | Source |
|---|---|---|
| ground | `#0A0A0F` | DESIGN.md `bg` |
| black (notch, the black frame, curtain) | `#000000` | hardware black, the island |
| ink | `#F5F5FA` | DESIGN.md `text` |
| muted | `#A0A2B4` | DESIGN.md `textSecondary` (7.6:1 on ground) |
| line | `rgb(245 245 250 / 0.1)` | hairline |
| accent | `#8561FF` | aurora violet: focus, selection, the live dot |
| light | `#409EFF → #8561FF → #F26BD9` | the phone's screen light only (corona, glow) |
| white frame | `#FFFFFF` | the key-on flash; ink flips to `#0A0A0F` through `--ink` on `<html>` |

**Type:** Geist (variable, 100 to 900) for everything, because it is the Android app's typeface. Geist Mono only for
measured values (lux, codes, timings). Display 600 with tracking −0.055em at mega size, leading 0.86. Body 17 px/1.55.

**Grid:** 12 columns, gutter `clamp(16px, 2.2vw, 40px)`. Three hang lines: column 1, column 5, column 8. Section rhythm:
hero (full, sticky), sends (airy), light key (sticky narrative), more (normal), film (full bleed), privacy (tight),
get it (airy), footer (full).

**Shape:** surfaces 28 px (the Android cards and the Mac panel), controls are pills, the notch has 10 px bottom corners.

**Motion:** one ease family (expo for entrances, power3 for UI, a soft back for the island spring). Hovers 0.3 to 0.5 s.
Primary buttons light up white from the top (the key-on), nav labels roll, inline links keep an underline that rises,
index rows drop their island out of the notch.

**Idle:** the menu bar's light readout keeps reading the room (a few lux of noise on the GSAP ticker), and the hero
phone's screen light breathes and leans toward the pointer. Both stop under reduced motion.

## Composition plan

```
HERO (scene, sticky 330svh)            SENDS (pinned stage, five taps, 560svh)
┌──────────── menu bar ▀▀notch▀▀ ───┐  ┌────────── ▀▀island▀▀ ─────────────┐
│ meta          ▐ phone ▌           │  │ The real     ▐phone▌   ┌window──┐ │
│               ▐ back  ▌ corona    │  │ thing, not a ▐front▌   │ result │ │
│               ▐       ▌           │  │ picture.     ▐/back▌   │ lands  │ │
│ TOUCH.                IT'S THERE. │  │ 01 ━━━━ 02 03 04 05     caption   │
│ lede · CTA                        │  └───────────────────────────────────┘
└───────────────────────────────────┘
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

Inner pages: `/how-it-works/` (the light key in full: the round, the math, verdicts, privacy), `/get-started/`
(requirements, three real steps, troubleshooting). 404: the notch with nothing in it, "Nothing landed here."

## Genericness test

- A generic launch page would put a purple gradient blob behind a centered phone mockup facing the viewer. Changed:
  the ground is black, the phone shows its back (the screen faces the Mac, as the app's own illustration demands), and
  the only colored light is the light that phone emits.
- Three feature cards became a gallery wall of real captures at different sizes.
- A fake "Download" button became an honest requirements block and the build-from-source path.
- Mono for every label became mono only for real measurements.
