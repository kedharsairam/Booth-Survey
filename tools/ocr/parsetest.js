// Gate for the printed-line parser, on the lines this roll actually prints.
//
// labeltest.js gates the label patterns. This gates what happens to the value once a label has
// claimed it, which is where the house number has been broken twice by two different causes:
//
//   1. The roll sometimes prints the value behind its own abbreviation -- "D.No 90-51-11/6" on
//      serial 1123, "DOOR NUMBER 90-35-35/8" on serial 1125, confirmed on the card. Reading the
//      whole string as the value is not untidy, it is wrong: the booklet groups households by
//      this value, so a prefixed and an unprefixed spelling of one door are two households.
//   2. The separator after the label is sometimes misread as a letter -- "House Number t
//      90-41-1/5" is "House Number : 90-41-1/5" with the colon read as "t", and eight cards on
//      page 19 carry it. The generic rule that strips only punctuation cannot see that.
//
// Both are handled by the same fact: a house number begins with a digit. There is no need to
// enumerate the misreads once that is known, and enumerating them is what kept missing them.
//
// The negatives are the point of the test. A name and a relation must come back exactly as
// printed, or a fix aimed at the house field has quietly corrupted the name field -- which is
// the bug this project's husband-line work was about, and the reason these negatives are here
// rather than only the positives.
//
// It calls the real parseLine rather than a copy. A reconstruction passes whatever the copy
// says and describes nothing the pipeline does.

const { parseLine } = require('./cardread.js');

const CASES = [
  // The plain form, and the separator read three different ways.
  ['House Number : 90-5-5/5', 'house', '90-5-5/5'],
  ['House Number: 90-5-5/5', 'house', '90-5-5/5'],
  ['House Number :90-2-2/2', 'house', '90-2-2/2'],
  ['House Number (cent) 90-6-6/6', 'house', '90-6-6/6'],
  ['House Number = 90-20-20/2', 'house', '90-20-20/2'],
  ['house nn 90-21-21/3', 'house', '90-21-21/3'],

  // The printed prefixes, as the roll prints them and as the recogniser returns them. The
  // recogniser drops spaces, so "DOOR NUMBER 90-35-35/8" comes back "DOORNUMBER93-5-5/5", and
  // between "o" and "1" there is no word boundary to anchor on.
  ['House Number : D.No 90-51-11/6', 'house', '90-51-11/6'],
  ['House Number : D No 90-51-11/6', 'house', '90-51-11/6'],
  ['House Number : D.No. 90-51-11/6', 'house', '90-51-11/6'],
  ['House Number : DOOR NUMBER 90-35-35/8', 'house', '90-35-35/8'],
  ['House Number : Door No 90-51-11/6', 'house', '90-51-11/6'],
  ['House Number : DNO93-5-5/5', 'house', '90-35-35/8'],
  ['House Number : D.No90-1-1/1', 'house', '90-51-11/6'],
  ['House Number : DOORNO92-5-5/4', 'house', '90-34-34/7'],
  ['House Number : DOORNUMBER92-6-6/3', 'house', '90-52-12/7'],
  ['House Number : DNO-90-18-18/9', 'house', '90-18-18/9'],

  // The separator misread as a letter. Found on page 19, where the colon reads as "t" on
  // eight cards in a row.
  ['House Number t 90-41-1/5', 'house', '90-41-1/5'],
  ['House Number 90-41-1/5', 'house', '90-41-1/5'],

  // A house value that legitimately contains letters and must keep them.
  ['House Number : 90-37-37/1', 'house', '90-37-37/1'],
  ['House Number : 90-31-31/4', 'house', '90-31-31/4'],
  ['House Number : 90-2-2/2', 'house', '90-2-2/2'],

  // The negatives. A fix aimed at the house field must not reach any other field.
  ['Fathers Name  VENKATA', 'father', 'VENKATA'],
  ['Fathers Name:VENKATA RAMA KRISHNA', 'father', 'VENKATA RAMA KRISHNA'],
  ['Husbands Name: RAMBABU', 'father', 'RAMBABU'],
  ['husbant: RAMBABU', 'father', 'RAMBABU'],
  ['Name : HEMA SRI SAI REENA', 'name', 'HEMA SRI SAI REENA'],
  ['Age : 56 Gender : Male', 'age', '56'],
];

let bad = 0;
for (const [line, field, want] of CASES) {
  const hit = parseLine(line).find((e) => e.field === field);
  const got = hit ? hit.value : '(no field)';
  const ok = got === want;
  if (!ok) bad++;
  console.log(`  ${JSON.stringify(line).padEnd(40)} ${field}=${JSON.stringify(got).padEnd(24)}${ok ? '' : `  WANT ${JSON.stringify(want)}`}`);
}

// The house value must never come back carrying letters that are not part of it. Stated
// separately because it is the property the booklet depends on: it groups by this string.
const housey = CASES.filter(([, f]) => f === 'house');
const dirty = housey.filter(([line]) => {
  const hit = parseLine(line).find((e) => e.field === 'house');
  return !hit || !/^\d/.test(hit.value);
});
if (dirty.length) {
  console.log(`  ${dirty.length} house value(s) do not begin with a digit:`);
  for (const [line] of dirty) console.log(`    ${JSON.stringify(line)}`);
  bad += dirty.length;
}

console.log(bad ? `\n  ${bad} case(s) wrong` : `\n  all ${CASES.length} printed forms parse correctly`);
process.exit(bad ? 1 : 0);