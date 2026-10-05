// Locates the printed box that holds the serial number, on every card of a page.
//
// Why the serial needs its own crop rather than a constant one
// ----------------------------------------------------------
// The serial is printed inside a box. Tesseract reads "11" plus the box's right
// border as a single token and answers "1". Removing the border is the whole
// difference between roughly 50% and 100% on this field.
//
// No fixed crop edge can do it. The box's right border moves with the serial's
// width -- measured on English page 3: dx 201 for the serial "1", dx 206 for "10",
// dx 209 for "25", dx 212 for "11" -- because the serial is right-aligned inside a
// border drawn to suit it, or the scan drifts with the text. The digits always stop
// about 8 px short of it, so a crop wide enough for the widest serial admits the
// rule and a crop narrow enough to exclude it clips the last digit. There is no
// third option, which is why this measures the box per card.
//
// The box's other three edges are fixed: left rule at dx 8-9, top rule at dy 8-9,
// bottom rule at dy 46-47, across all 30 cards of the page. They are measured too
// rather than assumed, because "fixed" here means "fixed on this page of this
// document", and a hard-coded constant is exactly the thing that silently rots.
//
// How the box is found
// --------------------
// Two horizontal rules mark the box's top and bottom. Both are skipped by their
// score, which is set from measurement: the box's rules ink about 0.71 of the search
// width, the card's own top border inks 1.00 because it genuinely spans the card.
// The card's border row is also excluded by position, because taking it as the box's
// top makes the interior 8 px too tall and loses the box entirely.
//
// Left and right are then the vertical lines inked over the box's full interior
// height. The card's own left and right borders are vertical lines too, so they
// qualify; they are separated out by how much of the card they cover. Measured: the
// card's border covers 1.000 of the card's height, the box's left rule covers 0.176
// of it while covering 1.000 of the box band. A column must be full-height across the
// box and clearly not full-height across the card.
//
// The digits never qualify. A "1" is a tall thin stroke, but it is one or two columns
// wide and leaves the rest of the interior blank, so it covers about half the rows.

const { execFileSync } = require('child_process');
const { detect, readGray } = require('./grid.js');
const { pagePng } = require('./layout.js');

// Search window, as fractions of the card. The serial box sits in the left part of
// the card's upper band.
const SX0 = 0.00, SX1 = 0.46;
const SY0 = 0.00, SY1 = 0.30;

// Ink threshold. The native image is clean black-on-white scan data, so anything
// above this is printed mark rather than paper texture.
const INK = 100;

// A horizontal rule is inked across most of the search width.
const RULE_FRAC = 0.5;

// A vertical rule is inked over the box's full interior height.
const VLINE_FRAC = 0.95;

// A column inked over more than this much of the card belongs to the card's frame,
// not to a box drawn inside it.
const CARD_SPAN_MAX = 0.6;

// Clearance between the interior and each rule. Two pixels at native resolution,
// enough to drop the antialiased fringe the scanner leaves on a rule's edge -- which
// is itself enough to make tesseract join the rule to the last digit.
const INSET = 2;

/**
 * Finds the serial box on every detected card of a page.
 *
 * @param {string} png   path to the page's native-resolution PNG
 * @param {object} grid  result of detect() for that page
 * @returns {Array<{card:number, rect:object}>} in card order, cards whose box could
 *   not be located are omitted rather than guessed
 */
function serialBoxes(png, grid) {
  const { w, px } = readGray(png);
  const inked = (x, y) => 255 - px[y * w + x] > INK;
  const found = [];

  for (let i = 0; i < grid.cards.length; i++) {
    const c = grid.cards[i];
    const cw = c.x1 - c.x0, ch = c.y1 - c.y0;
    const sx0 = Math.round(c.x0 + cw * SX0);
    const sx1 = Math.round(c.x0 + cw * SX1);
    const sy0 = Math.round(c.y0 + ch * SY0);
    const sy1 = Math.round(c.y0 + ch * SY1);

    // Horizontal rules, excluding the card's own top border.
    const cardTop = Math.round(c.y0);
    const rules = [];
    for (let y = sy0; y < sy1; y++) {
      let n = 0;
      for (let x = sx0; x < sx1; x++) if (inked(x, y)) n++;
      if (n / (sx1 - sx0) > RULE_FRAC && y !== cardTop) rules.push(y);
    }
    if (rules.length < 2) continue;

    // The box's bottom is the rule immediately below its top, not the last rule on the
    // card. Text can score above the rule threshold: on page 43 card 4 the "Name :"
    // line measures 0.51 of the search width and registers as a third rule at dy 65,
    // which taken as the bottom gives a 65 px interior instead of 47 and leaves the
    // vertical rules nowhere near full-height, so the box is lost. The box is the first
    // region closed off below the card's own border, so the second rule is what ends
    // it.
    const top = rules[0];
    const bottom = rules[1];

    // The box is about 39 px tall at native resolution. Anything implausible is
    // reported as unlocated rather than cropped, so a card whose rules pair up wrongly
    // cannot be read as if it had a serial.
    if (bottom - top < 25 || bottom - top > 60) continue;

    // Vertical rules across the box interior, minus anything that belongs to the card.
    const iy0 = top + 1, iy1 = bottom - 1;
    const ih = iy1 - iy0;
    const cardH = Math.round(c.y1 - c.y0);
    const verts = [];
    for (let x = Math.round(c.x0); x < sx1; x++) {
      let nb = 0;
      for (let y = iy0; y <= iy1; y++) if (inked(x, y)) nb++;
      if (nb / ih < VLINE_FRAC) continue;
      let nc = 0;
      for (let y = cardTop; y < Math.round(c.y1); y++) if (inked(x, y)) nc++;
      if (nc / cardH > CARD_SPAN_MAX) continue;
      verts.push(x);
    }
    if (verts.length < 2) continue;

    const left = Math.min(...verts), right = Math.max(...verts);
    if (right - left < 20) continue;

    found.push({
      card: i,
      top, bottom, left, right,
      // left, right, top and bottom are all absolute page coordinates -- the loops
      // that produce them walk x from the card's own origin upward and y from the
      // card's own origin downward. The rect is therefore built from them directly,
      // with no card origin added. Adding one is the bug this had: it shifted every
      // crop right by the card's x and down by the card's y, which put the digits
      // outside the crop entirely and made every recognition come back empty.
      rect: {
        x: left + INSET,
        y: top + INSET,
        w: right - left - 2 * INSET,
        h: bottom - top - 2 * INSET,
      },
    });
  }
  return found;
}

