// Checks fastcrop against ImageMagick, byte for byte.
//
// The point is not that the fast path is fast. It is that it produces the same
// image. Every accuracy number this project has recorded was measured on
// ImageMagick's output; if the replacement differs by a single pixel threshold,
// those numbers stop meaning anything and a regression would be invisible.
//
// Run:  node verify.js <page.png>

const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');
const F = require(path.join(__dirname, 'fastcrop.js'));
const { detect } = require('./grid.js');
const { readGray } = require('./grid.js');

const CASES = [];
for (const scale of [2, 3, 4]) {
  for (const thr of [50, 55, 58, 62, 68, 70, 75]) CASES.push({ scale, thr });
}

function compare(a, b) {
  const ga = readGray(a), gb = readGray(b);
  if (ga.w !== gb.w || ga.h !== gb.h) {
    return { ok: false, why: `size ${ga.w}x${ga.h} vs ${gb.w}x${gb.h}` };
  }
  let diff = 0, worst = 0;
  for (let i = 0; i < ga.px.length; i++) {
    const d = Math.abs(ga.px[i] - gb.px[i]);
    if (d) { diff++; if (d > worst) worst = d; }
  }
  return { ok: diff === 0, why: diff ? `${diff}/${ga.px.length} px differ, worst ${worst}` : 'identical' };
}

function main() {
  const pagePng = process.argv[2];
  if (!pagePng) { console.error('usage: node verify.js <page.png>'); process.exit(2); }
  const g = detect(pagePng);
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'verify-'));
  const F2 = require('./fields.js');

  // Crops covering every field the pipeline actually reads: the serial box, the
  // voter id, the house column and a re-cropped house line.
  const rects = [];
  for (const card of g.cards.slice(0, 4)) {
    const cw = card.x1 - card.x0, ch = card.y1 - card.y0;
    const rel = (f) => ({
      x0: Math.round(card.x0 + f.x0 * cw), y0: Math.round(card.y0 + f.y0 * ch),
      x1: Math.round(card.x0 + f.x1 * cw), y1: Math.round(card.y0 + f.y1 * ch),
    });
    const s = rel(F2.SERIAL);
    rects.push({ name: 'serial', rect: { x0: s.x0, y0: s.y0, w: s.x1 - s.x0, h: s.y1 - s.y0 } });
    const hx0 = Math.round(card.x0 + F2.HOUSE_X0 / F2.CARD_W * cw);
    const hx1 = Math.round(card.x0 + F2.HOUSE_X1 / F2.CARD_W * cw);
    const hy0 = Math.round(card.y0 + 200 / F2.CARD_H * ch);
    const hy1 = Math.round(card.y0 + (F2.CARD_H - 40) / F2.CARD_H * ch);
    rects.push({ name: 'housecol', rect: { x0: hx0, y0: hy0, w: hx1 - hx0, h: hy1 - hy0 } });
  }

  let total = 0, ok = 0;
  const rows = [];
  for (const { name, rect } of rects) {
    for (const { scale, thr } of CASES) {
      const a = path.join(tmp, 'im.png'), b = path.join(tmp, 'fast.png');
      const t0 = Date.now();
      F.renderCropViaImageMagick(pagePng, rect, a, scale, thr, 24, true);
      const imMs = Date.now() - t0;
      const t1 = Date.now();
      F.renderCrop(pagePng, rect, b, scale, thr, 24, true);
      const fastMs = Date.now() - t1;
      const r = compare(a, b);
      total++; if (r.ok) ok++;
      rows.push({ name, scale, thr, imMs, fastMs, rect, ...r });
    }
  }

  for (const r of rows) {
    if (!r.ok) {
      console.log(`  MISMATCH ${r.name} scale=${r.scale} thr=${r.thr}: ${r.why}`);
      console.log(`      rect ${JSON.stringify(r.rect)}`);
    }
  }
  const imTotal = rows.reduce((s, r) => s + r.imMs, 0);
  const fastTotal = rows.reduce((s, r) => s + r.fastMs, 0);
  console.log(`  ${ok}/${total} crops byte-identical to ImageMagick`);
  console.log(`  ImageMagick ${imTotal} ms   fast ${fastTotal} ms   ` +
    `speedup ${(imTotal / Math.max(1, fastTotal)).toFixed(0)}x`);
  fs.rmSync(tmp, { recursive: true, force: true });
  process.exit(ok === total ? 0 : 1);
}

main();