// Reads the serial number off a card, and checks it against what the layout says
// it should be.
//
// Two things this gets right that a plain crop does not:
//
//   * the serial box's right-hand border is blanked before recognition. The serial
//     is printed inside a box, and that border runs the full height of the crop.
//     Tesseract reads "11" plus a full-height bar as one token and answers "1",
//     which is how 1006 of 1128 serials came back wrong on the English roll --
//     the digits were all there, the recogniser was simply also being shown the
//     frame around them.
//
//   * the crop is measured, not inherited. The serial box's position was measured
//     on the Telugu roll and reused on the English one, where it clipped the last
//     digit of every two-digit serial. Positions are re-measured per document.
//
// Works on either roll: pass the pdf and the page range.

const fs = require('fs');
const path = require('path');
const os = require('os');
const { execFileSync } = require('child_process');
const { createWorker } = require('tesseract.js');
const { readGray } = require('./grid.js');
const { renderLineSync } = require('./house.js');
const T = require('./templates.js');
const F = require('./fields.js');
const { pageLayout } = require('./layout.js');

function rel(card, f) {
  const cw = card.x1 - card.x0, ch = card.y1 - card.y0;
  const x0 = Math.round(card.x0 + f.x0 * cw), y0 = Math.round(card.y0 + f.y0 * ch);
  const x1 = Math.round(card.x0 + f.x1 * cw), y1 = Math.round(card.y0 + f.y1 * ch);
  return { x0, y0, w: x1 - x0, h: y1 - y0 };
}

// The serial is printed inside a box, and that box's right border runs the full
// height of the crop. Tesseract reads "11" followed by a full-height bar as one
// token and answers "1" -- which is how 1006 of 1128 serials came back wrong on
// the English roll, every digit present in the crop.
//
// Cropping tighter does not fix it. The digits sit about 25 px from the border, so
// an edge that clears the frame clips the last digit and an edge that clears the
// digits includes the frame. Three attempts failed exactly that way: a fixed right
// edge at dx 820 clipped them; blanking every column whose ink exceeded 70% of the
// crop height deleted the digit "1" (a "1" is a tall thin stroke, precisely what
// that test targets); and raising the threshold to 85% missed the border, whose
// coverage inside the crop is 84%.
//
// So the digits are read one at a time. The crop is generous, the ink is split into
// column runs, the run spanning the line's full height is dropped as the border,
// and each remaining run is recognised alone at a single-character segmentation
// mode. Nothing has to know where the border is: it is the one run taller than the
// rest.
const ONE_CHAR = { tessedit_char_whitelist: '0123456789', tessedit_pageseg_mode: '10' };

function serialGlyphs(png, card, tmp, tag) {
  const strip = rel(card, { x0: F.SERIAL.x0, y0: F.SERIAL.y0, x1: 905 / 2576, y1: F.SERIAL.y1 });
  const stripFile = renderLineSync(png, strip, path.join(tmp, `${tag}-strip.png`));
  const img = readGray(stripFile);
  const runs = T.glyphRuns(img);
  if (!runs.length) return null;

  const heights = runs.map((r) => {
    const box = T.glyphInkBox(img, r[0], r[1]);
    return box ? box.y1 - box.y0 + 1 : 0;
  });
  const sorted = [...heights].sort((a, b) => a - b);
  const median = sorted[Math.floor(sorted.length / 2)] || 1;

  const kept = [];
  for (let k = 0; k < runs.length; k++) {
    if (heights[k] > median * 1.35 && heights[k] > median + 6) continue;   // the border
    if (heights[k] < median * 0.45) continue;                            // speck
    kept.push(runs[k]);
  }
  if (!kept.length) return null;
  return { runs: kept, strip, dropped: runs.length - kept.length };
}

