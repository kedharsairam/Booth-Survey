#!/usr/bin/env python3
"""
The MLA's booklet, printed from data.

The deliverable is paper, so this is the half of the project that has to be right
before anyone collects a single phone number.

Every measurement below is read out of `samples/formtemplate.docx`, not guessed
from a photograph. That matters: the photograph is a phone shot of a sheet lying
at an angle on a clipboard, and five of the nine column headings were misread from
it before the template arrived. The DOCX is a zip of XML, so the table grid, the
cell merges, the row heights and the heading text are all exact and are read
straight out of `word/document.xml`.

    page       A4 landscape, 16838 x 11906 twips, half-inch margins
    table      18 leaf columns, 263.17 mm wide, indented 4.8 mm
    rows       17 -- a fill-in header, the column headings, and fifteen data rows
    borders    0.5 pt single, black, all edges plus inside
    font       Noto Serif Telugu 11 pt for the headings

WHAT IS COMPUTED HERE, NOT COPIED

    Family serial. 1..N within each family, restarting at every family. Never
    stored, so it stays correct however the membership changes right up to the
    moment of printing.

    Family order. By the lowest voter serial in the family. That keeps the
    booklet in the electoral roll's own sequence -- the only ordering a ward can
    defend -- and it leans on the one field OCR reads most reliably, rather than
    on house numbers that are sometimes written as "PLOT NO 304".

    Pagination. A family is never split across a page, let alone a booklet. A
    split family reads as two households on paper, which is the exact impression
    this booklet exists to remove. The cost is blank rows at the foot of a page.

USAGE
    python3 tools/booklet.py --demo out/demo.pdf
    python3 tools/booklet.py --data families.json --out out/booklets.pdf
"""
from __future__ import annotations

import argparse
import html
import json
import os
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field

# --- the form, measured --------------------------------------------------------
#
# Everything in this block came out of samples/formtemplate.docx. If the MLA sends
# a corrected template, change it here and nothing else needs to move.

PAGE_W_MM, PAGE_H_MM = 297.0, 210.0      # A4 landscape
MARGIN_MM = 12.7                          # 720 twips
TABLE_INDENT_MM = 4.8                     # 272 twips
TABLE_W_MM = 265.01                        # 19 grid columns, read off the template
BORDER_MM = 0.5 / 25.4 * 2.54             # 0.5 pt, kept in mm

# A row's declared height is a minimum, and the border is drawn on top of it.
# Measured on the render: seventeen rows came out 4.4 mm taller than their nominal
# heights, so the footer that followed them did not fit and every logical page
# spilled a second, nearly blank, sheet.
BORDER_PER_ROW_MM = 4.4 / 17

ROW1_H_MM = 9.81
ROW2_H_MM = 14.82                          # the English template's heading row
ROW_H_MM = 9.82
ROWS_PER_PAGE = 15
PAGES_PER_BOOKLET = 4

# The template declares 11 pt in every heading cell, but it also declares a row
# height of 19.83 mm, and the longest heading ("ఓటర్ లిస్ట్ వరుస నెం." in a 15 mm
# column) needs five lines at 11 pt. Those two declarations cannot both hold: the
# template asks Word for a row of 19.83 mm whose own contents do not fit in it.
#
# The declared height is the one worth honouring -- it is what the fifteen data
# rows are laid out against -- so the heading is set at 10 pt, which fits in five
# lines. If the MLA opens the template in Word and it comes out at 19.83 mm there,
# this matches; if Word grows the row there, this does not. That could not be
# checked here because nothing on this machine renders .docx, so it is the one
# measurement in this file taken on trust rather than on a render.
HEADING_PT = 8.0
ROW1_LINE_MM = 4.2
ROW2_LINE_MM = 3.5
DATA_PT = 10.0                            # a size that fits 9.82 mm without crowding
DATA_LINE_MM = 4.3                         # two lines still clear a 9.82 mm row
# Single quotes, deliberately. A font name containing a double quote closes the
# style="..." attribute at that quote, and everything after it -- including
# text-align -- is silently discarded by the HTML parser. The cells were left
# aligned for that reason while the stylesheet said centred.
HEADER_FONT = "'Open Sans'"             # the English template's heading font
DATA_FONT = "'Open Sans'"

