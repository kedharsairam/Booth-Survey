// Field crop boxes and recognition settings.
//
// Why fractions and not pixels: the roll is a scanned image, so a card's pixel
// size depends on the DPI it was rendered at and on the page trim. The card's
// internal layout is fixed by the form, so the grid detector supplies each
// card's pixel box and everything here is a fraction of it. That makes the
// extractor independent of render settings.
//
// The constants were measured off a 300 dpi render where a card came out
// 2576 x 1038 px.

const CARD_W = 2576;
const CARD_H = 1038;

// serial -- the electoral-roll serial, boxed at the top left of the card.
//
// The X range was measured on the Telugu roll, where the digits sat between the box's own
// near-black borders at dx ~5 and dx ~875, right-aligned, ending near dx 850; the two- and
// three-digit serials reached furthest left, about dx 610. That range is used here too, and
// it is correct for this roll as well -- measured on English page 3 with measure-serial.js,
// the digits land in the same relative position. It was worth re-checking anyway, because
// the failure that follows this one was never a geometry failure.
//
// The Y range, though, was Telugu-only in effect, and the recognition mode was worse than
// useless here. Two separate faults, found together on a full English run:
//
//   - 15 blank serials, 72 serial strings on more than one card, and confidence 0 on cards
//     whose digit is plainly legible. The crops were checked and are clean -- a 1, 2, 3, 4
//     and 5 side by side, each with the box's right-hand rule showing as a stray vertical
//     bar. So the position was fine and the read was failing.
//   - Under psm 8 with a digits-only whitelist, tesseract returned the *empty string* for
//     card 0 -- a bare "1", which is a single narrow stem with no baseline to its left or
//     right to anchor a word. psm 7 and psm 6 read all five cards correctly.
//
// So the position is kept and the mode below is changed. A single bare digit is a line, not
// a word, and psm 8's model of a word is the wrong shape for it.
const SERIAL = { x0: 600 / CARD_W, y0: 58 / CARD_H, x1: 866 / CARD_W, y1: 168 / CARD_H };

// serial box -- the ruled rectangle the serial sits in, taken as a whole.
//
// The digits inside it touch its right-hand rule on every card measured, so no crop of this
// box contains the serial and excludes the rule. read.js therefore takes the box and removes
// the rule by its shape -- a column inked the full height of the interior -- rather than
// trying to place a crop between them. The X range is the box's own left and right rules,
// found on English page 3 by row profile (the box's top and bottom borders are rows inked
// across 89% of the box's width, at dy 8 and dy 46 of a 249 px card); the Y range is the
// interior between them.
//
// This is deliberately wider than SERIAL. SERIAL is where the digits are; this is where the
// box is, and the box has to be taken whole so the rules can be seen and removed.
//
// The fractions are of the card box, measured on English page 3 where a card is 618 x 249 px,
// and written as plain decimals rather than as pixels over CARD_W and CARD_H. Dividing those
// pixels by the Telugu card size instead -- the way every constant above is written -- puts
// the box at 8 px from the card's top instead of 9, and the reader then takes a crop of the
// card's top border and reports every serial blank. The units here are a property of the
// document measured, exactly like the fractions they replace.
const SERIAL_BOX = { x0: 0.005, y0: 0.036, x1: 0.345, y1: 0.190 };

// epic -- the voter id, top right, e.g. "ZZZ5857149". Measured ink dx
// 1898..2514, dy 73..147. Always 3 letters then 7 digits.
const EPIC = { x0: 1860 / CARD_W, y0: 55 / CARD_H, x1: 2545 / CARD_W, y1: 168 / CARD_H };

// house -- the house/door number. X range only; the Y comes from line detection
// at run time, because it is not fixed (see LINE below). Measured: the Telugu
// label "ఇంటి సంఖ్య" ends and its colon sits at dx ~490, the value starts at
// dx ~512, and the photo box's left rule is at dx 1934. So the crop starts
// just left of the colon and any leading ':' is stripped afterwards.
const HOUSE_X0 = 512;
const HOUSE_X1 = 1920;

