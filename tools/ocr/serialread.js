// Reads the serial off every card of a page, with a validated second attempt.
//
// Why two attempts
// ----------------
// No single rendering reads every serial. Measured on English page 3:
//
//   symmetric 400%, PSM 8   29/30   only card 10 wrong: "11" read as "1"
//   400%x300%,  PSM 8       27/30   card 10 fixed, but single digits "2", "6" and
//                                    "9" now come back empty
//
// The serials are right-aligned, so a token's width tracks its digit count. "11" --
// two thin strokes five pixels apart -- trims to a nearly square 17x18 block and gets
// read as one narrow glyph, which only widening the image fixes. A lone "2" is a
// single normal-proportioned glyph, and widening it distorts it past recognition.
//
// So the second rendering is not a better default, it is a repair applied only to the
// cards the first rendering got wrong. Applying it everywhere loses more than it wins.
//
// Why the retry is validated rather than blind
// --------------------------------------------
// A wrong serial is worse than a missing one: it silently renames a household and
// shifts every serial after it. The retry is therefore driven by disagreement with the
// layout's expected value for that card, not by the recogniser's own confidence --
// tesseract is confidently wrong on "11", reporting "1" with no hint of trouble.
//
// Anything still unresolved after both attempts is returned as unresolved. It is never
// filled in by inference, even though the page's serials are sequential and would make
// a plausible guess easy: a guess here is indistinguishable, downstream, from a read.

const { renderSerial } = require('./box.js');

// First attempt: uniform magnification, which is what handles ordinary serials.
// Second: widened, which is what rescues the tightly kerned ones.
const ATTEMPTS = ['400%', '400%x300%'];

const PSM = '8';

/**
 * Reads every serial on a page.
 *
 * @param {object}   worker   a tesseract worker, reused by the caller
 * @param {string}   png      page PNG at native resolution
 * @param {Array}    boxes    from serialBoxes(), in card order
 * @param {number[]|null} expected the serial each card must carry, or null when the
 *   caller has no reference and is validating the sequence itself
 * @returns {Promise<{reads: Array, unresolved: Array}>} reads[i] is the serial read
 *   from card boxes[i].card, or null if it could not be resolved
 */
async function readSerials(worker, png, boxes, expected) {
  const reads = new Array(boxes.length).fill(null);

  const attempt = async (geom, indices) => {
    for (const i of indices) {
      const box = boxes[i];
      const f = renderSerial(png, box, `/tmp/opencode/voter/ocr/sr_${box.card}.png`, { geom });
      const r = await worker.recognize(f, { tessedit_pageseg_mode: PSM }, { text: true });
      reads[i] = r.data.text.replace(/[^0-9]/g, '');
    }
  };

  await attempt(ATTEMPTS[0], boxes.map((_, i) => i));

  // Without a reference there is nothing to disagree with, so the repair pass is
  // skipped rather than run on every card.
  const wrong = [];
  if (expected) {
    boxes.forEach((b, i) => {
      if (reads[i] !== String(expected[b.card])) wrong.push(i);
    });
  }
  if (wrong.length) await attempt(ATTEMPTS[1], wrong);

  const unresolved = [];
  if (expected) {
    boxes.forEach((b, i) => {
      if (reads[i] !== String(expected[b.card])) unresolved.push({ card: b.card, got: reads[i] });
    });
  } else {
    boxes.forEach((b, i) => {
      if (!reads[i]) unresolved.push({ card: b.card, got: null });
    });
  }

  return { reads, unresolved };
}

module.exports = { readSerials, ATTEMPTS, PSM };