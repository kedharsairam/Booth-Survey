# Booth Survey

An offline Android app and an A4 booklet generator for a door-to-door survey, built from an
electoral roll.

**The app ships with no voter data in it.** Not in this repository, not in the APK, not on the
phone until somebody imports a sheet. A list of named voters with their IDs and house numbers
has no business being baked into a binary anyone can install, and the app is meant to be usable
by whoever is doing a booth rather than only by the ward it was written for.

Four tabs: **Home**, **Voters**, **Families**, **Settings**, with the active booth named in the
app bar of all four.

## Three rules shape everything

1. **Serial is the hero.** It is the one value the extraction got perfectly, so the app's list
   is ordered on it. The booklet is ordered on door number instead, so a street is walked in one
   direction.
2. **A family exists only because somebody made one.** Nothing is inferred from the door number.
   A house number in an electoral roll is a printing convention, not a record of who lives where.
3. **Every field is editable, including the serial.** The roll this was extracted from is a
   draft. When the finalized list renumbers people, editing the number is two seconds and
   redoing an extraction is an afternoon. Each voter therefore carries a hidden id that nothing
   keys on, so duplicate serials are survivable.

Nothing happens without being asked for. Creating a voter opens an empty form that is not
attached to anything and is discarded if you go back; deleting one asks first, from the list row
or from the voter. There is no undo, because every destructive action is confirmed instead.

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
editable in Settings. They are not in the sheet but they are printed on every booklet page.

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
  recoverable here, but if the roll struck them off as dead or moved away the booklet would
  otherwise still send you to their door

Re-importing the same roll changes nothing, and says so: "0 added, 0 changed, 1129 already
matched".

## Print order

The booklet prints families -- or single people not yet in a family -- by:

1. the **lowest door number** among the members, compared by value rather than as text, so
   `10-1-5` comes before `10-1-12`
2. ties broken on the **lowest electoral serial** in the block
3. within a block, the order **you arranged**, with the up and down arrows
4. a **blank door number sorts last** and never merges

Nobody is left out: anyone not yet in a family prints as a numbered family of one. Family
numbers restart at 1 in each booklet and a family is never split across a page.

## Settings

Three managed lists, shared across every booth -- caste is the same seven categories for a whole
constituency and area names overlap between neighbouring booths. All three also learn from use,
so a value typed on a voter is offered next time.

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

Forty-six tests, concentrated on the things that quietly corrupt data:

- the reader finds the four columns **by name, not position**, across shared strings and inline
  strings and a sheet that is not `sheet1.xml`; a missing column loads nothing rather than
  loading blanks
- re-importing the same roll changes nothing; renumbering everybody still matches by voter ID; a
  blank cell does not erase; the same voter ID twice does not collapse two people into one
- print order by value not as text, family at its lowest door number, ties on roll order, blanks
  last, family never split across a page
- door numbers and voter IDs stay text in the spreadsheet
- the template the app writes is one the app can read back

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
