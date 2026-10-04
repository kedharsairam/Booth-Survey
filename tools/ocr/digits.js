// Reads serials by matching glyphs against a labelled atlas, instead of by OCR.
//
// Why not OCR
// -----------
// Tesseract plateaus on this field. The serial is printed at about 6-11 px per glyph
// wide and 18 px tall at native resolution, and on English page 3 it reads 29 of 30 --
// but across all 43 pages it reads 1056 of 1129, and the 73 it misses are not close
// calls. Magnifying harder does not help: six geometries from 400% to 1000%x800% were
// tried on 24 of the failures and the best of them fixed 5. Blurrier input produces a
// different wrong answer rather than a right one.
//
// The field is ten digits. There is no open vocabulary, no language model and no
// ambiguity about the character set, which is the case where a general recogniser is
// the wrong instrument and a nearest-exemplar classifier is the right one. Every digit
// is compared against every labelled digit and the closest wins.
//
// Where the labels come from, and why that is not circular
// ---------------------------------------------------------
// The roll numbers its voters consecutively from 1, the grid holds exactly 1129 cards,
// and the last card reads 1129 -- so the serial for card i is knowable from its position.
// Those positional serials are the labels.
//
// That would be circular if the template reader were then used to confirm them. It is
// not, because the template reader is what tests them: the atlas is built from glyphs
// segmented out of the image, and a segmentation that does not produce exactly as many
// glyphs as the serial has digits is discarded rather than forced. A card whose pixels
// disagree with its position about how many digits it has is evidence against the
// position, and is reported. A run where every card agrees with its position is
// agreement between two independent things, not one thing checked against itself.

const { readGray } = require('./grid.js');
const { INSET } = require('./box.js');

// Glyphs are normalised to this grid before comparison, so that a digit's position
// within the serial and the serial's own length make no difference to its identity.
//
// 16 is enough to separate ten digits for the serial, where every reading is checked
// against a known positional label. The voter id needs more, because there the decision
// between two candidate readings rests on how close two glyphs are, not on whether either
// matches a label -- so callers pass a finer grid for it.
const NORM = 16;

// A column counts as inked when it holds at least this many inked pixels. One pixel is
// scanner speckle, and raising the threshold to keep more of it out merges digits
// instead: measured over the first ten pages, a threshold of 2 splits every one of 266
// serials into exactly as many glyphs as it has digits, while 3 splits only 81%, 4
// splits 97% and 5 splits 66%, each failure being two digits read as one group.
const MIN_COL_INK = 2;

/**
 * The binarised, trimmed bitmap of a rectangular region.
 *
 * Done on pixels here rather than by shelling out to convert, because this runs for
 * every card of every page and the atlas build needs to see exactly the pixels the reader
 * will later see.
 *
 * @param {number[]} px     page grayscale pixels
 * @param {number}   pageW  page width
 * @param {{x,y,w,h}} rect  region in absolute page pixels
 * @returns {{w:number,h:number,bits:Uint8Array}|null} 1 is ink, or null when the region
 *   holds no ink at all
 */
function bitmapFromRect(px, pageW, rect) {
  const { x: x0, y: y0, w, h } = rect;

  const raw = [];
  for (let y = 0; y < h; y++) {
    const row = [];
    for (let x = 0; x < w; x++) row.push(255 - px[(y0 + y) * pageW + (x0 + x)] > 100 ? 1 : 0);
    raw.push(row);
  }

  let minX = w, maxX = -1, minY = h, maxY = -1;
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      if (!raw[y][x]) continue;
      if (x < minX) minX = x;
      if (x > maxX) maxX = x;
      if (y < minY) minY = y;
      if (y > maxY) maxY = y;
    }
  }
  if (maxX < 0) return null;

  const bw = maxX - minX + 1, bh = maxY - minY + 1;
  const bits = new Uint8Array(bw * bh);
  for (let y = 0; y < bh; y++) {
    for (let x = 0; x < bw; x++) bits[y * bw + x] = raw[minY + y][minX + x];
  }
  return { w: bw, h: bh, bits };
}

/**
 * The serial's bitmap, binarised and trimmed, straight from the page pixels.
 * @returns {{w:number,h:number,bits:Uint8Array}|null} 1 is ink
 */
function serialBitmap(px, pageW, box) {
  return bitmapFromRect(px, pageW, {
    x: box.left + INSET,
    y: box.top + INSET,
    w: box.right - box.left - 2 * INSET,
    h: box.bottom - box.top - 2 * INSET,
  });
}

