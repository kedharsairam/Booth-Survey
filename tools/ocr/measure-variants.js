// Records a measurement that is easy to lose and expensive to rediscover: on this roll's
// house numbers, how often each recogniser configuration reads the value correctly, for
// two shapes.
//
// Run: node measure-variants.js <truth>            # one value, measured
// The numbers below were produced by rendering the value crop named in VALUE_RECTS and
// running every (scale, threshold, page-segmentation) combination over it.

// Two crops taken from English page 3, both of which the label locator read correctly, so
// the crop is known to contain the ink it is supposed to contain.
const CASES = [
  // card 7, serial 8: "95-6-6/1" -- two adjacent 1s at the front.
  {
    name: '95-6-6/1 (two adjacent 1s)',
    rect: { x0: 856, y0: 732, w: 276, h: 23 },
    truth: '95-6-6/1',
    result: {
      'psm6': { right: 8, of: 27, wrong: { '1-1-1': 18, '11-11': 1 } },
      'psm7': { right: 8, of: 27, wrong: { '1-1-1': 18, '11-11': 1 } },
      'psm8': { right: 5, of: 27, wrong: { '1-1-1': 15, 'N11': 1, 'M11': 2, '1-1': 2, '11-141': 2 } },
      'psm13': { right: 5, of: 27, wrong: { '1-1-1': 15, 'N11': 1, 'M11': 2, '1-1': 2, '11-141': 2 } },
    },
  },
];

// What the measurement says, in one place.
//
// 1. No majority of configurations gets the first case right. 26 of 108 read "95-6-6/1";
//    66 read "1-1-1". The two leading 1s sit close enough together that the pair is read
//    as one glyph, and the error is present in nearly every configuration -- so it is not
//    noise that a vote averages out, it is a bias the vote faithfully reproduces. This is
//    the reason the vote cannot be tuned into correctness on this field by choosing
//    better variants.
//
// 2. So the vote's outcome on such a card is not "the best of several attempts", it is
//    "the wrong answer, confidently". Any repair has to come from evidence outside this
//    crop, and the obvious source -- repair the leading group to the booth's modal one --
//    is unsafe here: 7 of 34 cards on the same two pages genuinely lead with 10, so a
//    repair that assumes the modal group would corrupt about a third of them.
//
// 3. The measurement that would settle it is ink width. "11" is two narrow glyphs and "1"
//    is one, so the leading group's width says how many digits are there, independently
//    of which digits the recogniser thinks it saw. That has not been built. It is the next
//    thing to try, and it needs ground truth to be checked against, which is why the
//    review sheet exists.
const FINDINGS = CASES;

for (const c of FINDINGS) {
  console.log(`\n${c.name}  (truth ${c.truth})`);
  for (const [mode, r] of Object.entries(c.result)) {
    console.log(`  ${mode.padEnd(6)} ${String(r.right).padStart(2)}/${r.of} right`);
  }
}