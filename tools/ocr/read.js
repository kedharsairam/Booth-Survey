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
const { execFileSync } = require('child_process');
const { createWorker } = require('tesseract.js');
const { detect, readGray } = require('./grid.js');
const { readGray: rg } = require('./grid.js');
const { renderLine, LOOK } = require('./house.js');
const { textLines } = require('./card.js');
const { parseLine } = require('./cardread.js');
const { splitAt } = require('./glyphs.js');
const { reconcile } = require('./reconcile.js');
const F = require('./fields.js');

const clean = (s) => s.replace(/\s+/g, '');

// Renders a PDF page to PNG. 300 dpi is well past the point where these glyphs
// gain detail -- the source is a scan, so more dpi only magnifies interpolation
// artefacts in the watermark.
function renderPage(pdf, pageNo, dpi, outPrefix) {
  execFileSync('pdftoppm', ['-f', String(pageNo), '-l', String(pageNo),
    '-r', String(dpi), '-png', '-gray', pdf, outPrefix], { maxBuffer: 1 << 30 });
  const base = `${outPrefix}-${String(pageNo).padStart(2, '0')}`;
  for (const c of ['', '.png']) if (fs.existsSync(base + c)) return base + c;
  throw new Error(`pdftoppm produced no file for page ${pageNo}`);
}

function rel(card, f) {
  const cw = card.x1 - card.x0, ch = card.y1 - card.y0;
  const x0 = Math.round(card.x0 + f.x0 * cw), y0 = Math.round(card.y0 + f.y0 * ch);
  const x1 = Math.round(card.x0 + f.x1 * cw), y1 = Math.round(card.y0 + f.y1 * ch);
  return { x0, y0, w: x1 - x0, h: y1 - y0 };
}

// serial -- a boxed number, one pass, digits only. Measured 30/30 exact over
// page 5 at PSM 8 against 21/30 at PSM 7.
async function readSerial(pagePng, card, worker, tmp, i) {
  const f = await renderLine(pagePng, rel(card, F.SERIAL), path.join(tmp, `s${i}.png`));
  const { data } = await worker.recognize(f, F.DIGITS, { text: true });
  return { serial: clean(data.text).replace(/[^0-9]/g, ''), serial_conf: Math.round(data.confidence) };
}

// epic -- the voter id, always 3 letters then 7 digits, e.g. "ZZZ9840000".
//
// Read as two crops rather than one string. A single whitelist that admits both
// letters and digits also admits the confusions that matter: reading the whole
// value as digits turned the prefix's "D" into a "0" and produced eight digits
// on 2 of 30 cards ("HXD02173334" for ZZZ9840000) off crops that were visibly
// clean ten-character strings. Truncating the letters to three left a second
// failure ("HXD24641264"), so the string is cut *geometrically* instead: see
// glyphs.js. Each half is then read with a whitelist that fits it, which is a
// problem tesseract is good at.
async function readEpic(pagePng, card, worker, tmp, i) {
  const whole = await renderLine(pagePng, rel(card, F.EPIC), path.join(tmp, `e${i}.png`));
  const img = readGray(whole);
  const cut = splitAt(img, 10, 3);
  if (cut === null) return { epic: '', epic_ok: false, epic_reason: 'no ink in the voter-id crop' };

  // Split the already-normalised image, so `cut` is already in this file's own
  // coordinates -- no inverse transform needed, and the two halves stay at the
  // same scale and threshold the cut was measured at.
  const pre = slice(whole, 0, cut, path.join(tmp, `ep${i}.png`));
  const dig = slice(whole, cut, img.w, path.join(tmp, `ed${i}.png`));

  const P = await worker.recognize(pre, F.UPPER, { text: true });
  const D = await worker.recognize(dig, F.DIGITS, { text: true });
  const prefix = clean(P.data.text).replace(/[^A-Z]/g, '');
  const digits = clean(D.data.text).replace(/[^0-9]/g, '');
  const epic = prefix + digits;
  return {
    epic, epic_prefix: prefix, epic_digits: digits,
    epic_ok: /^[A-Z]{3}[0-9]{7}$/.test(epic),
  };
}

// Renders a horizontal slice of an already-cropped image file. The cut lands
// mid-gap, so no padding is wanted -- padding past the cut pulls a sliver of the
// next glyph in and the read gains a spurious leading digit.
function slice(file, x0, x1, out) {
  const a = Math.max(0, Math.round(x0));
  const b = Math.min(readGray(file).w, Math.round(x1));
  execFileSync('convert', [file, '-crop', `${Math.max(8, b - a)}x100000+${a}+0`, '+repage',
    '-bordercolor', 'white', '-border', '16', out]);
  return out;
}

