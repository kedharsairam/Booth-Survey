// Reads the electoral roll's voter cards: serial number, voter id, house number.
//
// Names are deliberately not read. The roll is Telugu and names are never
// OCR'd here -- dad types them from the the visit, with Gboard, into the app.
// Only the three fields the book needs as printed are extracted.
//
// Each field is OCR'd on its own tight crop with a character whitelist, rather
// than by recognising a whole card. A card is mostly Telugu prose; feeding that
// to a recogniser configured for Latin digits invites substitutions. A crop of
// "91-7-7/1" with a digits-and-hyphen whitelist is a far easier problem.
//
// Cards are processed in parallel across several Tesseract workers. A single
// worker takes about seven minutes for a 30-card page, which is roughly five hours
// for the 43 list pages; the work is independent per card, so it should spread
// across cores.
//
// It did not, until renderLine stopped using execFileSync. That call blocks the
// Node event loop for the length of the ImageMagick spawn, so although the cards
// were fanned out over several workers, every crop in every worker was serialised
// behind the same thread. Measured on page 5:
//
//     1 worker, blocking spawns   421 s
//     8 workers, blocking spawns  405 s    <- 4% for 8x the workers
//     8 workers, async spawns      96 s    <- user time 9m20 against 1m36 wall
//    12 workers, async spawns      87 s
//
// The crops are byte-identical either way -- same binary, same arguments -- and the
// 30 house numbers on page 5 come out the same 25/30 against the ground truth read
// by eye. So a booth is about 64 minutes rather than five hours, for free.

const fs = require('fs');
const path = require('path');
const os = require('os');
const { createWorker } = require('tesseract.js');
const { readGray } = require('./grid.js');
const { renderLine, LOOK } = require('./house.js');
const { cardPages } = require('./layout.js');
const { textLines } = require('./card.js');
const { parseLine } = require('./cardread.js');
const { luhnValid } = require('./epic.js');
const { reconcile } = require('./reconcile.js');
const F = require('./fields.js');

const clean = (s) => s.replace(/\s+/g, '');

// The roll's last serial, printed on its final card. The positional serial is only
// trustworthy if the arithmetic over the pages lands here, so this is checked on every run
// and the run refuses to produce anything if it does not.
const LAST_SERIAL = 1129;

// Pages come from layout.js's pagePng, which extracts the scan's own pixels.
//
// This used to render each page itself, with pdftoppm at 300 dpi, on the reasoning that
// more resolution must help. Three things were wrong with that:
//
//   - The roll's pages are a single JPEG XObject of 1983 x 2806 -- A4 at 240 dpi. Asking
//     poppler for 300 dpi produces 8263 x 11692, a 4.167x interpolation of the same pixels
//     carrying no new detail. layout.js says so, in detail, and measures it: grid detection
//     215 ms native against 2145 ms interpolated, and the serial digits 18 px tall rather
//     than a smoothed 75.
//   - Every rectangle this reader recorded -- house_rect among them -- came out in that
//     interpolated space. Nothing else in the tool works in it. layout.js's pagePng is
//     1983 px wide; the rects ran to x 7356. So the review sheet cropped off the page, and
//     build.js and the evaluation harness would have done the same.
//   - It cost four fifths of the wall clock. The 43-page English run took 90 minutes
//     rendering pixels that were already on disk in better form.
//
// One page image, one coordinate space, and the disk cache that layout.js already keeps.

function rel(card, f) {
  const cw = card.x1 - card.x0, ch = card.y1 - card.y0;
  const x0 = Math.round(card.x0 + f.x0 * cw), y0 = Math.round(card.y0 + f.y0 * ch);
  const x1 = Math.round(card.x0 + f.x1 * cw), y1 = Math.round(card.y0 + f.y1 * ch);
  return { x0, y0, w: x1 - x0, h: y1 - y0 };
}

