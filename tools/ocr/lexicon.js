// A vocabulary of the names actually printed in this roll, harvested from the roll itself.
//
// Why a lexicon, for a field with no check digit
// ----------------------------------------------
// A voter id carries a Luhn digit and a serial is checked against the roll's own
// numbering, so both can be proved right or wrong. A name cannot: the character set is
// open, so there is nothing to check a reading against, and the honest accuracy figure
// for names is unknown rather than merely imperfect.
//
// But the vocabulary is not open in practice. A ward of about 1,100 households draws on a
// few hundred surnames, and they repeat. That turns an open-vocabulary problem into a
// closed one -- the same reason speech recognition is far more accurate against a
// language model than against an open alphabet. A reading that is one or two characters
// away from a name the roll prints thirty times is very probably a misreading of that
// name, not a new one.
//
// What this module does and does not do
// -------------------------------------
// It reports. A token that is not in the vocabulary is flagged as unseen, and a token that
// is close to a frequent one is reported as a probable misreading *alongside* what was
// printed. Nothing is rewritten. A correction applied silently would be indistinguishable,
// later, from a correct read, and the whole reason the booklets have a check on the door
// number is that a plausible-looking wrong value is the dangerous kind.

/** Levenshtein distance, with an early exit once the limit is exceeded. */
function editDistance(a, b, limit = Infinity) {
  if (a === b) return 0;
  if (Math.abs(a.length - b.length) > limit) return limit + 1;

  let prev = new Array(b.length + 1);
  let cur = new Array(b.length + 1);
  for (let j = 0; j <= b.length; j++) prev[j] = j;

  for (let i = 1; i <= a.length; i++) {
    cur[0] = i;
    let rowMin = cur[0];
    for (let j = 1; j <= b.length; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost);
      if (cur[j] < rowMin) rowMin = cur[j];
    }
    if (rowMin > limit) return limit + 1;
    const t = prev; prev = cur; cur = t;
  }
  return prev[b.length];
}

// Characters that this font at this size reliably confuses, collapsed so that a token
// differing only by these compares as a near miss rather than a different name. Kept
// separate from the distance itself, because collapsing them outright would let a real
// difference through: "LALITHA" and "LALITMA" are not the same name.
const CONFUSABLE = new Map(Object.entries({
  0: 'O', O: 'O', Q: 'O', D: 'O',
  1: 'I', I: 'I', L: 'I', 7: 'I',
  5: 'S', S: 'S', 8: 'B',
  2: 'Z', Z: 'Z',
  6: 'G', G: 'G',
  U: 'V', V: 'V',
}));

function fold(s) {
  let out = '';
  for (const ch of s.toUpperCase()) out += CONFUSABLE.get(ch) || ch;
  return out;
}

/**
 * Builds the vocabulary from harvested names.
 *
 * @param {Array<{name:string, father:string}>} rows
 * @param {number} minCount tokens seen fewer times than this are not trusted outright
 * @param {number} ratio     a proposed correction must be at least this many times more
 *   common than the token it replaces, so that a rare name is not swapped for a
 *   different rare name
 * @returns {{counts:Map, common:string[], size:number, tokens:number, minCount:number,
 *   ratio:number}}
 */
function buildLexicon(rows, minCount = 3, ratio = 3) {
  const counts = new Map();
  let tokens = 0;

  for (const r of rows) {
    // The surname is the last token of a name in this roll, and it is the part that
    // repeats: "HEMA SRI SAI REENA KONDAPALLI" is four given names and one surname. Both
    // the voter's own name and the father's are harvested, because a household's members
    // share it and the father's line adds the members who are not themselves voters.
    for (const field of [r.name, r.father]) {
      if (!field) continue;
      for (const t of field.toUpperCase().split(/[^A-Z]+/).filter(Boolean)) {
        if (t.length < 3) continue;              // initials and stray marks
        counts.set(t, (counts.get(t) || 0) + 1);
        tokens++;
      }
    }
  }

  const common = [...counts.entries()]
    .filter(([, n]) => n >= minCount)
    .sort((a, b) => b[1] - a[1])
    .map(([t]) => t);

  return { counts, common, size: counts.size, tokens, minCount, ratio };
}

