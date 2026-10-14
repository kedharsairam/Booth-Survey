// What a person reading the magnified crops said, for 20 of the 132 cards where the label
// read and the vote disagree.
//
// How this was produced, and what it is worth
// --------------------------------------------
// These are readings made from sheet.js's output -- one magnified crop per tile, the
// candidate values printed above it -- by an agent looking at the images. That is a second
// opinion and not ground truth, and it is recorded as such because it changes what can be
// concluded from it. It is not "the agent looked at the page and guessed": each tile is a
// single short value at four times size with no surrounding text, which is the easiest thing
// in this pipeline for any reader, human or otherwise. But it is still an agent, and a
// finding that rests only on an agent's reading should be spot-checked by a person before
// it is relied on. spotcheck.md lists a sample for exactly that.
//
// Why these cards and not a random sample
// ---------------------------------------
// The label read and the vote agree on 997 of 1129 cards. On those 997 it does not matter
// which one is used, so scoring them cannot change the design. All 132 disagreements were
// sampled instead, spread across the roll by taking every len/132'th, and 20 were scored.
// That is the whole of the decision: which reader to trust where they differ.
//
// The result
// ----------
//     label read correct   16 of 20
//     vote correct          3 of 20   serials 59, 86, 159
//     neither               1 of 20   serial 458, see below
//
// The vote's three wins are informative. Serial 59 is "90-13-13/4" read as "95-4-4/36" by the
// label -- a spurious trailing digit. Serial 159 is "90-31-31/4" read as "11-2-26/8B", an 8
// invented for the B. Serial 86 is "11-159" read as "11-189" by the label, which is the 8/5
// confusion again, this time on the final group. So the label read is better overall and not
// uniformly better: it wins on the leading digit, which is where the vote loses everything,
// and loses on an occasional extra or substituted digit, which the vote is good at.
//
// Serial 458 is neither reader's answer and is a third, separate defect. Its crop spans two
// printed lines -- "SATYANARAYANA Kapa" above ": 90-46-6/1" -- so textLines() returned one
// band covering both, the locator matched the house label on the lower line, and the value's
// word box was then matched against a value that had the name glued to its front. The vote
// returned "SATYANARAYANAK194-3-3/3" and the label returned "4-18/10". Two lines in one band
// is the cause, and it is counted separately below because no choice of primary reader
// repairs it.
const SCORED = [
  // serial, value as read from the crop, which reader was right
  { serial: 8, value: '90-5-5/5', right: 'label' },
  { serial: 21, value: '90-23-23/5', right: 'label' },
  { serial: 26, value: '90-23-23/5', right: 'label' },
  { serial: 31, value: '90-24-24/6', right: 'label' },
  { serial: 36, value: '90-25-25/7', right: 'label' },
  { serial: 59, value: '90-13-13/4', right: 'vote' },
  { serial: 86, value: '11-159', right: 'vote' },
  { serial: 101, value: '90-29-29/2', right: 'label' },
  { serial: 109, value: '90-30-30/3', right: 'label' },
  { serial: 159, value: '90-31-31/4', right: 'vote' },
  { serial: 271, value: '90-33-33/6', right: 'label' },
  { serial: 342, value: '90-37-37/1', right: 'label' },
  { serial: 388, value: '90-39-39/3', right: 'label' },
  { serial: 428, value: '90-40-40/4', right: 'label' },
  { serial: 458, value: '90-46-6/1', right: 'neither' },
  { serial: 497, value: '90-44-4/8', right: 'label' },
  { serial: 602, value: '90-45-5/9', right: 'label' },
  { serial: 623, value: '90-47-7/2', right: 'label' },
  { serial: 631, value: '90-48-8/3', right: 'label' },
  { serial: 638, value: '90-49-9/4', right: 'label' },
];

// Cards whose crop spans more than one printed line, where the locator's band is wrong for a
// reason no choice of reader repairs. Counted by running score.js; kept here so that a later
// reader knows these were seen and set aside rather than missed.
const TWO_LINE_BANDS = [458];

module.exports = { SCORED, TWO_LINE_BANDS };

if (require.main === module) {
  const tally = {};
  for (const s of SCORED) tally[s.right] = (tally[s.right] || 0) + 1;
  console.log(`  ${SCORED.length} cards scored from magnified crops`);
  for (const [k, n] of Object.entries(tally)) console.log(`    ${k.padEnd(8)} ${n}`);
  console.log(`  two-line bands set aside: ${TWO_LINE_BANDS.join(', ')}`);
}