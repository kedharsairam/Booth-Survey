// Page layout for the roll: which pages carry cards, how many, and which serial
// each one starts at.
//
// This is the single source of truth for "which card is which voter", and it is
// derived from the detected card counts rather than from any assumption about
// page length. That distinction is the whole point.
//
// The roll does not hold 30 records per page. It holds 1129 records across 43
// list pages, and the counts run 30, 30, 26, 30, 30, 30, 4, 30, ... 18 -- short
// pages fall at part boundaries. The first implementation derived a card's serial
// from (page - 4) * 30 + 1 + index, which is right for the first two pages and
// wrong by four records from page 7 onward and by more further on. Nothing
// complained: the template matcher simply trained on shifted labels and managed
// 30% on held-out serials while its averaged digit templates still looked
// plausible to the eye.
//
// Deriving the offset from the counts keeps this correct by construction. The
// independent check is that the total lands on the roll's own last serial, 1129,
// and that spot checks of the short pages agree: page 10 ends on serial 180,
// page 46 opens on 1112 and holds 18 cards.

const fs = require('fs');
const path = require('path');
const os = require('os');
const { execFileSync } = require('child_process');
const { detect } = require('./grid.js');
const { completeLattice } = require('./lattice.js');

const FIRST_LIST_PAGE = 4;
const LAST_LIST_PAGE = 46;
const CACHE = path.join(os.tmpdir(), 'rollcache');

// A short fingerprint of the document, used to keep two rolls' caches apart.
//
// The cache is keyed by page number, and the Telugu and English rolls both start
// their card pages at low numbers -- so without this the English roll would
// silently read back the Telugu roll's cached card boxes and appear to work,
// returning Telugu data.
function docKey(pdf) {
  const st = fs.statSync(pdf);
  return `${path.basename(pdf).replace(/[^A-Za-z0-9]+/g, '_').slice(0, 40)}-${st.size}`;
}

// Page images are cached on disk. Rendering a page costs more than analysing it
// and nothing here changes the pixels, so every run after the first is cheap.
//
// Native extraction, not a page render. Each page is a single JPEG XObject of
// 1983 x 2806 px, which is exactly A4 at 240 dpi. Asking poppler to render at 300
// dpi does not add information -- it interpolates 4.17x, producing a 96-megabyte
// image from a 5.5-megabyte original, softening the hard edges that binarisation
// and glyph segmentation depend on. pdfimages hands back the original pixels.
// Measured on this roll: grid detection 215 ms native against 2145 ms at 300 dpi,
// and the serial digits are 18 px tall rather than an interpolated 75.
function pagePng(pdf, page, dpi = 240) {
  const dir = path.join(CACHE, docKey(pdf));
  fs.mkdirSync(dir, { recursive: true });
  const base = path.join(dir, `p${page}`);
  if (fs.existsSync(base + '.png')) return base + '.png';
  // pdfimages numbers its output, so the single page extracted here lands at -000.
  execFileSync('pdfimages', ['-f', String(page), '-l', String(page), '-png', pdf, base],
    { maxBuffer: 1 << 30 });
  const extracted = base + '-000.png';
  if (!fs.existsSync(extracted)) throw new Error(`pdfimages produced nothing for page ${page}`);
  fs.renameSync(extracted, base + '.png');
  return base + '.png';
}

// Detected card boxes per page, cached on disk.
//
// detect() has to read a 300 dpi page -- an 8263 x 11692 grayscale image, roughly
// 96 MB -- and doing that for all 43 list pages costs about fifteen minutes, which
// dominated every evaluation run. The boxes never change, so they are written once.
//
// What is cached is the grid AFTER lattice completion, not what detect() returned.
// detect() misses a card whose ink does not look like the rest -- it misses exactly one
// on the English roll, on page 13 -- and caching the raw detection would bake that loss
// in permanently and hand it to every downstream consumer. Cache entries written before
// completion existed carry no `added` field and are recomputed, so the fix reaches
// consumers that never re-run detection.
const BOX_CACHE = () => path.join(CACHE, 'layout.json');

function detectCached(pdf, page, dpi) {
  const dir = path.join(CACHE, docKey(pdf));
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, 'layout.json');
  const all = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : {};
  if (all[page] && all[page].added !== undefined) return all[page];
  const png = pagePng(pdf, page, dpi);
  const g = detect(png);
  const done = completeLattice(png, g);
  all[page] = { w: g.w, h: g.h, cards: done.cards, added: done.added };
  fs.writeFileSync(file, JSON.stringify(all));
  return all[page];
}

// Ordered list of card pages, each with the serial its first card must carry.
//
// The first and last card pages are parameters rather than constants because the
// two rolls number them differently: the Telugu roll carries cards on pages 4-46,
// the English roll on 3-45. Both hold 43 pages and both total 1129 records.
function pageLayout(pdf, dpi = 240, firstPage = FIRST_LIST_PAGE, lastPage = LAST_LIST_PAGE) {
  const out = [];
  let offset = 0;
  for (let p = firstPage; p <= lastPage; p++) {
    let cards;
    try {
      cards = detectCached(pdf, p, dpi).cards;
    } catch (err) {
      if (process.env.LAYOUT_DEBUG) console.error('  page ' + p + ': ' + err.message);
      continue;                                   // not a card page
    }
    if (!cards.length) continue;
    out.push({ page: p, png: pagePng(pdf, p, dpi), cards, count: cards.length, first: offset + 1 });
    offset += cards.length;
  }
  return { pages: out, total: offset };
}

module.exports = { pageLayout, pagePng, docKey, FIRST_LIST_PAGE, LAST_LIST_PAGE, CACHE };

if (require.main === module) {
  const { pages, total } = pageLayout(process.argv[2], Number(process.argv[3] || 300));
  console.log(`  ${pages.length} list pages, ${total} cards`);
  console.log(`  ${pages.map((p) => `p${p.page}=${p.count}@${p.first}`).join('  ')}`);
}