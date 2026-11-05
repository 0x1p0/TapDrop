# TapDrop wire protocol (v1)

Transport: TCP on the LAN. The Mac listens on port 47913 (falls back to a random
port) and advertises Bonjour/DNS-SD service `_tapdrop._tcp` with TXT `id=<macId>`, `v=1`.

Every frame is `u32 big-endian length` + `payload`. Frames before the secure
channel is up are UTF-8 JSON. After it is up, every frame is
`AES-256-GCM(ciphertext || 16-byte tag)` of `type byte || body`, where type
`J` (0x4A) = JSON and `D` (0x44) = raw file bytes (≤ 256 KiB).

GCM nonce = `u32 BE direction || u64 BE counter` (direction 1 = phone→Mac,
2 = Mac→phone, counters start at 0 and are implicit, so replays/reorders fail).

## Pairing (numeric comparison, like Bluetooth)

```
phone → {"t":"pair","v":1,"device":id,"name":"Android phone","pub":b64(P-256 SPKI DER)}
mac   → {"t":"pair-wait","mac":macId,"name":"MacBook Air","pub":b64(DER)}
          both: shared = ECDH(P-256)
                salt   = SHA256(phonePub || macPub)
                code   = u32BE(HKDF-SHA256(shared, salt, "tapdrop/sas/v1", 4)) % 1e6
                key    = HKDF-SHA256(shared, salt, "tapdrop/pair/v1", 32)
          user checks the 6-digit code matches on both screens and clicks Pair on the Mac
mac   → {"t":"pair-ok"}  |  {"t":"pair-no"}
```

## Session (one request per connection)

```
phone → {"t":"hello","v":1,"device":id,"nonce":b64(16)}
mac   → {"t":"hello","v":1,"mac":macId,"name":...,"nonce":b64(16)}   (or {"t":"error","code":"unpaired"})
          sessionKey = HKDF-SHA256(pairKey, nonceP || nonceM, "tapdrop/session/v1", 32)
phone ⇒ encrypted request
```

Requests: `ping` → `pong{name,pending}`; `send` (phone pushes a batch) → `ok`;
`getclip` (Mac answers with a batch holding its clipboard); `inbox` (Mac answers
with a batch of everything queued for the phone; phone replies `ok`, Mac dequeues).

## Batch

```
{"t":"send","count":n,"total":bytes|-1,"from":name}
  {"t":"file","name":..,"size":bytes|-1,"mime":..,"clip":bool}  D D D …  {"t":"eof"}
  {"t":"text","text":..,"clip":bool,"otp":"HDFC Bank"?}   (otp present → one-time code, shown in the notch)
  …
{"t":"done"}                       receiver → {"t":"ok","saved":k}
```

## Live link ("watch")

While the phone's screen is on it keeps one session open: `{"t":"watch"}` → `{"t":"watching","pending":n,"notch":bool}`
(`notch`: the Mac has a light sensor in its notch). `watching` is always the first message on the session.

Mac → phone: `{"t":"grab",…}` (a light-key round, below), `{"t":"room",…}`, `{"t":"lux",…}`, `{"t":"key-seen",…}`,
`{"t":"key-ok",…}`, `{"t":"key-fail",…}`, `{"t":"release","id":n}`, `{"t":"inbox","pending":n}`.
Phone → Mac: `{"t":"caps","key":3}` once, right after `watching`; `{"t":"ka"}` every 15 s (Mac echoes);
`{"t":"knock"}`, `{"t":"key-on",…}`, `{"t":"key-off",…}`, `{"t":"diag",…}`, `{"t":"grab-ack",…}`.
Unknown messages are ignored on both sides.

A phone has one live link. When a phone opens a new one, the Mac closes any older link from the same phone (after a
network blip the old one can look open for minutes; grabs sent down it would reach nobody). The Mac drops a link it
has heard nothing on for 45 s (three missed `ka`), and the phone gives up on one that's silent for 45 s, then
reconnects.