// serial -- the roll's own number for the card, printed right-aligned inside a ruled box at
// the top left.
//
// Two faults, both found on a full English run and both invisible in the output:
//
//   1. The digits touch the box's right-hand rule. On every card measured (30 of 30 on page
//      3) the serial's ink runs into the rule, so there is no crop that contains the whole
//      serial and excludes the rule. The old crop included both, and the rule -- a full-height
//      vertical stroke -- reads as a "1" under a digits-only whitelist. That is where "11"
//      became "1", and "71" on one card was a genuine serial while its neighbours were not.
//   2. psm 8 returned the empty string for a single-digit serial. A bare "1" is one narrow
//      stem with nothing either side of it, which is not the shape psm 8's word model wants.
//      Measured on five cards: psm 7 and psm 6 read all five, psm 8 missed the single-digit
//      one entirely. F.DIGITS now uses psm 7.
//
// So the rule is found by its shape rather than by its position, exactly as the house field
// finds its line by its label. A column inked across the full height of the box's interior is
// a border; a digit stands on the baseline and never reaches the top of the interior, because
// the interior has headroom above the tallest digit. Cutting the box to the columns that are
// not borders leaves the digits, whatever their alignment.
async function readSerial(pagePng, card, pageW, px, worker, tmp, i) {
  const box = rel(card, F.SERIAL_BOX);
  const h = box.h;

  // Column ink within the box, as a fraction of its height.
  const colFrac = [];
  for (let x = box.x0; x < box.x0 + box.w; x++) {
    let n = 0;
    for (let y = box.y0; y < box.y0 + h; y++) {
      if (255 - px[y * pageW + x] > 100) n++;
    }
    colFrac.push(n / h);
  }
  const isRule = (k) => colFrac[k] > 0.85;

  // Keep every run of non-rule columns that carries ink. More than one run means something
  // inside the box is not the serial -- the watermark, or a speck -- so the widest is taken,
  // which is the one the digits are in because they are the only ink of any size.
  const runs = [];
  let cur = null;
  for (let k = 0; k < colFrac.length; k++) {
    const ink = !isRule(k) && colFrac[k] > 0;
    if (ink) {
      if (!cur) { cur = [k, k]; runs.push(cur); } else cur[1] = k;
    } else cur = null;
  }
  const digitRuns = runs.filter((r) => r[1] - r[0] >= 1);
  if (!digitRuns.length) {
    return { serial: '', serial_conf: 0, serial_reason: 'no ink inside the serial box' };
  }
  digitRuns.sort((a, b) => (b[1] - b[0]) - (a[1] - a[0]));
  const [k0, k1] = digitRuns[0];

  // A little margin, so a digit's first column and its last are both inside the crop. The
  // crop is then trimmed by the renderer, which is safe here -- nothing maps a box back out
  // of this image, so no inverse transform has to survive.
  const x0 = Math.max(box.x0, box.x0 + k0 - 3);
  const x1 = Math.min(box.x0 + box.w, box.x0 + k1 + 4);
  const rect = { x0, y0: box.y0, w: x1 - x0, h };

  const reads = [];
  for (const v of SERIAL_VARIANTS) {
    const f = await renderLine(pagePng, rect, path.join(tmp, `s${i}-${v.scale}-${v.thr}-${v.psm}.png`),
      v.scale, v.thr, true);
    const { data } = await worker.recognize(f,
      { tessedit_char_whitelist: '0123456789', tessedit_pageseg_mode: String(v.psm) }, { text: true });
    reads.push(clean(data.text).replace(/[^0-9]/g, ''));
  }
  const tally = new Map();
  for (const t of reads) tally.set(t, (tally.get(t) || 0) + 1);
  let serial = '', votes = 0;
  for (const [t, n] of tally) if (n > votes) { votes = n; serial = t; }
  return {
    serial, serial_conf: votes, serial_reads: reads.length,
    serial_unanimous: tally.size === 1,
    serial_alternatives: [...tally.entries()].sort((a, b) => b[1] - a[1]),
  };
}

// The serials differ by digit count and by how close they sit to the box's rule, and no
// single configuration reads all of them, so the vote is kept. psm 7 leads because it is the
// only mode that reads a lone digit at all; psm 6 is carried alongside it because it ties on
// measurement and disagrees differently.
const SERIAL_VARIANTS = [
  { scale: 3, thr: 45, psm: 7 }, { scale: 3, thr: 62, psm: 7 }, { scale: 3, thr: 72, psm: 7 },
  { scale: 2, thr: 62, psm: 7 }, { scale: 3, thr: 62, psm: 6 }, { scale: 2, thr: 62, psm: 6 },
];