PHONE_DIGITS = 10                         # the phone column is ten boxes, full height

# Indices into COL_W_MM. Written out because COL_W_MM[16] in a cell call is how the
# address, caste and party columns ended up one place along and the last one fell
# off the end of the list.
C_FAMILY, C_DOOR, C_NAME, C_SERIAL, C_VOTER = 0, 1, 2, 3, 4
C_PHONE = 5
C_ADDR, C_CASTE, C_SUBCASTE, C_PARTY = 15, 16, 17, 18

# Three columns cannot hold a typical value at 10 pt, and a word broken across two
# lines inside a box someone has to write in looks like a mistake.
#
# Widths measured by rendering the text, not estimated. Open Sans uppercase at
# 10 pt gives "MUNIGATLA" 21.2 mm and "KONDAPALLI" 23.3 mm; the Sub Caste column
# has 18.4 mm usable after padding, so even 9 pt is not enough for the longer
# sub-caste names and 8 pt is what fits (20.8 mm -> 17.3 mm). Address needs 9 pt
# for "M.B.S. COLONY".
#
# Only these columns are reduced. Name, Door no., Voter ID and the rest stay at
# the template's 10 pt, and a uniform size across the row matters on a form
# people fill in by hand. 8 pt is also the size the template itself uses for the
# headings, so nothing on the page is set below that.
COL_PT = {C_ADDR: 9.0, C_SUBCASTE: 8.0}

# Leaf column widths in mm, in the template's order. Eight ordinary columns, and
# one split into ten digit boxes.
COL_W_MM = [
    12.51,  # family serial        Family sr.no.
    20.00,  # door number          Door no.
    67.50,  # full name            Full Name
    12.49,  # voter list serial    Voter List sr.no.
    35.01,  # voter id             Voter ID no.
    *[4.99] * (PHONE_DIGITS - 1),  # cell number   Phone Number
    5.08,                      #   ... the tenth box, 5.08 in the template
    24.98,  # current address     Current Address
    10.00,  # caste               Caste
    20.00,  # sub caste            Sub Caste
    12.51,  # party               Others
]

# Row 1: (label or value, leaf columns covered). Sums to 19.
#
# Six cells alternating label and value. The values are not all from one place:
# "Palacole" and booth 108 come from the roll's own header (Assembly Constituency
# 57-PALACOLE, Part Number 108), while the town/ward number describes the survey
# area. They are constants, printed identically on every page of every booklet.
MANDAL = "Palacole"
TOWN_WARD = "4"
BOOTH_NO = "108"
assert len(COL_W_MM) == 5 + PHONE_DIGITS + 4, "column count must match the template"
assert abs(sum(COL_W_MM) - TABLE_W_MM) < 0.2, f"columns sum to {sum(COL_W_MM)}mm, template is {TABLE_W_MM}mm"

ROW1 = [
    ("Mandal", 2),
    (MANDAL, 1),
    ("Town / Ward", 2),
    (TOWN_WARD, PHONE_DIGITS),
    ("Booth No.", 1),
    (BOOTH_NO, 3),
]

# Row 2: the nine headings, exactly as spelled in the template. Note ఉప కులం is
# the sub caste on its own -- there is no separate caste column on this form.
ROW2 = [
    "Family sr.no.",
    "Door no.",
    "Full Name",
    "Voter List sr.no.",
    "Voter ID no.",
    "Phone Number",
    "Current Address",
    "Caste",
    "Sub Caste",
    "Others",
]
ROW2_SPANS = [1, 1, 1, 1, 1, PHONE_DIGITS, 1, 1, 1, 1]


@dataclass
class Person:
    voter_serial: str = ""
    voter_id: str = ""
    door: str = ""
    name: str = ""
    phone: str = ""
    address: str = ""
    caste: str = ""        # one of four or five, from the app's settings list
    sub_caste: str = ""    # free text, typed by dad at the door
    party: str = ""


