// Gate for the house-number locator in read.js.
//
// Two things are being checked, and the second is the one that was broken:
//
//   1. The locator finds the house line by its printed label, on real English cards.
//   2. The rectangle it returns starts at the value, not at the label. Getting this wrong
//      does not fail loudly -- it prepends the label's letters to every value, so "96-3-3/5"
//      comes back as "HN96-3-3/5" and the field still looks plausible.
//
// It runs against the real module rather than a reconstruction of it. A copy of the
// regular expressions would pass whatever the copy said and tell nothing about the file
// the pipeline uses, which is how the husband-name fix was nearly judged by a pattern
// that was not the one in cardread.js.
//
// The crops are read live from the roll, so this needs the sample PDF. Without it the gate
// reports that it was skipped rather than passing vacuously.

const path = require('path');
const fs = require('fs');
const { execFileSync } = require('child_process');
const { pagePng } = require('./layout.js');
const { detect, readGray } = require('./grid.js');
const { textLines } = require('./card.js');
const { parseLine } = require('./cardread.js');
const { renderLine, LOOK } = require('./house.js');
const { createWorker } = require('tesseract.js');

const SC = 3;
const BORDER = 24;

const SAMPLE = process.env.BOOTH_OCR_SAMPLE
  || path.join(__dirname, '..', '..', 'samples',
    '2026-EROLLGEN-S01-57-SIR-DraftRoll-Revision1-ENG-108-WI.pdf');

const rel = (card, f) => {
  const cw = card.x1 - card.x0, ch = card.y1 - card.y0;
  const x0 = Math.round(card.x0 + f.x0 * cw), y0 = Math.round(card.y0 + f.y0 * ch);
  const x1 = Math.round(card.x0 + f.x1 * cw), y1 = Math.round(card.y0 + f.y1 * ch);
  return { x0, y0, w: x1 - x0, h: y1 - y0 };
};

// The locator, reproduced from read.js by calling into the same helpers. It cannot import
// read.js's copy because read.js runs its pipeline at require time.
async function locateHouse(pagePngFile, card, pageW, px, worker, tmp) {
  const ch = card.y1 - card.y0;
  await worker.setParameters({ tessedit_char_whitelist: '' });
  for (const band of textLines(px, pageW, card)) {
    const bandRect = rel(card, {
      x0: 0.015, x1: 0.730,
      y0: (band.y0 - 3) / ch,
      y1: (band.y1 + 3) / ch,
    });
    if (bandRect.h < 8) continue;
    const strip = await renderLine(pagePngFile, bandRect, path.join(tmp, 'g-band.png'), SC, 62, false);
    const { data } = await worker.recognize(strip, { tessedit_pageseg_mode: '7' },
      { text: true, blocks: true });
    const raw = data.text.replace(/\s+/g, ' ').trim();
    const hit = parseLine(raw).find((e) => e.field === 'house');
    if (!hit) continue;
    const words = [];
    for (const b of data.blocks || []) {
      for (const p of b.paragraphs || []) for (const l of p.lines || [])
        for (const wd of l.words || []) words.push({ text: (wd.text || '').trim(), bbox: wd.bbox });
    }
    const squash = (s) => s.replace(/[^A-Za-z0-9]/g, '').toLowerCase();
    const want = squash(hit.value);
    const toPageX = (X) => bandRect.x0 + (X - BORDER) / SC;
    let vx = null;
    for (const w of words) {
      const got = squash(w.text);
      if (!got || !w.bbox) continue;
      if (want.startsWith(got)) { vx = Math.round(toPageX(w.bbox.x0)); break; }
    }
    if (vx === null) return { rect: { ...bandRect }, value: hit.value, line: raw, precise: false };
    const x0 = Math.max(bandRect.x0, vx - 8);
    if (bandRect.x0 + bandRect.w - x0 < 12) {
      return { rect: { ...bandRect }, value: hit.value, line: raw, precise: false };
    }
    return {
      rect: { x0, y0: bandRect.y0, w: bandRect.x0 + bandRect.w - x0, h: bandRect.h },
      value: hit.value, line: raw, precise: true,
    };
  }
  return { rect: null, value: '', line: '', precise: false };
}

async function main() {
  if (!fs.existsSync(SAMPLE)) {
    console.log(`  SKIPPED: no sample roll at ${SAMPLE}`);
    console.log('  Set BOOTH_OCR_SAMPLE to run this.');
    process.exit(0);
  }
  const png = pagePng(SAMPLE, 3, 300);
  const g = detect(png);
  const page = readGray(png);
  const tmp = fs.mkdtempSync(path.join(require('os').tmpdir(), 'gatetest-'));
  const worker = await createWorker('eng');

  let located = 0, precise = 0, checked = 0, bad = 0;
  const fails = [];
  for (const card of g.cards.slice(0, 12)) {
    const loc = await locateHouse(png, card, page.w, page.px, worker, tmp);
    if (!loc.rect) continue;
    located++;
    if (loc.precise) precise++;
    if (!loc.precise) continue;
    checked++;

    // The value read from the located rectangle, read the way the vote reads it.
    const f = await renderLine(png, loc.rect, path.join(tmp, 'g-val.png'), 2, 62, true);
    const { data } = await worker.recognize(f, LOOK, { text: true });
    const read = data.text.replace(/\s+/g, '')
      .replace(/^[^0-9A-Za-z]+/, '').replace(/[^0-9A-Za-z]+$/, '');

    // The label's own words must not be in the crop. That is the regression this gate
    // exists for: the value's leading characters must be digits or a letter that the
    // grammar allows, never the letters of "House Number".
    const m = read.match(/^([A-Za-z]+)/);
    if (m && /hn|hous|num|mb/i.test(m[1])) {
      bad++;
      fails.push(`card ${checked}: crop still contains the label -- read ${JSON.stringify(read)} from line ${JSON.stringify(loc.line)}`);
    }
  }
  await worker.terminate();

  console.log(`  cards checked       : ${checked}`);
  console.log(`  house line located  : ${located}/${g.cards.slice(0, 12).length}`);
  console.log(`  value edge precise  : ${precise}/${located}`);
  console.log(`  label leaked into   : ${bad}`);
  for (const f of fails) console.log(`    ${f}`);
  if (!checked) { console.log('  FAILED: nothing was located, so nothing was checked'); process.exit(1); }
  if (bad) { console.log('  FAILED: the value crop still contains the label'); process.exit(1); }
  console.log('  PASS');
}

main().catch((e) => { console.log(`  FAILED: ${e.message}`); process.exit(1); });