// epic -- the voter id: three letters then seven digits, the last of which is a Luhn check
// digit. e.g. "ZZZ6517775".
//
// Read as one string, and judged by the grammar and the check digit. It used to be cut in
// half -- three characters, then seven -- and each half read under a whitelist that fitted
// it, because a single whitelist admitting both letters and digits produced eight-digit
// strings on 2 of 30 cards on the interpolated render ("HXD02173334" for ZZZ6517775).
//
// The cut was worse than the problem it solved. It put the position of the boundary in
// the hands of splitAt, which merges the crop's ink columns across a gap measured in
// pixels, so the cut was correct only at the scale where MERGE_GAP was measured. On the
// scan's native pixels the merge swallowed adjacent characters, the cut landed inside the
// prefix, and the field went to zero: ten cards that read ZZZ9818485, ZZZ4340618,
// ZZZ3239431 came back as "W72959", "M11", garbage. A fixed pixel gap is a fixed pixel gap
// is wrong at every scale it was not measured at, and this document is not the one the
// constant was measured on.
//
// The grammar and the checksum are the better constraint, because they come from the
// document rather than from the pixels: a wrong read has to be wrong in a way that still
// yields three letters, seven digits, and a valid checksum to get past them. Measured over
// 60 single-pass reads of ten cards -- two scales, three segmentation modes -- 60 of 60
// matched ^[A-Z]{3}[0-9]{7}$ and every card read the same string under all six
// configurations. The variants are kept so that disagreement is still recorded.
const EPIC_VARIANTS = [
  { scale: 3, psm: 7 }, { scale: 2, psm: 7 }, { scale: 3, psm: 8 }, { scale: 3, psm: 13 },
];
const ALNUM = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ';

async function readEpic(pagePng, card, worker, tmp, i) {
  const reads = [];
  for (const v of EPIC_VARIANTS) {
    const f = await renderLine(pagePng, rel(card, F.EPIC),
      path.join(tmp, `e${i}-${v.scale}-${v.psm}.png`), v.scale, 62);
    const { data } = await worker.recognize(f,
      { tessedit_char_whitelist: ALNUM, tessedit_pageseg_mode: String(v.psm) }, { text: true });
    reads.push(clean(data.text).replace(/[^A-Za-z0-9]/g, '').toUpperCase());
  }
  const tally = new Map();
  for (const t of reads) tally.set(t, (tally.get(t) || 0) + 1);
  let epic = '', votes = 0;
  for (const [t, n] of tally) if (n > votes) { votes = n; epic = t; }

  const m = /^([A-Z]{3})([0-9]{7})$/.exec(epic);
  if (!m) {
    return { epic, epic_ok: false, epic_reason: 'does not match the voter-id grammar',
      epic_votes: votes, epic_reads: reads.length, epic_unanimous: tally.size === 1 };
  }
  const [, prefix, digits] = m;
  return {
    epic, epic_prefix: prefix, epic_digits: digits,
    epic_ok: luhnValid(digits),
    epic_reason: luhnValid(digits) ? '' : 'fails the check digit',
    epic_votes: votes, epic_reads: reads.length, epic_unanimous: tally.size === 1,
  };
}

// Renders a horizontal slice of an already-cropped image file. Used to cut the voter id
// into a letter half and a digit half; see readEpic for why that is gone.
//
// glyphs.js keeps its own copy of this idea and epicread.js still needs it, so it was not
// changed -- only this call site was removed, because a helper whose only caller is gone
// is not a helper anyone is maintaining.

