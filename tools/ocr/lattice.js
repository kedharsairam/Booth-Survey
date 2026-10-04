// Completes the card grid from its own lattice.
//
// Why
// ---
// The cards are printed on a strict lattice -- three columns by ten rows, with column
// left edges at x 47, 680, 1314 and row top edges 264 px apart -- and detect() finds
// them by looking for printed ink. That misses a card whose ink does not look like the
// rest. It has: English page 13 row 9 column 0 carries a card (serial 291, voter id
// TST000007, house 91-3-3/5) that detect() returns nothing for. That single card is
// the whole 1128-versus-1129 discrepancy between the two rolls.
//
// Filling lattice slots is the right repair rather than loosening detect()'s
// thresholds. The lattice is not an assumption about where cards ought to be -- it is
// measured, from the columns and rows that were found -- and it makes the detector
// indifferent to any one card's oddity. Loosening thresholds instead would have to be
// re-tuned per document and would start inventing cards elsewhere.
//
// Why an ink test is not optional
// -------------------------------
// Pages legitimately end mid-row: page 5 stops at 26 of 27, page 9 at 4 of 6, page 16
// at 16 of 18. Those empty slots have no card in them and must stay empty, because a
// card invented here is a voter who does not exist, and it would be indistinguishable
// from a real one everywhere downstream -- in the family grouping, in the booklet, and
// on the printed sheet.
//
// So every empty slot is tested against the image before it is filled, and a slot that
// does not carry a card's rules is left empty. The distinction that matters is between
// an empty slot at the END of the last row, which is how the roll stops, and a hole in
// the MIDDLE of the grid, which means a card was missed. Both look identical to a
// lattice fit; only the image tells them apart.

const { readGray } = require('./grid.js');

// Cards are this wide and tall, taken from the median of whatever was detected. Only a
// fraction is needed to test for a card's rules, so an approximate size is enough and
// avoids depending on the detection being exactly right.
const MEDIAN = (xs) => {
  const s = [...xs].sort((a, b) => a - b);
  return s[Math.floor(s.length / 2)];
};

// Clusters values that agree to within tol. The lattice edges land on the same pixel on
// every card, so this is generous for jitter and still far tighter than the 264 px row
// pitch and 633 px column pitch it has to keep apart.
function cluster(values, tol = 12) {
  const s = [...values].sort((a, b) => a - b);
  const out = [];
  for (const v of s) {
    if (!out.length || v - out[out.length - 1].at(-1) > tol) out.push([v]);
    else out[out.length - 1].push(v);
  }
  return out.map((g) => MEDIAN(g));
}

// Does this slot hold a card? A card is a rectangle drawn in rules, so its top edge is a
// horizontal line spanning the width and its left edge a vertical line spanning the
// height. Requiring both rules, rather than just any ink, keeps a stray mark or a
// continuation of the previous card from counting.
//
// Each edge is scored as the best of the first few rows or columns, not just the first.
// A card's rules are exactly one pixel wide -- measured on page 13, ink fraction 1.000
// on the top rule's own row and 0.002 on the row below it -- so testing a fixed offset
// from the fitted edge fails on every card whenever the lattice is a pixel out. Scoring
// a small band tolerates that without becoming loose enough to admit a smudge.
function holdsCard(px, w, box) {
  const ink = (x, y) => 255 - px[y * w + x] > 100;
  const spanX = box.x1 - box.x0 - 8;
  const spanY = box.y1 - box.y0 - 8;

  let bestTop = 0;
  for (let d = 0; d <= 2; d++) {
    let n = 0;
    for (let x = box.x0 + 4; x < box.x1 - 4; x++) if (ink(x, box.y0 + d)) n++;
    bestTop = Math.max(bestTop, n / spanX);
  }
  if (bestTop < 0.5) return false;

  let bestLeft = 0;
  for (let d = 0; d <= 2; d++) {
    let n = 0;
    for (let y = box.y0 + 4; y < box.y1 - 4; y++) if (ink(box.x0 + d, y)) n++;
    bestLeft = Math.max(bestLeft, n / spanY);
  }
  return bestLeft >= 0.5;
}

/**
 * Fills lattice slots that hold a card the grid detector missed.
 *
 * @param {string} png   page PNG at native resolution
 * @param {object} grid  result of detect(), whose cards are read-only
 * @returns {{cards: Array, added: number}} cards in reading order, and how many were
 *   recovered
 */
function completeLattice(png, grid) {
  const found = grid.cards;
  if (found.length < 2) return { cards: found, added: 0 };

  const cw = MEDIAN(found.map((c) => c.x1 - c.x0));
  const ch = MEDIAN(found.map((c) => c.y1 - c.y0));
  const cols = cluster(found.map((c) => Math.round(c.x0)));
  const rows = cluster(found.map((c) => Math.round(c.y0)));

  const have = new Set(found.map((c) => `${Math.round(c.x0)},${Math.round(c.y0)}`));
  const missing = [];
  for (const y of rows) {
    for (const x of cols) {
      if (have.has(`${x},${y}`)) continue;
      missing.push({ x0: x, y0: y, x1: x + cw, y1: y + ch });
    }
  }
  if (!missing.length) return { cards: found, added: 0 };

  // Only pay for the pixels if there is a slot in question.
  const { w, px } = readGray(png);
  const added = missing.filter((b) => holdsCard(px, w, b));

  const cards = [...found, ...added]
    .sort((a, b) => (Math.round(a.y0) - Math.round(b.y0)) || (Math.round(a.x0) - Math.round(b.x0)));
  return { cards, added: added.length, slots: missing.length };
}

module.exports = { completeLattice };