## Light key (tap to send)

A tap sends only when the Mac's notch ambient light sensor (ALS) sees the phone's own screen come and go: right after the
phone says its screen is white at full brightness, the notch must read a level only a screen pressed on it gives; right
after the phone says the screen went black, the notch must go dark with it — in a lit room darker than the room, because
the phone sits on the sensor. A hand or a shadow can darken the sensor but can't make light; a lamp, the sun or a hand
leaving the notch can't go dark on cue; a phone a few centimetres away can't reach the level. Only the screen counts: a
torch is a point source that lights the sensor from across a desk (through the fingers of a hand over the notch, or past
a phone held in front of it) as brightly as on contact. No light key, no send.

**Capabilities.** The phone announces `{"t":"caps","key":3}` (3: the light key with its dark echo). The Mac only sends
grabs to sessions with that version; for any other phone a trigger shows "Update TapDrop on <phone> to keep tapping" in
the notch instead (once per session). A phone that gets a grab without `"key":3` (an older Mac) declines it with
`why:"update-mac"`.

**Room.** B is the room's light at the notch (a slow follow of the uncovered level, frozen during rounds). The room is
dark when B < 6 lux, with hysteresis: dark below 5, lit above 8. The Mac sends `{"t":"room","dark":bool,"lux":B}` when a
session starts (as soon as B is known) and whenever the dark flag flips.

**Triggers** (each starts a round; one round at a time, rounds ≥ 1.5 s apart):
- DIP, lit room (a phone screen showing dark content over the notch, or anything else covering it): the first reading with
  `lux ≤ max(2, 0.30·B)` after a peak `≥ 0.75·B` within 1.2 s and a drop of ≥ 3 lux.
- RISE, dark room (a phone screen arriving): `lux ≥ max(B + 8, 3·B)` and ≥ 8 above the 1.2 s minimum.
- KNOCK, any light: the phone sends `{"t":"knock"}` when its proximity sensor goes near after reading far for ≥ 0.5 s,
  while the phone moved in the last second, is upright (|gravity z| < 8 m/s²), is on and unlocked, isn't in a call, no
  round or picker is running on it, ≥ 1.5 s after its last knock and ≥ 2.5 s after its last sent tap. A knock round
  targets that phone only; DIP and RISE target every live phone with the current light key.

A trigger alone never sends anything: it only starts a round, and the round needs the phone's light.

**Round.**

```
mac   → {"t":"grab","id":n,"key":3,"dark":bool}
          P = highest reading from the trigger (KNOCK: 80 ms before it) to 40 ms after the grab
          covered = lit room && P ≤ max(2, 0.30·B)          (logged; decides how release is judged)
phone → {"t":"grab-ack","id":n,"ok":false,"kind":"none","why":…}       (declined; nothing lights up)
   or   {"t":"key-on","id":n,"src":"screen"}                            (its screen is white at full brightness)
mac   → {"t":"key-seen","id":n,"peak":lux,"need":lux}                   (the rise: the notch reads ≥ need)
   or   {"t":"key-fail","id":n,"peak":lux,"need":lux,"why":…}
phone → {"t":"key-off","id":n}                                          (its screen is black now)
mac   → {"t":"key-ok","id":n,"peak":lux,"need":lux,"fall":lux}          (the fall: the notch went dark with it)
   or   {"t":"key-fail","id":n,"peak":lux,"need":lux,"why":…}
phone → {"t":"grab-ack","id":n,"ok":true,"kind":"link|screenshot|pick|inbox"}   then sends as usual
   or   {"t":"grab-ack","id":n,"ok":false,"kind":"none","why":"no-light"}       after any key-fail: nothing is sent
mac   → {"t":"release","id":n}                                          (the phone left the notch)
```

- Without a key-on within 2 s of the grab: `key-fail` `no-light-sent`. (The phone takes its screenshot first, which
  can take a second on a cold start, then waits for its white frame to reach the display.)
