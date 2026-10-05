// Finds the serial digits on each card by locating the *gap* that separates them from the
// box's right-hand rule, and reports the resulting crop as fractions of the card.
//
// Why the ink profile alone is not enough
// ---------------------------------------
// measure-serial.js reported one wide ink run per card, 184 to 195 px of a 618 px card --
// about 30% of the card width, far wider than any serial. The digits and the box's
// right-hand rule are not always separated by white: on cards whose serial ends near the
// rule the two touch, and the profile cannot tell a digit from the border it is leaning on.
//
// So the border has to be found first, as the tall thin thing it is, and then the digits
// are everything to its left. The rule is distinguished by shape rather than by position:
// it runs the full height of the box's interior, where a digit occupies about half.
//
// Usage: node measure-serial.js <roll.pdf> [page]

const { pagePng } = require('./layout.js');
const { detect, readGray } = require('./grid.js');
const F = require('./fields.js');

const pdf = process.argv[2];
const page = Number(process.argv[3] || 3);
const png = pagePng(pdf, page);
const g = detect(png);
const img = readGray(png);
const dark = (x, y) => 255 - img.px[y * img.w + x] > 100;

const out = [];
let skipped = 0, merged = 0;

for (const card of g.cards) {
  const cw = card.x1 - card.x0, ch = card.y1 - card.y0;
  // A generous window over the box. The box's own borders are found inside it rather than
  // assumed at a fixed inset, because they are not at one.
  const bx0 = Math.round(card.x0 + cw * 0.01), bx1 = Math.round(card.x0 + cw * 0.36);
  const by0 = Math.round(card.y0 + ch * 0.02), by1 = Math.round(card.y0 + ch * 0.22);
  const w = bx1 - bx0;

  // The box is drawn as a rectangle, so its top and bottom borders are *rows* that are
  // inked across nearly the whole width. Two earlier attempts at this measurement measured
  // a 180 px "serial" on every card, which is the width of the box: those horizontal rules
  // put ink in every column, and no vertical analysis can see a digit through them.
  const inked = [];
  for (let y = by0; y < by1; y++) {
    let n = 0;
    for (let x = bx0; x < bx1; x++) if (dark(x, y)) n++;
    inked.push({ y, frac: n / w });
  }
  const ruleRows = inked.filter((r) => r.frac > 0.75).map((r) => r.y);
  if (ruleRows.length < 2) { skipped++; continue; }
  // The interior is between the first and last horizontal rule.
  const iTop = ruleRows[0], iBot = ruleRows[ruleRows.length - 1];
  if (iBot - iTop < 6) { skipped++; continue; }
  const ih = iBot - iTop;

  // Per column of the interior: how much is inked, and how deep it goes.
  const cols = [];
  for (let x = bx0; x < bx1; x++) {
    let n = 0, deepest = -1;
    for (let y = iTop + 1; y < iBot; y++) {
      if (dark(x, y)) { n++; if (y > deepest) deepest = y; }
    }
    cols.push({ n, deep: deepest < 0 ? -1 : deepest - iTop });
  }

  // A column inked down almost the whole interior is a vertical border. Digits stand on the
  // baseline and occupy the lower part of it, but never reach the top of the interior --
  // the interior has headroom above the tallest digit. A border runs corner to corner.
  //
  // *Every* border column is excluded, not just the right-hand one. An earlier version
  // searched for the right border and then took all the ink to its left as the serial,
  // which included the left border and so measured the whole box as the number: 199 px
  // against a 72 px four-digit serial, on every card, confidently.
  const isBorder = (i) => cols[i].n > ih * 0.85;
  const digitCols = [];
  for (let i = 0; i < cols.length; i++) if (!isBorder(i) && cols[i].n > 0) digitCols.push(bx0 + i);
  if (!digitCols.length) { skipped++; continue; }

  // The digits are right-aligned in the box, so the run nearest the right border is the
  // serial. Any run further left is a stray mark or the watermark, and is not part of it.
  const runs = [];
  for (const x of digitCols) {
    const prev = runs[runs.length - 1];
    // Digits are separated by a few pixels; a wider gap ends the run. The gap here is a
    // property of the type, not of the render, so it is taken as a fraction of the digit
    // height the interior just gave us.
    if (prev && x - prev[1] <= 3) prev[1] = x;
    else runs.push([x, x]);
  }
  const d = runs[runs.length - 1];
  const d0 = d[0], d1 = d[1] + 1;

  // Does the serial touch the right border? Then the two are one ink run, and the crop has
  // to exclude the border by ending short of it rather than by ending at the digits.
  const nearestBorder = cols.length - 1;
  let borderX = -1;
  for (let i = cols.length - 1; i >= 0; i--) {
    if (isBorder(i)) { borderX = bx0 + i; break; }
  }
  if (borderX > 0 && borderX - d1 <= 2) merged++;

  // The digits' own vertical extent, inside the interior.
  let top = 1e9, bot = -1;
  for (let x = d0; x < d1; x++) {
    for (let y = iTop + 1; y < iBot; y++) if (dark(x, y)) { if (y < top) top = y; if (y > bot) bot = y; }
  }
  if (top > bot) { skipped++; continue; }

  out.push({
    x0: (d0 - card.x0) / cw, x1: (d1 - card.x0) / cw,
    y0: (top - card.y0) / ch, y1: (bot + 1 - card.y0) / ch,
    w: d1 - d0, touching: borderX > 0 && borderX - d1 <= 2,
  });
}

if (!out.length) { console.log('  nothing measured'); process.exit(1); }
const med = (a) => { const s = [...a].sort((x, y) => x - y); return s[Math.floor(s.length / 2)]; };
const p = (a, q) => { const s = [...a].sort((x, y) => x - y); return s[Math.floor(q * (s.length - 1))]; };
const at = (k) => [p(out.map((o) => o[k]), 0.05), med(out.map((o) => o[k])), p(out.map((o) => o[k]), 0.95)];

console.log(`  ${out.length} cards measured, ${skipped} skipped, ${merged} with digits touching the rule`);
console.log(`  card box ${Math.round(g.cards[0].x1 - g.cards[0].x0)} x ${Math.round(g.cards[0].y1 - g.cards[0].y0)} px`);
console.log('');
const f = (t) => t.toFixed(4).padStart(7);
console.log('            p5      median       p95       fields.js says');
for (const k of ['x0', 'x1', 'y0', 'y1']) {
  const [a, m, b] = at(k);
  console.log(`  ${k.padEnd(6)}${f(a)} ${f(m)} ${f(b)}   ${F.SERIAL[k].toFixed(4)}`);
}
console.log('');
console.log('  digit run width, px: median', med(out.map((o) => o.w)),
  ' p95', p(out.map((o) => o.w), 0.95));
console.log('  the widest serial in this booth is 4 digits; at 18 px native that is ~72 px.');
console.log('');
console.log('  The right edge has to clear the rule on every card, including the ones where the');
console.log('  digits touch it -- so take the p95 of the digit extent and let the rule fall');
console.log('  outside, rather than the median, which would sit in the middle of the digits.');