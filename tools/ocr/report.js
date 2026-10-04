// Turns an OCR run into something a human can check.
//
// Produces three things:
//   * a CSV of the extracted fields, one row per voter, which is what the app
//     will eventually import;
//   * a summary of how the fields held up;
//   * a side-by-side image of every card that failed, pixels above and the value
//     read below, so a wrong read can be confirmed rather than argued about.
//
// Ground truth is only available for the serial number, which the roll prints in
// ascending order -- so on any page, card i of page p must hold serial
// (p - FIRST_LIST_PAGE) * 30 + 1 + i. Everything else is checked by eye.

const fs = require('fs');
const path = require('path');
const os = require('os');
const { execFileSync } = require('child_process');
const { createWorker } = require('tesseract.js');
const { detect } = require('./grid.js');
const { renderLineSync, cropColumn } = require('./house.js');
const F = require('./fields.js');

function rel(card, f) {
  const cw = card.x1 - card.x0, ch = card.y1 - card.y0;
  const x0 = Math.round(card.x0 + f.x0 * cw), y0 = Math.round(card.y0 + f.y0 * ch);
  const x1 = Math.round(card.x0 + f.x1 * cw), y1 = Math.round(card.y0 + f.y1 * ch);
  return { x0, y0, w: x1 - x0, h: y1 - y0 };
}

const csvCell = (s) => (/[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s);

async function main() {
  const [jsonl, firstListPageArg, ...rest] = process.argv.slice(2);
  const outDir = rest[0] || path.join(__dirname, '..', '..', 'out');
  const firstListPage = Number(firstListPageArg || 4);
  const recs = fs.readFileSync(jsonl, 'utf8').trim().split('\n').map(JSON.parse);
  fs.mkdirSync(outDir, { recursive: true });

  // ---- CSV -------------------------------------------------------------
  const head = ['page', 'serial', 'voter_id', 'house_number', 'house_votes', 'house_reads', 'flag'];
  const rows = [head.join(',')];
  for (const r of recs) {
    const flags = [];
    if (!r.epic_ok) flags.push('voter_id_shape');
    if (!r.house_unanimous) flags.push('house_disagreement');
    if (!r.house_located) flags.push('house_not_found');
    rows.push([r.page, r.serial, r.epic, r.house, r.house_votes, r.house_reads, flags.join(' ')].map(csvCell).join(','));
  }
  const csv = path.join(outDir, 'ocr-sample.csv');
  fs.writeFileSync(csv, rows.join('\n') + '\n');

  // ---- accuracy against the one thing we know for certain --------------
  let serialOk = 0, epicShape = 0, houseUnanimous = 0;
  const serials = [];
  for (const r of recs) {
    const page = Number(String(r.page).replace(/\D/g, ''));
    const want = (page - firstListPage) * 30 + 1 + r.idx;
    serials.push({ got: r.serial, want: String(want) });
    if (r.serial === String(want)) serialOk++;
    if (r.epic_ok) epicShape++;
    if (r.house_unanimous) houseUnanimous++;
  }
  const dupEpic = recs.length - new Set(recs.map((r) => r.epic)).size;
  const seq = serials.map((s) => Number(s.got)).filter((n) => !Number.isNaN(n));
  const contiguous = seq.length === recs.length && seq.every((v, i) => i === 0 || v === seq[i - 1] + 1);

  const flagCount = recs.filter((r) => !r.epic_ok || !r.house_unanimous).length;

  console.log(`  records          ${recs.length}`);
  console.log(`  serial correct   ${serialOk}/${recs.length}   (ground truth: roll prints serials in order, ${firstListPage * 0 + (firstListPage)} is the first list page)`);
  console.log(`  serial run       ${seq.length ? seq[0] + '..' + seq[seq.length - 1] : '-'}   strictly ascending by 1: ${contiguous ? 'yes' : 'NO'}`);
  console.log(`  voter id shape   ${epicShape}/${recs.length} match [A-Z]{3}[0-9]{7}`);
  console.log(`  voter id unique  ${dupEpic === 0 ? 'yes' : 'NO, ' + dupEpic + ' duplicates'}`);
  console.log(`  house unanimous  ${houseUnanimous}/${recs.length}   (all preprocessing variants agreed)`);
  console.log(`  flagged for a human eye: ${flagCount}/${recs.length}`);
  console.log(`  csv              ${csv}`);

  // ---- side-by-side evidence for the flagged cards --------------------
  const flagged = recs.filter((r) => !r.epic_ok || !r.house_unanimous);
  if (!flagged.length || !rest[1]) {
    console.log(flagged.length ? '  (pass a page png to also render evidence images)' : '  nothing flagged');
    return;
  }
  const pagePng = rest[1];
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'evidence-'));
  const worker = await createWorker('eng');
  const g = detect(pagePng);
  try {
    for (const r of flagged) {
      const card = g.cards[r.idx];
      if (!card) continue;
      const s = renderLineSync(pagePng, rel(card, F.SERIAL), path.join(tmp, `s${r.idx}.png`), 1);
      const e = renderLineSync(pagePng, rel(card, F.EPIC), path.join(tmp, `e${r.idx}.png`), 1);
      const loc = await cropColumn(pagePng, card, path.join(tmp, `c${r.idx}.png`), worker);
      const h = loc.rect ? renderLineSync(pagePng, loc.rect, path.join(tmp, `h${r.idx}.png`), 1) : null;
      const parts = [s, e].concat(h ? [h] : []);
      const out = path.join(outDir, `evidence-${r.page}-card${String(r.idx).padStart(2, '0')}.png`);
      execFileSync('convert', [...parts,
        '-resize', 'x90', '-bordercolor', 'gray', '-border', '2',
        '-background', 'white', '+append', '-gravity', 'North',
        '-splice', '0x54', '-pointsize', '26', '-fill', 'black', '-annotate', '+14+14',
        `#${r.idx} serial=${r.serial} epic=${r.epic}${r.epic_ok ? '' : ' SHAPE-BAD'} house=${r.house} ${r.house_votes}/${r.house_reads}`,
        out]);
      console.log(`  evidence ${path.basename(out)}`);
    }
  } finally {
    await worker.terminate();
  }
}

main().catch((e) => { console.error(e); process.exit(1); });