/**
 * Renders the serial box interior for one card, ready for recognition.
 *
 * Three things happen after the crop, and each fixes a failure that was measured
 * rather than anticipated.
 *
 * Thresholded. The scanner leaves an antialiased fringe on the box's rules that sits
 * just above the ink threshold, so a grey crop of this nearly blank strip carries
 * faint vertical smudges at both edges which look like rules and are not. Binarising
 * removes them, and it is what the recogniser wants anyway.
 *
 * Trimmed. The box interior is about 190 px wide and holds about 6 px of ink, sitting
 * hard against the right edge because the serial is right-aligned. Handed over that
 * way the recogniser returns nothing at all for most cards -- 18 of 30 came back empty
 * on the first run. The serial is a single short word; the crop should be a single
 * short word too.
 *
 * Magnified, then margined. Native digits are about 18 px tall, well under the
 * ~30 px x-height the engine is tuned for, and trimming removes the quiet zone the
 * engine also wants. Magnify before adding the margin so the margin scales with the
 * glyphs.
 *
 * The magnification is deliberately non-uniform, and that is measured rather than
 * cosmetic. A serial is right-aligned in its box, so the token's width tracks its
 * digit count: "10" fills the strip and reads cleanly, while "11" -- two thin strokes
 * five pixels apart -- trims to a nearly square 17x18 block. The engine reads that as
 * one narrow glyph and answers "1", on every magnification that is symmetric. Widening
 * the image alone opens the gap between the strokes without thickening them, and
 * fixes it: on page 3, symmetric 300% fails card 10, 400%x300% and 500%x300% read it
 * correctly, and 600%x300% starts failing card 9 instead. 400%x300% is the setting
 * that leaves the rest of the page alone.
 */
function renderSerial(png, box, out, opts = {}) {
  const { geom = '400%x300%', filter = null } = opts;
  const { x, y, w, h } = box.rect;
  const args = [
    png, '-crop', `${w}x${h}+${x}+${y}`, '+repage',
    '-colorspace', 'Gray',
    '-threshold', '60%',
    '-trim', '+repage',
  ];
  // A filter must be named before the resize that uses it.
  if (opts.filter) args.push('-filter', opts.filter);
  args.push('-resize', geom, '-bordercolor', 'white', '-border', '10', out);
  execFileSync('convert', args);
  return out;
}

module.exports = { serialBoxes, renderSerial, INSET };

if (require.main === module) {
  const pdf = process.argv[2];
  const page = Number(process.argv[3] || 3);
  const dpi = Number(process.argv[4] || 240);

  const png = pagePng(pdf, page, dpi);
  const grid = detect(png);
  const boxes = serialBoxes(png, grid);

  console.log(`  page ${page}: ${grid.cards.length} cards, box located on ${boxes.length}\n`);
  console.log('   card   top  bottom   left  right   crop');
  for (const b of boxes.slice(0, 30)) {
    console.log(`   ${String(b.card).padStart(4)}  ${String(b.top).padStart(5)}  ${String(b.bottom).padStart(6)}`
      + `  ${String(b.left).padStart(5)}  ${String(b.right).padStart(5)}`
      + `   ${b.rect.w}x${b.rect.h}+${b.rect.x}+${b.rect.y}`);
  }

  // Offsets relative to the card, so a drift between pages is visible immediately
  // rather than showing up much later as one misread serial.
  console.log(`\n  relative to the card's own top-left corner, across ${boxes.length} cards:`);
  for (const k of ['top', 'bottom', 'left', 'right']) {
    const v = boxes.map((b) => b[k] - Math.round(grid.cards[b.card].x0) - (k === 'top' || k === 'bottom' ? Math.round(grid.cards[b.card].y0) : 0));
    console.log(`    ${k.padEnd(7)} ${Math.min(...v)}..${Math.max(...v)}`);
  }

  const files = boxes.slice(0, 12).map((b) =>
    renderSerial(png, b, `/tmp/opencode/voter/ocr/inner_${b.card}.png`));
  if (files.length) {
    execFileSync('montage', [...files, '-tile', '6x2', '-geometry', '+4+4',
      '-background', 'white', '/tmp/opencode/voter/ocr/inner.png']);
    console.log(`\n  wrote /tmp/opencode/voter/ocr/inner.png (${files.length} box interiors, thresholded)`);
  }
}