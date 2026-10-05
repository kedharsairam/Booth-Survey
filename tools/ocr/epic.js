// Validates an Indian EPIC (voter id) number using its Luhn check digit.
//
// An EPIC number is three letters identifying the constituency, then a six digit
// serial, then one check digit computed with Luhn over the serial. That check digit
// is the single most useful validation available on this data, because it turns a
// plausible-looking read into a verifiable one: a misread digit changes the Luhn
// sum and the number is rejected. Ten reads in ten are caught by chance alone,
// before any other check.
//
// This matters here because the voter id is the primary key of the whole dataset.
// Everything else -- the booklet, the merge against the final roll, the re-import
// when the real electoral roll arrives -- hangs off it. A silently wrong voter id
// is worse than a missing one, because it will merge one person into another.
//
// Source of the algorithm: stdnum's `stdnum.in.epic`, which follows the ECI
// programme guideline. The Luhn variant here doubles every second digit counting
// from the right, the standard construction.

const SHAPE = /^[A-Z]{3}[0-9]{7}$/;

// Luhn check over a digit string whose final character is the check digit.
function luhnValid(digits) {
  if (!/^\d+$/.test(digits)) return false;
  let sum = 0;
  let double = false;                      // the rightmost digit is the check digit
  for (let i = digits.length - 1; i >= 0; i--) {
    let d = digits.charCodeAt(i) - 48;
    if (double) {
      d *= 2;
      if (d > 9) d -= 9;
    }
    sum += d;
    double = !double;
  }
  return sum % 10 === 0;
}

// The check digit an EPIC *should* end with, given its first six digits. Used to
// repair a single-digit misread rather than merely reject it: if exactly one
// position fails, try substituting each other digit and see which one validates.
function expectedCheckDigit(serial6) {
  for (let c = 0; c <= 9; c++) {
    if (luhnValid(serial6 + String(c))) return String(c);
  }
  return null;
}

// Returns { ok, reason, repair }. `repair` is a corrected EPIC when exactly one
// substitution makes the number validate, otherwise null.
function check(epic) {
  if (!epic) return { ok: false, reason: 'empty', repair: null };
  if (!SHAPE.test(epic)) return { ok: false, reason: 'shape', repair: null };
  const letters = epic.slice(0, 3);
  const digits = epic.slice(3);
  if (luhnValid(digits)) return { ok: true, reason: 'luhn', repair: null };

  // Try to find the single substituted digit.
  const fixes = [];
  for (let i = 0; i < 7; i++) {
    for (let d = 0; d <= 9; d++) {
      const ch = String(d);
      if (ch === digits[i]) continue;
      const cand = digits.slice(0, i) + ch + digits.slice(i + 1);
      if (luhnValid(cand)) fixes.push(letters + cand);
    }
  }
  return {
    ok: false,
    reason: 'luhn',
    repair: fixes.length === 1 ? fixes[0] : null,
    candidates: fixes.slice(0, 5),
  };
}

module.exports = { check, luhnValid, expectedCheckDigit, SHAPE };

if (require.main === module) {
  const known = process.argv.slice(2);
  for (const e of known) {
    const r = check(e);
    console.log(`  ${e}  ${r.ok ? 'VALID' : 'INVALID (' + r.reason + ')'}${r.repair ? '  repair -> ' + r.repair : ''}`);
  }
}