// Reads voter ids by template matching, with the check digit as a hard constraint.
//
// The problem this solves
// ----------------------
// Tesseract inserts characters into these ids. On a three-page sample it read 62 of 86
// as well-formed and check-digit-valid; the failures were not near misses but invented
// characters -- ZZZ9951849 came back as WQQO0062950, ZZZ7263494 as WZZZ7263494,
// ZZZ5974548 as HXD246412156. The crops were clean; the segmentation was not. This is
// the same over-segmentation that capped the serial field at 29 of 30, and template
// matching fixed it there, so it is applied here rather than re-tuned around.
//
// Why the check digit changes everything
// -------------------------------------
// A voter id is three letters then seven digits, and the last digit is a Luhn check digit.
// That is a constraint the pixels do not have to supply: seven digits that fail Luhn are
// almost certainly misread, and among the plausible readings of the same ten glyphs
// there is usually exactly one that passes.
//
// So this is not OCR with a validation step bolted on afterwards. It is decoding under a
// constraint. Each glyph is ranked, the top candidates per position are kept, and the
// combinations are enumerated until a Luhn-valid id appears. A random misread survives
// the check digit about 9.8% of the time, so one surviving candidate among a handful is
// strong evidence rather than a guess -- and where more than one survives, or none does,
// that is reported rather than resolved by preference.
//
// The letter positions are additionally constrained to letters actually seen in this
// roll. A ward's roll is issued per part and the prefix set is tiny, so the third
// position does not have to be chosen from 26 possibilities for the constraint to bind.

const { bitmapFromRect, segment, normalise } = require('./digits.js');
const { luhnValid } = require('./epic.js');

// An id is three letters then seven digits.
const LETTERS = 3;
const DIGITS = 7;

// How many alternatives to keep per position. Tried in order: the nearest exemplar alone
// first, then two per position (128 combinations), then three (2187).
//
// Narrow-first matters for more than speed. With three alternatives everywhere there are
// 2187 candidate ids and roughly one in ten satisfies the check digit, so around 219 pass
// and "exactly one passed" -- the signal that a reading is certain -- can never happen.
// Measured: every one of 116 ids came back ambiguous. Widening only after the narrow
// reading has failed its check digit keeps that signal meaningful.
const BEAMS = [1, 2, 3];

// Column-ink thresholds tried when splitting the id into glyphs, in order.
//
// The voter id needs a lower threshold than the serial, and measuring says so: at 2 the
// serial splits into exactly the right number of glyphs for all 266 serials tested, but
// the voter id splits into ten for only 101 of 116, because the letters have thin strokes
// that fall below it. At 1 the id splits correctly for 115 of 116. All are tried in turn
// so the reader adapts to whichever separates the glyphs rather than being tuned to one.
const MIN_COL_INKS = [1, 2, 3];

/**
 * How far a glyph may sit from its nearest exemplar and still be believed.
 *
 * Calibrated by leave-one-out over the atlas rather than guessed. The squared-distance
 * scale here runs into the tens, not fractions: an early bound of 0.9 rejected 30 of 116
 * perfectly good ids. A bound that rejects real digits is worse than no bound at all,
 * because the constraint would then go on to invent a reading from nothing.
 */
let MAX_DISTANCE = Infinity;

/** Sets the calibrated bound; the caller calibrates before reading. */
function setMaxDistance(d) { MAX_DISTANCE = d; }

/** Splits an id bitmap into its ten glyphs, trying each threshold in turn. */
function segmentId(bmp) {
  for (const minInk of MIN_COL_INKS) {
    const g = segment(bmp, minInk);
    if (g.length === LETTERS + DIGITS) return g;
  }
  return null;
}

/** Ranked candidates for one glyph: [{char, distance}], nearest first. */
function rank(vec, atlas, alphabet, beam) {
  const scored = [];
  for (const ex of atlas) {
    if (alphabet && !alphabet.includes(ex.char)) continue;
    let d = 0;
    for (let i = 0; i < vec.length; i++) {
      const t = vec[i] - ex.vec[i];
      d += t * t;
    }
    scored.push({ char: ex.char, distance: d });
  }
  scored.sort((a, b) => a.distance - b.distance);

  // Collapse to distinct characters, keeping each one's best distance.
  const seen = new Map();
  for (const s of scored) if (!seen.has(s.char)) seen.set(s.char, s);
  return [...seen.values()].slice(0, beam);
}

