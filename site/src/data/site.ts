import { to } from './url';
// All visible copy for the TapDrop site lives here.
// Facts come from README.md, PROTOCOL.md, docs/INSTALL.md and docs/DESIGN.md in this repository. Nothing here is
// invented: no store badges, quotes, counts or prices. TapDrop is free and open source under the MIT License.
// The production URL below is a placeholder until a domain is chosen.

import linkIsland from '../assets/island/lumen_link.png';
import photoIsland from '../assets/island/lumen_recv_IMG_20261104_181203.png';
import videoIsland from '../assets/island/lumen_recv_VID-20261105-WA0012.png';
import pdfIsland from '../assets/island/lumen_received-pdf.png';
import textIsland from '../assets/island/lumen_text.png';
import codeIsland from '../assets/island/lumen_code.png';
import clipIsland from '../assets/island/lumen_clipboard.png';

import chromeArticle from '../assets/phone/chrome_article.png';
import photosLake from '../assets/phone/photos_lake.png';
import msgsThread from '../assets/phone/msgs_thread.png';
import photosVideo from '../assets/phone/photos_video.png';
import pdfViewer from '../assets/phone/pdf_viewer.png';
import pickArea from '../assets/phone/pick_area.png';
export { pickArea };
import otpNotif from '../assets/phone/otp_notif.png';
import homeDark from '../assets/phone/home-dark.png';
import shareSheet from '../assets/phone/share_tapdrop.png';
import testTap from '../assets/phone/test-tap.png';
const androidHome = homeDark;

import macPanel from '../assets/mac/panel-dark.png';
import macTapToSend from '../assets/mac/tap-to-send-dark.png';

export const links = {
  repo: 'https://github.com/0x1p0/TapDrop',
  releases: 'https://github.com/0x1p0/TapDrop/releases/latest',
  // The newest release's files, by names that never change (every release also carries TapDrop.dmg and TapDrop.apk).
  dmg: 'https://github.com/0x1p0/TapDrop/releases/latest/download/TapDrop.dmg',
  apk: 'https://github.com/0x1p0/TapDrop/releases/latest/download/TapDrop.apk',
  license: 'https://github.com/0x1p0/TapDrop/blob/main/LICENSE',
  guide: to('/get-started/'),
};

export const site = {
  name: 'TapDrop',
  version: '2.1',
  url: 'https://tapdrop.pages.dev/',
  tagline: 'Touch. It’s there.',
  title: 'TapDrop: touch your phone to your Mac’s notch',
  description:
    'Touch your Android phone to your Mac’s notch and what’s on its screen lands on the Mac, as the original file. Free, open source, no cloud.',
};

export const nav = [
  { href: to('/'), label: 'Tap' },
  { href: to('/how-it-works/'), label: 'How it works' },
  { href: to('/get-started/'), label: 'Get started' },
];

export const hero = {
  meta: 'TapDrop 2.1 · Android and Mac',
  open: 'Free and open source (MIT). No accounts, no cloud, no tracking.',
  lede:
    'Touch the top of your Android phone’s screen to your Mac’s notch and hold it until it buzzes. The page, photo, video or PDF on it lands on your Mac.',
  steps: [
    { lux: 112, label: 'Room', text: 'Touch the top of the screen to the notch.' },
    { lux: 1480, label: 'White', text: 'The screen flashes white. The notch sees it.' },
    { lux: 2, label: 'Black', text: 'Then black. The notch goes dark with it.' },
    { lux: 112, label: 'Counted', text: 'It buzzes. Lift it off. It’s on your Mac.' },
  ],
  landed: {
    island: photoIsland,
    name: 'IMG_20261104_181203.jpg',
    islandAlt: 'The TapDrop island under the notch: IMG_20261104_181203.jpg, from Android phone, saved to Downloads, with an Open button',
    caption: 'The original file in full quality. Not a screenshot of it.',
  },
};

export type Send = {
  what: string;
  gets: string;
  lands: string; // the one-line result shown on the stage
  kind: 'page' | 'photo' | 'video' | 'pdf' | 'text';
  island: ImageMetadata;
  islandAlt: string;
  phone: ImageMetadata;
  phoneAlt: string;
};

