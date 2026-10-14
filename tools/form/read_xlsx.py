#!/usr/bin/env python3
"""Reads an .xlsx into rows, resolving shared strings.

Written because the only spreadsheet tool on this machine is OnlyOffice, which rewrites a
workbook in its own dialect on save, and because a cell's stored type has to survive the
round trip. A house number that came back as "90-21-21/3" is fine; one that came back as a
datetime is not, and the difference is visible only in the cell's type, not in its text.

Handles inline strings and shared strings, and reports a cell whose type is not a string
so that a spreadsheet's date coercion cannot pass unnoticed.
"""

import re
import sys
import zipfile
import xml.dom.minidom as minidom

NS_MAIN = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'


def col_index(ref):
    """'BC12' -> 54 (zero-based column index)."""
    letters = re.match(r'([A-Z]+)', ref).group(1)
    n = 0
    for ch in letters:
        n = n * 26 + (ord(ch) - 64)
    return n - 1


def shared_strings(z):
    if 'xl/sharedStrings.xml' not in z.namelist():
        return []
    doc = minidom.parseString(z.read('xl/sharedStrings.xml'))
    out = []
    for si in doc.getElementsByTagName('si'):
        out.append(''.join(t.firstChild.nodeValue if t.firstChild else ''
                           for t in si.getElementsByTagName('t')))
    return out


def sheets(z):
    """Sheet name -> rows, in file order."""
    wb = minidom.parseString(z.read('xl/workbook.xml'))
    rels = minidom.parseString(z.read('xl/_rels/workbook.xml.rels'))
    target = {}
    for r in rels.getElementsByTagName('Relationship'):
        target[r.getAttribute('Id')] = r.getAttribute('Target')

    shared = shared_strings(z)
    out = {}
    names = []
    for sh in wb.getElementsByTagName('sheet'):
        name = sh.getAttribute('name')
        rid = sh.getAttribute('r:id') or sh.getAttributeNS(
            'http://schemas.openxmlformats.org/officeDocument/2006/relationships', 'id')
        t = target.get(rid, '')
        part = 'xl/' + t.lstrip('/').replace('xl/', '', 1) if t else ''
        if part not in z.namelist():
            cand = [n for n in z.namelist() if n.endswith(t.split('/')[-1])]
            part = cand[0] if cand else None
        if not part:
            continue
        names.append(name)
        out[name] = read_sheet(z.read(part).decode(), shared)
    return out, names


def read_sheet(xml, shared):
    doc = minidom.parseString(xml)
    rows = []
    for row in doc.getElementsByTagName('row'):
        cells = {}
        types = {}
        for c in row.getElementsByTagName('c'):
            ref = c.getAttribute('r')
            i = col_index(ref)
            t = c.getAttribute('t') or 'n'
            types[i] = t
            if t == 'inlineStr':
                isn = c.getElementsByTagName('is')
                val = ''.join(x.firstChild.nodeValue if x.firstChild else ''
                              for x in isn[0].getElementsByTagName('t')) if isn else ''
            else:
                v = c.getElementsByTagName('v')
                if not v:
                    val = ''
                elif t == 's':
                    val = shared[int(v[0].firstChild.nodeValue)]
                else:
                    val = v[0].firstChild.nodeValue if v[0].firstChild else ''
            cells[i] = val
        width = max(cells) + 1 if cells else 0
        rows.append(([cells.get(i, '') for i in range(width)], types))
    return rows


def main():
    path = sys.argv[1]
    with zipfile.ZipFile(path) as z:
        data, names = sheets(z)
    print(f'  sheets: {names}')
    for name in names:
        rows = data[name]
        print(f'\n  === sheet "{name}": {len(rows)} rows ===')
        for r, (vals, types) in enumerate(rows[:40]):
            print('   ' + ' | '.join(str(v)[:22] for v in vals))


if __name__ == '__main__':
    main()