/**
 * Enumerates candidate ids under the Luhn constraint.
 *
 * @param {string} prefix        the letters
 * @param {Array<Array>} ranked  ranked digit candidates per position
 * @returns {{id:string, cost:number, count:number}|null} the cheapest Luhn-valid id and
 *   how many candidates passed the check digit, or null when none did
 */
function solveLuhn(prefix, ranked) {
  let best = null;
  let count = 0;

  const walk = (i, digits, cost) => {
    if (i === ranked.length) {
      const d = digits.join('');
      if (!luhnValid(d)) return;
      count++;
      if (!best || cost < best.cost) best = { id: prefix + d, cost };
      return;
    }
    for (const c of ranked[i]) walk(i + 1, digits.concat(c.char), cost + c.distance);
  };
  walk(0, [], 0);
  return best ? { id: best.id, cost: best.cost, count } : null;
}

/**
 * Reads one voter id from the page.
 *
 * @param {number[]} px      page grayscale pixels
 * @param {number}   pageW   page width
 * @param {{x,y,w,h}} rect   the id's region, absolute
 * @param {object}   atlases {letters, digits}, each an array of {char, vec}
 * @param {string[]} prefixes the letter prefixes seen in this roll, or null to allow all
 * @returns {{id:string, status:string, glyphs:number, repaired:boolean}|null}
 *   status is one of:
 *     certain   -- the nearest reading at every position satisfied the check digit
 *     repaired  -- that reading failed, and exactly one alternative did
 *     ambiguous -- several alternatives satisfied it, so the check digit cannot decide
 *     invalid   -- nothing satisfied it
 *     noglyph   -- a glyph sits too far from every exemplar to be believed
 *     glyphcount-- the crop does not split into ten glyphs at any threshold
 */
function readEpic(px, pageW, rect, atlases, prefixes = null, norm = 16) {
  // Collected during the beam search below and reset per beam, so a stale list from an
  // earlier beam cannot leak into a later one's alternatives.
  let allSolutions = [];
  const bmp = bitmapFromRect(px, pageW, rect);
  if (!bmp) return null;

  const glyphs = segmentId(bmp);
  if (!glyphs) return { id: '', status: 'glyphcount', glyphs: segment(bmp, 1).length };

  const vecs = glyphs.map((g) => normalise(bmp, g, norm));

  // The letter positions, constrained to the prefixes this roll actually uses.
  const letterChoices = prefixes && prefixes.length
    ? prefixes
    : [...new Set(atlases.letters.map((e) => e.char))];

  for (const beam of BEAMS) {
    // Score every permitted prefix. Each glyph is compared only against exemplars of the
    // character in that position of that prefix, so the cost measures the match for a
    // prefix rather than a match for some unrelated letter.
    const prefixScored = letterChoices.map((p) => {
      let cost = 0;
      for (let i = 0; i < LETTERS; i++) {
        const best = rank(vecs[i], atlases.letters, [p[i]], beam)[0];
        cost += best ? best.distance : Infinity;
      }
      return { prefix: p, cost };
    }).filter((p) => Number.isFinite(p.cost))
      .sort((a, b) => a.cost - b.cost).slice(0, 2);

    const digitRanked = [];
    for (let i = 0; i < DIGITS; i++) digitRanked.push(rank(vecs[LETTERS + i], atlases.digits, null, beam));

    // A digit too far from every exemplar is reported rather than forced: past the
    // calibrated bound the crop is not a glyph this font drew, and no amount of
    // constraint should be trusted to invent a reading from it.
    if (digitRanked.some((r) => !r.length || r[0].distance > MAX_DISTANCE)) {
      return { id: '', status: 'noglyph', glyphs: glyphs.length };
    }

    const solutions = prefixScored.map((p) => solveLuhn(p.prefix, digitRanked)).filter(Boolean);
    allSolutions = prefixScored.map((p) => collectLuhn(p.prefix, digitRanked)).flat();
    if (!solutions.length) continue;

    const passing = solutions.reduce((a, s) => a + s.count, 0);
    const best = solutions.reduce((a, b) => (b.cost < a.cost ? b : a));

    if (beam === 1) return { id: best.id, status: 'certain', glyphs: glyphs.length, repaired: false, alternatives: [] };

    // Every reading that satisfied the check digit, so a person checking the card can see
    // what the alternatives were rather than being handed one answer with no reasoning.
    const unique = [...new Set(allSolutions.map((s2) => s2.id))];
    if (passing === 1) return { id: best.id, status: 'repaired', glyphs: glyphs.length, repaired: true, alternatives: unique };
    return {
      id: best.id, status: 'ambiguous', glyphs: glyphs.length, repaired: true,
      passing, alternatives: unique,
    };
  }

  return { id: '', status: 'invalid', glyphs: glyphs.length };
}


