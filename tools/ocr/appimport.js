// Converts the extraction into the sheet the app reads.
//
// This is the seam between the two halves of the project, and it is a seam rather than a
// detail. Everything upstream is about a printed roll: geometry, labels, check digits, glyph
// templates. Everything downstream is about households and a printed booklet. Neither half
// needs to know about the other, and the only thing crossing between them is a spreadsheet
// with four columns.
//
// The format is the app's, not this tool's preference. It was handed extracted.csv and refused
// it: "The sheet is missing: a worksheet." That is the app being right about its input and
// this tool wrong about the contract, so the contract is what changed -- the extraction keeps
// all 27 columns for auditing, and this writes the four the app needs.
//
// Which four, and what is given up
// -------------------------------
//   serial        the roll's own numbering, taken from position and proved against the card
//   voter id      the id as extracted, with its status already resolved upstream. The repair
//                 is not repeated here; a card whose id is still unproved is carried through
//                 as read rather than silently dropped, so nothing is lost in translation.
//   name          the voter's own name. The relation name is not included: it is harvested
//                 separately for the family grouping, where the fact that household members
//                 share it is evidence, and putting it in the name field is the bug this
//                 project's husband-line fix was about.
//   house number  the door number, which is what the app groups by.
//
// Rows are not filtered. A card with a blank house number is still a voter, and dropping it
// here would make the counts in the app disagree with the counts in the extraction for a
// reason nobody would find. They are carried, and the app shows them as ungrouped.
//
// Usage: node appimport.js [extracted.csv] [out.xlsx]

const fs = require('fs');
const path = require('path');
const { build } = require('./xlsx.js');

const OUT = path.join(__dirname, '..', '..', 'out');

// A minimal CSV reader, adequate for this file and no more. It handles quoted fields and
// doubled quotes, because a name from this roll can contain a comma -- and a name containing
// a comma, split naively, shifts every column after it, which in the household grouping looks
// exactly like a data error on a card that is perfectly fine.
function parseCsv(text) {
  const rows = [];
  let row = [];
  let field = '';
  let quoted = false;
  let i = 0;
  while (i < text.length) {
    const c = text[i];
    if (quoted) {
      if (c === '"') {
        if (text[i + 1] === '"') { field += '"'; i += 2; continue; }
        quoted = false; i++; continue;
      }
      field += c; i++; continue;
    }
    if (c === '"' && field === '') { quoted = true; i++; continue; }
    if (c === ',') { row.push(field); field = ''; i++; continue; }
    if (c === '\r') { i++; continue; }
    if (c === '\n') { row.push(field); rows.push(row); row = []; field = ''; i++; continue; }
    field += c; i++;
  }
  if (field !== '' || row.length) { row.push(field); rows.push(row); }
  return rows.filter((r) => r.length && r.some((c) => c !== ''));
}

const HEADERS = ['Serial', 'Voter ID', 'Name', 'House Number'];

function main() {
  const csvPath = process.argv[2] || path.join(OUT, 'extracted.csv');
  const xlsxPath = process.argv[3] || path.join(OUT, 'voters.xlsx');
  if (!fs.existsSync(csvPath)) {
    console.error(`  no extraction at ${csvPath} -- run build.js first`);
    process.exit(1);
  }

  const rows = parseCsv(fs.readFileSync(csvPath, 'utf8'));
  const header = rows[0].map((h) => h.trim().toLowerCase().replace(/[^a-z0-9]/g, ''));
  const col = (want, aliases) => {
    const i = header.indexOf(want);
    if (i >= 0) return i;
    for (const a of aliases) {
      const j = header.indexOf(a);
      if (j >= 0) return j;
    }
    throw new Error(`the extraction has no ${want} column. It has: ${rows[0].join(', ')}`);
  };

  const iSerial = col('serial', ['serialno', 'serialnumber']);
  const iId = col('voterid', ['voteridno', 'epic', 'id']);
  const iName = col('name', ['fullname', 'nameofvoter']);
  const iHouse = col('housenumber', ['house', 'houseno']);

  const data = rows.slice(1).map((r) => [
    r[iSerial] || '',
    r[iId] || '',
    r[iName] || '',
    r[iHouse] || '',
  ]);

  const buf = build(HEADERS, data);
  fs.writeFileSync(xlsxPath, buf);

  // Counted and reported, because these four numbers are what the app is about to show and a
  // silent mismatch here is invisible until somebody counts rows in two places.
  const withId = data.filter((r) => r[1]).length;
  const withHouse = data.filter((r) => r[3]).length;
  const doors = new Set(data.filter((r) => r[3]).map((r) => r[3]));
  console.log(`  ${data.length} rows -> ${xlsxPath} (${buf.length} bytes)`);
  console.log(`    columns       : ${HEADERS.join(', ')}`);
  console.log(`    with voter id : ${withId}/${data.length}`);
  console.log(`    with house    : ${withHouse}/${data.length}`);
  console.log(`    distinct doors: ${doors.size}`);
  console.log(`  hand this file to the app: Settings, then Import voters list.`);
}

main();