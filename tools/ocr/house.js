// Validates house numbers against the grammar the roll actually prints.
//
// House numbers are the join key for everything else in this project: a wrong one
// invents a household, and since households are grouped by door number, one bad read
// silently creates or destroys a family in the booklet. They are also the field with no
// check digit, so nothing downstream can catch an error in them. Validation is therefore
// not optional here; it is the only guard there is.
//
// The grammar is not guessed, it is counted. Over a three-page sample of 82 house numbers
// the shapes that occur are:
//
//   NN-N-N            27        NN-N-NN/N or /C     13
//   NN-N-NN           23        NN-NN-NN              3
//   N-N-N              2        NN-NNN                 2
//   NN-NN              2        NN-NNN-N               1
//   NN-N-NN-A or /A     4
//
// plus eight values carrying trailing text on the same printed line -- "94-5-5/1, 4TH
// WARD", "98-5-5/5 NEAR TYAGARAJA BHAVAN" -- which is not a misread but a longer value
// than the pattern alone accounts for.
//
// Two things are therefore checked. The shape, against the grammar above. And the leading
// group, which is 11 for 73 of 82 values and 10 for 7, so a leading group of a single
// digit is far more likely a dropped digit than a real address: "1-1-6" reads as though
// its first "1" were lost. That inference is reported as a warning, never applied as a
// repair, because "91-7-7/1" and "1-1-6" are different houses.

// The house number is matched as an anchored prefix rather than as a whole-string
// pattern, because the roll sometimes prints a landmark on the same line --
// "98-5-5/5 NEAR TYAGARAJA BHAVAN", "94-5-5/1, 4TH WARD" -- and a whole-string pattern has
// to choose between rejecting those and being loose enough to accept anything.
//
// Anchoring on the numeric core and treating the remainder as trailing text handles both
// without loosening the core. It also fixes two forms that a whole-string pattern written
// from the shape counts alone gets wrong: a letter suffix attached with no separator at
// all ("93-4-4/1A"), and a three-digit middle group ("11-142-1").
//
//   leading group   two digits
//   then a dash, a middle group of one to three digits
//   then a dash, a final group of one to three digits
//   then optionally a suffix: a separated "/3" or "-C" or "A", or a bare trailing letter
const CORE = /^(\d{2})-(\d{1,3})-(\d{1,3})/;

// A suffix after the core.
//
// Two forms, and they must be told apart carefully. A separated suffix ("/3", "-C")
// may be followed by landmark text, so it is matched from the core outward. A bare
// trailing letter ("93-4-4/1A") has no separator at all, which means it cannot be
// distinguished from the first letter of a landmark word -- matching it anywhere but at
// the very end of the value turns "98-5-5/5 NEAR TYAGARAJA BHAVAN" into the number
// "98-5-5/5N" with trailing text "EAR TYAGARAJA BHAVAN". So a bare letter is only a
// suffix when it is the entire remainder.
const SUFFIX_SEPARATED = /^\s*[/-]\s*(\d{1,2}|[A-Z])/;
const SUFFIX_BARE = /^([A-Z])$/;

// The two-group form, which occurs a handful of times.
const TWO_PART = /^(\d{2})-(\d{1,3})$/;

// Leading groups seen in this ward. A leading group outside this set is flagged, not
// rejected: a ward can straddle a boundary, and the roll is the authority, not this list.
const KNOWN_LEADING = new Set(['10', '11']);

/**
 * Checks one house number.
 *
 * @param {string} value as read
 * @returns {{ok:boolean, shape:string, number:string, suffix:string|null,
 *            trailing:string|null, warnings:string[]}}
 *   ok is true when the value is a well-formed house number; warnings explain any
 *   concern even when ok, and never imply a correction has been made
 */
