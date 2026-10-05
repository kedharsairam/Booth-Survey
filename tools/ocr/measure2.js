// Measures, per card, where the serial digits end and where the serial box's right
// border begins.
//
// These two numbers set the crop's right edge, and they have to be measured
// together because they are close: the digits run right up against the frame. An
// edge that clears the border also risks clipping the last digit, and an edge that
// clears the digits includes the border -- and tesseract reads "11" plus a
// full-height bar as one token and answers "1". Both failure modes were seen on
// this roll before the crop was measured rather than guessed.

const { detect, readGray } = require('./grid.js');
const { pagePng } = require('./layout.js');

const pdf = process.argv[2];
const pages = (process.argv[3] || '3,6').split(',').map(Number);

for (const page of pages) {
  const png = pagePng(pdf, page, 300);
  const g = detect(png);
  const { w, px } = readGray(png);
  console.log(`  page ${page}:`);
  let digitMax = 0, borderMin = 1e9;
  for (const i of [0, 9, 10, 24, 25, 26, 29]) {
    const c = g.cards[i];
    if (!c) continue;
    // Look across the whole serial strip, card-relative.
    const Y0 = Math.round(c.y0 + 20), Y1 = Math.round(c.y0 + 200);
    const H = Y1 - Y0;
    const cols = [];
    for (let dx = 400; dx < 900; dx++) {
      let ink = 0, longest = 0, run = 0;
      for (let y = Y0; y < Y1; y++) {
        if (255 - px[y * w + Math.round(c.x0 + dx)] > 100) { ink++; run++; if (run > longest) longest = run; }
        else run = 0;
      }
      cols.push({ dx, ink, longest });
    }
    // Digits: ink columns whose longest contiguous run is under 80% of the strip.
    const digits = cols.filter((k) => k.ink > 3 && k.longest < H * 0.80);
    // A border: one long contiguous run, and ink columns on both sides of it.
    const borders = cols.filter((k) => k.longest >= H * 0.80);
    const dRight = digits.length ? Math.max(...digits.map((k) => k.dx)) : null;
    const bAt = borders.length ? Math.min(...borders.map((k) => k.dx)) : null;
    if (dRight !== null) digitMax = Math.max(digitMax, dRight);
    if (bAt !== null) borderMin = Math.min(borderMin, bAt);
    console.log(`    card ${String(i).padStart(2)}: digits end dx ${dRight === null ? '-' : dRight}   border candidates dx ${borders.map((k) => k.dx).join(',') || '-'}`);
  }
  console.log(`    => rightmost digit across these cards: dx ${digitMax};  nearest border: dx ${borderMin}`);
  if (digitMax && borderMin < 1e9) console.log(`    => a right edge at dx ${Math.round((digitMax + borderMin) / 2)} sits between them`);
}