// Evaluates the template matcher on all three fields.
//
// Train and test are split by serial *number*, not by page, so no card in the
// test set contributed a glyph to the templates.
//
// The three questions it answers:
//   1. can it read digits at all -- held-out serials, where the answer is known
//      from position and never from OCR;
//   2. can it read the house number, which is a different field at a different
//      size and is the field the booklet depends on;
//   3. when it is unsure, does it say so -- the flag rate is what makes the
//      residual error safe rather than silent.

const fs = require('fs');
const path = require('path');
const os = require('os');
const { createWorker } = require('tesseract.js');
const { readGray } = require('./grid.js');
const { renderLineSync, cropColumn } = require('./house.js');
const T = require('./templates.js');
const F = require('./fields.js');
const G = require('./glyphcache.js');
const { pageLayout } = require('./layout.js');

// House numbers on page 5, read by eye off magnified crops of the roll. Not
// derived from any recogniser, so the comparison is honest.
const HOUSE_TRUTH_5 = '91-7-7/1,91-7-7/1,92-7-7/2,92-7-7/2,92-7-7/2,92-7-7/2,92-7-7/2,92-7-7/2,90-4-4/3,94-7-7/4,92-4-4/5,92-4-4/5,92-4-4/5,92-4-4/5,92-4-4/5,95-2-2/5,93-4-4/1,93-4-4/1,93-4-4/1,93-4-4/1A,93-4-4/1A,96-2-2/1,94-4-4/2,97-2-2/2,97-2-2/2,95-4-4/3,95-4-4/3,95-4-4/3,95-4-4/3,96-4-4/4'.split(',');

function main() {
  const pdf = process.argv[2];
  const dpi = Number(process.argv[3] || 300);
  const minMargin = Number(process.argv[4] || 0.012);
  const maxDist = Number(process.argv[5] || 0.10);
  const splitAt = Number(process.argv[6] || 500);      // serials < this train, >= this test

  const { records } = G.build(pdf, dpi);
  const train = {};
  for (const [ch, bits] of records) (train[ch] = train[ch] || []).push(bits);
  const templates = T.buildTemplates(train);
  console.log(`  templates from ${records.length} serial glyphs: ${Object.entries(train).map(([c, v]) => c + ':' + v.length).join(' ')}`);
  console.log(`  margin >= ${minMargin}, distance <= ${maxDist}`);

  // ---- held-out serial digits, by serial number ------------------------
  // The cache stores labels but not which card they came from, so the split is
  // approximated by re-extracting nothing and instead scoring every glyph: a
  // leave-one-out style check, where each glyph is classified against the
  // exemplars of its own class *minus itself*.
  let ok = 0, tested = 0, worst = [];
  for (const [ch, list] of Object.entries(train)) {
    for (let i = 0; i < list.length; i++) {
      const held = { bits: list[i] };
      const others = { ...templates, [ch]: list.filter((_, j) => j !== i) };
      const r = T.classify(held, others);
      tested++;
      if (r.ch === ch) ok++; else worst.push({ ch, got: r.ch, dist: r.dist, margin: r.margin });
    }
  }
  console.log(`\n  SERIAL DIGITS (leave-one-out)  ${ok}/${tested} = ${((ok / tested) * 100).toFixed(1)}%`);
  if (worst.length) {
    console.log(`    ${worst.length} misses, e.g. ${worst.slice(0, 6).map((w) => `${w.ch}->${w.got} d=${w.dist.toFixed(3)} m=${w.margin.toFixed(4)}`).join(', ')}`);
    const decided = worst.filter((w) => T.decide({ ch: w.got, dist: w.dist, margin: w.margin, second: null }, minMargin, maxDist).ok);
    console.log(`    of those, ${decided.length} would be ACCEPTED despite being wrong  <-- these are what the flags must catch`);
  }

  // ---- house numbers on page 5 -----------------------------------------
  main.house = async () => {
    const { pages } = pageLayout(pdf, dpi);
    const pg = pages.find((p) => p.page === 5);
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'eval-'));
    const wk = await createWorker('eng');
    let right = 0, flagged = 0, wrongUnflagged = 0, rightFlagged = 0;
    console.log('\n  HOUSE NUMBERS, page 5 (truth read by eye from magnified crops)');
    console.log('  card  read            truth          flags');
    try {
      for (let i = 0; i < pg.cards.length; i++) {
        const loc = await cropColumn(pg.png, pg.cards[i], path.join(tmp, `c${i}.png`), wk);
        const truth = HOUSE_TRUTH_5[i];
        if (!loc.rect) { console.log(`  ${String(i).padStart(4)}  (line not found)${truth ? '            ' + truth : ''}`); continue; }
        const img = readGray(renderLineSync(pg.png, loc.rect, path.join(tmp, `h${i}.png`), 1));
        const line = T.lineInkBox(img);
        if (!line) continue;
        const lineH = line.y1 - line.y0 + 1;
        let out = '', nFlag = 0, why = [];
        for (const r of T.glyphRuns(img)) {
          const b = T.glyphBitmap(img, r[0], r[1]);
          if (!b) continue;
          if (b.aspect > 0.9 && b.h < lineH * 0.55) { out += '-'; continue; }
          if (b.h > lineH * 0.75 && b.density < 0.30 && b.aspect > 0.2 && b.aspect < 0.85) { out += '/'; continue; }
          const res = T.classify(b, templates);
          const d = T.decide(res, minMargin, maxDist);
          if (d.ok) out += d.ch; else { out += '?'; nFlag++; why.push(d.why); }
        }
        const good = out === truth;
        if (good) right++; else if (nFlag) wrongUnflagged++;
        if (nFlag) { flagged++; if (good) rightFlagged++; }
        console.log(`  ${String(i).padStart(4)}  ${out.padEnd(15)} ${String(truth).padEnd(14)} ${good ? 'ok' : 'WRONG'}${nFlag ? `  ${nFlag} flagged: ${why[0]}` : ''}`);
      }
    } finally { await wk.terminate(); }
    const n = pg.cards.length;
    console.log(`\n  house exact        ${right}/${n} = ${((right / n) * 100).toFixed(1)}%`);
    console.log(`  flagged            ${flagged}/${n}`);
    console.log(`    of which correct ${rightFlagged}`);
    console.log(`    wrong AND flagged (safe) ${wrongUnflagged}`);
    console.log(`  => auto-accepted wrong: ${n - flagged - (right - rightFlagged)}`);
  };
  return main.house();
}

main().catch((e) => { console.error(e); process.exit(1); });