# Muse Float

A tiny Android app that gives Muse the floating chat-head button the
Android app does not have: a small circular bubble with **your own Muse
avatar** that floats over other apps. Drag it anywhere (it snaps to the
nearest screen edge and remembers the spot), tap it to reopen Muse, and
it fades to a chosen opacity after a few idle seconds.

## Download

Grab the latest APK from this repo's
[Releases](../../releases) page and open it on your phone. Android will
ask you to allow installing from your browser or files app; that is the
standard sideload prompt. Muse Float is not on the Play Store.

## Set it up

1. Open Muse Float and grant the "display over other apps" permission
   when it walks you to the system settings screen.
2. Under **Bubble face**, pick your Muse avatar image. Muse does not
   expose avatars to other apps, so you supply the picture: save your
   avatar image to your phone (for example from your Muse profile),
   then choose it here. It is center-cropped to a circle and stored only
   inside the app.
3. Optionally pick a second, different night face. The bubble swaps on
   its own: day face from 07:00, night face after 19:00. Set only one
   and it is used around the clock.
4. Tap **Start bubble**.

## Gestures

- **Tap** reopens Muse (the Muse app when installed, else muse.ai)
- **Double tap** snoozes the bubble for an hour; it reappears on its
  own, or early from the notification's "Show bubble" action
- **Long press** opens a quick capture card: type a thought, Send
  shares it as plain text so it lands staged in the Muse app's
  composer. The card's mic button records a voice note instead (up to
  5 minutes), shared the same way as an audio file
- **Drag onto the X** at the bottom center of the screen to dismiss

Nothing is stored by the capture card; the text lives only in the card
until you send it, and a voice note lives only in the app's cache until
it is shared.

## Privacy

Muse Float has **no network access at all**: the manifest does not even
request the internet permission. No accounts, no analytics, no ads, no
wake locks, no sensors. Your face images stay in the app's private
storage on your phone. The only permissions it uses are the overlay
permission (to draw the bubble), notifications (for the bubble's status
notification, optional), the microphone (only when you record a voice
note, and only after you grant it), and start-on-boot (only if you
leave the restart toggle on).

## Settings

- **A tap opens**: the Muse app, or muse.ai in the browser
- **Bubble face**: your own day and night avatar images
- **Bubble size**: 44 to 120 dp
- **Idle fade opacity**: how see-through the bubble gets when idle
  (never fully invisible, so you can always find it again)
- **Restart after reboot**: bring the bubble back on its own

## Building

The app is plain Kotlin on framework APIs only, with no third-party
libraries. Release builds run in GitHub Actions (see
`.github/workflows/build-release.yml`): the workflow builds the signed
APK and publishes it to Releases. Signing material arrives as repo
secrets (`KEY_STORE`, `KEY_PASSWORD`, `ALIAS_NAME`, `ALIAS_PASSWORD`);
the keystore is never committed.

## Code map

- `MainActivity.kt` — the settings screen: permission walk-through,
  tap target choice, face pickers, Start/Stop, boot toggle, size and
  opacity sliders, microphone grant
- `FloatingService.kt` — the bubble: tap, double tap, long press and
  drag gestures, drag-to-dismiss, snooze, quick capture, day/night
  faces, fade and pulse. The CHANGE-ME block at the top holds the
  timing and sizing values
- `Faces.kt` — the user's picked face images in app-private storage,
  with the day/night fallback and the bundled default
- `BootReceiver.kt` — restarts the bubble after a reboot when enabled
- `Prefs.kt` — saved target, bubble position, enabled and boot flags,
  size, opacity, snooze deadline, and the Muse-app finder

## License

MIT, see [LICENSE](LICENSE). Muse Float is an unofficial companion
made by a Muse user; it is not affiliated with Meta. "Muse" is used
only to describe what the bubble opens.
