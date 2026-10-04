"""Read the MLA's form template straight out of word/document.xml.

A .docx is a zip of XML. The geometry that matters -- page size, margins, the
table's column widths, row heights, and the exact heading text in each cell -- is
all in there as numbers. Reading them is the only way to know what the form
actually says; a photograph of a printed blank cannot show column widths, and
reading one that way previously produced five wrong headings out of nine.

This reports what the file *declares*. It is not a rendering: this machine cannot
run Word, and the one converter available refuses to run headless. Row heights in
particular are declared with hRule="atLeast", so they are floors, not guarantees --
Word grows a row whose content needs more room.
"""

import re
import sys
import zipfile

TW_PER_MM = 1440.0 / 25.4          # 1440 twips per inch, 25.4 mm per inch


def mm(tw):
    return f"{tw / TW_PER_MM:.2f}"


def pt(half_points):
    return f"{half_points / 2:.1f}"


def main():
    path = sys.argv[1]
    with zipfile.ZipFile(path) as z:
        xml = z.read("word/document.xml").decode("utf8")

    # ---- page -------------------------------------------------------------
    size = re.search(r'<w:pgSz[^>]*w:w="(\d+)"[^>]*w:h="(\d+)"', xml)
    if size:
        w, h = int(size.group(1)), int(size.group(2))
        orient = "landscape" if w > h else "portrait"
        print(f"  page   {mm(w)} x {mm(h)} mm  ({orient})")
    else:
        print("  page   ?")

    mar = re.search(r"<w:pgMar[^>]*", xml)
    if mar:
        def g(k):
            m = re.search(rf'w:{k}="(\d+)"', mar.group(0))
            return int(m.group(1)) if m else 0
        print(f"  margins mm  top {mm(g('top'))}  right {mm(g('right'))}  "
              f"bottom {mm(g('bottom'))}  left {mm(g('left'))}")

    # ---- table ------------------------------------------------------------
    tbl = re.search(r"<w:tbl>[\s\S]*?</w:tbl>", xml)
    if not tbl:
        print("  no table found")
        return
    t = tbl.group(0)

    grid = [int(m) for m in re.findall(r'<w:gridCol[^>]*w:w="(\d+)"', t)]
    print(f"\n  grid columns: {len(grid)}")
    print(f"    widths mm: {', '.join(mm(w) for w in grid)}")
    print(f"    total    : {mm(sum(grid))} mm")

    rows = re.findall(r"<w:tr[ >][\s\S]*?</w:tr>", t)
    print(f"\n  rows: {len(rows)}")

    for i, r in enumerate(rows, start=1):
        h = re.search(r'<w:trHeight[^>]*w:val="(\d+)"[^>]*', r)
        rule = re.search(r'w:hRule="(\w+)"', h.group(0)) if h else None
        cells = re.findall(r"<w:tc>[\s\S]*?</w:tc>", r)

        texts = []
        for c in cells:
            s = "".join(re.findall(r"<w:t[^>]*>([^<]*)</w:t>", c)).strip()
            if s:
                texts.append(s)

        spans = []
        for c in cells:
            s = re.search(r'<w:gridSpan[^>]*w:val="(\d+)"', c)
            n = int(s.group(1)) if s else 1
            if re.search(r"<w:vMerge", c):
                n += 100                       # marker, not a count
            spans.append(n)

        height = mm(int(h.group(1))) if h else "-"
        print(f"    row {i:>2}  h {height} mm {rule.group(1) if rule else '':<8} "
              f"cells {len(cells):>2}  spans [{', '.join(str(s) for s in spans)}]")
        if texts:
            print(f"              {' '.join(repr(t) for t in texts)}")

    sizes = sorted({int(s) for s in re.findall(r'<w:sz w:val="(\d+)"', xml)})
    print(f"\n  font sizes (pt): {', '.join(pt(s) for s in sizes)}")
    fonts = sorted(set(re.findall(r'w:ascii="([^"]+)"', xml)))
    print(f"  fonts: {', '.join(fonts)}")


if __name__ == "__main__":
    main()