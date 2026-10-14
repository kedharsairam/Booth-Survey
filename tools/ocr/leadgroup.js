// The leading group of a house number, learned from the booth rather than assumed.
//
// Why this exists
// ---------------
// Measured against the page-5 ground truth (30 house numbers read by eye from
// magnified crops), the pipeline read 25 correctly. Of the five it got wrong, four
// were the same mistake:
//
//     read '1-1-6'    truth '90-24-24/6'      leading group '1'
//     read '1-1-7'    truth '90-25-25/7'      leading group '1'
//     read '1-1-7'    truth '90-25-25/7'      leading group '1'
//     read '17-1-15'  truth '90-13-13/4'     leading group '17'
//
// Not noise. One cause: the first glyph of the leading group being lost, or read as
// something else. house.js already says as much in its comments -- "a leading group of a
// single digit is far more likely a dropped digit than a real address" -- and then
// deliberately stops there: "That inference is reported as a warning, never applied as a
// repair, because '90-24-24/6' and '1-1-6' are different houses."
//
// The caution is right about silence and wrong about inaction. A wrong house number is
// not a wrong address that gets tidied later; it is a wrong *household*, because the
// booklet groups by door number and nothing downstream can catch it -- unlike a voter
// id, this field has no check digit. Leaving a value that is known to be malformed, and
// known to be one edit from the only plausible answer, is strictly worse than repairing
// it and saying so.
//
// So the repair is applied, and it is never silent. It introduces a third status:
//
//     valid     read, and well formed
//     inferred  repaired from a leading group this booth does not use
//     flagged   read, but malformed in a way with no single obvious repair
//
// Anything not 'valid' already reaches the review CSV, so an inferred value is put in
// front of a person with the reasoning attached rather than printed into a booklet.
//
// Why it is learned per booth
// ---------------------------
// The obvious repair is to hardcode "11", since 73 of 82 sampled values lead with 11 and
// 7 more with 10. That is a fact about one ward, not about the format. A neighbour whose
// houses lead with 12 would have every value rewritten, and -- worse -- validateHouse
// already marks every one of them not-ok, because its KNOWN_LEADING set is hardcoded to
// {10, 11}. So the target group here is the modal leading group among this booth's own
// *cleanly read* values, and nothing in this file trusts a hardcoded ward.
//
// What it will not do
// -------------------
//   - repair a value whose leading group is plausible but whose shape is otherwise
//     wrong, because the leading group is not then the problem
//   - repair when the booth's own clean values give no clear modal group
//   - repair when two groups tie, because there is then no evidence for either
//   - repair anything that is not a bare number, such as a value carrying a landmark
//     on the same printed line
//   - touch the middle group, the final group, the suffix, or anything else
//
// In short: it changes the one part of the value that this booth's own data says is
// nearly constant, and nothing else.

const { KNOWN_LEADING } = require('./house.js');

/**
 * Is this a complete house number, ignoring which groups this ward uses?
 *
 * Deliberately not validateHouse, for the hardcoded-set reason above. The shape comes
 * from the grammar house.js documents:
 *
 *   NN-N-N  NN-N-NN  NN-NNN  NN-NN-NN  N-N-N  NN-NNN-N  NN-N-NN/N  NN-N-NN/C  NN-N-NN-A
 *
 * restricted to the three-group forms, because that is all this file rewrites. The
 * two-group form (NN-N) occurs a handful of times and is left to validateHouse.
 */
// The suffix is restricted to the forms house.js actually counted: "/3" and "/C" after
// the final group, or a bare trailing letter ("93-4-4/1A"). It is deliberately *not*
// [/-]\d, because that also swallows a fourth numeric group -- which let "9-9-9-9" pass as
// a complete value and get its first two digits rewritten, hiding the fact that it has
// one group too many.
const COMPLETE_THREE = /^\d{2}-\d{1,3}-\d{1,3}(?:\/\d{1,2}|\/[A-Z]|-[A-Z]|[A-Z])?$/;

/** Splits a value into its leading digits and the rest, or null if it does not start with digits. */
function split(value) {
  const m = /^(\d{1,3})(.*)$/.exec((value || '').trim());
  return m ? { group: m[1], tail: m[2] } : null;
}

/** The leading group of a value, or null. */
function leadingOf(value) {
  const s = split(value);
  return s ? s.group : null;
}

/**
 * Leading groups this booth actually uses, counted over the values that read cleanly.
 *
 * Only rows whose house_status is 'valid' get a vote. A misread that happens to start
 * with a plausible group must not be allowed to decide what a plausible group is, or
 * the repair learns its own mistakes back.
 *
 * @returns Map<string, number>
 */
function learnLeadingGroups(rows) {
  const counts = new Map();
  for (const r of rows) {
    if (r.house_status !== 'valid') continue;
    const g = leadingOf(r.house_number || r.house);
    if (!g) continue;
    counts.set(g, (counts.get(g) || 0) + 1);
  }
  return counts;
}

