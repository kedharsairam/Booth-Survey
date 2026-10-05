// Writes a real .xlsx, because the app will not read a CSV dressed up as one.
//
// The boundary between this tool and the app is a spreadsheet, and that was the decision: the
// app has no OCR, no geometry and no opinion about how a roll is printed, and this tool has no
// opinion about families or booklets. A file both sides can read is what keeps it that way.
// When the app was handed extracted.csv it refused it -- "The sheet is missing: a worksheet" --
// which is the app being right about its own input format and this tool being wrong about the
// contract between them.
//
// Why write the file rather than shell out to a converter
// -------------------------------------------------------
// There is no openpyxl, no spreadsheet npm package in this tool, and no guarantee that zip is
// installed on the machine that runs it. Node ships a deflate and a CRC32, and an .xlsx is a
// zip of a handful of XML parts, so the whole thing is about two hundred lines and no
// dependencies. That matters for a tool whose other half is a Kotlin app that must build
// offline: a dependency here is a dependency that can fail to install at the worst moment.
//
// Inline strings rather than a shared-strings table: one less part, one less relationship, and
// no index bookkeeping that could put a name under the wrong header. The file is a little
// larger, which is irrelevant at this size.
//
// Everything here is deterministic. The same rows produce the same bytes, so re-running the
// extraction does not produce a spurious diff and a booklet can be rebuilt from a stored
// checksum.

const zlib = require('zlib');

// ---------------------------------------------------------------- zip

// A zip entry. `deflate` is the raw stream, which is what a zip member holds -- the container
// supplies the size and the checksum, not the deflate format.
function entry(name, text) {
  const nameBuf = Buffer.from(name, 'utf8');
  const raw = Buffer.from(text, 'utf8');
  const body = zlib.deflateRawSync(raw, { level: 9 });
  return { nameBuf, raw, body, crc: zlib.crc32(raw) >>> 0 };
}

// Builds a zip archive. Only the two compression methods that matter are here -- stored and
// deflated -- and deflated is always used, so the "stored" branch exists only so the local
// header and central directory agree on a method value that is not a lie.
function zip(entries) {
  const parts = [];
  const central = [];
  let offset = 0;

  for (const e of entries) {
    const lh = Buffer.alloc(30);
    lh.writeUInt32LE(0x04034b50, 0);      // local file header signature
    lh.writeUInt16LE(20, 4);               // version needed
    lh.writeUInt16LE(0, 6);                // flags
    lh.writeUInt16LE(8, 8);                // method: deflate
    lh.writeUInt16LE(0, 10);               // mod time -- fixed, so output is reproducible
    lh.writeUInt16LE(0x21, 12);            // mod date: 1980-01-01, same reason
    lh.writeUInt32LE(e.crc, 14);
    lh.writeUInt32LE(e.body.length, 18);
    lh.writeUInt32LE(e.raw.length, 22);
    lh.writeUInt16LE(e.nameBuf.length, 26);
    lh.writeUInt16LE(0, 28);               // extra field length
    parts.push(lh, e.nameBuf, e.body);

    const cd = Buffer.alloc(46);
    cd.writeUInt32LE(0x02014b50, 0);       // central directory header signature
    cd.writeUInt16LE(20, 4);               // version made by
    cd.writeUInt16LE(20, 6);               // version needed
    cd.writeUInt16LE(0, 8);
    cd.writeUInt16LE(8, 10);
    cd.writeUInt16LE(0, 12);
    cd.writeUInt16LE(0x21, 14);
    cd.writeUInt32LE(e.crc, 16);
    cd.writeUInt32LE(e.body.length, 20);
    cd.writeUInt32LE(e.raw.length, 24);
    cd.writeUInt16LE(e.nameBuf.length, 28);
    cd.writeUInt16LE(0, 30);               // extra
    cd.writeUInt16LE(0, 32);               // comment
    cd.writeUInt16LE(0, 34);               // disk number
    cd.writeUInt16LE(0, 36);               // internal attributes
    cd.writeUInt32LE(0, 38);               // external attributes
    cd.writeUInt32LE(offset, 42);
    central.push(cd, e.nameBuf);

    offset += lh.length + e.nameBuf.length + e.body.length;
  }

  const cdBuf = Buffer.concat(central);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(0, 4);
  eocd.writeUInt16LE(0, 6);
  eocd.writeUInt16LE(entries.length, 8);
  eocd.writeUInt16LE(entries.length, 10);
  eocd.writeUInt32LE(cdBuf.length, 12);
  eocd.writeUInt32LE(offset, 16);
  eocd.writeUInt16LE(0, 20);

  return Buffer.concat([...parts, cdBuf, eocd]);
}