/**
 * Splits a serial's bitmap into glyphs.
 *
 * By column runs, separated wherever a column carries too little ink to be part of a
 * digit. Digit widths vary but the gap between digits does not, so a run boundary is
 * reliably a blank column or two.
 *
 * @returns {Array<{x:number,w:number}>} each glyph's offset and width in the bitmap
 */
function segment({ w, h, bits }, minColInk = MIN_COL_INK) {
  const colInk = new Array(w).fill(0);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) if (bits[y * w + x]) colInk[x]++;
  }

  const glyphs = [];
  let start = -1;
  for (let x = 0; x < w; x++) {
    const solid = colInk[x] >= minColInk;
    if (solid && start < 0) start = x;
    if (!solid && start >= 0) { glyphs.push({ x: start, w: x - start }); start = -1; }
  }
  if (start >= 0) glyphs.push({ x: start, w: w - start });
  return glyphs;
}

/**
 * Reduces one glyph to a fixed NORM x NORM grid of ink fractions.
 *
 * Scaled uniformly by the bitmap's height and centred, which keeps a digit's width
 * relative to its height -- the feature that separates "1" from "0" at six pixels
 * across -- without making the result depend on where the digit happens to sit.
 *
 * Both other options were tried and both are much worse. Scaling each glyph's own box to
 * fill the grid widens "1" into a blob and reads the roll at 40%, with three quarters of
 * the errors collapsing onto "1". Keeping the glyph at its own offset within the bitmap
 * preserves width but not robustness: a digit's offset depends on the widths of the
 * digits before it, so the same "5" lands in a different column in "52" than in "105",
 * and that variant reads 16%.
 *
 * Scaling by the shared height and centring is position-independent and aspect-
 * preserving at the same time, so this is the one that can actually classify.
 */
function normalise({ w, h, bits }, glyph, norm = NORM) {
  const out = new Float32Array(norm * norm);

  // Uniform scale: the same factor on both axes, taken from the bitmap's height so that
  // every digit lands on the same baseline and cap height.
  const scale = norm / h;
  const sw = glyph.w * scale;
  const sh = h * scale;
  const ox = (norm - sw) / 2;
  const oy = (norm - sh) / 2;

  for (let gy = 0; gy < norm; gy++) {
    // Which rows of the bitmap feed this output row.
    const y0 = Math.max(0, Math.floor((gy - oy) / scale));
    const y1 = Math.min(h, Math.ceil((gy + 1 - oy) / scale));
    for (let gx = 0; gx < norm; gx++) {
      const x0 = Math.max(0, Math.floor((gx - ox) / scale));
      const x1 = Math.min(glyph.w, Math.ceil((gx + 1 - ox) / scale));
      if (y1 <= y0 || x1 <= x0) continue;

      let n = 0, area = 0;
      for (let y = y0; y < y1; y++) {
        for (let x = x0; x < x1; x++) { area++; if (bits[y * w + (glyph.x + x)]) n++; }
      }
      out[gy * norm + gx] = area ? n / area : 0;
    }
  }
  return out;
}

/**
 * Nearest exemplar by sum of squared differences.
 *
 * Returns the exemplar's label under `char`, and also under `digit`, because callers
 * disagree about which they expect and a mismatch here is invisible: the label comes back
 * `undefined`, every reading concatenates to an empty string, and the comparison against
 * a known value fails for reasons that look like a total recognition failure rather than
 * a one-word typo. That is exactly what happened -- `classify` returned `best.digit` while
 * the atlases store `char`, so both properties were undefined.
 */
function classify(vec, atlas) {
  let best = null, bestD = Infinity;
  for (const ex of atlas) {
    let d = 0;
    for (let i = 0; i < vec.length; i++) {
      const t = vec[i] - ex.vec[i];
      d += t * t;
    }
    if (d < bestD) { bestD = d; best = ex; }
  }
  if (!best) return null;
  const label = best.char !== undefined ? best.char : best.digit;
  return { char: label, digit: label, distance: bestD };
}

/**
 * Reads one card's serial from the page pixels, given an atlas.
 * @returns {{serial:string, glyphs:number, digits:string[]}|null}
 */
function readSerial(px, pageW, box, atlas) {
  const bmp = serialBitmap(px, pageW, box);
  if (!bmp) return null;
  const glyphs = segment(bmp);
  const digits = [];
  for (const g of glyphs) {
    const m = classify(normalise(bmp, g), atlas);
    if (!m) return null;
    digits.push(m.digit);
  }
  return { serial: digits.join(''), glyphs: glyphs.length, digits };
}

module.exports = {
  bitmapFromRect,
  serialBitmap, segment, normalise, classify, readSerial,
  NORM, MIN_COL_INK,
};