// Reads one card's fields: name, father's name, house number, age, gender, voter id.
//
// The shape of the problem
// ------------------------
// Each field is read from the printed line that carries its own label, not from a fixed
// offset, because the fields do not sit at fixed offsets. A card with a two-line name and
// a two-line father's name pushes "House Number" 57 px lower than a card with one line
// each, so a positional crop reads whatever field happens to be there -- which is how a
// door number ends up holding somebody's age.
//
// Values also wrap. A long name continues onto the next line with no label on it, so
// lines are attributed to the field of the nearest label above them, and a line with
// neither is left unattributed rather than being attached to a guess.
//
// The voter's id is the exception and is read positionally: it sits in a fixed framed box
// at the top right of the card, clear of every other field, because it is the one field
// the card draws a frame around.

const { execFileSync } = require('child_process');
const { textLines } = require('./card.js');

// Label text as the roll prints it, and what each one means here. Ordered so that the
// longer labels are tested before the shorter ones they contain: a bare "name" must not
// claim the "Name" inside "Fathers Name".
// The relation lines -- "Father's Name" and "Husband's Name" -- have to be recognised, and
// they have to be recognised *by stem*.
//
// Measured on the English roll: the label recogniser cannot spell these. Across 86 cards
// the father line came out as "fathers" every time (apostrophe dropped), and the husband
// line came out as one of "husbant" (13), "husbanc" (6) or "husban" (6) -- the final d lost
// every single time. So neither /husband/i nor /husband'?s?\s*name/i matches any observed
// spelling. The stem is the only reliable thing.
//
// This matters more than a mis-parse. An unrecognised line is treated as a wrapped
// continuation of whatever field is above it, and a relation line sits directly under the
// name line -- so an unrecognised husband line lands in the name. Measured on that roll:
// 25 of 86 cards carried a husband line, which is the "almost half" of names that came out
// wrong.
//
// The value is not thrown away. lexicon.js harvests it deliberately, because the members of
// a household share it and it is evidence about people who are not themselves voters. What
// must never happen is it being written as the voter's own name.
// Matched on the stem, then optionally the possessive and the word "name". Every part of
// that is load-bearing, and each was found by a form the roll actually produced:
//
//   /(?:father|husban)\w*/i                      matched "husbant:" but not "Husbands Name:",
//                                                 because there was no room for the " Name"
//                                                 suffix, so the regex backtracked and the
//                                                 generic "name" alternative claimed the line
//   /(?:father|husban)\w*'?(?:\s*name)?/i       still wrong, and instructive: with the
//                                                 apostrophe *outside* the optional group,
//                                                 "Father's Name" matched only as "Father'",
//                                                 leaving " Name:" unmatched, so the whole
//                                                 alternative failed at position 0 and the
//                                                 generic "name" alternative claimed the line
//                                                 further along
//   /(?:father|husban)[\w']*(?:\s*name)?/i       apostrophe and word inside one optional
//                                                 group, so the group is all-or-nothing
//
// The apostrophe matters because the roll prints "Father's Name" and "Husband's Name" with
// one, while the label-only crop loses it ("fathers"). Both spellings occur, so both are
// allowed.
const RELATION_RE = /(?:father|husban)[\w']*(?:\s*name)?/i;

// "House Number :", "House Number:", and the label-only crop's "house n" / "house nn".
// A bare /house/i was tried and broke the colon path entirely: "house" matched, then the
// following \s*: found "Number" instead of a colon, the alternative failed, and the whole
// house label stopped being recognised at all.
const HOUSE_RE = /house(?:\s*\w*)?/i;

const FIELD_LABELS = [
  // The roll prints an "Others" line for political affiliation. It is not a field this
  // project extracts, but it has to be recognised, because an unrecognised line is
  // treated as a wrapped continuation of whatever field is above it -- which put
  // "Others: Naga Anja" into somebody's name.
  { field: 'others', re: /others/i },
  // "house" alone, not "house number". Measured label OCR on this roll gives 'house n',
  // 'house nn' and 'house in', and /house\s*number/i matches none of them. An unrecognised
  // line is treated as a wrapped continuation of the field above it, and the house line
  // sits directly under the relation line, so a miss here appends a door number to a
  // relation name. "house" is unambiguous as a label word, so the shorter pattern is safer.
  { field: 'house', re: HOUSE_RE },
  // Before "name", and that order is load-bearing: "husbands name" contains "name", so a
  // table with name first routes the husband's line straight into the name field.
  { field: 'father', re: RELATION_RE },
  { field: 'gender', re: /gender/i },
  { field: 'age', re: /age/i },
  { field: 'name', re: /name/i },
];

// Every label the roll prints, in one pattern. The alternation order is what keeps
// "Fathers Name:" from being read as a name field: the scan is left to right, so it
// reaches the "F" of "Fathers" first and the longer alternative matches there.
// One pattern for the relation lines, shared with FIELD_LABELS, so the two cannot drift.
// Relation alternatives come before "name" for the reason given above.
const LABEL_RE = new RegExp(
  `(others|${HOUSE_RE.source}|${RELATION_RE.source}|gender|age|name)\\s*:`, 'gi');

// The same labels with no colon required, for the lines where the colon was misread.
const LABEL_WORD_RE = new RegExp(
  `\\b(others|${HOUSE_RE.source}|${RELATION_RE.source}|gender|age|name)\\b`, 'i');

// The voter's id box, as fractions of the card. It is the one framed field, top right,
// and does not move. Measured on page 3: ink from dx 455 to dx 610, dy 15 to dy 32.
const VID = { x0: 0.730, x1: 0.995, y0: 0.045, y1: 0.150 };

// Renders a card-relative region as a clean, magnified strip for recognition.
function render(png, card, box, out, { scale = 300, threshold = true } = {}) {
  const cw = card.x1 - card.x0, ch = card.y1 - card.y0;
  const x = Math.round(card.x0 + cw * box.x0);
  const y = Math.round(card.y0 + ch * box.y0);
  const w = Math.round(cw * (box.x1 - box.x0));
  const h = Math.round(ch * (box.y1 - box.y0));
  const args = [png, '-crop', `${w}x${h}+${x}+${y}`, '+repage', '-colorspace', 'Gray'];
  if (threshold) args.push('-threshold', '60%', '-trim', '+repage');
  args.push('-resize', `${scale}%`, '-bordercolor', 'white', '-border', '10', out);
  execFileSync('convert', args);
  return out;
}

/**
 * Splits a recognised line into every field it carries.
 *
 * A line is not necessarily one field. The roll prints age and gender on one line,
 * "Age : 28 Gender : Female", so reading only up to the first colon left the age field
 * holding the gender as well. Each label claims the text between itself and the next
 * label, which is what the printed layout means.
 *
 * The fallback matters more than it looks. The colon is read correctly on 204 lines out
 * of 207, but on the rest it comes back as something else -- "House Number (cent) 97-3-3/1",
 * "House Number = 97-6-6/3" -- and when the colon is missing the line carries no label as
 * far as the pattern is concerned, so the caller treats it as a wrapped continuation of
 * whatever field was above. That is how "KOTAH NAGAIAH DODDIPATLA" ended up recorded as
 * the name "KOTAH NAGAIAH DODDIPATLA House Number (cent) 97-3-3/1". So when the colon is
 * absent, the label word alone is enough to open a field.
 *
 * @returns {Array<{field:string|null, value:string}>} in reading order; a line with no
 *   label yields one entry whose field is null, which the caller treats as a continuation
 */
function parseLine(text) {
  const t = text.replace(/\s+/g, ' ').trim();
  if (!t) return [];

  const fieldOf = (label) => {
    for (const { field, re } of FIELD_LABELS) if (re.test(label)) return field;
    return null;
  };

  const marks = [];
  LABEL_RE.lastIndex = 0;
  let m;
  while ((m = LABEL_RE.exec(t)) !== null) {
    marks.push({ field: fieldOf(m[1]), start: m.index, end: m.index + m[0].length });
  }

  if (!marks.length) {
    // No colon was read. Fall back to the bare label word, and treat the single token
    // between the label and the value as separator debris -- it is the misread colon.
    const word = LABEL_WORD_RE.exec(t);
    if (!word) return [{ field: null, value: t }];
    const field = fieldOf(word[1]);
    if (!field) return [{ field: null, value: t }];
    // The single misread separator token sits between the label and the value. It is
    // stripped only if it is punctuation -- "House Number (cent) 97-3-3/1" -- because a
    // looser rule would eat the value itself when the value follows the label directly.
    const after = t.slice(word.index + word[0].length);
    return [{ field, value: after.replace(/^\s*[^A-Za-z0-9]*\s*/, '').trim() }];
  }

  const out = [];
  for (let i = 0; i < marks.length; i++) {
    // Each label claims the text up to where the next label begins. Cutting at the next
    // label's start rather than its end matters when the labels are adjacent, as they
    // are on "Age : 28 Gender : Female" -- otherwise the age would keep the word
    // "Gender".
    const stop = i + 1 < marks.length ? marks[i + 1].start : undefined;
    out.push({ field: marks[i].field, value: t.slice(marks[i].end, stop).trim() });
  }
  return out;
}

/**
 * Derives a card's fields from its recognised lines.
 *
 * Separate from recognition so that a change to how lines are understood costs a re-parse
 * of cached text rather than another fifteen-minute pass over the roll.
 *
 * @param {string[]} rawLines the recognised lines, in reading order
 * @returns {{fields:object, unattributed:string[]}}
 */
function parseFields(rawLines) {
  const fields = {};
  const unattributed = [];
  let last = null;

  for (const raw of rawLines || []) {
    for (const { field, value } of parseLine(raw)) {
      if (field) {
        fields[field] = value;
        last = field;
      } else if (last && value) {
        fields[last] = `${fields[last]} ${value}`.replace(/\s+/g, ' ').trim();
      } else if (value) {
        unattributed.push(raw);
      }
    }
  }
  return { fields, unattributed };
}

/**
 * Reads every field from one card.
 *
 * @param {object} worker a tesseract worker, reused across the whole run
 * @param {string} png    page PNG at native resolution
 * @param {number} pageW  page width in pixels
 * @param {number[]} px   page grayscale pixels
 * @param {object} card   card bounds from the grid
 * @returns {Promise<{fields: object, lines: Array, unattributed: Array}>}
 */
async function readCard(worker, png, pageW, px, card) {
  // The serial reader runs on the same worker with a digits-only whitelist. Left set, it
  // would strip every letter out of these fields and return "10-1-31" for a name.
  await worker.setParameters({ tessedit_char_whitelist: '' });

  const lines = [];
  const ch = card.y1 - card.y0;
  const bands = textLines(px, pageW, card);

  for (const band of bands) {
    const strip = render(png, card, {
      x0: 0.015, x1: 0.730,
      y0: (Math.round(card.y0 + band.y0) - Math.round(card.y0)) / ch,
      y1: (Math.round(card.y0 + band.y1) - Math.round(card.y0)) / ch,
    }, '/tmp/opencode/voter/ocr/line.png', { scale: 300 });

    const r = await worker.recognize(strip, { tessedit_pageseg_mode: '7' }, { text: true });
    const raw = r.data.text.replace(/\s+/g, ' ').trim();
    lines.push({ band, raw });
  }

  const { fields, unattributed } = parseFields(lines.map((l) => l.raw));

  const vidStrip = render(png, card, VID, '/tmp/opencode/voter/ocr/vid.png', { scale: 400 });
  const vr = await worker.recognize(vidStrip, { tessedit_pageseg_mode: '8' }, { text: true });
  fields.voter_id = vr.data.text.replace(/[^A-Za-z0-9]/g, '').toUpperCase();

  return { fields, lines, unattributed };
}

module.exports = {
  readCard, render, parseLine, parseFields, VID, FIELD_LABELS, LABEL_RE,
  RELATION_RE, HOUSE_RE, LABEL_WORD_RE,
};