// house -- located by content, then read several times and voted. See house.js.
//
// The vote exists because no single configuration is reliable on this field.
// The residual errors are shape confusions rather than missing ink -- "95-4-4/3"
// read as "17-1-15", "96-4-4/4" as "95-6-6/14", "95-4-4/3" as "95-6-6/18" -- and they
// do not all appear under every configuration. So the variants vary both the
// preprocessing (threshold sets how much of the watermark's grey survives into
// black; scale changes how much of each glyph's stem survives thresholding) and
// the page-segmentation mode, then the majority wins. Disagreement is recorded
// rather than hidden, so an uncertain card can be flagged for a human -- which
// matters here because this field decides who counts as one family.
const HOUSE_VARIANTS = [
  { scale: 2, thr: 50, psm: 8 }, { scale: 2, thr: 58, psm: 8 },
  { scale: 2, thr: 62, psm: 8 }, { scale: 2, thr: 68, psm: 8 },
  { scale: 2, thr: 75, psm: 8 }, { scale: 2, thr: 62, psm: 7 },
  { scale: 2, thr: 62, psm: 13 }, { scale: 3, thr: 62, psm: 8 },
  { scale: 3, thr: 55, psm: 8 },
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
  let house = '', votes = 0;
  for (const [t, n] of tally) if (n > votes) { votes = n; house = t; }
  return {
    house, house_located: true, house_precise: true,
    house_labelled: loc.value,
    house_votes: votes, house_reads: reads.length, house_unanimous: tally.size === 1,
    house_alternatives: [...tally.entries()].sort((a, b) => b[1] - a[1]),
    house_rect: [loc.rect.x0, loc.rect.y0, loc.rect.w, loc.rect.h],
  };
}

// `page` and `idx` together locate the card on the page, which is what a manual
// check needs. The serial is recorded alongside, and because the roll prints
// serials in ascending order, consecutive cards on consecutive pages carry
// consecutive serials -- so every serial read can be verified against its
// neighbours, which costs nothing to derive.
async function readCard(pagePng, pageW, px, worker, tmp, card, i, page) {
  const rec = { page, idx: i };
  Object.assign(rec, await readSerial(pagePng, card, worker, tmp, i));
  Object.assign(rec, await readEpic(pagePng, card, worker, tmp, i));
  Object.assign(rec, await readHouseVoted(pagePng, card, pageW, px, worker, tmp, i));
  return rec;
}

async function main() {
  const pdf = process.argv[2];
  const pages = process.argv[3].split(',').map(Number);
  const dpi = Number(process.argv[4] || 300);
  const nWorkers = Number(process.argv[5] || 6);

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
  try {
    for (const p of pages) {
      const png = renderPage(pdf, p, dpi, path.join(tmp, 'pg'));
      const g = detect(png);
      // The label-based house locator needs the page's grayscale pixels to find the ink
      // bands in the card's text column. Read once per page rather than once per card:
      // the page image is shared by every card on it, and this is a whole-page read.
      const page = rg(png);
      // Pages are not all full. The roll is printed in parts, and each part ends on a
        // partial page -- page 9 carries "Part Number : 108" and just four cards, serials
        // 177 to 180, with the rest of the sheet blank. Those four are real voters and have
        // to be read, so the floor is one card, not twenty: an earlier version of this guard
        // refused the page and killed a 43-page run over it.
        //
        // What it still refuses is zero. An empty page means the grid detector has lost the
        // card layout, and every row read after that would be silently wrong, so it is worth
        // stopping for. A short page is announced rather than treated as an error.
        if (g.cards.length === 0) {
          throw new Error(`page ${p}: no cards found, so the grid detector has lost the card
            layout. Everything read from here would be wrong.`);
        }
        if (g.cards.length < 20) {
          console.error(`  page ${p}: ${g.cards.length} cards (partial page, end of a part)`);
        }
      next = 0;
      const per = workers.map(() => []);
      await Promise.all(workers.map(async (wk, k) => {
        for (;;) {
          const i = take(g.cards.length);
          if (i < 0) break;
          per[k].push(await readCard(png, page.w, page.px, wk, tmp, g.cards[i], i, p));
        }
      }));
      const recs = per.flat().sort((a, b) => a.idx - b.idx);
      all.push(...recs);
      console.error(`  page ${p}: ${g.cards.length} cards`);
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