export const sends: { title: string; intro: string; rows: Send[]; footnote: string } = {
  title: 'Whatever’s on screen.',
  intro:
    'TapDrop looks at your screen only when a tap counts, then sends what’s behind it. Smart mode decides from what’s showing, and you can choose for each app.',
  rows: [
    {
      what: 'A web page',
      gets: 'Opens in your Mac’s browser as the page itself, not a picture of it.',
      lands: 'The live page, in your browser',
      kind: 'page',
      island: linkIsland,
      islandAlt: 'Island: Opened link from Android phone, stories.design',
      phone: chromeArticle,
      phoneAlt: 'An article open in Chrome on the phone',
    },
    {
      what: 'A photo',
      gets: 'The original file, full quality, straight into Downloads.',
      lands: 'The original photo, in Downloads',
      kind: 'photo',
      island: photoIsland,
      islandAlt: 'Island: IMG_20261104_181203.jpg from Android phone, in Downloads',
      phone: photosLake,
      phoneAlt: 'A lake photo open in the phone’s gallery',
    },
    {
      what: 'A video, mid-clip',
      gets: 'The original video file, found even with the player’s controls hidden.',
      lands: 'The video file itself, ready to play',
      kind: 'video',
      island: videoIsland,
      islandAlt: 'Island: VID-20261105-WA0012.mp4 from Android phone, in Downloads',
      phone: photosVideo,
      phoneAlt: 'A video paused full screen on the phone',
    },
    {
      what: 'A PDF or document',
      gets: 'The file itself, with its own name.',
      lands: 'The document, under its own name',
      kind: 'pdf',
      island: pdfIsland,
      islandAlt: 'Island: Boarding pass.pdf from Android phone, in Downloads',
      phone: pdfViewer,
      phoneAlt: 'A boarding pass PDF open on the phone',
    },
    {
      what: 'A part you circle',
      gets: 'Just that part. Any words in it arrive as text on your clipboard.',
      lands: 'Just those words, on your clipboard',
      kind: 'text',
      island: textIsland,
      islandAlt: 'Island: Copied text from Android phone, Gate B32, boarding at 7:40',
      phone: msgsThread,
      phoneAlt: 'A message thread on the phone: Gate B32, boarding at 7:40',
    },
  ],
  footnote:
    'Anything else arrives as a screenshot, copied to your clipboard too.',
};

export const originals = {
  eyebrow: 'Finding the original',
  intro:
    'Android never tells an app which file another app has open. So TapDrop recognises it by its light, against a fingerprint of every photo and video on your phone, made once in the background.',
  steps: [
    { label: 'On screen', text: 'You tap a photo of a receipt. To Android, it’s only pixels.' },
    { label: '256 numbers', text: 'TapDrop trims any black bars and reads the screen as a 16 × 16 grid of brightness.' },
    { label: 'Your library', text: 'Every photo already has a grid of its own. The closest ranks first at 0.959. The next best manages 0.537.' },
    { label: 'Up close', text: 'The top few get a sharper look, a 48 × 48 grid, edges and all. It has to be close, and clearly better than the rest.' },
    { label: 'The original', text: 'The photo itself lands on your Mac, at full size, under its own name. About a second, from tap to sent.' },
  ],
  video:
    'Every video gets a grid every half second. A paused frame is ranked against all of them, then the moments around the best one are decoded frame by frame. Wherever you stopped it, the whole video arrives.',
  note:
    'When TapDrop can’t find the original for certain, it sends a screenshot and tells you so. Never a guess. A picture that isn’t in your gallery, such as one inside a chat you never saved, and streaming video, which isn’t kept as a file, always arrive as screenshots. The scores above are from a real run on a library of 29,674 photos.',
};

export const numbers = {
  eyebrow: 'What it costs',
  intro:
    'TapDrop keeps a fingerprint of each photo and video, never a copy. Measured on a real phone: 29,674 photos and 12.7 hours of video.',
  space: [
    { value: '0.45', unit: 'KB', label: 'per photo. A grid of light and a short hash.' },
    { value: '30', unit: 'KB', label: 'per minute of video, a grid every half second.' },
    { value: '38', unit: 'MB', label: 'for the whole library, both kinds. Less than a few photos.' },
  ],
  time: [
    { value: '15 min', label: 'to fingerprint all 29,674 photos, once.' },
    { value: '1.5 h', label: 'for the 2,499 videos, about 2 s each, in the background.' },
    { value: 'On arrival', label: 'New photos and videos are added as they land on your phone.' },
    { value: 'On your phone', label: 'The fingerprints stay there. They never travel to the Mac.' },
  ],
};

export const lightKey = {
  title: 'Only the notch can send.',
  intro:
    'The notch on a MacBook holds an ambient light sensor. TapDrop uses it as a key: a tap counts only when it sees your phone’s own screen light up and go dark, pressed right against it.',
  chapters: [
    { id: 'room', label: 'Room', text: 'Before anything happens, the notch reads the light in your room.' },
    { id: 'covered', label: 'Covered', text: 'Your phone lands on the notch, screen first, and covers the sensor.' },
    { id: 'white', label: 'White', text: 'The phone’s screen turns white at full brightness. The notch sees the light arrive.' },
    { id: 'black', label: 'Black', text: 'Then the screen turns black, and the notch goes dark on cue.' },
    {
      id: 'counted',
      label: 'Counted',
      text: 'White − black is your phone’s own light, in a dark room or in sunlight. Enough of it, and the phone buzzes.',
    },
  ],
  cant: [
    { what: 'A hand over the notch', why: 'No light' },
    { what: 'A lamp switching on', why: 'Never went dark' },
    { what: 'Sun on the notch', why: 'Never went dark' },
    { what: 'A phone a few cm away', why: 'Too weak' },
    { what: 'A tap on your palm', why: 'Not the notch' },
  ],
};

