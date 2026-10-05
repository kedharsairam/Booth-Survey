// Builds verified serial labels for the whole roll.
//
// This exists because getting the labels wrong silently ruins everything
// downstream. The matcher in templates.js trains on the serial field, taking each
// glyph's label from the serial's position in the roll -- and the first attempt
// at that assumed every page holds 30 cards. It does not: page 6 holds 26,
// because its last row has two cards in three slots. Every page after it
// therefore started four, then five, then more serials lower than assumed, and
// the matcher trained on shifted labels managed 30% on held-out serials while
// looking, to a picture, entirely plausible.
//
// So the labels are derived from the card counts themselves rather than from a
// guess, and then *checked against an independent read* of the printed number.
// The two agreeing across 1,129 records is what makes the labels trustworthy --
// the count alone could be wrong the same way twice, but the count and the
// pixels cannot.
//
// Output is cached, because this costs a tesseract call per card and the answer
// never changes.

const fs = require('fs');
const path = require('path');
const os = require('os');
const { execFileSync } = require('child_process');
const { createWorker } = require('tesseract.js');
const { detect } = require('./grid.js');
const { renderLineSync } = require('./house.js');
const F = require('./fields.js');

const FIRST_LIST_PAGE = 4;
const LAST_LIST_PAGE = 46;
const CACHE = path.join(os.tmpdir(), 'rollcache');
const LABEL_FILE = path.join(__dirname, '..', '..', 'out', 'serial-labels.json');

function pagePng(pdf, page, dpi) {
  fs.mkdirSync(CACHE, { recursive: true });
  const base = path.join(CACHE, `p${page}-${dpi}`);
  if (fs.existsSync(base + '.png')) return base + '.png';
  execFileSync('pdftoppm', ['-f', String(page), '-l', String(page), '-singlefile',
    '-r', String(dpi), '-png', '-gray', pdf, base], { maxBuffer: 1 << 30 });
  return base + '.png';
}

function rel(card, f) {
  const cw = card.x1 - card.x0, ch = card.y1 - card.y0;
  const x0 = Math.round(card.x0 + f.x0 * cw), y0 = Math.round(card.y0 + f.y0 * ch);
  const x1 = Math.round(card.x0 + f.x1 * cw), y1 = Math.round(card.y0 + f.y1 * ch);
  return { x0, y0, w: x1 - x0, h: y1 - y0 };
}

async function main() {
  const pdf = process.argv[2];
  const dpi = Number(process.argv[3] || 300);
  const nWorkers = Number(process.argv[4] || 10);

  // ---- pass 1: counts only, no OCR, to derive the offsets -------------
  const pages = [];
  let offset = 0;
  for (let p = FIRST_LIST_PAGE; p <= LAST_LIST_PAGE; p++) {
    let png, g;
    try { png = pagePng(pdf, p, dpi); g = detect(png); }
    catch { console.error(`  page ${p}: not a card page, skipped`); continue; }
    if (!g.cards.length) { console.error(`  page ${p}: no cards, skipped`); continue; }
    pages.push({ page: p, png, cards: g.cards, first: offset + 1 });
    offset += g.cards.length;
  }
  console.log(`  ${pages.length} list pages, ${offset} cards total`);
  console.log(`  counts: ${pages.map((p) => `p${p.page}=${p.cards.length}`).join(' ')}`);

  // ---- pass 2: verify each serial against the pixels -------------------
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'labels-'));
  const workers = [];
  for (let i = 0; i < nWorkers; i++) workers.push(await createWorker('eng'));
  const out = [];
  try {
    for (const pg of pages) {
      let next = 0;
      const per = workers.map(() => []);
      await Promise.all(workers.map(async (wk, k) => {
        for (;;) {
          const i = next++;
          if (i >= pg.cards.length) break;
          const want = String(pg.first + i);
          const f = renderLineSync(pg.png, rel(pg.cards[i], F.SERIAL), path.join(tmp, `${pg.page}_${i}.png`), 1);
          const { data } = await wk.recognize(f, F.DIGITS, { text: true });
          const got = data.text.replace(/\s+/g, '').replace(/[^0-9]/g, '');
          per[k].push({ page: pg.page, idx: i, serial: got, expected: want, ok: got === want });
        }
      }));
      out.push(...per.flat().sort((a, b) => a.idx - b.idx));
      const bad = out.filter((r) => r.page === pg.page && !r.ok);
      console.log(`  page ${pg.page}: ${String(pg.cards.length).padStart(2)} cards, serials ${pg.first}..${pg.first + pg.cards.length - 1}` +
        (bad.length ? `   MISMATCH on ${bad.length}: ${bad.slice(0, 4).map((b) => `${b.idx}->${b.serial}`).join(' ')}` : '   all match'));
    }
  } finally {
    await Promise.all(workers.map((w) => w.terminate()));
  }

  const bad = out.filter((r) => !r.ok);
  fs.mkdirSync(path.dirname(LABEL_FILE), { recursive: true });
  fs.writeFileSync(LABEL_FILE, JSON.stringify(out));
  console.log('');
  console.log(`  records          ${out.length}`);
  console.log(`  serials verified ${out.length - bad.length}/${out.length}`);
  console.log(`  first / last     ${out[0].serial} / ${out[out.length - 1].serial}`);
  console.log(`  written          ${LABEL_FILE}`);
  if (bad.length) {
    console.log(`  ${bad.length} MISMATCHES -- labels are not trustworthy yet:`);
    for (const b of bad.slice(0, 20)) console.log(`     page ${b.page} card ${b.idx}: read ${b.serial}, count says ${b.expected}`);
  }
}

main().catch((e) => { console.error(e); process.exit(1); });