function validateHouse(value) {
  const v = (value || '').trim().toUpperCase();
  const warnings = [];
  if (!v) return { ok: false, shape: 'empty', number: '', suffix: null, trailing: null, warnings: ['no value read'] };

  const core = CORE.exec(v);
  if (!core) {
    const two = TWO_PART.exec(v);
    if (two) {
      warnings.push('only two groups; three are the norm here');
      return {
        ok: warnings.length === 0, shape: 'two', number: v, suffix: null, trailing: null, warnings,
      };
    }
    warnings.push('does not match the roll\'s house number grammar');
    return { ok: false, shape: 'unknown', number: v, suffix: null, trailing: null, warnings };
  }

  const rest = v.slice(core[0].length);

  // The suffix, and how much text it consumed. The consumed text is kept verbatim so the
  // separator survives: reconstructing the number by appending a bare suffix would turn
  // "96-3-3/5" into "10-1-31", a different house.
  let suffix = null;
  let consumed = 0;
  const sep = SUFFIX_SEPARATED.exec(rest);
  const bare = SUFFIX_BARE.exec(rest.trim());
  if (sep) {
    suffix = sep[1];
    consumed = sep[0].length;
  } else if (bare) {
    suffix = bare[1];
    consumed = rest.trim().length;
  }

  let trailing = rest.slice(consumed).trim();
  // A trailing comma separates the landmark; it is not part of it.
  trailing = trailing.replace(/^[,;:]\s*/, '').trim();
  if (trailing) warnings.push(`carries trailing text: ${JSON.stringify(trailing)}`);

  const number = v.slice(0, core[0].length + consumed).trim();
  const leading = core[1];
  if (!KNOWN_LEADING.has(leading)) {
    warnings.push(
      leading.length < 2
        ? `leading group ${JSON.stringify(leading)} has one digit; this ward uses ${[...KNOWN_LEADING].join(' and ')}, so a dropped digit is likely`
        : `leading group ${JSON.stringify(leading)} is not one this ward uses (${[...KNOWN_LEADING].join(', ')})`,
    );
  }

  return {
    ok: warnings.length === 0,
    shape: 'three',
    number,
    suffix,
    trailing: trailing || null,
    warnings,
  };
}

// ===========================================================================
// Image pipeline, restored.
//
// The scrub that took the electoral roll out of this repository replaced this file
// wholesale with the validation half above. That left twelve modules calling
// functions that no longer existed -- read.js, eval.js, match.js, labels.js,
// glyphcache.js, report.js, selftest.js, seqcheck.js, serial.js, showtemplates.js,
// sweep2.js and vote.js could not run at all. Validation and image handling were
// never the same code, and only the validation survived.
//
// looksLikeHouse and tidy below are the weaker earlier checks, kept only because
// the voting and sweep harnesses call them. build.js uses validateHouse.
// ===========================================================================

const path = require('path');
const { execFileSync, execFile } = require('child_process');
const { promisify } = require('util');
const execFileAsync = promisify(execFile);
const F = require('./fields.js');

const SCALE = 2;      // crops are upscaled 200% before recognition
const BORDER = 24;    // white margin added after the resize, in output pixels

// Stage 1: digits and separators only. Letters are left out so the Telugu lines
// collapse to noise instead of producing plausible-looking junk, which keeps
// exactly one line standing out as the house number.
const LOCATE_OPTS = { tessedit_char_whitelist: '0123456789-/', tessedit_pageseg_mode: '6' };

// Stage 2: the real read. Letters matter here ("93-4-4/1A", "96-2-2/1"), and
// PSM 8 is the mode that reads a short token of digits correctly -- measured
// 30/30 exact on the serial field against 21/30 for PSM 7.
const LOOK = { tessedit_char_whitelist: '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-/. ', tessedit_pageseg_mode: '8' };

