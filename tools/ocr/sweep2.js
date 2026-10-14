// Sweeps the house-number pipeline's two free parameters and reports both
// accuracy and flag rate for each combination.
//
// Flag rate is not a secondary metric. A wrong house number invents a household,
// so the only acceptable behaviour for an unsure glyph is to say so; but a flag on
// every second card is not a usable system either. The combination worth having is
// the one with high accuracy *and* a low flag rate, and which that is cannot be
// reasoned about in advance -- the distance distributions have to be looked at.

const fs = require('fs');
const path = require('path');
const os = require('os');
const { createWorker } = require('tesseract.js');
const { readGray } = require('./grid.js');
const { renderLineSync, cropColumn } = require('./house.js');
const T = require('./templates.js');
const G = require('./glyphcache.js');
const { pageLayout } = require('./layout.js');

const TRUTH = '90-24-24/6,90-24-24/6,90-25-25/7,90-25-25/7,90-25-25/7,90-25-25/7,90-25-25/7,90-25-25/7,90-26-26/8,90-27-27/9,90-7-7/7,90-7-7/7,90-7-7/7,90-7-7/7,90-7-7/7,90-8-8/8,90-9-9/9,90-9-9/9,90-9-9/9,93-4-4/1A,93-4-4/1A,90-10-10/1,90-11-11/2,90-12-12/3,90-12-12/3,90-13-13/4,90-13-13/4,90-13-13/4,90-13-13/4,90-14-14/5'.split(',');

async function main() {
  const pdf = process.argv[2];
  const dpi = Number(process.argv[3] || 300);
  const { records } = G.build(pdf, dpi);
  const train = {};
  for (const [ch, bits] of records) (train[ch] = train[ch] || []).push(bits);
  const templates = T.buildTemplates(train);

  const { pages } = pageLayout(pdf, dpi);
  const pg = pages.find((p) => p.page === 5);
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'sweep-'));
  const wk = await createWorker('eng');

  // Locate each house line once; only the rendering of it varies below.
  const locs = [];
  try {
    for (let i = 0; i < pg.cards.length; i++) {
      locs.push(await cropColumn(pg.png, pg.cards[i], path.join(tmp, `c${i}.png`), wk));
    }
  } finally { await wk.terminate(); }

  // Cache each located line as a full-resolution crop so the scale sweep does not
  // re-ask tesseract where the line is.
  const raw = [];
  for (let i = 0; i < locs.length; i++) {
    if (!locs[i].rect) { raw.push(null); continue; }
    const r = locs[i].rect;
    const f = path.join(tmp, `raw${i}.png`);
    require('child_process').execFileSync('convert', [
      pg.png, '-crop', `${r.w}x${r.h}+${r.x0}+${r.y0}`, '+repage', f]);
    raw.push(f);
  }

  const results = [];
  for (const scale of [1, 2, 3]) {
    for (const thr of [50, 62, 72]) {
      const rows = [];
      for (let i = 0; i < pg.cards.length; i++) {
        if (!raw[i]) { rows.push(null); continue; }
        const img = readGray(renderLineFrom(raw[i], path.join(tmp, `s${scale}_${thr}_${i}.png`), scale, thr));
        rows.push(readHouse(img, templates));
      }
      results.push({ scale, thr, rows });
    }
  }

  console.log(`  page 5, ${pg.count} cards.  'auto' = accepted with no flag.`);
  console.log('  scale thr |  exact   auto-ok  auto-WRONG  flagged');
  for (const r of results) {
    let right = 0, autoRight = 0, autoWrong = 0, flagged = 0, n = 0;
    for (let i = 0; i < pg.cards.length; i++) {
      const row = r.rows[i];
      if (!row) continue;
      n++;
      const good = row.out === TRUTH[i];
      if (good) right++;
      if (row.flagged) flagged++;
      else if (good) autoRight++;
      else autoWrong++;
    }
    const mark = autoWrong === 0 ? '   <-- no silent errors' : '';
    console.log(`  ${String(r.scale).padStart(5)} ${String(r.thr).padStart(3)} | ${String(right + '/' + n).padStart(6)} ${String(autoRight).padStart(9)} ${String(autoWrong).padStart(11)} ${String(flagged).padStart(9)}${mark}`);
  }

  // Dump every card's glyph distances for the best-looking configuration so the
  // thresholds can be set from the distribution rather than by guessing.
  const best = results.find((r) => r.scale === 2 && r.thr === 62) || results[0];
  console.log(`\n  distances for scale=${best.scale} thr=${best.thr}`);
  for (let i = 0; i < pg.cards.length; i++) {
    const row = best.rows[i];
    if (!row) continue;
    console.log(`  card ${String(i).padStart(2)}  ${row.out.padEnd(14)} ${TRUTH[i].padEnd(14)} ` +
      row.distances.map((d) => d.toFixed(3)).join(' ') +
      (row.flagged ? `   [${row.why}]` : ''));
  }
}

function renderLineFrom(src, out, scale, thr) {
  const { execFileSync } = require('child_process');
  execFileSync('convert', [src, '-colorspace', 'Gray',
    `-resize`, `${scale * 100}%`, '-threshold', `${thr}%`,
    '-trim', '+repage', '-bordercolor', 'white', '-border', '24', out]);
  return out;
}

function readHouse(img, templates) {
  const line = T.lineInkBox(img);
  if (!line) return { out: '', flagged: true, why: 'no ink', distances: [] };
  const lineH = line.y1 - line.y0 + 1;
  let out = '', distances = [], flagged = false, why = '';
  for (const r of T.glyphRuns(img)) {
    const b = T.glyphBitmap(img, r[0], r[1]);
    if (!b) continue;
    if (b.aspect > 0.9 && b.h < lineH * 0.55) { out += '-'; continue; }
    if (b.h > lineH * 0.75 && b.density < 0.30 && b.aspect > 0.2 && b.aspect < 0.85) { out += '/'; continue; }
    const res = T.classify(b, templates);
    distances.push(res.dist);
    const d = T.decide(res, 0.012, 0.10);
    if (d.ok) out += d.ch;
    else { out += '?'; if (!flagged) { flagged = true; why = d.why; } }
  }
  return { out, flagged, why, distances };
}

main().catch((e) => { console.error(e); process.exit(1); });