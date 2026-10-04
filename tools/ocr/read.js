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

// epic -- the voter id, always 3 letters then 7 digits, e.g. "TST000004".
//
// Read as two crops rather than one string. A single whitelist that admits both
// letters and digits also admits the confusions that matter: reading the whole
// value as digits turned the prefix's "D" into a "0" and produced eight digits
// on 2 of 30 cards ("HXD02173334" for TST000004) off crops that were visibly
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

async function readHouseVoted(pagePng, card, worker, tmp, i) {
  const { cropColumn } = require('./house.js');
  const loc = await cropColumn(pagePng, card, path.join(tmp, `col${i}.png`), worker);
  if (!loc.line) return { house: '', house_located: false };
  if (!loc.rect) return { house: clean(loc.line.text), house_located: true, house_precise: false };

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
    house_votes: votes, house_reads: reads.length, house_unanimous: tally.size === 1,
    house_alternatives: [...tally.entries()].sort((a, b) => b[1] - a[1]),
    house_rect: [loc.rect.x0, loc.rect.y0, loc.rect.w, loc.rect.h],
  };
}

// `page` and `idx` together locate the card on the page, which is what a manual
// check needs. The serial is recorded alongside, and because the roll prints
// serials in ascending order, the expected value for card i of page p is
// (p - FIRST_LIST_PAGE) * 30 + 1 + i -- so every serial read can be verified
// against ground truth that costs nothing to derive.
async function readCard(pagePng, worker, tmp, card, i, page) {
  const rec = { page, idx: i };
  Object.assign(rec, await readSerial(pagePng, card, worker, tmp, i));
  Object.assign(rec, await readEpic(pagePng, card, worker, tmp, i));
  Object.assign(rec, await readHouseVoted(pagePng, card, worker, tmp, i));
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
  const take = () => (next < 30 ? next++ : -1);
  const all = [];
  try {
    for (const p of pages) {
      const png = renderPage(pdf, p, dpi, path.join(tmp, 'pg'));
      const g = detect(png);
      if (g.cards.length !== 30) throw new Error(`page ${p}: expected 30 cards, got ${g.cards.length}`);
      next = 0;
      const per = workers.map(() => []);
      await Promise.all(workers.map(async (wk, k) => {
        for (;;) {
          const i = take();
          if (i < 0) break;
          per[k].push(await readCard(png, wk, tmp, g.cards[i], i, p));
        }
      }));
      const recs = per.flat().sort((a, b) => a.idx - b.idx);
      all.push(...recs);
      console.error(`  page ${p}: 30 cards`);
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