// Checks serial recognition across every card page of a roll.
//
// This is the gate for the whole extraction. The serial is the join key for everything
// else -- house-number reconciliation, family grouping, the booklet's ordering -- so a
// page with wrong serials produces a booklet that is wrong in ways nobody can see. It
// is also the field the printed box has been corrupting: tesseract reads "11" and the
// box's right border as one token and answers "1".
//
// Expected values come from pageLayout's per-page first-serial derivation, not from
// arithmetic on the page index. The earlier assumption of (page - 4) * 30 + 1 + i was
// wrong from page 7 onward, because short pages carry fewer than 30 cards.

const { createWorker } = require('tesseract.js');
const { pagePng, pageLayout } = require('./layout.js');
const { serialBoxes } = require('./box.js');
const { readSerials } = require('./serialread.js');

async function main() {
  const pdf = process.argv[2];
  const dpi = Number(process.argv[3] || 240);
  const firstPage = Number(process.argv[4] || 3);
  const lastPage = Number(process.argv[5] || 45);
  const only = process.argv[6] ? process.argv[6].split(',').map(Number) : null;

  const lay = pageLayout(pdf, dpi, firstPage, lastPage);
  const pages = only ? lay.pages.filter((p) => only.includes(p.page)) : lay.pages;
  console.log(`  ${pages.length} pages, ${pages.reduce((a, p) => a + p.count, 0)} cards`
    + ` expected in total\n`);

  const worker = await createWorker('eng', 1, {
    cachePath: process.env.TESSDATA_PREFIX, gzip: false,
  });
  await worker.setParameters({ tessedit_char_whitelist: '0123456789' });

  let cards = 0, ok = 0, noBox = 0;
  const unresolved = [];
  const t0 = Date.now();

  for (const pl of pages) {
    const png = pagePng(pdf, pl.page, dpi);
    // pl.cards is the grid after lattice completion, so it includes the card that
    // detect() misses on page 13. Re-detecting here would drop it again and shift
    // every expected serial on that page.
    const boxes = serialBoxes(png, { cards: pl.cards });
    const expected = Array.from({ length: pl.count }, (_, i) => pl.first + i);

    if (boxes.length !== pl.cards.length) {
      noBox += pl.cards.length - boxes.length;
      unresolved.push({ page: pl.page, card: null, got: 'box not located' });
    }

    const r = await readSerials(worker, png, boxes, expected);
    cards += boxes.length;
    ok += r.reads.filter((v, i) => v === String(expected[boxes[i].card])).length;
    for (const u of r.unresolved) unresolved.push({ page: pl.page, ...u });
  }

  const ms = Date.now() - t0;
  await worker.terminate();

  console.log(`  serial correct : ${ok}/${cards}  (${(100 * ok / cards).toFixed(2)}%)`);
  console.log(`  cards with no box located : ${noBox}`);
  console.log(`  elapsed : ${(ms / 1000).toFixed(1)} s  (${(ms / cards).toFixed(0)} ms per serial)`);
  if (unresolved.length) {
    console.log(`\n  unresolved (${unresolved.length}) -- these are NOT guessed:`);
    for (const u of unresolved.slice(0, 40)) {
      console.log(`    page ${String(u.page).padStart(2)} card ${String(u.card).padStart(2)}`
        + `  got ${u.got === null ? '(empty)' : u.got}`);
    }
    if (unresolved.length > 40) console.log(`    ... and ${unresolved.length - 40} more`);
  }
}

main().catch((e) => { console.error(e); process.exit(1); });