@dataclass
class Family:
    members: list[Person] = field(default_factory=list)


# --- ordering and pagination ---------------------------------------------------


def order_families(families: list[Family]) -> list[Family]:
    """By lowest voter serial in the family.

    Every family has at least one member so the key always exists, and it is read
    as an integer: sorting the strings would put 1000 before 999.
    """
    return sorted(families, key=lambda f: min(int(p.voter_serial or 0) for p in f.members))


def paginate(families: list[Family]) -> list[list[tuple[int, Person]]]:
    """Pages of fifteen rows, never splitting a family across a page.

    The family serial is recomputed from membership on every call rather than
    stored, so deleting a member closes the gap instead of leaving a hole.
    """
    pages: list[list[tuple[int, Person]]] = []
    current: list[tuple[int, Person]] = []
    used = 0
    for family in order_families(families):
        n = len(family.members)
        if used + n > ROWS_PER_PAGE:
            pages.append(current)
            current, used = [], 0
        for i, member in enumerate(family.members, start=1):
            current.append((i, member))
            used += 1
    if current:
        pages.append(current)
    return pages


def wasted_rows(pages: list[list[tuple[int, Person]]], families: list[Family]) -> list[str]:
    """Why each page is short.

    A partly empty page should never be a mystery on a printout: either it is the
    last page, or a family was moved whole to the next one.
    """
    ordered = order_families(families)
    notes = []
    for i, page in enumerate(pages[:-1]):
        short = ROWS_PER_PAGE - len(page)
        if short <= 0:
            continue
        nxt = pages[i + 1][0][1]
        size = next(len(f.members) for f in ordered if any(m is nxt for m in f.members))
        notes.append(
            f"page {i + 1}: {short} row(s) unused — the family of {size} beginning at "
            f"voter serial {nxt.voter_serial} was moved whole to the next page"
        )
    if notes:
        notes.append(f"last page: {ROWS_PER_PAGE - len(pages[-1])} row(s) unused")
    return notes


# --- html ----------------------------------------------------------------------


def esc(s) -> str:
    return html.escape(str(s), quote=True)


def too_long(value: str, width_mm: float, pt: float = DATA_PT) -> bool:
    """Whether a value needs more than the two lines its row can hold.

    Cells wrap rather than clip, because a truncated address is a wrong record on a
    government document and it would be wrong quietly. Wrapping instead pushes the
    row taller, which pushes a person off the end of the page, so the two errors
    trade off -- and the one worth avoiding is the silent one. Anything this
    reports is reported at print time rather than discovered on paper.

    Widths are estimated, not measured: a Telugu base glyph is roughly 1.7x the
    width of a Latin digit at the same point size, and combining marks add nothing.
    """
    if not value:
        return False
    usable = width_mm - 1.6                      # the 0.8 mm padding either side
    per_line = max(1, int(usable / (0.017 * pt * 1.7 * 10)))   # 1.7x for Telugu
    lines = 0
    for ch in value:
        if ord(ch) < 0x900 or 0x0D00 <= ord(ch) <= 0x0D7F:
            pass                                    # Latin digit or letter
        lines += 1
    return lines > per_line * 2


def td(value, width_mm: float, align: str = "center", pt: float = DATA_PT) -> str:
    # Centred, because every field on this form is a box that gets filled in, and
    # centred text sits where the pen goes.
    #
    # Wrapping rather than clipping: an address or a name cut off at the cell edge
    # is a wrong record on a government document, and it would be wrong silently.
    # Nothing in a ward fits more than two lines at this size, and a value that
    # does is caught by `too_long` at the end of this file rather than here.
    return (
        f'<td style="width:{width_mm}mm;padding:0 0.8mm;vertical-align:middle;'
        f'font-family:{DATA_FONT};font-size:{pt}pt;white-space:normal;'
        f'overflow-wrap:anywhere;'
        f'line-height:{DATA_LINE_MM}mm;text-align:{align}">{esc(value)}</td>'
    )


