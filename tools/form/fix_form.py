"""Repair the form template's column geometry.

The defect: in rows 2 to 17 the first cell carries <w:gridSpan w:val="2"/>, so those
rows describe a 20-column table while the table's <w:grid> declares 19. Everything
after the first cell is therefore shifted one column left, and three headings land
in columns that cannot hold them -- a voter id gets 4.99 mm, which is less than
one character of "ZZZ9818485".

Row 1 is already consistent (its spans sum to 19), and the grid widths are all
sensible in the order the headings imply. So the grid is right and one attribute
is wrong: the first cell of every data row should span 1 column, not 2. Removing
that makes rows 2-17 sum to 19 like row 1, and each heading receives the width its
neighbour in the grid already declares.

    before   Family 32.51  Door 67.50  Full Name 12.49  Voter ID 4.99  ...
    after    Family 12.51  Door 20.00  Full Name 67.50  Voter ID 35.01  ...

Writes to a new file rather than in place, and refuses to run if the input does not
have the defect it expects, so it cannot silently "fix" a file that has since been
corrected by hand.
"""

import re
import shutil
import sys
import zipfile

def main():
    src, dst = sys.argv[1], sys.argv[2]
    with zipfile.ZipFile(src) as z:
        parts = {n: z.read(n) for n in z.namelist()}
        order = z.namelist()

    xml = parts["word/document.xml"].decode("utf8")

    tbl = re.search(r"<w:tbl>[\s\S]*?</w:tbl>", xml)
    if not tbl:
        sys.exit("no table in document.xml")
    table = tbl.group(0)

    grid = [int(m) for m in re.findall(r'<w:gridCol[^>]*w:w="(\d+)"', table)]
    rows = list(re.finditer(r"<w:tr[ >][\s\S]*?</w:tr>", table))
    print(f"  grid columns: {len(grid)}   rows: {len(rows)}")

    def span_sum(row_xml):
        total = 0
        for c in re.findall(r"<w:tc>[\s\S]*?</w:tc>", row_xml):
            s = re.search(r'<w:gridSpan[^>]*w:val="(\d+)"', c)
            total += int(s.group(1)) if s else 1
        return total

    before = [span_sum(r.group(0)) for r in rows]
    print(f"  span sums per row before: {before}")
    if all(s == len(grid) for s in before):
        sys.exit("rows already agree with the grid; refusing to touch the file")

    # Drop the gridSpan from the first cell of every row that overruns the grid.
    fixed_rows = 0
    out = []
    last = 0
    for r in rows:
        row_xml = r.group(0)
        if span_sum(row_xml) != len(grid):
            def kill_first_span(m):
                return m.group(1)          # drop the gridSpan, keeping the cell open
            new_row, n = re.subn(
                r"(<w:tc>(?:(?!</w:tc>)[\s\S])*?)<w:gridSpan[^>]*w:val=\"2\"\s*/>",
                kill_first_span,
                row_xml,
                count=1,
            )
            if n:
                fixed_rows += 1
                row_xml = new_row
        out.append(table[last:r.start()] + row_xml)
        last = r.end()
    out.append(table[last:])
    new_table = "".join(out)

    after = [span_sum(r.group(0)) for r in re.finditer(r"<w:tr[ >][\s\S]*?</w:tr>", new_table)]
    print(f"  span sums per row after:  {after}")
    print(f"  rows repaired: {fixed_rows}")
    if any(s != len(grid) for s in after):
        sys.exit("still mismatched after repair; not writing")

    parts["word/document.xml"] = (xml[:tbl.start()] + new_table + xml[tbl.end():]).encode("utf8")

    shutil.copyfile(src, dst)
    with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as z:
        for n in order:
            z.writestr(n, parts[n])
    print(f"  written {dst}")


if __name__ == "__main__":
    main()