// Line detection, in card fractions.
//
// The house number cannot be cropped at a fixed y. When a voter's name wraps to
// two lines, every field below it shifts down by one line pitch -- card 45 on
// page 5 is the worked example: its name wraps, so its house number sits at
// dy 561 instead of 448, and a fixed crop reads the watermark off blank paper
// instead ("o8eRRNTP)IONE"). So the text lines are found per card.
//
// A row counts as writing when enough of the region's width carries ink. The
// watermark is sparse -- roughly 9% of a row against 25-40% for a line of text
// -- so LINE_INK_MIN separates them without needing to know the watermark's
// angle or grey level.
//
// Note what is deliberately *not* done here: adjacent bands are not merged.
// Telugu sets the horizontal headstroke apart from the body of a syllable and
// vowel marks dip below the baseline, so one visual line arrives as several
// bands -- but the gaps *inside* a line (up to 22 px) overlap the gaps *between*
// lines (as little as 13 px), so no gap threshold separates the two. Merging at
// 18 px fuses the relation line into the house line and costs most of the page
// its house numbers. Lines are told apart by their pitch instead; see
// houseLine() in read.js.
const TEXT_REGION = { x0: 50 / CARD_W, x1: 1900 / CARD_W, y0: 150 / CARD_H, y1: (CARD_H - 40) / CARD_H };
const LINE_INK_MIN = 0.012;    // fraction of region width that counts as a text row
const LINE_MIN_H = 28;         // px; below this a band is a stray mark, not a line
const PITCH_TOLERANCE = 0.45;  // fraction of the pitch a band may sit off and still match

// tesseract variable names, not friendly aliases. tesseract.js forwards every
// unrecognised key in the options object straight to `SetVariable`, so a
// plausible-looking `whitelist: '0123456789'` is accepted without complaint and
// then does nothing -- which shows up as letters surviving inside digit-only
// fields ("o0", "O1" for serials 50 and 51) rather than as an error.
//
// PSM 8 ("single word") is what reads these fields. Measured over the 30 cards
// of page 5, each serial known to be 31..60 by position:
//
//   psm 7  (single line)  21/30 exact -- drops one digit or merges the pair
//   psm 8  (single word)  30/30 exact
//   psm 13 (raw line)     30/30 exact
//
// PSM 7 loses a digit on roughly every third card because these are pairs of
// glyphs with no inter-word space to anchor a "line" against. PSM 8 and 13
// agree; PSM 8 is used. The trim on the crop is what separates them -- without
// it PSM 8 drops to 28/30 with confidence around 11.
const PSM8 = { tessedit_pageseg_mode: '8' };
// Recognition settings per field.
//
// psm 7, "assume a single text line", not psm 8's "assume a single word". Measured on the
// serial crops of English page 3, five cards, each carrying a clean digit plus the box's
// right-hand rule:
//
//     mode   results across the five cards
//     psm 7  1 2 3 4 5      all five correct
//     psm 6  1 2 3 4 5      all five correct
//     psm 8  (blank) 2 3 4 5   the single-digit card returned nothing at all
//     psm 13 2 3 4 5        same as psm 8
//
// psm 8 is also what the house vote was seven-ninths built from, where the same measurement
// puts psm 7 and psm 6 two to three times ahead of it. It is the wrong default for this
// form throughout: these values are digit runs inside a printed field, not words.
const DIGITS = { tessedit_char_whitelist: '0123456789', tessedit_pageseg_mode: '7' };
const UPPER = { tessedit_char_whitelist: 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', tessedit_pageseg_mode: '7' };
const HOUSE_WL = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-/. ';

// The house field is the only one read by more than one configuration and
// voted on. It is also the only genuinely variable field: values run from
// "91-7-7/1" to "17 3 1/1" to "PLOT NO 304", and the grey diagonal watermark has
// dark cores that survive thresholding and occasionally append themselves to
// the digits. Two passes that disagree are recorded as a disagreement rather
// than silently taking the first answer.
const HOUSE_PASSES = [
  { name: 'wide', opts: { tessedit_char_whitelist: HOUSE_WL, tessedit_pageseg_mode: '8' } },
  { name: 'raw', opts: { tessedit_pageseg_mode: '6' } },
];

const FIELDS = { serial: SERIAL, epic: EPIC };

module.exports = {
  CARD_W, CARD_H, SERIAL, SERIAL_BOX, EPIC, HOUSE_X0, HOUSE_X1,
  TEXT_REGION, LINE_INK_MIN, LINE_MIN_H, PITCH_TOLERANCE,
  PSM8, DIGITS, UPPER, HOUSE_PASSES, FIELDS,
};