// house -- located by content, then read several times and voted. See house.js.
//
// The vote exists because no single configuration is reliable on this field, and
// disagreement is recorded rather than hidden, so an uncertain card can be flagged for a
// human -- which matters here because this field decides who counts as one family.
//
// The page-segmentation mode is not spread evenly across the variants, and that is the
// whole finding. Measured over 108 configurations (three cards x nine thresholds x four
// scales) on the roll's own native pixels:
//
//     value          psm 6   psm 7   psm 8   psm 13
//     97-3-3/1         24/27   24/27    7/27     7/27
//     90-7-7/5           14/27   14/27    9/27     9/27
//     95-6-6/1            8/27    8/27    5/27     5/27
//
// psm 6 and psm 7 -- "assume a single uniform block of text" and "assume a single text
// line" -- are two to three times better than psm 8's "assume a single word" here, and
// this value is not a word: it is a hyphenated run of digit groups, which psm 8's model
// fits badly. The set below was seven psm 8 out of nine, so the vote was dominated by the
// worst mode and returned "M111" and "RIREV" for values it had no business producing.
//
// These counts were taken after the page render was corrected to the scan's native pixels.
// Before that correction every variant ran on a 4.167x interpolation, so the measurements
// that chose the old set do not describe anything this code now does.
const HOUSE_VARIANTS = [
  // psm 7 -- one text line, and the mode the label locator's own read already uses.
  { scale: 3, thr: 45, psm: 7 }, { scale: 3, thr: 50, psm: 7 },
  { scale: 3, thr: 62, psm: 7 }, { scale: 3, thr: 72, psm: 7 },
  { scale: 2, thr: 62, psm: 7 },
  // psm 6 -- one block. Ties with psm 7 on all three cards and reads differently enough
  // to be worth carrying: where the two agree the value is solid, and where they do not,
  // the card is one worth a person's eye.
  { scale: 3, thr: 45, psm: 6 }, { scale: 3, thr: 62, psm: 6 },
  { scale: 2, thr: 62, psm: 6 },
  // Two psm 8 reads, kept so the vote's failure modes are not homogeneous. psm 8 is the
  // mode that invents letters, and seeing where it disagrees is what separates a merely
  // uncertain card from a legible one. Two, not seven.
  { scale: 3, thr: 62, psm: 8 }, { scale: 3, thr: 50, psm: 8 },
];

// house -- located by its label, then voted over several readings. See house.js.
//
// The locator is the printed label, not a box. A field's position is a property of one
// document: the box this used to crop, F.HOUSE_X0..HOUSE_X1 scaled into the card, was
// measured on the Telugu roll and fitted it. On the English roll the same box was wider
// than the label it wanted and overlapped the two lines above it, so it returned
// "mber : 96-3-3/5" with the name and the relation line still in frame. Under the
// digits-only stage-1 whitelist the letters of those two lines are unreadable, so they
// collapse to noise -- but the recogniser still returns something for a line it has
// ink for, and the vote then picks a winner out of noise. That is where the leading
// groups came from: "A" 106 times, "AA" 33, "TT" 17, "N11" 30, against "11" 606. A
// leading group of A is not a house number; it is a letter from somebody's name.
//
// The fix costs one extra recognition per card and needs no new geometry at all, because
// the label position is already computed for the name reader: textLines() gives the ink
// bands in the text column, and the house value is the one whose band parses to the
// house field. This is the same call the name reader makes on the same crop, so the
// house read is now anchored to the same evidence as every other field on the card.
async function locateHouseByLabel(pagePng, card, pageW, px, worker, tmp, i) {
  const ch = card.y1 - card.y0;
  const bands = textLines(px, pageW, card);

  // The serial and voter-id readers both leave a digits-only whitelist set on the shared
  // worker. Left in place it would strip the letters out of "House Number :" and the label
  // could never be found -- the value would look unreadable while the line is perfectly
  // clear. This is the same reset the name reader does, for the same reason.
  await worker.setParameters({ tessedit_char_whitelist: '' });

  const SC = 3;      // the strip is upscaled 300% before recognition
  const BORDER = 24; // white margin added after the resize, in strip pixels

  for (const band of bands) {
    const bandRect = rel(card, {
      x0: 0.015, x1: 0.730,
      y0: (band.y0 - 3) / ch,
      y1: (band.y1 + 3) / ch,
    });
    if (bandRect.h < 8) continue;

    // No -trim on this crop. The strip is read for its word boxes as much as its text,
    // and a trimmed crop's origin is shifted by an amount that cannot be recovered from
    // the arguments -- so a box from a trimmed strip cannot be mapped back onto the page.
    // Untrimmed, strip pixel (BORDER, BORDER) is page pixel (bandRect.x0, bandRect.y0).
    const strip = await renderLine(pagePng, bandRect, path.join(tmp, `hl${i}.png`), SC, 62, false);
    const { data } = await worker.recognize(strip, { tessedit_pageseg_mode: '7' },
      { text: true, blocks: true });
    const raw = data.text.replace(/\s+/g, ' ').trim();
    const hit = parseLine(raw).find((e) => e.field === 'house');
    if (!hit) continue;

    const words = [];
    for (const blk of data.blocks || []) {
      for (const par of blk.paragraphs || []) {
        for (const ln of par.lines || []) {
          for (const wd of ln.words || []) words.push({ text: (wd.text || '').trim(), bbox: wd.bbox });
        }
      }
    }

    // The value starts after the label, not at the label. Returning the label along with
    // it is what turned every house number on the first pass into "HN96-3-3/5" -- "HN" is
    // not a house number, it is the first two letters of the word that was sitting there.
    //
    // Which word is the value is taken by matching the words against the value parseLine
    // already extracted, rather than by counting characters. Counting is what it looked
    // like at first and it clipped the leading "1" off 9 cards in 34: tesseract's word
    // list and its text output do not always join the same way, so an offset computed from
    // the string lands one or two characters off the box. Matching the value's own text to
    // a word needs them to agree about *which* token, which they do.
    const squash = (s) => s.replace(/[^A-Za-z0-9]/g, '').toLowerCase();
    const want = squash(hit.value);
    const toPageX = (X) => bandRect.x0 + (X - BORDER) / SC;
    let vx = null;
    for (const w of words) {
      const got = squash(w.text);
      if (!got || !w.bbox) continue;
      // A word that begins the value is its first word. Prefixes are allowed because the
      // recogniser sometimes splits the value itself -- "96-3-3/5" as "10" and "1-3/1" --
      // and the first piece is still where the value begins.
      if (want.startsWith(got)) {
        vx = Math.round(toPageX(w.bbox.x0));
        break;
      }
    }

    // No word box to work from: the label is left out by construction, which is right but
    // less precise, and is recorded so the caller can tell the two apart.
    if (vx === null) {
      return { rect: { ...bandRect }, value: hit.value, line: raw, precise: false };
    }
    // Keep a little of the space to the left of the first value glyph. Cutting exactly at
    // its edge clips the left stem of a digit like 1 on some renderings.
    const x0 = Math.max(bandRect.x0, vx - 8);
    if (bandRect.x0 + bandRect.w - x0 < 12) {
      return { rect: { ...bandRect }, value: hit.value, line: raw, precise: false };
    }
    return {
      rect: { x0, y0: bandRect.y0, w: bandRect.x0 + bandRect.w - x0, h: bandRect.h },
      value: hit.value, line: raw, precise: true,
    };
  }
  return { rect: null, value: '', line: '', precise: false };
}

