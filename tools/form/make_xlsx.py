#!/usr/bin/env python3
"""Writes the extracted data as a real .xlsx with the identifier columns stored as text.

Why this exists
---------------
Opening the CSV in a spreadsheet gets the house numbers silently rewritten. "98-3-3/2",
"94-2-2/4" and "91-3-3/5" are all parseable as dates, and a spreadsheet that type-guesses
on import turns them into date values -- 11 March 2001, 10 January 2044, and so on. The
number is then no longer the number that is printed on the card, which is the one number
in this dataset that must not be altered, because it is what identifies a household.

There is no way to defeat this from inside a CSV. Quoting does not help: a quoted field
containing only digits and hyphens is still text that the importer is entitled to
reinterpret, and OnlyOffice does. A leading apostrophe would work in a cell but would
become part of the value in the file.

So the data is written as a genuine spreadsheet with the vulnerable columns held as text.
A cell stored as a text type is not a candidate for date conversion, so the importer has
nothing to guess and leaves it alone.

Writes two sheets: the full extracted list, and the shorter flagged list.
"""

import csv
import sys
import zipfile
from xml.sax.saxutils import escape

# Columns kept as text. Everything that could be mistaken for a date or a number, or that
# is an identifier where leading zeros and format both matter.
TEXT_COLUMNS = {
    'serial', 'serial_read', 'voter_id', 'id_ocr', 'id_repair_from', 'id_alternatives',
    'name', 'name_suggestion', 'name_detail', 'father', 'others',
    'house', 'house_number', 'house_warnings', 'age', 'gender',
    'voter_id_note', 'unattributed',
}

# Columns wide enough to read without clicking into each cell.
WIDTHS = {
    'name': 42, 'father': 32, 'name_suggestion': 32, 'name_detail': 30,
    'house_number': 15, 'house_warnings': 40, 'id_alternatives': 46,
    'voter_id': 13, 'id_ocr': 13, 'id_repair_from': 13, 'others': 18,
}


def column_letter(index):
    """1 -> A, 26 -> Z, 27 -> AA."""
    s = ''
    while index > 0:
        index, rem = divmod(index - 1, 26)
        s = chr(65 + rem) + s
    return s


def read_rows(path):
    with open(path, newline='', encoding='utf-8') as f:
        r = csv.reader(f)
        header = next(r)
        return header, [row for row in r]


def sheet_xml(header, rows, name):
    """A worksheet where every cell is either a number or an inline string.

    An inline string is stored with an explicit text type, so the cell's type is part of
    the file rather than something the importer infers.
    """
    # Indices, not names: the lookup below tests the column index, so a dict keyed by
    # header text silently made every cell a string -- including page and card, which
    # then sorted as text and put 10 before 2.
    numeric = {i for i, h in enumerate(header) if h not in TEXT_COLUMNS}

    out = [
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>',
        '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">',
        # Freeze the header so it stays visible while scrolling 1,129 rows.
        '<sheetViews><sheetView workbookViewId="0">'
        '<pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/>'
        '</sheetView></sheetViews>',
    ]

    widths = [(i + 1, WIDTHS.get(h, max(9, min(22, len(h) + 3)))) for i, h in enumerate(header)]
    if widths:
        out.append('<cols>' + ''.join(
            f'<col min="{i}" max="{i}" width="{w}" customWidth="1"/>' for i, w in widths
        ) + '</cols>')

    out.append('<sheetData>')

    def cell(ref, value, is_number):
        if value is None or value == '':
            return f'<c r="{ref}"/>'
        if is_number:
            return f'<c r="{ref}"><v>{escape(str(value))}</v></c>'
        return f'<c r="{ref}" t="inlineStr"><is><t xml:space="preserve">{escape(str(value))}</t></is></c>'

    out.append('<row r="1">' + ''.join(
        cell(f'{column_letter(i + 1)}1', h, False) for i, h in enumerate(header)
    ) + '</row>')

    for n, row in enumerate(rows, start=2):
        cells = []
        for i, v in enumerate(row):
            if i >= len(header):
                break
            cells.append(cell(f'{column_letter(i + 1)}{n}', v, i in numeric))
        out.append(f'<row r="{n}">' + ''.join(cells) + '</row>')

    out.append('</sheetData></worksheet>')
    return ''.join(out)


def write_xlsx(path, sheets):
    """sheets: list of (name, header, rows)."""
    z = zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED)

    sheet_entries = ''.join(
        f'<sheet name="{escape(name)}" sheetId="{i + 1}" r:id="rId{i + 1}"/>'
        for i, (name, _, _) in enumerate(sheets)
    )
    rels = ''.join(
        f'<Relationship Id="rId{i + 1}" '
        f'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" '
        f'Target="worksheets/sheet{i + 1}.xml"/>'
        for i in range(len(sheets))
    )
    overrides = ''.join(
        f'<Override PartName="/xl/worksheets/sheet{i + 1}.xml" '
        'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
        for i in range(len(sheets))
    )

    z.writestr('[Content_Types].xml',
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Default Extension="xml" ContentType="application/xml"/>'
        '<Override PartName="/xl/workbook.xml" '
        'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
        + overrides + '</Types>')

    z.writestr('_rels/.rels',
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" '
        'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" '
        'Target="xl/workbook.xml"/></Relationships>')

    z.writestr('xl/workbook.xml',
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
        'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
        f'<sheets>{sheet_entries}</sheets></workbook>')

    z.writestr('xl/_rels/workbook.xml.rels',
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        + rels + '</Relationships>')

    for i, (_, header, rows) in enumerate(sheets):
        z.writestr(f'xl/worksheets/sheet{i + 1}.xml', sheet_xml(header, rows, _))

    z.close()


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else 'out'
    sheets = []
    for src, name in [('extracted.csv', 'all'), ('review.csv', 'flagged')]:
        try:
            header, rows = read_rows(f'{out_dir}/{src}')
        except FileNotFoundError:
            print(f'  {src} not found, skipping')
            continue
        sheets.append((name, header, rows))
        print(f'  {src}: {len(rows)} rows, {len(header)} columns -> sheet "{name}"')

    dest = f'{out_dir}/voterlist.xlsx'
    write_xlsx(dest, sheets)
    print(f'  wrote {dest}')
    print('  house numbers, voter ids, serials and names are stored as text,')
    print('  so nothing can reinterpret "98-3-3/2" as a date on import.')


if __name__ == '__main__':
    main()