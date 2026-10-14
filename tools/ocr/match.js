// Measures the template matcher against tesseract on a page whose house numbers
// are known.
//
// Ground truth: the house number for every card of page 5, read by eye off
// magnified crops of the roll. It is not derived from the OCR -- that is the
// whole point -- so the two can be compared honestly.
//
// Training data comes only from the serial field, whose value is known from each
// card's position. Nothing here is trained on house numbers, so the house field
// is genuinely unseen at classification time.

const fs = require('fs');
const path = require('path');
const os = require('os');
const { execFileSync } = require('child_process');
const { createWorker } = require('tesseract.js');
const { detect, readGray } = require('./grid.js');
const { renderLineSync, cropColumn } = require('./house.js');
const T = require('./templates.js');
const F = require('./fields.js');

const TRUTH = {
  5: '90-24-24/6,90-24-24/6,90-25-25/7,90-25-25/7,90-25-25/7,90-25-25/7,90-25-25/7,90-25-25/7,90-26-26/8,90-27-27/9,90-7-7/7,90-7-7/7,90-7-7/7,90-7-7/7,90-7-7/7,90-8-8/8,90-9-9/9,90-9-9/9,90-9-9/9,93-4-4/1A,93-4-4/1A,90-10-10/1,90-11-11/2,90-12-12/3,90-12-12/3,90-13-13/4,90-13-13/4,90-13-13/4,90-13-13/4,90-14-14/5'.split(','),
  // Page 6, serials 61..86, read off the page image.
  6: '90-14-14/5,90-15-15/6,90-15-15/6,90-15-15/6,90-15-15/6,90-16-16/7,90-16-16/7,90-16-16/7,90-16-16/7,90-16-16/7,90-16-16/7,90-17-17/8,90-17-17/8,90-17-17/8,90-17-17/8,90-19-19/1,90-19-19/1,95-7-7/5A,90-36-36/9,90-38-38/2/46,97-5-5/4A,90-42-2/6,90-49-9/4,90-49-9/4,90-50-10/5,90-28-28/1,11-15,11-17,11-12-1,11-15-9,11-142-1,11-159,90-28-28/1,11-15,11-17'.split(','),
};

const FIRST_LIST_PAGE = 4;
const PER_PAGE = 30;

function rel(card, f) {
  const cw = card.x1 - card.x0, ch = card.y1 - card.y0;
  const x0 = Math.round(card.x0 + f.x0 * cw), y0 = Math.round(card.y0 + f.y0 * ch);
  const x1 = Math.round(card.x0 + f.x1 * cw), y1 = Math.round(card.y0 + f.y1 * ch);
  return { x0, y0, w: x1 - x0, h: y1 - y0 };
}

function pngFor(pdf, page, dpi, prefix) {
  execFileSync('pdftoppm', ['-f', String(page), '-l', String(page), '-singlefile', '-r', String(dpi),
    '-png', '-gray', pdf, prefix], { maxBuffer: 1 << 30 });
  const base = `${prefix}-${String(page).padStart(2, '0')}`;
  for (const c of ['', '.png']) if (fs.existsSync(base + c)) return base + c;
  throw new Error('pdftoppm produced nothing for page ' + page);
}

// Reconciles detected runs against a known glyph count.
//
// Repeated digits touch: "44", "56" and "60" each arrive as a single run about
// twice the width of one digit, which is why 9 of 30 serials on page 5 were
// being dropped from training. When the expected count is known -- and for the
// serial it always is, since the roll prints serials in ascending order -- the
// fix is to split the widest runs until the counts agree. Splitting the widest
// rather than an arbitrary one is what makes it safe: a merged pair is the only
// run wide enough to need it.
function reconcileRuns(runs, expected, lineH) {
  const out = runs.map((r) => [r[0], r[1]]);
  let guard = 0;
  while (out.length < expected && guard++ < 40) {
    let bi = 0, bw = -1;
    for (let i = 0; i < out.length; i++) {
      const w = out[i][1] - out[i][0] + 1;
      if (w > bw) { bw = w; bi = i; }
    }
    if (bw < lineH * 0.5) break;                 // nothing wide enough left to split
    const mid = Math.round((out[bi][0] + out[bi][1]) / 2);
    out.splice(bi, 1, [out[bi][0], mid], [mid, out[bi][1]]);
  }
  return out;
}

// Pulls every glyph of a card's serial, labelled by its position. The roll
// prints serials in ascending order, so the label needs no OCR at all.
function serialSamples(pagePng, card, serialText, tmp, i) {
  const f = renderLineSync(pagePng, rel(card, F.SERIAL), path.join(tmp, `s${i}.png`), 1);
  const img = readGray(f);
  const line = T.lineInkBox(img);
  if (!line) return { samples: null, note: 'no ink' };
  const lineH = line.y1 - line.y0 + 1;
  const runs = reconcileRuns(T.glyphRuns(img), serialText.length, lineH);
  if (runs.length !== serialText.length) return { samples: null, note: `${runs.length} glyphs vs ${serialText.length} chars` };
  const out = [];
  for (let k = 0; k < runs.length; k++) {
    const b = T.glyphBitmap(img, runs[k][0], runs[k][1]);
    if (!b) return { samples: null, note: 'empty glyph' };
    out.push({ ch: serialText[k], bits: b.bits, aspect: b.aspect });
  }
  return { samples: out };
}