// house -- read from the printed line, then cross-checked by a vote over the tightened crop.
//
// Two readers, and the label line is the primary one. This was not decided on plausibility;
// the two were scored against 20 magnified crops of the 132 cards where they disagree, and
// the label line won 16, the vote won 3, and one card was neither reader's answer. The
// scores and the crops are in score.js, and spotcheck.md says plainly that those 20 readings
// were made by an agent and are a second opinion.
//
// The vote's three wins are why it is kept rather than deleted: it is better on a substituted
// or invented digit, where the label line read "95-4-4/36" for "95-4-4/3" and "11-2-26/8B" for
// "90-3-3/4". The label line is better on the leading digit, which is where the vote loses
// everything -- 81 cards came back with a one-digit leading group against 4 for the label
// line, because the value's own first two 1s merge into one glyph once the crop is tightened
// around them and read alone.
//
// So: the label line provides the value, the vote provides disagreement. Where they differ
// the card reaches the review sheet, and `house_agrees` records which reader it went with so
// a later reader can tell a card that was confidently read from one that was contested.
async function readHouseVoted(pagePng, card, pageW, px, worker, tmp, i) {
  const loc = await locateHouseByLabel(pagePng, card, pageW, px, worker, tmp, i);
  if (!loc.rect) return { house: '', house_located: false };

  const reads = [];
  for (const v of HOUSE_VARIANTS) {
    const f = await renderLine(pagePng, loc.rect, path.join(tmp, `h${i}-${v.scale}-${v.thr}-${v.psm}.png`), v.scale, v.thr);
    const opts = { ...LOOK, tessedit_pageseg_mode: String(v.psm) };
    const { data } = await worker.recognize(f, opts, { text: true });
    reads.push(clean(data.text).replace(/^[^0-9A-Za-z]+/, '').replace(/[^0-9A-Za-z]+$/, ''));
  }
  const tally = new Map();
  for (const t of reads) tally.set(t, (tally.get(t) || 0) + 1);
  let voted = '', votes = 0;
  for (const [t, n] of tally) if (n > votes) { votes = n; voted = t; }

  // parseLine already removed the roll's printed prefix and any separator the recogniser
  // misread, on the way to producing loc.value. Nothing is stripped again here: two places
  // doing it is how the colon path and the fallback path ended up disagreeing.
  const house = loc.value.replace(/\s+/g, '');
  return {
    house, house_located: true, house_precise: loc.precise,
    house_voted: voted,
    house_agrees: voted === house,
    house_votes: votes, house_reads: reads.length, house_unanimous: tally.size === 1,
    house_alternatives: [...tally.entries()].sort((a, b) => b[1] - a[1]),
    house_rect: [loc.rect.x0, loc.rect.y0, loc.rect.w, loc.rect.h],
  };
}