- A `src` other than `screen`: `key-fail` `screen-only` at once.
- The rise: the first reading taken from grab + 40 ms to key-on + 900 ms with `lux ≥ need`, `need = max(S, B + 200)`
  in any room, where S is 600, or the phone's own floor once it has been calibrated on this Mac (below). A white screen
  at full brightness pressed on the notch reads ~1 500 lux, one 10 cm away ~400, 30 cm away tens; B + 200 keeps the
  room itself (a hand leaving the notch in a bright room) from ever counting. Otherwise `not-seen` when the window ends.
- After `key-seen` the phone must report `key-off` within 1 s (`no-key-off` otherwise); it reports it once its black
  frame is on the display.
- The fall: a reading taken after key-off, within 450 ms, with `lux ≤ peak / 3` and, in a lit room, `lux ≤ max(0.6·B, 8)` — the
  black phone blocks the room's light, so it's on the sensor. Otherwise `stayed-lit` (a lamp or the sun, a hand that just
  left the notch, a phone held off it, or the phone pulled away too soon). `peak` is the brightest reading while the
  light was on.
- One phone's echo at a time: the first phone seen is checked; if several phones light up, the first accepted wins and
  the others get `other-phone`. A key-on for a round that isn't running gets `late`.
- The phone keeps its screen white at most 1.3 s after key-on and black at most 1.6 s after key-off without an answer,
  then switches it off and gives up. It only sends on a `key-ok` that comes after its own `key-off`: a `key-ok` without
  the echo never counts. Answers only count on the session the grab came on, for that round id. It buzzes the moment
  `key-ok` arrives (the person holding it to the notch can lift it then), before it reads the rest of the screen.
- Release (sent to the phone that passed, for its pick countdown): covered rounds once `lux > max(0.6·B, 3)`; open rounds
  once its light is off and `|lux − B| ≤ max(4, 0.5·B)` for 2 readings in a row; at the latest 6 s after the verdict.
- A grab-ack with `ok:true` from any light-key phone counts only for a round whose key passed.

