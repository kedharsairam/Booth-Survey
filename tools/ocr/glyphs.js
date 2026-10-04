// Splits a row of characters into individual glyph boxes.
//
// Needed for the voter id, which is always 3 letters followed by 7 digits. No
// whitelist admits both halves without also admitting the confusions that
// matter -- "D" and "Q" become "0", "S" becomes "5" -- so instead of asking the
// recogniser to read the whole string and hoping, the string is cut in two at
// a geometric boundary and each half is read with a whitelist that fits it.
//
// The cut is found from the ink, not guessed. Characters are separated by
// narrow gaps, so the column ink profile falls into runs; runs separated by a
// small gap are the same character (a "2" breaks across its diagonal, an "H"
// sheds a sliver) and runs separated by a wide gap are different characters.
//
// Measured over the 30 cards of page 5, merging runs across gaps of 6 px or
// less yields exactly 10 groups -- one per character -- on 29 cards and 11 on
// one. Anything else is repaired by merging the closest pair or splitting the
// widest group, and failing that the cut falls back to the group boundary
// nearest the proportional position.

const { bands } = require('./grid.js');

const MERGE_GAP = 6;          // px; runs closer than this are one character
const SPLIT_AT = 3;           // characters before the cut (the prefix length)

// Column ink profile of a grayscale image, as a boolean per column.
function columnInk(img, thr = 128) {
  const { w, h, px } = img;
  const out = new Uint8Array(w);
  for (let x = 0; x < w; x++) {
    let n = 0;
    for (let y = 0; y < h; y++) if (px[y * w + x] < thr) n++;
    out[x] = n > 0 ? 1 : 0;
  }
  return out;
}

function mergeRuns(runs, gap) {
  const out = [];
  for (const r of runs) {
    const prev = out[out.length - 1];
    if (prev && r[0] - prev[1] <= gap) prev[1] = r[1];
    else out.push([r[0], r[1]]);
  }
  return out;
}

// Returns group boundaries as [x0, x1] pairs, ideally `expect` of them.
function glyphs(img, expect) {
  const runs = bands(columnInk(img), 1);
  if (!runs.length) return [];
  let g = mergeRuns(runs, MERGE_GAP);

  // Repair toward the expected count. Merge the closest pair first, split the
  // widest group second; both are unambiguous because a real inter-character
  // gap is far wider than the gap inside a broken character.
  while (g.length > expect) {
    let bi = 0, bd = Infinity;
    for (let i = 1; i < g.length; i++) {
      const d = g[i][0] - g[i - 1][1];
      if (d < bd) { bd = d; bi = i; }
    }
    g.splice(bi - 1, 2, [g[bi - 1][0], g[bi][1]]);
  }
  while (g.length < expect && g.length > 1) {
    let bi = 1, bw = -1;
    for (let i = 1; i < g.length; i++) {
      const w = g[i][1] - g[i][0];
      if (w > bw) { bw = w; bi = i; }
    }
    const mid = Math.round((g[bi][0] + g[bi][1]) / 2);
    g.splice(bi, 1, [g[bi][0], mid], [mid, g[bi][1]]);
  }
  return g;
}

// The x range to cut at, as a pixel offset in the image. Prefers the boundary
// between the `at`th and the next glyph, placed mid-gap: a cut anywhere inside
// the gap is safe, whereas a cut at the edge of a glyph with even a few pixels
// of padding drags a sliver of the neighbouring character into the crop and
// shows up as a stray leading digit in the read. If the glyph count was not
// recoverable, falls back to the gap nearest the proportional position.
function splitAt(img, expect, at = SPLIT_AT) {
  const g = glyphs(img, expect);
  const runs = g.length ? g : mergeRuns(bands(columnInk(img), 1), MERGE_GAP);
  if (!runs.length) return null;
  if (g.length === expect && g.length > at) {
    return Math.round((g[at - 1][1] + g[at][0]) / 2);
  }
  const left = runs[0][0], right = runs[runs.length - 1][1];
  const want = left + (right - left) * (at / expect);
  let best = runs[0][1], bd = Infinity;
  for (let i = 1; i < runs.length - 1; i++) {
    const d = Math.abs(runs[i][1] - want);
    if (d < bd) { bd = d; best = runs[i][1]; }
  }
  return Math.round((best + runs[runs.indexOf(best) + 1][0]) / 2);
}

module.exports = { glyphs, splitAt, columnInk, MERGE_GAP, SPLIT_AT };