// `page` and `idx` together locate the card on the page, which is what a manual check needs.
//
// The serial is `expected`, derived from where the card sits in the roll. The serial read
// from the card's ink is kept as `serial_read` beside it, and the two are compared, because
// that comparison is the only corroboration the positional serial has. It is not redundancy
// for its own sake: where the two disagree, exactly one of three things is true -- the
// recogniser merged two 1s, or the recogniser dropped a digit, or the grid put this card in
// the wrong slot -- and only one of those is a one-line fix. Counting the disagreements and
// where they fall is what tells them apart.
async function readCard(pagePng, pageW, px, worker, tmp, card, i, page, expected) {
  const rec = { page, idx: i, serial: String(expected) };
  const read = await readSerial(pagePng, card, pageW, px, worker, tmp, i);
  rec.serial_read = read.serial;
  rec.serial_conf = read.serial_conf;
  rec.serial_reads = read.serial_reads;
  rec.serial_unanimous = read.serial_unanimous;
  rec.serial_alternatives = read.serial_alternatives;
  rec.serial_reason = read.serial_reason;
  // True when the ink agrees with the position. A card where it does not is a card whose
  // position is worth checking before its other fields are trusted.
  rec.serial_agrees = read.serial === String(expected);
  Object.assign(rec, await readEpic(pagePng, card, worker, tmp, i));
  Object.assign(rec, await readHouseVoted(pagePng, card, pageW, px, worker, tmp, i));
  return rec;
}

