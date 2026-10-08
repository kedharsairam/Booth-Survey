// Measures where the serial digits actually sit inside a card.
//
// The point is to stop assuming a field position carries over between documents.
// F.SERIAL was measured on the Telugu roll and used on the English one, where it
// produced 122 correct serials out of 1128 -- the recogniser was being handed crops of
// blank paper and correctly reporting nothing. A field position is a property of one
// document until measured on another, which is worth keeping in mind: the same roll is
// not two versions of itself.
//
// English only. The Telugu roll was dropped; the English roll is the one that gets
// extracted from.

const { detect, readGray } = require('./grid.js');
const { pagePng } = require('./layout.js');

const ROLLS = [
  ['ENGLISH', process.argv[2], Number(process.argv[3] || 3)],
];

for (const [tag, pdf, page] of ROLLS) {
  const png = pagePng(pdf, page, 300);
  const g = detect(png);
  const { w, px } = readGray(png);
  console.log(`  ${tag} (page ${page})`);
  for (const i of [0, 9, 10, 29]) {
    const c = g.cards[i];
    if (!c) continue;
    // Scan the whole upper-right quadrant of the card for ink.
    const X0 = Math.round(c.x0 + 400), X1 = Math.round(c.x0 + 900);
    const Y0 = Math.round(c.y0), Y1 = Math.round(c.y0 + 260);
    let ax = 1e9, bx = -1, ay = 1e9, by = -1, n = 0;
    for (let y = Y0; y <= Y1; y++) {
      for (let x = X0; x <= X1; x++) {
        if (255 - px[y * w + x] > 120) {
          n++;
          if (x < ax) ax = x; if (x > bx) bx = x;
          if (y < ay) ay = y; if (y > by) by = y;
        }
      }
    }
    if (bx < 0) { console.log(`    card ${String(i).padStart(2)}: no ink found`); continue; }
    console.log(`    card ${String(i).padStart(2)}: serial ink at card dx ${ax - c.x0}..${bx - c.x0}  dy ${ay - c.y0}..${by - c.y0}   (${bx - ax + 1} x ${by - ay + 1} px, ${n} dark px)`);
  }
}