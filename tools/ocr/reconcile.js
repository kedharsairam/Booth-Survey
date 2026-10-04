// Reconciles house numbers across cards.
//
// The house number is not just a field, it is the join key: it is how a family
// is found and how the booklet decides who belongs together. Six people at one
// door must carry one string, and a single misread splits a family in two --
// which on paper reads as two households, the exact failure the booklet exists
// to avoid.
//
// That gives a strong constraint the recogniser cannot see on its own: within a
// page, the same door is read by several cards, and the correct spelling is the
// one most of them agree on. Observed on page 5, where a one-character OCR slip
// produced "1-1-7" against five other cards reading "92-7-7/2", and "17-1-15"
// against three reading "95-4-4/3".
//
// So a read that is one edit away from a better-supported read is snapped to it,
// and the card is marked reconciled rather than silently altered. A read with no
// better-supported neighbour is left alone: the roll genuinely does contain odd
// house numbers, and inventing a majority for every value would be worse than
// flagging it.

const MAX_EDITS = 1;

// Levenshtein distance, abandoned early once it exceeds `cap`.
function editDistance(a, b, cap = MAX_EDITS) {
  if (a === b) return 0;
  if (Math.abs(a.length - b.length) > cap) return cap + 1;
  let prev = Array.from({ length: b.length + 1 }, (_, j) => j);
  for (let i = 1; i <= a.length; i++) {
    const cur = [i];
    let rowMin = i;
    for (let j = 1; j <= b.length; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost);
      if (cur[j] < rowMin) rowMin = cur[j];
    }
    if (rowMin > cap) return cap + 1;
    prev = cur;
  }
  return prev[b.length];
}

// Applies the reconciliation in place. Returns a summary.
function reconcile(records) {
  const counts = new Map();
  for (const r of records) {
    if (!r.house) continue;
    counts.set(r.house, (counts.get(r.house) || 0) + 1);
  }

  let snapped = 0, ambiguous = 0;
  for (const r of records) {
    r.house_final = r.house;
    r.house_reconciled = false;
    if (!r.house) continue;
    const mine = counts.get(r.house);

    // A read that is one edit from a strictly better-supported read is a slip.
    let best = null, bestN = mine;
    for (const [value, n] of counts) {
      if (value === r.house || n <= bestN) continue;
      if (editDistance(r.house, value) <= MAX_EDITS) { best = value; bestN = n; }
    }
    if (best) {
      r.house_final = best;
      r.house_reconciled = true;
      r.house_was = r.house;
      snapped++;
      continue;
    }

    // Nothing better nearby, but this reading was not unanimous either. Worth a
    // human's eye before it becomes a family boundary.
    const support = r.house_alternatives ? r.house_alternatives[0][1] : 1;
    if (r.house_reads && support < r.house_reads) {
      r.house_unsure = true;
      ambiguous++;
    }
  }
  return { distinct: counts.size, snapped, ambiguous };
}

// Which reads still need a human. Anything the recogniser was unsure about, or
// that did not survive as a well-formed voter id.
function needsReview(r) {
  return !r.epic_ok || !r.house_located || !/^[0-9A-Z]/.test(r.house || '') || r.house_unsure === true;
}

module.exports = { reconcile, needsReview, editDistance, MAX_EDITS };