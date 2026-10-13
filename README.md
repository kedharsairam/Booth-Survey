# Booth Survey

Offline Android app for a door-to-door survey, built from an electoral roll. Prints an A4
booklet on the standard form.

**Ships with no voter data.** Not in this repo, not in the APK, not on the phone until
somebody imports a sheet.

## What it does

Four tabs, with the active booth named in the app bar of all four.

| | |
| --- | --- |
| **Home** | how much is left, and the two things you start work with |
| **Voters** | every person in serial order, with filters and bulk actions |
| **Families** | who lives with whom, and the order the street gets walked |
| **Settings** | import, backup, shared lists, about |

## Import

Settings → **Import voters list** picks a spreadsheet through the system picker. No
permission: the grant covers the one file you chose.

Four columns, any order, names matched loosely — `Voter ID`, `voter_id` and `voterid` are
the same column:

| column | also accepted as |
| --- | --- |
| `serial` | serial no, sl no |
| `voter id` | voter_id, voter id no |
| `name` | full name |
| `house number` | house, house no, door no |

A missing column is named and nothing loads.

Each booth is **its own file**. A second booth cannot damage the first. Re-importing a booth
already present **updates** it: matched on voter ID first, the sheet wins only on the four
fields it supplies, a blank cell never erases, and anyone in the app but not in the sheet is
kept and marked rather than deleted.

## Survey

Serial leads every row, and the list is ordered on it. Search is in the top bar; every filter
is in one sheet. Status, caste, party, address and not-in-the-latest-file combine.

Tick the circle on a row and the bar that appears works on everything ticked: visited, back
to to-visit, skipped, moved to a family, or removed.

**Skipped is not finished.** A skipped house is one nobody has been to, and it stays on the
list and in the count until someone goes.

## Families

Nothing infers a family from a door number — a house number in a roll is a printing
convention, not a record of who lives where. You make them.

Reorder with the arrows or by holding and dragging. The printed order is the order you
arranged, so the street is walked the way you walked it.

## Print

**Booklet for printing** — 15 forms to a sheet, 4 sheets to a booklet, laid out from the
standard form's own measurements. Row 1 carries the mandal, town or ward and booth number.

**Excel list (for the office)** — every field, names and door numbers kept as text.

Both offer *people still to visit only*, which means the same set of people in both files.

Filenames carry the date and time: `booklet-booth-98-13Oct-0619.pdf`.

## Backup

The work lives on one phone.

- **Back up** writes the whole booth — people, visits, families, and the words typed along the
  way — as one file wherever you say.
- The app also **saves one by itself** into `Downloads/Booth Survey/`, when the booth has
  changed and either it is a new day or six hours have passed. The newest twenty are kept.
- **Put back** shows you what is in a backup before touching anything, and saves what is
  being replaced first. A restore is never the only copy of anything.

## Not losing your place

The tab, both searches and every filter are remembered, so the app opens where you left it.
The last thing you changed can be put back — and that undo survives the app being closed, with
a twelve-hour limit.

## Accessibility

Built for somebody over sixty.

- Every tap target is at least 48dp
- Nothing important needs a gesture — every hold has a button
- Works at a large font; serials and counts never truncate
- Screen-reader labelled throughout, with headings, roles and state
- No jargon on screen, and nothing said by colour alone

## Building

Nothing to generate first — there is no seed.

```sh
cd android
~/.local/gradle-8.9/bin/gradle assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk ../dist/voter-survey.apk
```

## Tests

```sh
cd android && ~/.local/gradle-8.9/bin/gradle testDebugUnitTest
```

83 unit tests, plus a device test for the drag gesture. They concentrate on what quietly
corrupts data: re-import changing nothing, blank cells not erasing, the arranged family order
surviving a round-trip, and what "done" and "still to visit" mean — in the headline, in the
spreadsheet and in the booklet at the same time.

## Zero permissions

None are declared and none are needed. Verify it rather than trusting it:

```sh
$(ls $HOME/Android/Sdk/build-tools/*/aapt2 | head -1) dump permissions dist/voter-survey.apk
```

## Design

Spacing, type, radius, motion and touch targets come from
[kraft-foundation](https://github.com/kedharsairam/kraft-foundation), where the standard this
app is built to is written down.

This repo is private. It carries no voter data, and the dependency flows inward only.

## Support

If you enjoy Booth Survey, buy me a coffee:

<p align="center">
  <a href="https://buymeacoffee.com/kedhartech"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" width="182"></a>
</p>