**The phone's side.**
- Before any light, in this order, it declines with: `screen-off`, `locked`, `in-call`, `still` (no motion for 3 s and
  nothing near for 1 s — a phone on the desk while a hand covers the notch), `cooldown` (a round already running on it,
  the picker up, or < 2.5 s since a tap that sent; a failed key doesn't hold up the next try), `busy`.
- Which way it faces: the screen faces the notch (proximity near now or within 700 ms, or gravity z < −1.5), or it's
  upright either way → it lights its screen. With its back clearly to the notch (gravity z > 1.5, nothing near) it
  declines `facing`, in any room, and the Mac hints "Turn your phone around: its screen goes against the notch."
- What's on screen (the screenshot, which app it is) is captured before the screen lights up, so the Mac never gets a
  white frame; the page address, text and element boxes are read from the app's accessibility tree while the Mac looks
  for the light.
- `screen` is a full-screen accessibility overlay, white, with the window brightness override at full; key-on goes out
  the moment its first frame is committed. On `key-seen` the same overlay turns black (the override stays, so nothing
  dims in between) and key-off goes out the moment that frame is committed. The overlay window runs on its own thread
  and the link's reader starts the switch to black itself, so a busy app screen can't delay either. The overlay is
  removed on the verdict, a timeout, the link dropping, or the screen going off.

**Calibration.** How bright a phone's white screen reads on the notch depends on the phone and the Mac, so 600 is only
the floor for a phone nobody has calibrated. Settings → Tap to Send → Test your tap → Calibrate asks for three holds
("Touch the top of your phone's screen to the notch and hold it until it buzzes"). While it runs, every round that phone
is in is a calibration hold:
- the rise needs only `max(B + 150, 200)`, and the Mac holds `key-seen` back until the readings stop climbing (a reading
  within 4 % of the one before) or for 0.35 s, so the sample is the screen at full brightness, not a point on its
  brightness ramp (a hold still climbing when the 900 ms window ends counts as seen);
- the fall is checked as usual (`stayed-lit` otherwise);
- instead of `key-ok` the Mac sends `{"t":"key-fail","id":n,"peak":lux,"need":lux,"fall":lux,"why":"calibrating"}`:
  nothing is sent. The phone treats it as neutral (a short tick, no error, no hint) and answers like any failed key,
  `grab-ack` `ok:false` `why:"no-light"`; a `grab-ack` `ok:true` after it opens nothing on the Mac (the key never passed).

`peak` (the brightest reading while the light was on) is the sample. After three, the phone's contact level is their
median and its floor `S = clamp(round(0.5 × contact), 300, 1000)`: half leaves room for a phone held a little
off-centre, 300 still needs the screen within a few centimetres, 1 000 keeps a very bright phone from being asked for
more than it gives through a smudge. The Mac keeps it per phone (paired device id) in `calibration.json` in its app
folder and shows it with Reset (back to 600): "Android phone reads 1,480 lux on the notch. Taps now need 740." A hold that
fails (`not-seen`, `stayed-lit`, a decline) isn't a sample; the card says what to change. Calibration stops after 45 s
without a sample, on Cancel, when Tap to Send is switched off, or when the phone stays disconnected for 4 s. After a
tap that fails `not-seen` with a peak of at least 40 % of `need`, the card suggests calibrating.

**Diagnostics.** `{"t":"diag","on":bool}` from the phone: while on, the Mac streams `{"t":"lux","v":lux,"b":B}` to that
phone, at most 8 a second (its "Test your tap" screen).

**Declines and failures.** A declined grab carries `"why"`: `still`, `locked`, `screen-off`, `in-call`, `cooldown`,
`busy`, `facing`, `update-mac`, `no-light` (the key failed or the screen couldn't light up), and after a passed key:
`self` (TapDrop open, nothing queued), `off` (sending off for that app), `secure`, `no-screenshot`. Key failures carry
`no-light-sent`, `screen-only`, `not-seen`, `no-key-off`, `stayed-lit`, `other-phone`, `late`, and `calibrating` (a
calibration hold, not a failure). The Mac lists rounds with their numbers in Settings → Tap to Send → Recent taps (e.g.
`Covered 57 → 3 lux · asked Android phone`, `Light key ✓ screen 3 → 1480 lux (needed 600), dark again at 2 lux`,
`Calibration 1 of 3 · Android phone reads 1480 lux on the notch`), and after a failed key it shows a gentle hint in the notch
at most every 30 s, in the same words as the apps:

| Reason | Words |
|---|---|
| `not-seen`, `no-light-sent` | Your Mac didn't see your screen. Press the top of the screen flat on the notch. |
| `stayed-lit`, `no-key-off` | Keep your phone on the notch until it buzzes. |
| `facing`, `screen-only` | Turn your phone around: its screen goes against the notch. |
| `other-phone`, `late`, `gone`, `busy`, `cooldown` | That tap didn't count. Try again. |
| `calibrating` | (none: a calibration hold) |

Test builds only (debug, `TAPDROP_TEST=1`): a simulated notch sensor, `kill -USR1` / `-USR2` to cover and leave the notch,
`TAPDROP_TEST_KEY` (`pass`, `stay`, `fail`, `uncover`), `TAPDROP_TEST_ROOM` and `TAPDROP_TEST_PEAK` for the simulated
light (it reads the room again once a round is over), `TAPDROP_TEST_KEY_DELAY_MS` to hold the verdict open,
`TAPDROP_TEST_CALIBRATE=1` (calibrate the first light-key phone that connects without a calibration),
`TAPDROP_TEST_WATCH_SILENCE` (seconds before a silent live link is dropped), auto-pairing. A release build ignores all
of them.

`kind:"inbox"` means TapDrop itself was open, so the phone collects what's queued on the Mac instead of sending.
The content itself is sent on a normal `send` session.
