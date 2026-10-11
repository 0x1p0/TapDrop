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

While the phone's screen is on it keeps one session open: `{"t":"watch"}` → `{"t":"watching","pending":n}`.
Mac → phone: `{"t":"grab","id":n}` (notch light sensor covered), `{"t":"inbox","pending":n}`.
Taps can also start on the phone (any light): the phone's magnetometer feels the Mac's magnets (speakers, the trackpad's
haptic motor, MagSafe). The user finds the strongest spot once (the field there, e.g. 400 µT vs ~45 µT for Earth); when
the field later rises past half of that (at least 120 µT) right after the phone was moved, the phone runs the tap itself
and answers with an unsolicited `{"t":"grab-ack","ok":true,"kind":...,"spot":true}`, which the Mac treats like an
accepted grab.
Phone → Mac: `{"t":"ka"}` every 15 s (Mac echoes), `{"t":"grab-ack","id":n,"ok":bool,"kind":"link|screenshot|pick|inbox"}`.
`kind:"inbox"` means TapDrop itself was open, so the phone collects what's queued on the Mac instead of sending.
A declined grab carries `"why"`: `still` (phone wasn't moving — a hand over the notch), `locked`, `screen-off`,
`cooldown`, `busy`, `self` (TapDrop open, nothing queued), `off` (sending off for that app), `secure`, `no-screenshot`.
The Mac lists these in Settings → Tap to Send → Recent taps, and says so in the notch for `locked`, `screen-off` and `self`.
The content itself is sent on a normal `send` session.