async function serialCrop(png, card, file, worker, tmp, tag) {
  const seg = serialGlyphs(png, card, tmp, tag);
  if (!seg) return { serial: '', note: 'no usable glyph runs' };
  let out = '';
  for (let k = 0; k < seg.runs.length; k++) {
    const r = seg.runs[k];
    const g = { x0: seg.strip.x0 + r[0], y0: seg.strip.y0, w: r[1] - r[0] + 1, h: seg.strip.h };
    const gf = renderLineSync(png, g, `${file.replace(/\.png$/, '')}-${k}.png`);
    const { data } = await worker.recognize(gf, ONE_CHAR, { text: true });
    out += data.text.replace(/\s+/g, '').replace(/[^0-9]/g, '');
  }
  return { serial: out, glyphs: seg.runs.length, dropped: seg.dropped };
}

async function main() {
  const pdf = process.argv[2];
  const first = Number(process.argv[3] || 3);
  const last = Number(process.argv[4] || 45);
  const dpi = Number(process.argv[5] || 300);
  const nWorkers = Number(process.argv[6] || 10);
  const limitPages = Number(process.argv[7] || 0);

  const { pages, total } = pageLayout(pdf, dpi, first, last);
  const use = limitPages ? pages.slice(0, limitPages) : pages;
  console.log(`  ${pages.length} card pages, ${total} cards detected`);
  console.log(`  checking ${use.length} pages, ${use.reduce((a, p) => a + p.count, 0)} cards`);
  console.log(`  counts: ${pages.map((p) => `p${p.page}=${p.count}`).join(' ')}`);

  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'ser-'));
  const workers = [];
  for (let i = 0; i < nWorkers; i++) workers.push(await createWorker('eng'));
  const out = [];
  try {
    for (const pg of use) {
      let next = 0;
      const per = workers.map(() => []);
      await Promise.all(workers.map(async (wk, k) => {
        for (;;) {
          const i = next++;
          if (i >= pg.cards.length) break;
          const r = await serialCrop(pg.png, pg.cards[i], path.join(tmp, `${pg.page}_${i}.png`), wk, tmp, `${pg.page}_${i}`);
          per[k].push({
            page: pg.page, idx: i, expected: pg.first + i,
            serial: r.serial, glyphs: r.glyphs, dropped: r.dropped,
          });
        }
      }));
      out.push(...per.flat().sort((a, b) => a.idx - b.idx));
      const bad = out.filter((r) => r.page === pg.page && r.serial !== String(r.expected));
      process.stderr.write(`\r  page ${pg.page}: ${out.filter((r) => r.page === pg.page).length} read, ${bad.length} wrong   `);
    }
  } finally { await Promise.all(workers.map((w) => w.terminate())); }
  process.stderr.write('\n');

  const ok = out.filter((r) => r.serial === String(r.expected));
  console.log(`\n  SERIAL ACCURACY  ${ok.length}/${out.length} = ${((ok.length / out.length) * 100).toFixed(2)}%`);

  // A break in the printed run is the signal that a record was missed by the grid
  // rather than misread, and the two need different fixes.
  const nums = out.map((r) => Number(r.serial)).filter(Number.isFinite);
  const breaks = [];
  for (let i = 1; i < nums.length; i++) if (nums[i] !== nums[i - 1] + 1) breaks.push({ i, got: nums[i], want: nums[i - 1] + 1 });
  console.log(`  breaks in the printed sequence: ${breaks.length}`);
  for (const b of breaks.slice(0, 10)) {
    console.log(`     position ${b.i}: read ${nums[b.i]}, expected ${b.want}  (page ${out[b.i].page} card ${out[b.i].idx})`);
  }
  console.log(`  highest serial read: ${Math.max(...nums)}`);

  const bad = out.filter((r) => r.serial !== String(r.expected));
  if (bad.length) {
    console.log(`\n  first 15 disagreements:`);
    for (const b of bad.slice(0, 15)) {
      console.log(`     page ${b.page} card ${String(b.idx).padStart(2)}: read ${b.serial || '(blank)'}  expected ${b.expected}`);
    }
  }
  const file = path.join(__dirname, '..', '..', 'out', 'serials-read.json');
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(out));
  console.log(`\n  written ${file}`);
}

main().catch((e) => { console.error(e); process.exit(1); });