# Booth Survey

An Android app for one person: a retired surveyor in his sixties, walking a street in
India with a phone in one hand, writing down who he has and has not spoken to.

It holds one booth's electoral roll, records what happened at each door, groups people
into households, and prints a booklet in the order he walks the street.

**No account. No server. No internet permission.** The roll is imported to the phone,
kept there, and exported when he is finished.

---

## Install

Grab `booth-survey-<version>.apk` from the [releases page](https://github.com/kedharsairam/Booth-Survey/releases)
and install it on the phone. Android will ask to allow installs from that source.

The app needs **no permissions at all**. It cannot reach the network, cannot read your
contacts, and cannot see your location. Everything it stores is inside the app's own
private folder, and Android's backup is switched off, so the roll does not leave the
phone through any of the usual routes.

---

## Using it

**Import.** Settings → *Import voters list*, and choose the spreadsheet. Four columns:
serial, voter ID, name, house number. Re-importing a later version of the same roll
updates it and leaves everything you typed alone.

**Survey.** The Voters tab is the roll in order. Open somebody, mark them *visited* or
*skipped*, and press back. To do a street, work down the list.

**Households.** The Families tab groups people who live at one door. Add members, then
use ↑ ↓ to put them in the order you want — usually whoever you met first. Hold a card
to drag it into a different place in the walking order.

**Print.** Settings → *Print the list* writes a spreadsheet and a paper booklet, both in
walking order. The booklet is the artefact: one block per house, arranged the way you
arranged it.

**Backup.** Settings → *Back up* writes a copy of everything whenever you ask. The app
also takes one by itself into `Downloads/Booth Survey/` when the booth changes, keeping
the last 20.

---

## Built for a phone held at arm's length, outdoors

This app is not for the person who wrote it. It is for someone who is sixty-plus, may be
squinting into bright sun, and is standing on a step holding a phone one-handed while
talking to a stranger. So:

- **Nothing is under 17sp.** The type scale starts at 17sp for the smallest thing he has
  to read, and most of it is much larger.
- **Every touch target is at least 48dp** — Android's own floor, not the smaller 44dp.
- **Text contrast is 5.9:1 or better**, against a WCAG AA requirement of 4.5:1.
- **Card borders and row dividers are 3:1 or better.** Borders below that vanish in
  daylight, and a household whose edge has faded is not a household.
- **Status is a word, not a colour.** Green says *done*, amber says *skipped*, and both
  say so in letters. Colour is never the only signal.
- **It holds up at 1.3× system font**, which is a size a 60-year-old with presbyopia is
  likely to be running. Rows grow; nothing is clipped or lost off the end.
- **Every menu says which field it belongs to** and ticks the value already chosen, so
  there is no need to remember where you were.
- The core loop is four taps per person: open, *visited*, next.

---

## Building it

Requires a JDK and the Android SDK. The keystore is not in this repository and is not
needed for a debug build.

```sh
cd android
./gradlew assembleDebug          # build
./gradlew testDebugUnitTest      # 211 tests
./gradlew lintDebug              # Android lint
node ../tools/roll-check.py      # no roll data in the tree or in history
```

A release build is signed from `android/keystore.properties`, which is gitignored. The
build refuses to produce a shippable release without it.

---

## Tests

211 JVM unit tests covering the parts where being wrong matters quietly: the import, the
export, the walking order, household grouping, and the on-disk format. The PDF and
Compose UI are covered by instrumentation tests, run on a device.

`tools/roll-check.py` runs in CI and fails the build if a voter's name, EPIC number or
house number is found anywhere in the repository **or in its history**. That is not
hypothetical: eight real names, two real voter IDs and fifty-one real house numbers were
committed here while the work was in progress, and are being removed. The guard exists so
that the next person to open a real voter card does not quietly paste one into a test.

---

## Privacy

Nothing you do in this app is sent anywhere, because the app has no way to send it. No
network permission, no analytics, no crash reporting, no accounts, no cloud.

The one deliberate exception is the automatic backup into `Downloads/Booth Survey/`,
which is a plain unencrypted file in shared storage so a file manager can restore it. It
is capped at 20 copies and is mentioned on the Settings screen.

---

## Licence

See [LICENSE](LICENSE).
