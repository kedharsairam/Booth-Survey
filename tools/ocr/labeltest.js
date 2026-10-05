// Checks the label classifier against every spelling of every label this roll prints.
//
// This exists because the relation bug was found by reading one card, and then the first two
// attempts to fix it were both wrong -- one written against a label-strip reading rather
// than the reading the pipeline actually gets, and one that did not allow for the "s" in
// "Father's". Three of those failures were invisible to a build and to reading the code.
//
// So this tests the *exported* regexes, loaded from the module, not a reconstruction of
// them. An earlier version of this file rebuilt LABEL_RE by hand and disagreed with the
// module about "House Number", which is exactly the kind of drift this is meant to catch.
//
// The forms below are the ones actually observed, from two different OCR passes over the
// same cards:
//
//   cardread's own output on the English roll:
//       "Name : VIJAYALAKSHMI KAVURU"
//       "Husbands Name: RAMAKRISHANA"
//       "House Number : 96-4-4/4"
//       "Age : 53 Gender : Female"
//
//   and labelscan.js over 86 cards, which reads the label-only crop where the final letter
//   is often lost:
//       "fathers" 60x, "husbant" 13x, "husbanc" 6x, "husban" 6x,
//       "house n", "house nn", "house in"
//
// Run: node labeltest.js

const { LABEL_RE, LABEL_WORD_RE, FIELD_LABELS } = require('./cardread.js');

const fieldOf = (label) => {
  for (const { field, re } of FIELD_LABELS) if (re.test(label)) return field;
  return null;
};

// label text -> the field it must resolve to
const CASES = [
  ["Name:", 'name'], ["Name :", 'name'], ["name:", 'name'],

  // relation lines: both spellings the roll uses, and every mangled form observed
  ["Father's Name:", 'father'], ["Fathers Name:", 'father'], ["fathers:", 'father'],
  ["Fathers Name :", 'father'], ["Father's name:", 'father'],
  ["Husband's Name:", 'father'], ["Husbands Name:", 'father'], ["Husband Name:", 'father'],
  ["husband:", 'father'], ["husbant:", 'father'], ["husbanc:", 'father'], ["husban:", 'father'],
  ["husbands:", 'father'],

  // the rest of the card
  ["House Number :", 'house'], ["House Number:", 'house'], ["House:", 'house'],
  ["house n:", 'house'], ["house nn:", 'house'],
  ["Age :", 'age'], ["Gender :", 'gender'], ["Others:", 'others'],
];

// No-colon forms, which take the LABEL_WORD_RE fallback path.
const WORD_CASES = [
  ["Fathers name", 'father'], ["Father's name", 'father'],
  ["husbands name", 'father'], ["fathers", 'father'], ["husbant", 'father'],
  ["House n", 'house'], ["house nn", 'house'],
];

let bad = 0;

const check = (rx, text, want, heading) => {
  rx.lastIndex = 0;
  const m = rx.exec(text);
  const got = m ? fieldOf(m[1]) : null;
  const ok = got === want;
  if (!ok) bad++;
  console.log(`    ${JSON.stringify(text).padEnd(20)} captured ${JSON.stringify(m ? m[1] : null).padEnd(18)}`
    + `-> ${String(got).padEnd(8)}${ok ? '' : `  WRONG, expected ${want}`}`);
};

console.log('  with a colon -- LABEL_RE:');
for (const [text, want] of CASES) check(LABEL_RE, text, want);

console.log('  no colon -- LABEL_WORD_RE fallback:');
for (const [text, want] of WORD_CASES) check(LABEL_WORD_RE, text, want);

// The assertion that matters most, stated separately because it is the actual bug:
// no relation spelling may reach the name field, because an unrecognised line is treated as
// a wrapped continuation of the field above it, and the relation line sits under the name.
console.log('  the relation lines must never reach name:');
const RELATION_SPELLINGS = ["Father's Name", 'Fathers Name', 'Fathers', 'fathers',
  "Husband's Name", 'Husbands Name', 'Husband Name', 'husband', 'husbands',
  'husbant', 'husbanc', 'husban'];
for (const t of RELATION_SPELLINGS) {
  LABEL_RE.lastIndex = 0;
  const m = LABEL_RE.exec(t + ':');
  const got = m ? fieldOf(m[1]) : null;
  const ok = got === 'father';
  if (!ok) bad++;
  console.log(`    ${JSON.stringify(t).padEnd(20)} -> ${String(got).padEnd(8)}${ok ? '' : '  REACHED name'}`);
}

console.log(bad ? `\n  ${bad} form(s) misroute` : '\n  every form routes to the right field');
process.exit(bad ? 1 : 0);