/**
 * The range of middle groups this booth uses, over cleanly read values.
 *
 * A second, independent check on a proposed repair. A value like "99-99-99" is not wrong
 * only in its leading group -- its middle group is implausible too -- and rewriting just
 * the first two digits would produce a tidier-looking value while hiding the fact that the
 * rest was never read properly either.
 *
 * A range, deliberately, not a set. Membership was tried first and was wrong: page 30 has
 * exactly one house with middle group 1, and it is the very card whose middle group was
 * misread, so 1 never got counted -- and the set test then refused to repair a card whose
 * middle group was perfectly ordinary. Rare is not the same as implausible.
 *
 * @returns {{min:number, max:number, n:number}|null}
 */
function learnMiddleRange(rows) {
  const seen = [];
  for (const r of rows) {
    if (r.house_status !== 'valid') continue;
    const m = /^\d{2,3}-(\d{1,3})-/.exec((r.house_number || r.house || '').trim());
    if (m) seen.push(Number(m[1]));
  }
  if (!seen.length) return null;
  return { min: Math.min(...seen), max: Math.max(...seen), n: seen.length };
}

/**
 * The group to repair towards, or null when there is no evidence for one.
 *
 * Requires the winner to hold at least two thirds of the clean values and at least three
 * of them. Both thresholds exist to refuse rather than guess: a booth with one clean
 * value, or with 10 and 11 tied, gets no repair and keeps its warnings, which is the
 * correct outcome, because then a person looks at it.
 */
function modalGroup(counts) {
  if (!counts || counts.size === 0) return null;
  const sorted = [...counts.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
  const [top, topN] = sorted[0];
  if (sorted.length > 1 && sorted[1][1] === topN) return null;    // a tie is not evidence
  if (topN < 3) return null;
  const total = sorted.reduce((s, [, n]) => s + n, 0);
  if (topN / total < 2 / 3) return null;
  return top;
}

/**
 * Proposes a repaired house number, or null when no repair is justified.
 *
 * The test is "does putting the booth's own leading group back make this a complete,
 * well-formed house number" -- and *complete* is doing real work in that sentence.
 * `11-9-9-9` contains a valid core of `11-9-9` with junk left over, so a prefix match
 * alone would rewrite the first two digits of a value that was structurally wrong to
 * begin with, and hide the real fault.
 *
 * @param known the leading groups this booth demonstrably uses. A value whose group is
 *   already in that set is not a misread leading group -- 10 is a real group here as well
 *   as 11 -- and rewriting it to the modal one would be inventing a house.
 * @param middles the range of middle groups this booth uses, from learnMiddleRange. A
 *   candidate whose middle group falls outside it is refused, so a value that is wrong in
 *   more than one place is not half-corrected.
 * @returns {{value:string, from:string, to:string, why:string}|null}
 */
function proposeRepair(rawValue, modal, known, middles) {
  const value = (rawValue || '').trim();
  if (!value || !modal) return null;

  const parts = split(value);
  if (!parts) return null;
  const { group: from, tail } = parts;

  // Three digits is neither a lost digit nor a single misread one, so it is not this.
  if (!/^\d{1,2}$/.test(from)) return null;
  // The group is one this booth uses; whatever is wrong is elsewhere.
  if (from === modal) return null;
  // Never rewrite a group this ward demonstrably uses to a more common one: that turns
  // 10-1-4 into 90-22-22/4, which is a different house. Two sets count as "demonstrably uses",
  // and both are needed:
  //
  //   known       groups seen in *this booth's* clean reads -- but a single page of 30
  //               will not contain every group the ward uses, so on page 5 alone, which has
  //               no 10- value at all, 10 looks unknown and 10-1-4 would get rewritten.
  //   KNOWN_LEADING the groups counted across the whole 82-value sample, which is how 10 is
  //               known to be real despite appearing on neither page.
  //
  // Being wrong in this direction is the expensive one: a repair that fires here invents a
  // household rather than failing to fix one.
  if (known && known.has && known.has(from)) return null;
  if (KNOWN_LEADING.has(from)) return null;

  const candidate = modal + tail;
  if (!COMPLETE_THREE.test(candidate)) return null;

  // The middle group has to sit inside the range this booth actually uses, or the value
  // was wrong in more than one place and only the first error is being fixed.
  if (middles && middles.n >= 3) {
    const m = /^-(\d{1,3})-/.exec(tail);
    if (!m) return null;
    const g = Number(m[1]);
    if (g < middles.min || g > middles.max) return null;
  }

  return {
    value: candidate,
    from,
    to: modal,
    why: `leading group "${from}" is not one this booth uses; `
       + `read as "${candidate}", inferred and not read`,
  };
}

/**
 * The last resort when a booth has nothing clean to learn from: the sampled ward's own
 * groups. Reported to the caller so it can say so out loud rather than quietly guessing.
 */
function fallbackGroup() {
  const sorted = [...KNOWN_LEADING].sort();
  return sorted.length ? sorted[0] : null;
}

module.exports = {
  leadingOf, split, learnLeadingGroups, learnMiddleRange,
  modalGroup, proposeRepair, fallbackGroup, COMPLETE_THREE,
};