/** Every Luhn-valid id reachable from the ranked candidates, not just the cheapest. */
function collectLuhn(prefix, ranked) {
  const out = [];
  const walk = (i, digits) => {
    if (i === ranked.length) {
      const d = digits.join('');
      if (luhnValid(d)) out.push({ id: prefix + d });
      return;
    }
    for (const c of ranked[i]) walk(i + 1, digits.concat(c.char));
  };
  walk(0, []);
  return out;
}

/**
 * Ranks the single-substitution repairs of a reading by what the glyphs actually look like.
 *
 * epic.js finds every one-character edit that makes an id satisfy its check digit, but
 * accepts a repair only when exactly one edit validates. For a seven-digit Luhn that
 * almost never happens -- ZZZ5302955 has five valid single edits -- so uniqueness is not
 * available as a confidence signal at this length.
 *
 * Those five are still a far smaller and more plausible hypothesis space than anything
 * else available: each differs from what the recogniser actually saw by one character. So
 * the check digit proposes the candidates and the glyphs choose between them, by summing
 * each candidate's per-position distance to the labelled exemplars. Constraint and
 * evidence, each doing what it is good at.
 *
 * This is a ranking, not a proof. The caller records it as such.
 *
 * @returns {Array<{id:string, cost:number}>} best first; empty when the reading is
 *   already valid, malformed, or no single edit satisfies the check digit
 */
function rankRepairs(px, pageW, rect, atlases, prefixes, ocrId) {
  const bmp = bitmapFromRect(px, pageW, rect);
  if (!bmp) return [];
  const glyphs = segmentId(bmp);
  if (!glyphs) return [];
  if (luhnValid(ocrId.slice(3))) return [];         // nothing to repair

  const norm = glyphs.map((g) => normalise(bmp, g));

  // Distance from each glyph to every character, as the best exemplar of that character.
  const toChar = (vec, atlas) => {
    const best = new Map();
    for (const ex of atlas) {
      let d = 0;
      for (let i = 0; i < vec.length; i++) {
        const t = vec[i] - ex.vec[i];
        d += t * t;
      }
      const prev = best.get(ex.char);
      if (prev === undefined || d < prev) best.set(ex.char, d);
    }
    return best;
  };
  const letterCost = norm.slice(0, LETTERS).map((v) => toChar(v, atlases.letters));
  const digitCost = norm.slice(LETTERS).map((v) => toChar(v, atlases.digits));

  // Candidate edits: a digit in a digit position, a letter in a letter position. Also a
  // letter for a digit, because "0" and "O" are the same mark to a recogniser at this size
  // and one of the two readings is right -- which is how WQQO679557 arises.
  const out = [];
  for (let i = 0; i < ocrId.length; i++) {
    const alphabet = i < LETTERS ? 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789' : '0123456789';
    for (const ch of alphabet) {
      if (ch === ocrId[i]) continue;
      const cand = ocrId.slice(0, i) + ch + ocrId.slice(i + 1);
      if (!/^[A-Z]{3}[0-9]{7}$/.test(cand)) continue;
      if (!luhnValid(cand.slice(3))) continue;

      let cost = 0;
      let usable = true;
      for (let k = 0; k < LETTERS; k++) {
        const d = letterCost[k].get(cand[k]);
        if (d === undefined) { usable = false; break; }
        cost += d;
      }
      if (usable) {
        for (let k = 0; k < DIGITS; k++) {
          const d = digitCost[k].get(cand[LETTERS + k]);
          if (d === undefined) { usable = false; break; }
          cost += d;
        }
      }
      if (usable) out.push({ id: cand, cost });
    }
  }

  out.sort((a, b) => a.cost - b.cost);
  void prefixes;                        // the prefix set is already applied in readEpic
  return out;
}

module.exports = {
  readEpic, rank, solveLuhn, collectLuhn, rankRepairs, setMaxDistance, segmentId,
  LETTERS, DIGITS, BEAMS, MIN_COL_INKS,
};