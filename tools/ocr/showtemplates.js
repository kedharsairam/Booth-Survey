// Renders the digit templates as a contact sheet, so they can be looked at.
//
// Worth having as a file rather than a one-off command: a template set that does
// not classify held-out glyphs is nearly always a template set that looks wrong,
// and "looks wrong" is something a picture shows immediately and a distance
// matrix does not.

const fs = require('fs');
const path = require('path');
const os = require('os');
const { execFileSync } = require('child_process');
const { detect, readGray } = require('./grid.js');
const { renderLineSync } = require('./house.js');
const T = require('./templates.js');
const F = require('./fields.js');

const FIRST_LIST_PAGE = 4;
const PER_PAGE = 30;

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

// Writes one glyph bitmap as a PGM, the plainest lossless way to hand a bitmap
// to ImageMagick.
function writePgm(bits, file) {
  const buf = Buffer.alloc(bits.length);
  for (let i = 0; i < bits.length; i++) buf[i] = bits[i] ? 0 : 255;
  fs.writeFileSync(file, Buffer.concat([Buffer.from(`P5\n${T.CANVAS_W} ${T.CANVAS_H}\n255\n`), buf]));
}

function main() {
  const cache = process.argv[2];
  const outPng = process.argv[3];
  const pages = Number(process.argv[4] || 12);
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'tplvis-'));

  const train = {};
  let used = 0;
  for (let i = 0; i < pages; i++) {
    const page = FIRST_LIST_PAGE + i;
    const png = path.join(cache, `p${page}-300.png`);
    if (!fs.existsSync(png)) continue;
    const g = detect(png);
    g.cards.forEach((card, k) => {
      const serial = String((page - FIRST_LIST_PAGE) * PER_PAGE + 1 + k);
      const f = renderLineSync(png, rel(card, F.SERIAL), path.join(tmp, `s${page}_${k}.png`), 1);
      const img = readGray(f);
      const line = T.lineInkBox(img);
      if (!line) return;
      const lineH = line.y1 - line.y0 + 1;
      const runs = reconcileRuns(T.glyphRuns(img), serial.length, lineH);
      if (runs.length !== serial.length) return;
      runs.forEach((r, j) => {
        const b = T.glyphBitmap(img, r[0], r[1]);
        if (b) { (train[serial[j]] = train[serial[j]] || []).push(b.bits); }
      });
      used++;
    });
  }

  const tpl = T.buildTemplates(train);
  const keys = Object.keys(tpl).sort();
  const files = [];
  for (const k of keys) {
    const g = path.join(tmp, `t${k}.pgm`);
    const p = path.join(tmp, `t${k}.png`);
    writePgm(tpl[k].bits, g);
    execFileSync('convert', [g, '-scale', '400%', '-bordercolor', 'gray', '-border', '2', p]);
    files.push(p);
  }
  execFileSync('montage', [...files, '-tile', '5x2', '-geometry', '+8+8', '-background', '#bbbbbb', outPng]);
  console.log(`  ${keys.length} templates from ${used} serials -> ${outPng}`);
  console.log(`  samples: ${Object.entries(train).map(([c, v]) => c + ':' + v.length).join(' ')}`);
}

main();