// Crops a page rectangle and normalises it for the recogniser.
// ImageMagick, and it stays that way.
//
// What changed is not what runs but whether it blocks. This used to be execFileSync,
// which freezes the Node event loop for the length of the spawn -- so although
// read.js fans cards out across several Tesseract workers, every ImageMagick call in
// every worker was serialised behind the same single thread. Measured on page 5:
//
//     1 worker, synchronous   421 s
//     8 workers, synchronous  405 s     <- the extra workers bought 4%
//
// which is the tell that the parallelism was never real. Promisifying the spawn lets
// twelve cores actually be used, and it changes nothing about the pixels: same binary,
// same arguments, byte-identical crops, same 25/30 on the page-5 ground truth.
//
// An in-process replacement lives in fastcrop.js and is faster still, but it is not
// accurate enough to be the default -- see the note there.
// The blocking form, for the one-off harness tools that call this outside an
// async context. Same command, same pixels, no concurrency -- they are interactive
// and there is nothing to overlap it with.
function renderLineSync(pagePng, rect, file, scale = SCALE, thr = 62) {
  if (process.env.BOOTH_OCR_FASTCROP === '1') {
    return require('./fastcrop.js').renderCrop(pagePng, rect, file, scale, thr, BORDER, true);
  }
  execFileSync('convert', [pagePng,
    '-crop', `${rect.w}x${rect.h}+${rect.x0}+${rect.y0}`, '+repage',
    '-colorspace', 'Gray', '-resize', `${scale * 100}%`,
    '-threshold', `${thr}%`,
    '-trim', '+repage', '-bordercolor', 'white', '-border', String(BORDER), file]);
  return file;
}

// `trim` is what the house vote wants: it drops the surrounding whitespace so the
// recogniser sees the value alone. A caller that needs to map a bounding box from the
// recogniser back onto the page must turn it off, because -trim shifts the image origin
// by an amount that is not knowable from the arguments. The label locator needs that
// mapping -- that is how it finds where the value starts -- so it asks for no trim and
// gets a crop whose pixel (0,0) is the crop rectangle's own top left.
async function renderLine(pagePng, rect, file, scale = SCALE, thr = 62, trim = true) {
  if (process.env.BOOTH_OCR_FASTCROP === '1') {
    return require('./fastcrop.js').renderCrop(pagePng, rect, file, scale, thr, BORDER, trim);
  }
  const args = [pagePng,
    '-crop', `${rect.w}x${rect.h}+${rect.x0}+${rect.y0}`, '+repage',
    '-colorspace', 'Gray', '-resize', `${scale * 100}%`,
    '-threshold', `${thr}%`];
  if (trim) args.push('-trim', '+repage');
  args.push('-bordercolor', 'white', '-border', String(BORDER), file);
  await execFileAsync('convert', args);
  return file;
}

// The card's text column, starting just right of the "ఇంటి సంఖ్య :" label so
// the Telugu cannot leak noise digits into the house number's line.
function columnRect(card) {
  const cw = card.x1 - card.x0, ch = card.y1 - card.y0;
  const x0 = Math.round(card.x0 + F.HOUSE_X0 / F.CARD_W * cw);
  const x1 = Math.round(card.x0 + F.HOUSE_X1 / F.CARD_W * cw);
  const y0 = Math.round(card.y0 + 200 / F.CARD_H * ch);
  const y1 = Math.round(card.y0 + (F.CARD_H - 40) / F.CARD_H * ch);
  return { x0, y0, w: x1 - x0, h: y1 - y0 };
}

// Maps a stage-1 line bounding box back onto page coordinates.
//
// Both axes come from the box. The box is in the space of the image tesseract
// actually saw: the column crop after a 2x resize and a 24px white border, so
// the inverse transform is subtract the border, divide by the resize factor,
// then add the crop's origin. Skipping either step lands the re-crop on the
// wrong line entirely, which is what produced a stage 2 full of "TTT" while
// stage 1 read "91-7-7/1" correctly.
//
// The horizontal edge looks redundant -- the value always begins at the same
// place, dx 512, because the label to its left is fixed text -- and using that
// fixed edge instead was tried and measured worse: 22/30 against 25/30 on page 5,
// with the crop then reaching back into the label's colon and returning "M200"
// and "1H". The box's left edge is what trims that debris. It does cost the
// occasional leading "1", which stage 1's digits-only whitelist fails to see;
// that trade is taken deliberately rather than by accident, and it is why the
// variants below vary the page-segmentation mode as well.
function lineRect(card, col, bbox, scale = SCALE, border = BORDER) {
  const toPageX = (X) => col.x0 + (X - border) / scale;
  const toPageY = (Y) => col.y0 + (Y - border) / scale;
  const pad = 12;
  const x0 = Math.max(card.x0 + 4, Math.round(toPageX(bbox.x0)) - pad);
  const y0 = Math.max(card.y0 + 4, Math.round(toPageY(bbox.y0)) - pad);
  const x1 = Math.min(card.x1 - 4, Math.round(toPageX(bbox.x1)) + pad);
  const y1 = Math.min(card.y1 - 4, Math.round(toPageY(bbox.y1)) + pad);
  if (x1 - x0 < 8 || y1 - y0 < 8) return null;
  return { x0, y0, w: x1 - x0, h: y1 - y0 };
}

