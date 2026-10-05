// Reads every serial on every card page of a roll and checks the sequence.
//
// This replaces checking each page against pageLayout's expected values, which turned
// out to be the wrong reference. pageLayout derives each page's first serial by summing
// the card counts of the pages before it, so a single miscounted page shifts every
// serial after it and the check reports hundreds of correct reads as wrong. That is
// not a rounding error in the check -- it is the check inheriting the very assumption
// it was meant to test.
//
// The serial sequence is its own ground truth here, because it is the thing that
// answers the question the whole extraction depends on: has a card been lost? The roll
// numbers its voters consecutively from 1, so reading them in order gives:
//   - a total, which is the headcount the MLA's survey is sized against
//   - every gap, which is a card the grid detector missed
//   - every repeat, which is a card counted twice
// None of those need an assumed per-page offset to detect.
//
// Writes a CSV so the read can be inspected against the printed roll by eye.

const fs = require('fs');
const { createWorker } = require('tesseract.js');
const { pagePng, pageLayout } = require('./layout.js');
const { serialBoxes } = require('./box.js');
const { readSerials } = require('./serialread.js');

async function main() {
  const pdf = process.argv[2];
  const dpi = Number(process.argv[3] || 240);
  const firstPage = Number(process.argv[4] || 3);
  const lastPage = Number(process.argv[5] || 45);
  const outCsv = process.argv[6] || '/home/kedhar/Projects/opencode/opencode-config/projects/voterlistproject/out/serials.csv';

  const lay = pageLayout(pdf, dpi, firstPage, lastPage);
  console.log(`  ${lay.pages.length} card pages, ${lay.total} cards detected\n`);

  const worker = await createWorker('eng', 1, {
    cachePath: process.env.TESSDATA_PREFIX, gzip: false,
  });
  await worker.setParameters({ tessedit_char_whitelist: '0123456789' });

  const rows = [];
  const perPage = [];
  const t0 = Date.now();

  for (const pl of lay.pages) {
    const png = pagePng(pdf, pl.page, dpi);
    // pl.cards comes from pageLayout, which caches the grid after lattice completion,
    // so the recovered card on page 13 is included rather than re-detected here.
    const boxes = serialBoxes(png, { cards: pl.cards });

    // No expected values: this scan establishes the sequence rather than checking it
    // against something already assumed, so the two-attempt repair has no reference to
    // disagree with and is skipped.
    const r = await readSerials(worker, png, boxes, null);
    const vals = r.reads;
    perPage.push({
      page: pl.page, cards: pl.cards.length, boxes: boxes.length,
      read: vals.filter(Boolean).length,
      first: vals[0], last: vals[vals.length - 1],
    });
    boxes.forEach((b, i) => {
      rows.push({ page: pl.page, card: b.card, serial: vals[i] === null ? '' : vals[i] });
    });

    process.stdout.write(`\r  read ${rows.length} cards   `);
  }
  process.stdout.write('\n');

  await worker.terminate();

  console.log(`\n  page  cards  boxes  read   first    last`);
  for (const p of perPage) {
    console.log(`  ${String(p.page).padStart(4)}  ${String(p.cards).padStart(5)}  ${String(p.boxes).padStart(5)}`
      + `  ${String(p.read).padStart(4)}   ${String(p.first).padStart(6)}  ${String(p.last).padStart(6)}`);
  }

  // ---- sequence integrity ------------------------------------------------
  const nums = rows.filter((r) => r.serial !== '').map((r) => Number(r.serial));
  const seen = new Map();
  const repeats = [];
  for (const r of rows) {
    if (r.serial === '') continue;
    const n = Number(r.serial);
    if (seen.has(n)) repeats.push({ n, a: seen.get(n), b: r });
    else seen.set(n, r);
  }

  const gaps = [];
  const sorted = [...nums].sort((a, b) => a - b);
  for (let i = 1; i < sorted.length; i++) {
    if (sorted[i] > sorted[i - 1] + 1) {
      for (let m = sorted[i - 1] + 1; m < sorted[i]; m++) gaps.push(m);
    }
  }

  console.log(`\n  serials read : ${nums.length}`);
  console.log(`  range        : ${sorted[0]}..${sorted[sorted.length - 1]}`);
  console.log(`  distinct     : ${seen.size}`);
  console.log(`  gaps         : ${gaps.length}${gaps.length ? '  -> ' + gaps.slice(0, 40).join(', ') : ''}`);
  console.log(`  repeats      : ${repeats.length}`);
  for (const r of repeats.slice(0, 10)) {
    console.log(`      serial ${r.n} read on page ${r.b.page} card ${r.b.card} and page ${r.a.page} card ${r.a.card}`);
  }
  const blank = rows.filter((r) => r.serial === '');
  console.log(`  unreadable   : ${blank.length}`);
  for (const b of blank.slice(0, 20)) console.log(`      page ${b.page} card ${b.card}`);

  fs.mkdirSync(require('path').dirname(outCsv), { recursive: true });
  fs.writeFileSync(outCsv,
    'page,card,serial\n' + rows.map((r) => `${r.page},${r.card},${r.serial}`).join('\n') + '\n');
  console.log(`\n  wrote ${outCsv}`);
  console.log(`  elapsed ${((Date.now() - t0) / 1000).toFixed(1)} s`);
}

main().catch((e) => { console.error(e); process.exit(1); });