// Segments a card into its text lines and locates each field by its printed label.
//
// Why labels rather than fixed positions
// --------------------------------------
// The fields on this card are not at fixed offsets. A card whose name runs to two lines
// and whose father's name runs to two lines pushes "House Number" down by two rows
// relative to a card with one line each: measured on page 3, card 0's House Number sits
// at dy 176 and card 1's at dy 119, a 57 px difference in a 249 px card. Cropping by
// position therefore reads whichever field happens to be at that offset, which is how a
// door number ends up holding an age.
//
// Every field on the card carries its own label, printed on the same line as the value.
// The label is the anchor, and it moves with the value. This module finds the labels and
// hands back the region after the colon.
//
// Why lines, and not words
// -------------------------
// A value is not confined to one line. "HEMA SRI SAI REENA KONDAPALLI" wraps onto a
// second line with no label on it, and that second line belongs to the name. So the
// card is segmented into lines first, each line is attributed to whichever field's label
// it carries, and a line carrying no label continues whichever field the line above it
// belonged to. Attribution by label is also self-checking: if a line has no label and no
// field above it, the card's layout is not what this expects and it is reported rather
// than guessed.

// The printed labels, longest first so that "Fathers Name:" is matched before a bare
// "Name :" can claim it. Matched case-insensitively and ignoring runs of spaces, since
// the roll prints "House Number :" with a space before the colon and "Fathers Name:"
// without.
const LABELS = [
  { field: 'house', label: 'House Number' },
  { field: 'father', label: 'Fathers Name' },
  { field: 'name', label: 'Name' },
  { field: 'age', label: 'Age' },
  { field: 'gender', label: 'Gender' },
];

// The card's text block. The serial box occupies the top-left and the photo box the
// right-hand side from about dx 455 down, so the text column is bounded on both sides.
// Measured on page 3, text starts at dx 15 and the longest value ends near dx 450.
const TEXT_X0 = 0.020, TEXT_X1 = 0.720;
const TEXT_Y0 = 0.200, TEXT_Y1 = 0.985;

/**
 * Splits a card's text column into line bands.
 *
 * A band is a run of rows carrying ink, allowing for the descenders and the gap that
 * separates one printed row from the next. Rows with a little ink -- the tail of a
 * previous line's descender, or a stray mark -- are absorbed into the band above rather
 * than starting a band of their own.
 *
 * @returns {Array<{y0:number,y1:number}>} in reading order, card-relative
 */
function textLines(px, pageW, card) {
  const cw = card.x1 - card.x0, ch = card.y1 - card.y0;
  const x0 = Math.round(card.x0 + cw * TEXT_X0);
  const x1 = Math.round(card.x0 + cw * TEXT_X1);
  const y0 = Math.round(card.y0 + ch * TEXT_Y0);
  const y1 = Math.round(card.y0 + ch * TEXT_Y1);

  const rowInk = [];
  for (let y = y0; y < y1; y++) {
    let n = 0;
    for (let x = x0; x < x1; x++) if (255 - px[y * pageW + x] > 100) n++;
    rowInk.push({ y, n, frac: n / (x1 - x0) });
  }

  // A row belongs to a line if it carries more than a trace of ink. The trace of a row
  // between lines is under one part in a thousand of the column.
  const bands = [];
  let start = -1;
  for (let i = 0; i < rowInk.length; i++) {
    const on = rowInk[i].frac > 0.001;
    if (on && start < 0) start = i;
    if (!on && start >= 0) { bands.push([rowInk[start].y, rowInk[i - 1].y]); start = -1; }
  }
  if (start >= 0) bands.push([rowInk[start].y, rowInk[rowInk.length - 1].y]);

  // Merge bands separated by less than half a line's height: those are one line broken
  // by a gap in its ink, not two lines.
  const merged = [];
  for (const b of bands) {
    const prev = merged[merged.length - 1];
    if (prev && b[0] - prev[1] < 7) prev[1] = b[1];
    else merged.push(b);
  }
  return merged.map(([a, b]) => ({ y0: a - card.y0, y1: b - card.y0 + 1 }));
}

module.exports = { textLines, LABELS, TEXT_X0, TEXT_X1, TEXT_Y0, TEXT_Y1 };