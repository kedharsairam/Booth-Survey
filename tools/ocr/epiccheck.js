// Builds the letter and digit atlases for the voter-id reader, then reads the whole roll
// and checks the ids against their check digits.
//
// Where the atlas labels come from, and why that is not circular
// ---------------------------------------------------------------
// The atlases are built from ids that tesseract read, that are the right shape, and that
// pass their own check digit. A misread id fails the check digit about nine times in
// ten, so a read that satisfies all three is very probably a correct read -- these are
// the labels.
//
// The reader that then consumes the atlas sees only pixels. Where it disagrees with a
// label, one of the two is wrong and this run cannot tell which, so the disagreement is
// reported. Agreement is not circular here because the two are produced by different
// mechanisms: one is a general-purpose recogniser constrained only by a regex and a sum,
// the other is nearest-exemplar matching constrained by the same two things but over
// glyph shapes rather than over the whole string.

const { readGray } = require('./grid.js');
const { pagePng, pageLayout } = require('./layout.js');
const { bitmapFromRect, segment, normalise, classify } = require('./digits.js');
const { readEpic, setMaxDistance, segmentId } = require('./epicread.js');
const { VID } = require('./cardread.js');
const { luhnValid } = require('./epic.js');

const ID_RE = /^[A-Z]{3}[0-9]{7}$/;
const ATLAS_PAGES = Number(process.env.ATLAS_PAGES || 6);

// Glyph grid size, settable so the sweep can compare resolutions.
let NORM_SIZE = Number(process.env.NORM_SIZE || 16);

/** The absolute-pixel rectangle of a card's voter-id box. */
function vidRect(card) {
  const cw = card.x1 - card.x0, ch = card.y1 - card.y0;
  return {
    x: Math.round(card.x0 + cw * VID.x0),
    y: Math.round(card.y0 + ch * VID.y0),
    w: Math.round(cw * (VID.x1 - VID.x0)),
    h: Math.round(ch * (VID.y1 - VID.y0)),
  };
}

/** Glyph vectors for one card's id, or null when it does not split into ten. */
function idGlyphs(px, pageW, card) {
  const bmp = bitmapFromRect(px, pageW, vidRect(card));
  if (!bmp) return null;
  const g = segmentId(bmp);
  return g ? { bmp, g } : null;
}