export const more = {
  title: 'And when you’re not tapping.',
  tiles: [
    {
      id: 'codes',
      title: 'Codes, already copied.',
      text: 'One-time codes from texts and notifications show up in the notch, copied and ready to paste.',
      island: codeIsland,
      islandAlt: 'Island: Lumen Bank code 482 915, copied, press Command V to paste',
      phone: otpNotif,
      phoneAlt: 'A verification code notification on the phone',
    },
    {
      id: 'clipboard',
      title: 'Clipboard, both ways.',
      text: 'Send the Mac’s clipboard to your phone, or the phone’s to your Mac, in one tap.',
      island: clipIsland,
      islandAlt: 'Island: Clipboard sent to Android phone, Gate B32, boarding at 7:40',
      phone: homeDark,
      phoneAlt: 'TapDrop on the phone, ready to tap, with Your Mac’s clipboard under From your Mac',
    },
    {
      id: 'share',
      title: 'Share sheets, both ways.',
      text: 'Pick TapDrop in Android’s share sheet. On the Mac, drop files on the menu bar icon to queue them for your phone.',
      phone: shareSheet,
      phoneAlt: 'Android share sheet sharing three images, with MacBook Air and TapDrop Send to Mac',
      mac: macPanel,
      macAlt: 'The TapDrop menu bar panel on the Mac with Send Files, Send Clipboard and recent transfers',
    },
    {
      id: 'test',
      title: 'See what the notch sees.',
      text: 'Test your tap on either device: a live graph of the notch’s light and a plain-words verdict for every try.',
      phone: testTap,
      phoneAlt: 'Test your tap on the phone: a live light graph and the verdict That counted',
      mac: macTapToSend,
      macAlt: 'Tap to Send settings on the Mac with the Test your tap graph',
    },
  ],
};

export const film = {
  title: 'Watch it work.',
  text: 'Every kind of tap, codes, the clipboard and the light key, from start to finish.',
  length: '1:25',
  src: to('/media/tapdrop-film.mp4'),
  poster: to('/media/film-poster.webp'),
};

export const privacy = {
  title: 'It never leaves your Wi-Fi.',
  text:
    'Your phone and your Mac talk to each other directly, over your own network. You pair once by checking that six digits match on both screens, and every frame after that is encrypted end to end.',
  facts: [
    { value: '0', label: 'accounts to make' },
    { value: '0', label: 'servers in between' },
    { value: '6', label: 'digits to compare when you pair' },
    { value: '256', label: 'bit AES-GCM on every frame' },
  ],
  note: 'Screens that block screenshots, such as banking apps, are never captured.',
};

export const requirements = [
  { what: 'An Android phone', detail: 'Android 13 or newer, any brand.' },
  { what: 'A Mac with a notch', detail: 'Apple silicon, macOS 15 Sequoia or newer.' },
  { what: 'The same Wi-Fi', detail: 'Both on one network, not a guest network.' },
];

export const getIt = {
  title: 'Get TapDrop.',
  path:
    'TapDrop is free and open source. Download the Mac app from GitHub; your Mac then hands the Android app to your phone through a QR code. Pair once, and tap.',
  steps: [
    {
      title: 'Download for Mac',
      text: 'Open the DMG and drag TapDrop into Applications. It’s free, so it isn’t notarized by Apple: the first time, allow it once under Privacy & Security, Open Anyway.',
      href: to('/get-started/#open-it-the-first-time'),
      more: 'Opening it the first time',
    },
    {
      title: 'Scan the QR code',
      text: 'The Mac’s Set Up a Phone window shows a QR code: scan it and tap Download TapDrop (or download the Android app directly). It isn’t on the Play Store, so Android asks you to allow the install.',
      href: to('/get-started/#part-2-the-android-app'),
      more: 'Installing on Android',
    },
    {
      title: 'Pair, then tap',
      text: 'Pick your Mac on the phone, check the six digits match, click Pair. Then turn on tap to send and touch the notch.',
      href: to('/get-started/#part-3-pair'),
      more: 'Pairing and tap to send',
    },
  ],
};

export const shots = { androidHome, macPanel };
