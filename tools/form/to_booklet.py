#!/usr/bin/env python3
"""Converts the reviewed spreadsheet into the booklet generator's input.

The generator wants families, not a flat voter list, because a household must never be
split across a page or a booklet -- someone collecting a survey has to be able to finish
one house before moving on. Grouping is therefore done here, from the door number, and
the generator is given whole families.

The door number is the grouping key and the reason it is trimmed rather than normalised.
"It is a join key and it is also the one field a spreadsheet will helpfully rewrite into
something else", so the rules are deliberately narrow: strip the label text the roll
sometimes prints before the number, and reject anything that does not then look like a
house number. A value that fails is reported rather than guessed, because grouping a
voter under a door number that is wrong invents a household.

Ordering within a family is by electoral serial, and the family's position in the booklet
is by its lowest serial, so the booklet runs in the same order as the roll.
"""

import json
import re
import sys
import zipfile

from read_xlsx import sheets

# The roll prints the door number under more than one label. Measured across the English
# roll: "House Number : 98-3-3/2" on most cards and "DOOR NO. 92-5-5/4" or
# "DOOR NUMBER 93-5-5/5" on a handful -- 7 cards. The first version of the extractor only
# knew the first form and left the label text attached to the number.
LABEL = re.compile(r'^\s*(?:house\s*number|door\s*(?:no\.?|number))\s*[:.\-]?\s*', re.I)

# A door number as the roll prints it: groups of digits separated by dashes, with an
# optional suffix such as "/3", "-C" or a bare letter.
LOOKS_LIKE_HOUSE = re.compile(r'^\d{1,2}-\d{1,3}-\d{1,3}(?:\s*[/ -]?\s*(?:\d{1,2}|[A-Z]))?$')
LOOKS_LIKE_HOUSE_2 = re.compile(r'^\d{1,2}-\d{1,3}$')
LOOKS_LIKE_PLOT = re.compile(r'^(?:plot|flat|house)\s*(?:no\.?|number)?\s*\d+[a-z]?$', re.I)


def clean_door(raw):
    """Returns (door, note). note is non-empty when the value is not usable as-is."""
    v = (raw or '').strip()
    v = re.sub(r'\s+', ' ', v)
    stripped = LABEL.sub('', v).strip().rstrip(',.').strip()

    if not stripped:
        return '', 'no door number'
    if LOOKS_LIKE_PLOT.match(stripped):
        return stripped, f'not a house number, left as printed: {stripped!r}'
    if LOOKS_LIKE_HOUSE.match(stripped) or LOOKS_LIKE_HOUSE_2.match(stripped):
        note = ''
        if stripped != v:
            note = f'label text removed: {v!r} -> {stripped!r}'
        return stripped, note
    return stripped, f'does not look like a house number: {stripped!r}'


def main():
    src = sys.argv[1]
    out_path = sys.argv[2]
    upto = int(sys.argv[3]) if len(sys.argv) > 3 else None

    with zipfile.ZipFile(src) as z:
        data, names = sheets(z)
    sheet = data[names[0]]
    header = [h for h in sheet[0][0]]
    rows = []
    for vals, _types in sheet[1:]:
        d = dict(zip(header, vals))
        try:
            d['serial'] = int(d['serial'])
        except (KeyError, TypeError, ValueError):
            continue
        rows.append(d)
    rows.sort(key=lambda d: d['serial'])
    if upto:
        rows = [d for d in rows if d['serial'] <= upto]

    people = []
    problems = []
    for d in rows:
        door, note = clean_door(d.get('house_number'))
        if note:
            problems.append((d['serial'], d.get('house_number', ''), note))
        people.append({
            'voter_serial': str(d['serial']),
            'voter_id': (d.get('voter_id') or '').strip(),
            'door': door,
            'name': (d.get('name') or '').strip(),
            # Left blank for dad at the door: the roll carries no phone number, and the
            # Caste list is not populated until he fills it in the app.
            'phone': '', 'address': '', 'caste': '', 'sub_caste': '', 'party': '',
        })

    # Group into households by door number. Members keep electoral order; the household
    # itself is placed by its lowest serial so the booklet runs in roll order.
    by_door = {}
    for p in people:
        by_door.setdefault(p['door'], []).append(p)
    families = sorted(by_door.values(), key=lambda ms: int(ms[0]['voter_serial']))
    fams = [{'members': m} for m in families]

    with open(out_path, 'w', encoding='utf-8') as f:
        json.dump(fams, f, indent=1, ensure_ascii=False)

    print(f'  read {len(people)} voters, grouped into {len(fams)} households')
    sizes = {}
    for m in families:
        sizes[len(m)] = sizes.get(len(m), 0) + 1
    print('  households by size: ' + ', '.join(f'{n} member(s) x{c}' for n, c in sorted(sizes.items())))
    print(f'  wrote {out_path}')
    if problems:
        print(f'\n  {len(problems)} door number(s) needing a look:')
        for s, raw, note in problems:
            print(f'    serial {s:>4}  {raw!r}  -- {note}')


if __name__ == '__main__':
    main()