/**
 * Checks one name against the vocabulary.
 *
 * @returns {{status:string, tokens:Array}} status is the weakest token's status, where
 *   exact     -- every token is one the roll uses often, so the reading is corroborated
 *   corrected -- a rare token is one or two edits from a name the roll uses often, so it
 *                is probably a misreading of that name; the suggestion is given alongside
 *                what was printed and is never applied silently
 *   rare      -- a token the roll uses, but not often enough to be trusted on its own
 *   unseen    -- a token the roll does not use at all, so either a bad read or a name
 *                this harvest has not met elsewhere
 */
function checkName(name, lex, { maxEdit = 2 } = {}) {
  const raw = (name || '').toUpperCase().split(/[^A-Z]+/).filter((t) => t.length >= 3);
  if (!raw.length) return { status: 'empty', tokens: [] };

  const tokens = raw.map((t) => {
    const seen = lex.counts.get(t) || 0;

    // Only frequency confers trust. A token seen once is in the counts -- it matches
    // itself -- but so is a misreading, because the harvest recorded the misreading.
    // "Is this token in the roll" therefore cannot tell a rare real name from a bad read;
    // "is this token in the roll often" can. Treating mere presence as exact marked all
    // 146 sample names exact, including "SATYA RAC", which is a misreading of "RAO".
    if (seen >= lex.minCount) return { printed: t, status: 'exact', count: seen };

    // Rare, so try to explain it as a misreading of a name the roll uses often.
    //
    // Two guards keep this from doing damage, and both were added because the first
    // version of it suggested replacing correct names. It offered HEMA -> RAMA,
    // REENA -> VEERA and NARAAYANA -> NARAYANA, every one of which is a real name that
    // happens to sit two edits from another real name.
    //
    // The budget scales with length, because two edits is proportionally a much larger
    // change in a five-letter token than in a twelve-letter one. And the suggestion has to
    // be substantially more common than what was printed -- a correction that swaps one
    // rare name for another is not a correction, it is a different guess.
    const budget = Math.min(maxEdit, t.length >= 8 ? 2 : 1);

    // Folded comparison only for longer tokens. Folding collapses D->O, L->I, U->V, and
    // on a short token that manufactures similarity out of nothing.
    const folded = t.length >= 7 ? fold(t) : t;
    let best = null;
    let bestD = budget + 1;
    for (const c of lex.common) {
      const d = editDistance(folded, c.length >= 7 ? fold(c) : c, budget);
      if (d < bestD) { bestD = d; best = c; }
      if (bestD === 1) break;                    // cannot do better
    }

    if (best && bestD <= budget) {
      const suggestedCount = lex.counts.get(best) || 0;
      if (suggestedCount >= lex.ratio * Math.max(seen, 1)) {
        return {
          printed: t, status: 'corrected', suggestion: best,
          count: suggestedCount, edit: bestD, seen,
        };
      }
    }
    return { printed: t, status: seen ? 'rare' : 'unseen', seen };
  });

  // The verdict follows the surname, not the weakest token.
  //
  // Given names in this roll are legitimately rare -- a ward of 1,100 households uses
  // hundreds of given names, most borne by one or two people -- so requiring every token
  // to be common marked 334 of 479 names "rare" and not one "exact", which says nothing
  // about whether any of them were read correctly. The surname is the part that repeats
  // and the part that identifies the household, so that is what the verdict is about. The
  // given names are still checked and still reported, in `tokens`.
  const rank = { unseen: 0, rare: 1, corrected: 2, exact: 3 };
  const surname = tokens[tokens.length - 1];
  let given = 'exact';
  for (let i = 0; i < tokens.length - 1; i++) {
    if (rank[tokens[i].status] < rank[given]) given = tokens[i].status;
  }

  return {
    status: surname.status,
    surname: surname.status,
    given,
    tokens,
  };
}

/** Renders a check as a suggested corrected name, or null when nothing is suggested. */
function suggest(name, result) {
  if (result.status !== 'corrected') return null;
  return result.tokens.map((t) => (t.status === 'corrected' ? t.suggestion : t.printed)).join(' ');
}

module.exports = { buildLexicon, checkName, suggest, editDistance, fold };