// ---------------------------------------------------------------- xml

// Escapes the five characters XML cares about. Not optional: a name containing an ampersand is
// ordinary in this data -- surnames joined with & are a real risk in a South Indian roll -- and
// an unescaped one makes the whole file unreadable rather than making one cell wrong.
function esc(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
}

// A1-style reference for a cell. The column letters are computed rather than assumed, because
// the app addresses cells by reference when it reads the header row.
function ref(col, row) {
  let s = '';
  let n = col;
  while (n >= 0) {
    s = String.fromCharCode(65 + (n % 26)) + s;
    n = Math.floor(n / 26) - 1;
  }
  return `${s}${row}`;
}

/**
 * Writes a single-sheet workbook.
 *
 * @param {string[]} headers column headings, in order
 * @param {Array<Array<string|number>>} rows data rows
 * @returns {Buffer} the .xlsx file
 */
function build(headers, rows) {
  if (!headers || !headers.length) throw new Error('a sheet needs at least one column');
  const width = headers.length;
  for (const [i, r] of rows.entries()) {
    if (r.length !== width) {
      throw new Error(`row ${i + 1} has ${r.length} cells but there are ${width} columns`);
    }
  }

  const lines = ['<?xml version="1.0" encoding="UTF-8" standalone="yes"?>',
    '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">',
    `<cols>${headers.map((_, c) => `<col min="${c + 1}" max="${c + 1}" width="28" customWidth="1"/>`).join('')}</cols>`,
    '<sheetData>'];

  const rowXml = (cells, r) => {
    const body = cells.map((v, c) => {
      const r_ = ref(c, r);
      // A number is written as a number, so the app can sort and count on it. Everything else
      // is a string: a serial that might read as a date, or a house number that might read as
      // a fraction, is worse as a number than as text that is plainly text.
      if (typeof v === 'number' && Number.isFinite(v)) {
        return `<c r="${r_}"><v>${v}</v></c>`;
      }
      return `<c r="${r_}" t="inlineStr"><is><t xml:space="preserve">${esc(v)}</t></is></c>`;
    }).join('');
    return `<row r="${r}">${body}</row>`;
  };

  lines.push(rowXml(headers, 1));
  rows.forEach((r, i) => lines.push(rowXml(r, i + 2)));
  lines.push('</sheetData></worksheet>');
  const sheet = lines.join('');

  const files = [
    entry('[Content_Types].xml',
      '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
      + '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
      + '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
      + '<Default Extension="xml" ContentType="application/xml"/>'
      + '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
      + '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
      + '</Types>'),
    entry('_rels/.rels',
      '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
      + '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
      + '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
      + '</Relationships>'),
    entry('xl/workbook.xml',
      '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
      + '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"'
      + ' xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
      + '<sheets><sheet name="Voters" sheetId="1" r:id="rId1"/></sheets>'
      + '</workbook>'),
    entry('xl/_rels/workbook.xml.rels',
      '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
      + '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
      + '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>'
      + '</Relationships>'),
    entry('xl/worksheets/sheet1.xml', sheet),
  ];

  return zip(files);
}

module.exports = { build, zip, esc, ref };

if (require.main === module) {
  const fs = require('fs');
  const out = process.argv[3] || 'out/voters.xlsx';
  const rows = fs.readFileSync(process.argv[2], 'utf8').split('\n').filter(Boolean)
    .map((l) => l.split(','));
  const buf = build(rows[0], rows.slice(1));
  fs.writeFileSync(out, buf);
  console.log(`  ${rows.length - 1} rows x ${rows[0].length} cols -> ${out} (${buf.length} bytes)`);
}