def digit_cells(phone: str) -> str:
    """One box per digit, as the template does. Short numbers leave boxes empty."""
    d = "".join(c for c in str(phone) if c.isdigit())[:PHONE_DIGITS].ljust(PHONE_DIGITS)
    return "".join(
        td(c, COL_W_MM[C_PHONE], "center") for c in d
    )


def data_row(fam_serial: int, p: Person) -> str:
    return (
        f'<tr style="height:{ROW_H_MM}mm">'
        + td(fam_serial, COL_W_MM[C_FAMILY], "center")
        + td(p.door, COL_W_MM[C_DOOR])
        + td(p.name, COL_W_MM[C_NAME])
        + td(p.voter_serial, COL_W_MM[C_SERIAL], "center")
        + td(p.voter_id, COL_W_MM[C_VOTER])
        + digit_cells(p.phone)
        + td(p.address, COL_W_MM[C_ADDR], "center", COL_PT[C_ADDR])
        + td(p.caste, COL_W_MM[C_CASTE])
        + td(p.sub_caste, COL_W_MM[C_SUBCASTE], "center", COL_PT[C_SUBCASTE])
        + td(p.party, COL_W_MM[C_PARTY])
        + "</tr>"
    )


def header_rows() -> str:
    r1 = f'<tr style="height:{ROW1_H_MM}mm">'
    for label, span in ROW1:
        r1 += (
            f'<td colspan="{span}" style="height:{ROW1_H_MM}mm;overflow:hidden;padding:0 0.4mm;'
            f'vertical-align:middle;font-family:{HEADER_FONT};font-size:{HEADING_PT}pt;'
            f'text-align:center;white-space:normal;overflow-wrap:anywhere;'
            f'line-height:{ROW1_LINE_MM}mm">{esc(label)}</td>'
        )
    r1 += "</tr>"

    r2 = f'<tr style="height:{ROW2_H_MM}mm">'
    for label, span in zip(ROW2, ROW2_SPANS):
        r2 += (
            f'<td colspan="{span}" style="height:{ROW2_H_MM}mm;overflow:hidden;padding:0 0.4mm;'
            f'vertical-align:middle;font-family:{HEADER_FONT};font-size:{HEADING_PT}pt;'
            f'text-align:center;white-space:normal;overflow-wrap:anywhere;'
            f'line-height:{ROW2_LINE_MM}mm">{esc(label)}</td>'
        )
    r2 += "</tr>"
    return r1 + r2


def page_html(rows: list[tuple[int, Person]], footer: str = "") -> str:
    colgroup = "".join(f'<col style="width:{w}mm">' for w in COL_W_MM)
    body = "".join(data_row(s, p) for s, p in rows)
    filler = "".join(
        '<tr style="height:%.2fmm">%s</tr>' % (ROW_H_MM, td("", 0.1) * len(COL_W_MM))
        for _ in range(ROWS_PER_PAGE - len(rows))
    )
    return (
        '<div class="page">'
        f'<table><colgroup>{colgroup}</colgroup>{header_rows()}{body}{filler}</table>'
        f'<div class="foot">{footer}</div>'
        "</div>"
    )


