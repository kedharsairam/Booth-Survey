# Booth Survey

An offline Android app for a door-to-door survey, built from an electoral roll.

**The app ships with no voter data in it.** Not in this repository, not in the APK, not on the
phone until somebody imports a sheet. A list of named voters with their IDs and house numbers
has no business being baked into a binary anyone can install, and the app is meant to be usable
by whoever is doing a booth rather than only by the ward it was written for.

Four tabs: **Home**, **Voters**, **Families**, **Settings**, with the active booth named in the
app bar of all four.

## Three rules shape everything

1. **Serial is the hero.** It is the one value the extraction got perfectly, so the app's list
   is ordered on it. Families print in the order you arrange them, so a street is walked the
   way you walked it, not the way the roll numbers it.
2. **A family exists only because somebody made one.** Nothing is inferred from the door number.
   A house number in an electoral roll is a printing convention, not a record of who lives where.
3. **Every field is editable, including the serial.** The roll this was extracted from is a
   draft. When the finalized list renumbers people, editing the number is two seconds and
   redoing an extraction is an afternoon. Each voter therefore carries a hidden id that nothing
   keys on, so duplicate serials are survivable.

Nothing happens without being asked for. Creating a voter opens an empty form that is not
attached to anything and is discarded if you go back; deleting asks first, one voter from
their screen or many at once from selection, with the count on the question. There is no
undo, because every destructive action is confirmed instead.

## Importing

Settings -> Import voters list picks a spreadsheet through the system picker, which needs **no
permission** -- the grant covers the one file you chose. The sheet needs four columns, in any
order:

| column | also accepted as |
| --- | --- |
| `serial` | serial no, sl no |
| `voter id` | voter_id, voter id no |
| `name` | full name |
| `house number` | house, house no, door no |

Case, spaces and underscores are ignored, so `Voter ID`, `voter_id` and `voterid` are the same
column. If a required column is missing the import says which and loads nothing.

There is no bundled roll and no seed to generate. Settings -> **Export blank template** writes a
sheet with those four headings and one example row, for somebody starting a list from their own
roll.

Mandal, town or ward and booth number are asked for at import, remembered afterwards, and
editable in Settings.

## Booths are kept apart

Each booth is **its own file**. Covering a second booth cannot put a question mark against a
day's work on the first, and that guarantee is structural rather than a promise: a write that
goes wrong while saving booth 109 lands on booth 109's file, and the two never share a byte.

Importing a booth number that is already present **updates** it rather than replacing it:

- rows are matched to existing records by **voter ID first, serial second**. Voter ID is the
  identity key, so it is the only join that survives the finalized list renumbering everybody.
  Joining on serial after a renumber would attach one person's phone number to another person.
- the sheet wins on the four fields it supplies; phone, caste, address, family and who has been
  visited are untouched
- a **blank cell does not erase** a value already held, so a partial export cannot quietly
  delete names read off the roll
- anyone in the booth but not in the sheet is **kept and marked**, not deleted. Deleting is not
  recoverable here, but if the roll struck them off as dead or moved away the sheet would
  otherwise still send you to their door

Re-importing the same roll changes nothing, and says so: "0 added, 0 changed, 1129 already
matched".

## Voters tab

The list is in serial order with the serial leading every row, and nothing sits above
it: search lives in the top bar (tap the glass, the title becomes the field), and every
filter lives in one bottom sheet behind the tune icon, which carries the count of what is
on. Status, caste, party, address and not-in-the-latest-file combine with the search, and
everything applies live. Home's search ignores all of it -- "search anyone" means anyone.

Holding a row starts ticking. The bar works on all ticked rows at once: visited, back to
to-visit, skipped, moved to a family (an existing one, or a new one made of them), or
removed with the count on the confirmation.

## Families tab

Each card carries its walking number, display only and never exported: 1, 2, 3 down the
street in the order the sheet will print. Reorder with the grip or the arrows -- the
arrows stay because a screen reader cannot drag. Inside a family, members drag the same
way, and the family takes the name of whoever stands first. The detail screen says
where the family stands ("walking order 2 of 5").

## Print order

The spreadsheet prints families in the order **you arranged them** on the Families tab
-- the street is walked the way you walked it. Within a family, your member arrangement.
Anyone not yet in a family prints after every family, in roll order: build a family for
everyone, even one person on their own, and there will be none.
## Settings

Booth (rename, import), the survey sheet (blank template export), and the shared lists,
each in its own section. The three managed lists -- caste is the same seven categories
for a whole constituency and area names overlap between neighbouring booths -- sit in an
accordion, one open at a time with the count on the header, instead of three always-open
walls. All three also learn from use, so a value typed on a voter is offered next time.

Address takes an area name, not a full address.

There is no "restore the bundled roll" button, deliberately. Its worst outcome was somebody
tapping through a confirmation and losing a day of work. Updating a roll happens by importing
the new sheet, which cannot lose anything.

## Building

Nothing to generate first -- there is no seed.

```sh
cd android
~/.local/gradle-8.9/bin/gradle assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk ../dist/voter-survey.apk
```

## Tests

```sh
cd android && ~/.local/gradle-8.9/bin/gradle testDebugUnitTest
```

Fifty-three unit tests plus a device test, concentrated on the things that quietly corrupt data:

- the reader finds the four columns **by name, not position**, across shared strings and inline
  strings and a sheet that is not `sheet1.xml`; a missing column loads nothing rather than
  loading blanks. The BLO sheet's own headers ("Sl No", "House No", "Voter Name", "EPIC No")
  are covered.
- re-importing the same roll changes nothing; renumbering everybody still matches by voter ID; a
  blank cell does not erase; the same voter ID twice does not collapse two people into one
- the arranged family order survives a store round-trip and the spreadsheet rows follow it
- the list filters combine (status, family, caste, party, not-in-file) and the bulk moves
  append in roll order
- door numbers and voter IDs stay text in the spreadsheet
- the template the app writes is one the app can read back
- on-device: holding a member's grip past the long-press timeout and pulling it up reorders
  the family through the real gesture path (`DragTest`, needs a device)

## Design

Spacing, type, radius, motion and touch targets come from
[kraft-foundation](https://github.com/kedharsairam/kraft-foundation), which is also where the
standard this app is built to is written down. It targets **standard 1.0.0**, and
`kraft-lint` in that repository is what checks it.

This repo stays private: it carries no voter data (see the .gitignore essay at the top of
this project — code goes in, data does not), but it was written for a roll and is kept
private anyway. The foundation is public and the dependency flows inward only, so nothing
about the roll leaves through it.

The app keeps its own theme system — teal-paper light and dark schemes, status colours,
the `Space` CompositionLocal — with values now sourced from the shared tokens where they
match. Its own dimensions (`BoothMetrics`: 52dp field heights across five screens,
list and card minimums) stay local with their reasons, as do the hero display sizes
(40/26) and the shapes' raw steps, which live in the exempt theme file.

## Zero permissions

No permissions are declared and none are needed. Import and export both go through the system
document picker, which grants access to the single file that was chosen and nothing else.

Verify it rather than trusting it:

```sh
$(ls $HOME/Android/Sdk/build-tools/*/aapt2 | head -1) dump permissions dist/voter-survey.apk
```

## Support

If you enjoy Booth Survey, buy me a coffee:

<p align="center">
  <a href="https://buymeacoffee.com/kedhartech"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" width="182"></a>
</p>