async function main() {
  const normTag = ` [grid ${NORM_SIZE}]`;
  const pdf = process.argv[2];
  const dpi = Number(process.argv[3] || 240);
  const firstPage = Number(process.argv[4] || 3);
  const lastPage = Number(process.argv[5] || 45);
  const only = process.argv[6] ? process.argv[6].split(',').map(Number) : null;

  const lay = pageLayout(pdf, dpi, firstPage, lastPage);

  // ---- one OCR pass to harvest confident ids ------------------------------
  const { createWorker } = require('tesseract.js');
  const worker = await createWorker('eng', 1, {
    cachePath: process.env.TESSDATA_PREFIX, gzip: false,
  });
  await worker.setParameters({ tessedit_char_whitelist: '' });

  const { render } = require('./cardread.js');
  const harvested = [];
  const pagePixels = new Map();

  for (const pl of lay.pages) {
    const png = pagePng(pdf, pl.page, dpi);
    const { w, px } = readGray(png);
    pagePixels.set(pl.page, { png, w, px });
    for (let ci = 0; ci < pl.cards.length; ci++) {
      const c = pl.cards[ci];
      const strip = render(png, c, VID, '/tmp/opencode/voter/ocr/vid.png', { scale: 400 });
      const r = await worker.recognize(strip, { tessedit_pageseg_mode: '8' }, { text: true });
      const id = r.data.text.replace(/[^A-Za-z0-9]/g, '').toUpperCase();
      if (ID_RE.test(id) && luhnValid(id.slice(3))) harvested.push({ page: pl.page, ci, card: c, id });
    }
  }
  await worker.terminate();
  console.log(`  OCR harvested ${harvested.length} well-formed, check-digit-valid ids`);

  // ---- build the atlases from the first pages only ------------------------
  const atlasPages = new Set(lay.pages.slice(0, ATLAS_PAGES).map((p) => p.page));
  const atlasLabel = ATLAS_PAGES >= lay.pages.length ? 'the whole roll' : `the first ${ATLAS_PAGES} pages`;
  const letters = [], digits = [];
  const prefixes = new Set();
  let used = 0;

  for (const h of harvested) {
    if (!atlasPages.has(h.page)) continue;
    const { w, px } = pagePixels.get(h.page);
    const gg = idGlyphs(px, w, h.card);
    if (!gg) continue;
    used++;
    prefixes.add(h.id.slice(0, 3));
    for (let i = 0; i < 10; i++) {
      const vec = normalise(gg.bmp, gg.g[i], NORM_SIZE);
      const entry = { char: h.id[i], vec };
      if (i < 3) letters.push(entry); else digits.push(entry);
    }
  }
  const lc = {}; for (const e of letters) lc[e.char] = (lc[e.char] || 0) + 1;
  const dc = {}; for (const e of digits) dc[e.char] = (dc[e.char] || 0) + 1;
  console.log(`  atlases from ${used} ids on ${atlasLabel}`);
  console.log(`    letters ${letters.length}  ${Object.entries(lc).sort().map(([c, n]) => c + ':' + n).join(' ')}`);
  console.log(`    digits  ${digits.length}  ${Object.entries(dc).sort().map(([c, n]) => c + ':' + n).join(' ')}`);
  const prefixList = [...prefixes].sort();
  console.log(`    prefixes in this roll: ${prefixList.join(', ')}`);

  // ---- calibrate the distance bound by leave-one-out ----------------------
  //
  // The bound is "how far can a correctly-read glyph sit from its nearest exemplar".
  // That is answerable from the atlas itself: hold out each exemplar, match it against
  // the rest, and record the distance. Guessing it does not work -- the first guess of
  // 0.9 rejected 30 of 116 perfectly good ids, because the squared-distance scale here
  // runs into the tens.
  const loo = [];
  for (const atlas of [letters, digits]) {
    for (let i = 0; i < atlas.length; i++) {
      const rest = atlas.filter((_, j) => j !== i);
      const m = classify(atlas[i].vec, rest);
      if (m) loo.push(m.distance);
    }
  }
  loo.sort((a, b) => a - b);
  const p = (f) => loo[Math.floor(loo.length * f)];
  const bound = p(0.999) * 1.5;
  setMaxDistance(bound);
  console.log(`  leave-one-out distance over ${loo.length} exemplars:`);
  console.log(`    p50 ${p(0.5).toFixed(2)}   p90 ${p(0.9).toFixed(2)}   p99 ${p(0.99).toFixed(2)}`
    + `   max ${loo[loo.length - 1].toFixed(2)}`);
  console.log(`    bound set to ${bound.toFixed(2)} (1.5x the worst correct match)`);

  // ---- read every id under the check-digit constraint ---------------------
  const pages = only ? lay.pages.filter((p) => only.includes(p.page)) : lay.pages;
  const counts = { certain: 0, repaired: 0, ambiguous: 0, invalid: 0, noglyph: 0, glyphcount: 0 };
  let agree = 0, total = 0;
  const disagree = [];
  const agreeWith = new Map(harvested.map((h) => [`${h.page}:${h.ci}`, h.id]));

  for (const pl of pages) {
    const { w, px } = pagePixels.get(pl.page);
    for (let ci = 0; ci < pl.cards.length; ci++) {
      const c = pl.cards[ci];
      const r = readEpic(px, w, vidRect(c), { letters, digits }, prefixList, NORM_SIZE);
      if (!r) continue;
      counts[r.status] = (counts[r.status] || 0) + 1;
      total++;

      const truth = agreeWith.get(`${pl.page}:${ci}`);
      if (truth !== undefined && r.id) {
        if (r.id === truth) agree++;
        else disagree.push({ page: pl.page, card: ci, truth, got: r.id, status: r.status });
      }
    }
  }

  console.log(`\n  ids read: ${total}`);
  console.log(`    certain  -- nearest reading satisfied the check digit : ${counts.certain}`);
  console.log(`    repaired -- it failed, exactly one alternative passed  : ${counts.repaired}`);
  console.log(`    ambiguous-- several alternatives passed               : ${counts.ambiguous}`);
  console.log(`    invalid  -- nothing passed                            : ${counts.invalid}`);
  console.log(`    noglyph  -- a glyph too far from any exemplar         : ${counts.noglyph}`);
  console.log(`    glyphcount-- crop does not split into ten             : ${counts.glyphcount}`);
  const resolved = counts.certain + counts.repaired;
  console.log(`\n  resolved with a check-digit proof: ${resolved}/${total} (${(100 * resolved / total).toFixed(1)}%)`);

  console.log(`\n  where an independent OCR read exists to compare against: ${agree + disagree.length} ids`);
  console.log(`    agree : ${agree}`);
  console.log(`    disagree : ${disagree.length}`);
  for (const d of disagree.slice(0, 20)) {
    console.log(`      page ${String(d.page).padStart(2)} card ${String(d.card).padStart(2)}`
      + `  ocr ${d.truth}  template ${d.got}  (${d.status})`);
  }
}

main().catch((e) => { console.error(e); process.exit(1); });