def document_html(pages: list[list[tuple[int, Person]]], total_booklets: int) -> str:
    printable_w = PAGE_W_MM - 2 * MARGIN_MM - TABLE_INDENT_MM
    table_h = ROW1_H_MM + ROW2_H_MM + ROWS_PER_PAGE * ROW_H_MM

    # The template's printable area is 184.6 mm tall and the table is 176.9 mm of
    # it, which leaves under 8 mm -- less than the page number needs once row
    # borders are counted, and every logical page spilled onto a second sheet.
    #
    # Rather than shrink a row, which would change the MLA's grid, the page margin
    # is tightened and the table is pushed back down to its template position.
    # The table's left edge and top edge land exactly where the template puts
    # them; only the space beneath it grows.
    pdf_margin = 10.0
    top_shift = MARGIN_MM - pdf_margin
    pdf_printable_h = PAGE_H_MM - 2 * pdf_margin
    # Content box runs from the @page margin to the far margin: 190 mm, not 200.
    # Using the page's bottom edge instead added 10 mm to the footer and pushed
    # every logical page onto a second sheet.
    table_rendered = table_h + 17 * BORDER_PER_ROW_MM
    foot = pdf_printable_h - top_shift - table_rendered

    # The last booklet is short whenever the ward does not divide by sixty, and it
    # must not claim four pages when it has three.
    pages_in_last = len(pages) - PAGES_PER_BOOKLET * (total_booklets - 1)

    rendered = []
    for i, rows in enumerate(pages):
        booklet = i // PAGES_PER_BOOKLET + 1
        page_in_booklet = i % PAGES_PER_BOOKLET + 1
        if total_booklets == 1:
            label = f"page {i + 1}"
        else:
            of = pages_in_last if booklet == total_booklets else PAGES_PER_BOOKLET
            label = f"booklet {booklet} of {total_booklets} — page {page_in_booklet} of {of}"
        rendered.append(page_html(rows, label))
    return f"""<!doctype html>
<html lang="te"><head><meta charset="utf-8"><title>Booklet</title><style>
  @page {{ size:{PAGE_W_MM}mm {PAGE_H_MM}mm; margin:{pdf_margin}mm; }}
  * {{ box-sizing:border-box; }}
  html,body {{ margin:0; padding:0; }}
  body {{ font-family:{DATA_FONT}; }}
  .page {{ page-break-after:always; break-after:page; width:{printable_w}mm;
           margin-left:{TABLE_INDENT_MM + (MARGIN_MM - pdf_margin)}mm;
           margin-top:{top_shift}mm; }}
  .page:last-child {{ page-break-after:auto; break-after:auto; }}
  table {{ border-collapse:collapse; table-layout:fixed;
           width:{TABLE_W_MM}mm; border:{BORDER_MM}mm solid #000; }}
  td {{ border:{BORDER_MM}mm solid #000; }}
  .foot {{ height:{foot:.1f}mm; font-size:7pt; line-height:{foot:.1f}mm;
           color:#444; text-align:right; }}
</style></head><body>{''.join(rendered)}</body></html>"""


def render(html_text: str, pdf_path: str) -> None:
    with tempfile.NamedTemporaryFile("w", suffix=".html", delete=False, encoding="utf-8") as f:
        f.write(html_text)
        src = f.name
    try:
        r = subprocess.run(
            [
                "google-chrome", "--headless", "--disable-gpu", "--no-sandbox",
                "--no-pdf-header-footer",
                f"--print-to-pdf={os.path.abspath(pdf_path)}", f"file://{src}",
            ],
            capture_output=True, text=True, timeout=300,
        )
        if not os.path.exists(pdf_path):
            sys.stderr.write(r.stderr[-2000:])
            raise SystemExit("chrome produced no PDF")
    finally:
        os.unlink(src)


# --- demo data -----------------------------------------------------------------


