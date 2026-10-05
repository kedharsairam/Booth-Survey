"""Map each heading in the form template to the width it actually gets.

A .docx declares a table twice over: once as <w:gridCol> widths, and once per row
as cells carrying a <w:gridSpan> saying how many grid columns they cover. Those two
have to agree. When they do not, the file still opens -- Word reconciles it somehow
-- but the column a heading ends up in is not the one its grid width describes, and
the arithmetic stops meaning anything.

This computes the width each heading really receives, under the span mapping, and
compares it against the intent, so a mismatch is visible rather than inferred.
"""

import re
import sys
import zipfile

TW_PER_MM = 1440.0 / 25.4

# What each column has to hold, and how wide the previous working booklet made it.
# These are the widths the Telugu template and the verified demo booklet actually
# used, not a guess -- the first version of this check compared against numbers that
# were themselves wrong and flagged a correct 12.51 mm family column as too narrow.
PREVIOUS_MM = {
    "family": 12.51, "door": 20.00, "name": 67.50,
    "voter_list_sr": 14.99, "voter_id": 32.51, "phone": 49.90,
    "address": 27.50, "sub_caste": 20.00, "others": 18.24,
}
# "Caste" is new, so there is no previous width to compare against; 10 mm has to
# hold one of four or five short codes, which is enough for "BC-B" at 10 pt.
NEW_COLUMN_MM = {"Caste": 10.00}


def mm(tw):
    return tw / TW_PER_MM


def main():
    path = sys.argv[1]
    with zipfile.ZipFile(path) as z:
        xml = z.read("word/document.xml").decode("utf8")

    size = re.search(r'<w:pgSz[^>]*?w:w="(\d+)"[^>]*?w:h="(\d+)"', xml)
    if not size:
        size = re.search(r'<w:pgSz[^>]*?w:h="(\d+)"[^>]*?w:w="(\d+)"', xml)
        if size:
            w, h = int(size.group(2)), int(size.group(1))
        else:
            w = h = 0
    else:
        w, h = int(size.group(1)), int(size.group(2))
    if w:
        print(f"  page {mm(w):.2f} x {mm(h):.2f} mm  printable width {mm(w) - 25.4:.2f} mm")

    tbl = re.search(r"<w:tbl>[\s\S]*?</w:tbl>", xml).group(0)
    grid = [int(m) for m in re.findall(r'<w:gridCol[^>]*w:w="(\d+)"', tbl)]
    print(f"  grid columns declared: {len(grid)}   total {mm(sum(grid)):.2f} mm")

    rows = re.findall(r"<w:tr[ >][\s\S]*?</w:tr>", tbl)
    row2 = rows[1]
    cells = re.findall(r"<w:tc>[\s\S]*?</w:tc>", row2)

    print(f"\n  heading row: {len(cells)} cells\n")
    print(f"  {'heading':<20} {'span':>4} {'cols':>10} {'width mm':>10}   note")

    col = 0
    total_spans = 0
    rows_out = []
    for c in cells:
        text = "".join(re.findall(r"<w:t[^>]*>([^<]*)</w:t>", c)).strip()
        s = re.search(r'<w:gridSpan[^>]*w:val="(\d+)"', c)
        span = int(s.group(1)) if s else 1
        total_spans += span
        lo, hi = col + 1, col + span
        if hi <= len(grid):
            width = mm(sum(grid[col:col + span]))
        else:
            width = float("nan")
        flag = ""
        if hi > len(grid):
            flag = f"RUNS PAST THE GRID (needs col {hi}, grid has {len(grid)})"
        rows_out.append((text, span, lo, hi, width, flag))
        col += span

    for text, span, lo, hi, width, flag in rows_out:
        w = "n/a" if width != width else f"{width:.2f}"
        print(f"  {text:<20} {span:>4} {str(lo) + '-' + str(hi):>10} {w:>10}   {flag}")

    print(f"\n  spans sum to {total_spans}; grid declares {len(grid)}")
    if total_spans != len(grid):
        delta = total_spans - len(grid)
        print(f"  MISMATCH of {delta:+d} column(s) -- the row's spans and the")
        print("  table's grid do not describe the same table.")

    print("\n  --- compared with the verified demo booklet ---")
    key = {"Family sr.no.": "family", "Door no.": "door", "Full Name": "name",
           "Voter List sr.no.": "voter_list_sr", "Voter ID no.": "voter_id",
           "Phone Number": "phone", "Current Address": "address",
           "Sub Caste": "sub_caste", "Others": "others"}
    for text, span, lo, hi, width, flag in rows_out:
        if width != width:
            continue
        if text in NEW_COLUMN_MM:
            print(f"  {text:<20} {width:>7.2f} mm   NEW COLUMN, no previous width"
                  f"   {'tight but usable' if width >= 9 else 'TOO NARROW'}")
            continue
        k = key.get(text)
        if not k:
            continue
        ref = PREVIOUS_MM[k]
        delta = width - ref
        verdict = "same" if abs(delta) < 0.6 else (f"{delta:+.2f} mm vs booklet")
        print(f"  {text:<20} {width:>7.2f} mm   booklet used {ref:>6.2f} mm   {verdict}")


if __name__ == "__main__":
    main()