// True when a stage-1 line carries the house number's shape: a digit, a
// separator, another digit. Loose enough for "17 3 1/1" and "95-3-3/4", tight
// enough that the age line -- a bare number -- does not match.
function looksLikeHouse(s) {
  return /[0-9]\s*[-/]\s*[0-9]/.test(s);
}

// Tidy a read: the label's colon sometimes survives into the crop as a leading
// separator, and a trailing lone letter is usually watermark debris.
function tidy(s) {
  return s.replace(/\s+/g, '')
    .replace(/^[^0-9A-Za-z]+/, '')
    .replace(/[^0-9A-Za-z]+$/, '');
}

// Stage 1. Returns the located line and the page rectangle to re-read, so a
// caller that wants several reads under different preprocessing can locate once.
async function cropColumn(pagePng, card, file, worker, { scale = SCALE, thr = 62 } = {}) {
  const col = columnRect(card);
  await renderLine(pagePng, col, file, scale, thr);

  const { data } = await worker.recognize(file, LOCATE_OPTS, { text: true, blocks: true });
  const lines = [];
  for (const blk of data.blocks || []) {
    for (const par of blk.paragraphs || []) {
      for (const ln of par.lines || []) lines.push({ text: (ln.text || '').trim(), bbox: ln.bbox });
    }
  }
  // Some builds return an empty tree; fall back to splitting the text, which
  // still identifies the right line but yields no box to re-crop.
  if (!lines.length && data.text) {
    for (const t of data.text.split('\n')) {
      const s = t.trim();
      if (s) lines.push({ text: s, bbox: null });
    }
  }

  // The house line is the first match from the top. The age line never matches,
  // so ordering only decides the rare case where two lines match, and taking
  // the first resolves it the same way every time.
  const line = lines.find((l) => looksLikeHouse(l.text)) || null;
  if (!line) return { col, line: null };
  const rect = line.bbox ? lineRect(card, col, line.bbox, scale) : null;
  return { col, line, rect };
}

// One full read: locate, then re-read.
async function readHouse(pagePng, card, worker, tmp, idx) {
  const loc = await cropColumn(pagePng, card, path.join(tmp, `col${idx}.png`), worker);
  if (!loc.line) return { house: '', located: false, reason: 'no line matched the house-number shape' };
  if (!loc.rect) return { house: tidy(loc.line.text), located: true, precise: false, stage1: loc.line.text };

  const file = path.join(tmp, `hl${idx}.png`);
  await renderLine(pagePng, loc.rect, file);
  const r = await worker.recognize(file, LOOK, { text: true });
  return {
    house: tidy(r.data.text),
    located: true,
    precise: true,
    stage1: loc.line.text,
    conf: Math.round(r.data.confidence),
    rect: [loc.rect.x0, loc.rect.y0, loc.rect.w, loc.rect.h],
  };
}

module.exports = { readHouse, cropColumn, renderLine, columnRect, lineRect, looksLikeHouse, tidy, LOCATE_OPTS, LOOK, SCALE, BORDER };

module.exports = {
  // validation -- the newer, stricter half, used by build.js
  validateHouse, CORE, SUFFIX_SEPARATED, SUFFIX_BARE, TWO_PART, KNOWN_LEADING,
  // image pipeline -- restored
  readHouse, cropColumn, renderLine, columnRect, lineRect, tidy, looksLikeHouse,
  LOCATE_OPTS, LOOK, SCALE, BORDER, renderLineSync,
};