async function main() {
  const pdf = process.argv[2];
  const testPages = (process.argv[3] || '5').split(',').map(Number);
  const dpi = Number(process.argv[4] || 300);
  const minMargin = Number(process.argv[5] || 0.02);
  const maxDist = Number(process.argv[6] || 0.16);
  // Training pages default to the first 20 list pages. Training needs no OCR at
  // all -- only the known serial values and the pixels -- so this is cheap, and
  // more templates is the single thing that most improves the margins below.
  const trainPages = process.argv[7]
    ? process.argv[7].split(',').map(Number)
    : Array.from({ length: 20 }, (_, i) => FIRST_LIST_PAGE + i);

  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'tmpl-'));

  // ---- train on serials only ------------------------------------------
  const train = {};
  let trained = 0, skipped = 0;
  for (const p of trainPages) {
    let png;
    try { png = pngFor(pdf, p, dpi, path.join(tmp, 'tp' + p)); }
    catch { skipped += 0; continue; }             // page is not a list page
    const g = detect(png);
    g.cards.forEach((card, i) => {
      const serial = String((p - FIRST_LIST_PAGE) * PER_PAGE + 1 + i);
      const r = serialSamples(png, card, serial, tmp, `p${p}c${i}`);
      if (!r.samples) { skipped++; return; }
      for (const s of r.samples) (train[s.ch] = train[s.ch] || []).push(s.bits);
      trained++;
    });
  }
  const templates = T.buildTemplates(train);
  console.log(`  trained on ${trained} serials from pages ${trainPages[0]}-${trainPages[trainPages.length - 1]} (${skipped} skipped)`);
  console.log(`  samples per digit: ${Object.entries(train).map(([c, v]) => c + ':' + v.length).join(' ')}`);

  // ---- classify house glyphs ------------------------------------------
  const rows = [];
  for (const p of testPages) {
    const png = pngFor(pdf, p, dpi, path.join(tmp, 'pg'));
    const g = detect(png);
    const wk = await createWorker('eng');
    for (let i = 0; i < g.cards.length; i++) {
      const loc = await cropColumn(png, g.cards[i], path.join(tmp, `c${p}_${i}.png`), wk);
      if (!loc.rect) { rows.push({ p, i, house: null, note: 'line not found' }); continue; }
      const hf = renderLineSync(png, loc.rect, path.join(tmp, `h${p}_${i}.png`), 1);
      const img = readGray(hf);
      const line = T.lineInkBox(img);
      if (!line) { rows.push({ p, i, house: null, note: 'no ink' }); continue; }
      const lineH = line.y1 - line.y0 + 1;
      const runs = T.glyphRuns(img);
      let out = '', notes = [];
      for (const r of runs) {
        const b = T.glyphBitmap(img, r[0], r[1]);
        if (!b) continue;
        // Separators are recognised by shape, not by template, because the
        // templates only cover digits.
        //
        // A hyphen is wider than it is tall and short. A slash is full height but
        // sparse -- a thin diagonal stroke -- where every digit is chunky. Both
        // rules are geometric and so cannot silently turn a separator into a
        // digit, which is what happened when only the digit templates existed:
        // "90-26-26/8" came back as "92-7-7/273".
        if (b.aspect > 0.9 && b.h < lineH * 0.55) { out += '-'; continue; }
        if (b.h > lineH * 0.75 && b.density < 0.30 && b.aspect > 0.2 && b.aspect < 0.85) { out += '/'; continue; }
        const res = T.classify(b, templates);
        const d = T.decide(res, minMargin, maxDist);
        if (d.ok) out += d.ch;
        else { out += '?'; notes.push(d.why); }
      }
      rows.push({ p, i, house: out, notes });
    }
    await wk.terminate();
  }

  // ---- compare --------------------------------------------------------
  console.log('');
  let ok = 0, total = 0, wrong = 0;
  for (const r of rows) {
    const truth = (TRUTH[r.p] || [])[r.i];
    if (truth === undefined) continue;
    total++;
    const good = r.house === truth;
    if (good) ok++;
    if (!good) wrong++;
    const mark = good ? '   ' : ' <<';
    console.log(`  p${r.p} card ${String(r.i).padStart(2)}  ${String(r.house).padEnd(14)} ${String(truth).padEnd(14)}${mark}${r.notes && r.notes.length ? '  ' + r.notes[0] : ''}`);
  }
  console.log('');
  console.log(`  house exact  ${ok}/${total}  = ${total ? ((ok / total) * 100).toFixed(1) : '-'}%`);
  console.log(`  margin threshold ${minMargin}, distance ceiling ${maxDist}`);
}

main().catch((e) => { console.error(e); process.exit(1); });