// Self-test for the template matcher.
//
// The matcher is only worth anything if it can classify glyphs it was trained on
// the same kind of. This measures that directly, in two parts:
//
//   held-out serials  -- same field, same font, same size as training. If this is
//                        not ~100%, the matcher itself is broken and no amount of
//                        training data will help.
//   house digits      -- a different field at a different size, which is the
//                        case that actually matters.
//
// Run with no arguments to see the results.

const fs = require('fs');
const path = require('path');
const os = require('os');
const { execFileSync } = require('child_process');
const { createWorker } = require('tesseract.js');
const { detect, readGray } = require('./grid.js');
const { renderLineSync, cropColumn } = require('./house.js');
const T = require('./templates.js');
const F = require('./fields.js');
const { pageLayout } = require('./layout.js');

function rel(card, f) {
  const cw = card.x1 - card.x0, ch = card.y1 - card.y0;
  const x0 = Math.round(card.x0 + f.x0 * cw), y0 = Math.round(card.y0 + f.y0 * ch);
  const x1 = Math.round(card.x0 + f.x1 * cw), y1 = Math.round(card.y0 + f.y1 * ch);
  return { x0, y0, w: x1 - x0, h: y1 - y0 };
}

function reconcileRuns(runs, expected, lineH) {
  const out = runs.map((r) => [r[0], r[1]]);
  let guard = 0;
  while (out.length < expected && guard++ < 40) {
    let bi = 0, bw = -1;
    for (let i = 0; i < out.length; i++) {
      const w = out[i][1] - out[i][0] + 1;
      if (w > bw) { bw = w; bi = i; }
    }
    if (bw < lineH * 0.5) break;
    const mid = Math.round((out[bi][0] + out[bi][1]) / 2);
    out.splice(bi, 1, [out[bi][0], mid], [mid, out[bi][1]]);
  }
  return out;
}

function serialGlyphs(png, card, serialText, tmp, i) {
  const f = renderLineSync(png, rel(card, F.SERIAL), path.join(tmp, `s${i}.png`), 1);
  const img = readGray(f);
  const line = T.lineInkBox(img);
  if (!line) return null;
  const lineH = line.y1 - line.y0 + 1;
  const runs = reconcileRuns(T.glyphRuns(img), serialText.length, lineH);
  if (runs.length !== serialText.length) return null;
  return runs.map((r, k) => ({ ch: serialText[k], b: T.glyphBitmap(img, r[0], r[1]) }));
}

async function main() {
  const pdf = process.argv[2];
  const dpi = Number(process.argv[3] || 300);
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'selftest-'));

  // Labels come from layout.js, which derives each page's first serial from the
  // detected card counts. Pages are not all 30 records long -- page 6 holds 26,
  // page 10 holds 4 -- so any formula assuming 30 shifts every label after the
  // first short page.
  const { pages, total } = pageLayout(pdf, dpi);
  const TEST = new Set([16, 17]);
  const trainPages = process.env.TRAIN_PAGES ? pages.filter((p) => !TEST.has(p.page)).slice(0, Number(process.env.TRAIN_PAGES)) : pages.filter((p) => !TEST.has(p.page));
  const testPages = pages.filter((p) => TEST.has(p.page));
  console.log(`  layout: ${pages.length} pages, ${total} cards`);

  // ---- train ----------------------------------------------------------
  const train = {};
  let n = 0;
  for (const pg of trainPages) {
    pg.cards.forEach((card, i) => {
      const serial = String(pg.first + i);
      const gl = serialGlyphs(pg.png, card, serial, tmp, `p${pg.page}c${i}`);
      if (!gl) return;
      for (const g2 of gl) { if (!g2.b) continue; (train[g2.ch] = train[g2.ch] || []).push(g2.b.bits); n++; }
    });
  }
  const templates = T.buildTemplates(train);
  console.log(`  trained on ${n} glyphs from ${trainPages.length} pages`);
  console.log(`  per digit: ${Object.entries(train).map(([c, v]) => c + ':' + v.length).join(' ')}`);

  // ---- part 1: held-out serials ---------------------------------------
  let ok = 0, tested = 0, dists = [];
  for (const pg of testPages) {
    pg.cards.forEach((card, i) => {
      const serial = String(pg.first + i);
      const gl = serialGlyphs(pg.png, card, serial, tmp, `t${pg.page}c${i}`);
      if (!gl) return;
      for (const g2 of gl) {
        if (!g2.b) continue;
        const r = T.classify(g2.b, templates);
        tested++; dists.push(r.dist);
        if (r.ch === g2.ch) ok++;
      }
    });
  }
  dists.sort((a, b) => a - b);
  console.log(`\n  HELD-OUT SERIALS   ${ok}/${tested} = ${tested ? ((ok / tested) * 100).toFixed(1) : '-'}%`);
  console.log(`    best-match distance  median ${dists[Math.floor(dists.length / 2)].toFixed(3)}  p95 ${dists[Math.floor(dists.length * 0.95)].toFixed(3)}  max ${dists[dists.length - 1].toFixed(3)}`);

  // ---- part 2: house digits -------------------------------------------
  const wk = await createWorker('eng');
  let hok = 0, htot = 0, hd = [];
  try {
    for (const p of testPages) {
      const png = pagePng(pdf, p, dpi);
      const g = detect(png);
      for (let i = 0; i < g.cards.length; i++) {
        const loc = await cropColumn(png, g.cards[i], path.join(tmp, `c${p}_${i}.png`), wk);
        if (!loc.rect) continue;
        const img = readGray(renderLineSync(png, loc.rect, path.join(tmp, `h${pg.page}_${i}.png`), 1));
        const line = T.lineInkBox(img);
        if (!line) continue;
        for (const r of T.glyphRuns(img)) {
          const b = T.glyphBitmap(img, r[0], r[1]);
          if (!b) continue;
          if (b.aspect > 0.9 && b.h < (line.y1 - line.y0 + 1) * 0.55) continue;      // hyphen
          if (b.h > (line.y1 - line.y0 + 1) * 0.75 && b.density < 0.30) continue;   // slash
          const res = T.classify(b, templates);
          htot++; hd.push(res.dist);
        }
      }
    }
  } finally { await wk.terminate(); }
  hd.sort((a, b) => a - b);
  console.log(`  HOUSE DIGIT GLYPHS ${htot} classified (no labels; distance only)`);
  console.log(`    best-match distance  median ${hd[Math.floor(hd.length / 2)].toFixed(3)}  p95 ${hd[Math.floor(hd.length * 0.95)].toFixed(3)}  max ${hd[hd.length - 1].toFixed(3)}`);
  console.log(`    (compare with the serial figures above: if the house median is far higher, the two fields are not the same typeface)`);
}

main().catch((e) => { console.error(e); process.exit(1); });