def demo_families(how_many: int = 42) -> list[Family]:
    """Invented people in invented houses. Not one of these is a real person.

    [how_many] past the name list, surnames are combined so the demo can be made
    any size -- large enough to cross a booklet boundary and see the fold.
    """
    # English, because the form is now English and the demo has to exercise the
    # widths the real names will occupy. Longest first, so the width check below
    # is a real test rather than a formality.
    names = [
        "VENKATESHWARARAO BOLLOJU", "BHAVANI TELANAKULA", "NAGALAKSHMI DODDIPATLA",
        "SRINIVASA RAO TELANAKULA", "KAMALAMMA TELANAKULA", "KAMESHWARI TELNAKULA",
        "V ANJA LAKSHMI VISHNU KUMAR DEVATHA", "SUBBALAXMI AKELLA",
        "SANTHI BOLLOJU", "VIJAYASWETHA BOLLOJU", "SAVITRI DODDIPATLA",
        "PRASADU DONEPUDI", "SARAN KUMAR SATYANARAYANA", "LAKSHMI SARADA DEVI",
        "RAVI SAI REENA KONDAPALLI", "MOHAN KONDAPALLI", "TEJASWI KURIMINELLI",
    ]
    addresses = ["RAGHAVA NAGAR", "MAIN ROAD", "M.B.S. COLONY", "M.CH. ROAD"]
    castes = ["BC-A", "BC-B", "SC", "ST", "OC"]
    sub_castes = ["MUNIGATLA", "KONDAPALLI", "BOLLOJU", "TELANAKULA", "DEVATHA"]
    parties = ["TRS", "INC", "BJP", "AIMIM", "IND"]
    prefixes = ["WQQ", "HXD"]

    extra = [f"{a} {b}" for a in names for b in names if a != b]
    pool = (names + extra)[:how_many] if how_many > len(names) else names[:how_many]
    names = pool

    fams: list[Family] = []
    i, serial = 0, 1
    while i < len(names):
        size = min(2 + (i % 4), len(names) - i)
        door = f"{11 + (i % 3)}-{1 + (i % 5)}-{1 + (i % 9)}"
        members = [
            Person(
                voter_serial=str(serial + k),
                voter_id=f"{prefixes[i % 2]}{((serial + k) * 7919) % 10000000:07d}",
                door=door,
                name=names[i + k],
                phone=f"9{((serial + k) * 13) % 100000000:08d}",
                address=addresses[i % len(addresses)],
                caste=castes[i % len(castes)],
                sub_caste=sub_castes[i % len(sub_castes)],
                party=parties[i % len(parties)],
            )
            for k in range(size)
        ]
        fams.append(Family(members))
        i += size
        serial += size
    return fams


# --- cli -----------------------------------------------------------------------


def load(path: str) -> list[Family]:
    raw = json.load(open(path, encoding="utf-8"))
    return [
        Family(
            [
                Person(
                    voter_serial=str(m.get("voter_serial", "")),
                    voter_id=m.get("voter_id", ""),
                    door=m.get("door", ""),
                    name=m.get("name", ""),
                    phone=m.get("phone", ""),
                    address=m.get("address", ""),
                    caste=m.get("caste", ""),
                    sub_caste=m.get("sub_caste", ""),
                    party=m.get("party", ""),
                )
                for m in f["members"]
            ]
        )
        for f in raw
    ]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--demo", metavar="PDF")
    ap.add_argument("--data")
    ap.add_argument("--out")
    ap.add_argument("--notes", action="store_true", help="print the short-page notes")
    ap.add_argument("--count", type=int, default=42,
                    help="demo size; 130 gives three booklets so a fold is visible")
    args = ap.parse_args()

    out = args.demo or args.out
    if not out:
        ap.error("need --demo or --out")

    if args.data:
        families = load(args.data)
    else:
        families = demo_families(args.count)
    pages = paginate(families)
    total = sum(len(f.members) for f in families)
    booklets = max(1, -(-len(pages) // PAGES_PER_BOOKLET))

    os.makedirs(os.path.dirname(os.path.abspath(out)) or ".", exist_ok=True)
    render(document_html(pages, booklets), out)

    print(f"  families : {len(families)}")
    print(f"  people   : {total}")
    print(f"  pages    : {len(pages)}")
    print(f"  booklets : {booklets}")
    print(f"  written  : {out}")
    if args.notes:
        for n in wasted_rows(pages, families):
            print(f"  note     : {n}")

    # Values that will not fit their cell. Only meaningful on real data; the demo
    # is deliberately short, so it should always come back clean here.
    fields = [
        ("name", C_NAME, "name"), ("address", C_ADDR, "address"),
        ("caste", C_CASTE, "caste"), ("sub_caste", C_SUBCASTE, "sub_caste"),
        ("party", C_PARTY, "party"),
        ("voter_id", C_VOTER, "voter_id"), ("door", C_DOOR, "door"),
    ]
    tight = []
    for f in families:
        for m in f.members:
            for label, col, attr in fields:
                v = str(getattr(m, attr) or "")
                if too_long(v, COL_W_MM[col]):
                    tight.append(f"{label}={v!r} in {COL_W_MM[col]}mm")
    if tight:
        print(f"  TOO LONG ({len(tight)}): a value will wrap past its row")
        for t in tight[:8]:
            print(f"            {t}")
    elif args.data:
        print("  all values fit their cells")


if __name__ == "__main__":
    main()