async function main() {
  const pdf = process.argv[2];
  const pages = process.argv[3].split(',').map(Number);
  const nWorkers = Number(process.argv[4] || 6);

  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'rollocr-'));
  const workers = [];
  for (let i = 0; i < nWorkers; i++) workers.push(await createWorker('eng'));
  let next = 0;
  // Takes the next unclaimed card index on this page, or -1 when they are all claimed.
  // The limit is the page's own card count, not a constant: the English roll lays out 26
  // cards on some pages and 30 on others, so a hardcoded 30 handed out indices past the end
  // of the array and crashed the run partway through.
  // Called with this page's card count. A bare take() here made `next < undefined` false,
  // so every worker gave up immediately and the run reported forty-three pages read while
  // writing an empty file. Nothing in the output said so.
  const take = (n) => (next < n ? next++ : -1);
  const all = [];

  // The roll's own card list, and the serial each card must carry by position.
  //
  // The serial is the join key for everything downstream -- the review sheet, the family
  // grouping, the booklet -- and reading it from the card's ink was failing: two adjacent
  // 1s merge into one glyph, so "11" came back "1", "13" came back "3". On page 3 that left
  // 12 of 30 right, and a sheet labelled by those serials scores the wrong cards.
  //
  // The roll does not need to be read to number its own cards. It prints serial 1 on the
  // first card and counts up without interruption across 43 pages, in three columns by ten
  // rows, and the parts end on partial pages -- page 9 carries four cards and stops, page 12
  // twenty-six. So each page's first serial is the sum of the card counts of the pages
  // before it, which is arithmetic over what the grid detector already found and costs no
  // recognition at all.
  //
  // Two things make that safe, and both are why this uses cardPages rather than a formula:
  //
  //   - The card list must be the lattice-completed one. detect() alone misses a card on
  //     page 13, and every serial after it would then be one short -- silently, and by
  //     exactly one, which is the hardest kind of wrong to notice. cardPages reads the grid
  //     through lattice completion, which also sorts the cards into reading order, so index
  //     i really is the i-th card on the page.
  //   - The totals have to land on the roll's own last serial. They do: 43 list pages from
  //     page 3, 1129 cards, last serial 1129. That check is printed every run rather than
  //     asserted once, because it is what makes the arithmetic above trustworthy.
  //
  // The recognised serial is kept, as serial_read, and compared. It is the only field in this
  // reader that can corroborate the positional one, so discarding it would throw away the
  // only evidence that the arithmetic is right -- and where they disagree, the card is
  // exactly the one worth a person's eye.
  const lay = cardPages(pdf);
  const byPage = new Map(lay.pages.map((p) => [p.page, p]));
  console.error(`  roll: ${lay.pages.length} list pages from page ${lay.firstList}, ${lay.total} cards`);
  if (lay.total !== LAST_SERIAL) {
    throw new Error(`the roll's cards total ${lay.total}, but its last serial is ${LAST_SERIAL}. `
      + 'The positional serial cannot be trusted, so nothing is read rather than everything '
      + 'is read wrong.');
  }
  for (const p of pages) {
    if (!byPage.has(p)) {
      throw new Error(`page ${p} is not a list page of this roll. List pages are `
        + `${lay.pages[0].page}-${lay.pages[lay.pages.length - 1].page}.`);
    }
  }
  try {
    for (const p of pages) {
      // The card list and the page image both come from the roll's own layout, so the index
      // used here is the same index the serial is derived from. Taking the cards from a
      // second detect() call would be a second opinion about which cards exist, and it is
      // exactly the disagreement that shifts a serial.
      const { png, cards, first } = byPage.get(p);
      // The label-based house locator needs the page's grayscale pixels to find the ink
      // bands in the card's text column. Read once per page rather than once per card:
      // the page image is shared by every card on it, and this is a whole-page read.
      const page = readGray(png);
      if (cards.length < 20) {
        console.error(`  page ${p}: ${cards.length} cards, serials ${first}-${first + cards.length - 1} (partial page, end of a part)`);
      }
      // Pages are not all full. The roll is printed in parts, and each part ends on a
      // partial page -- page 9 carries "Part Number : 108" and just four cards, with the rest
      // of the sheet blank. Those four are real voters and have to be read, so a short page
      // is announced above rather than refused. An earlier version of this guard rejected any
      // page without 30 cards and killed a 43-page run on page 9; the next version made zero
      // cards fatal, which is the right line, because an empty page means the grid detector
      // has lost the layout and every row after it would be silently wrong.
      next = 0;
      const per = workers.map(() => []);
      await Promise.all(workers.map(async (wk, k) => {
        for (;;) {
          const i = take(cards.length);
          if (i < 0) break;
          per[k].push(await readCard(png, page.w, page.px, wk, tmp, cards[i], i, p, first + i));
        }
      }));
      const recs = per.flat().sort((a, b) => a.idx - b.idx);
      all.push(...recs);
      console.error(`  page ${p}: ${cards.length} cards`);
    }
  } finally {
    await Promise.all(workers.map((w) => w.terminate()));
  }

  // Cross-card reconciliation is off by default and should stay off. It was
  // measured on page 5 and made the house field *worse*: 25/30 became 17/30.
  // The idea -- one door, one spelling, so trust the majority -- is sound, but
  // consecutive house numbers in this roll are one edit apart by nature, so
  // "92-4-4/5" and "93-4-4/1" look like a misread of each other. It snapped five
  // correct "93-4-4/1" cards onto "92-4-4/5" and "93-4-4/1A" onto "93-4-4/1".
  // Reconciling this field safely needs a real notion of which cards share a
  // door, which is exactly what the house number was supposed to supply.
  // See reconcile.js for the attempt and why it was rejected.
  if (process.env.RECONCILE === '1') {
    const s = reconcile(all);
    console.error(`  reconciled: ${s.snapped} snapped, ${s.ambiguous} unsure, ${s.distinct} distinct doors`);
  } else {
    for (const r of all) r.house_final = r.house;
  }

  const out = path.join(__dirname, '..', '..', 'out', `ocr-p${pages.join('-')}.jsonl`);
  fs.writeFileSync(out, all.map((r) => JSON.stringify(r)).join('\n') + '\n');
  console.error(`  wrote ${out}`);
  process.stdout.write(fs.readFileSync(out, 'utf8'));
}

if (require.main === module) main().catch((e) => { console.error(e); process.exit(1); });