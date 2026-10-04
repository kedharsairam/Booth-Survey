// One OCR pass over the whole roll, cached to disk.
//
// Everything downstream needs the same recognised text twice, and recognising it is by
// far the expensive part -- around 820 ms per card, which is about fifteen minutes for the
// roll. The two things that need it are the labelled exemplars for the voter-id template
// reader and the vocabulary for the name lexicon, neither of which is known until the
// whole document has been read once. So it is read once, here, and cached.
//
// This pass stores text only. The template reader works on pixels, but the page images are
// already cached as PNGs, so it re-reads them for free rather than duplicating megabytes
// of glyph bitmaps into a JSON file.
//
// Output is JSON Lines, one card per line, appended as each page finishes. A run
// interrupted at page 30 resumes from page 31 rather than starting again, which matters
// because a fifteen-minute pass will get interrupted.

const fs = require('fs');
const path = require('path');
const { createWorker } = require('tesseract.js');
const { readGray } = require('./grid.js');
const { pagePng, pageLayout } = require('./layout.js');
const { readCard } = require('./cardread.js');

const OUT = path.join(__dirname, '..', '..', 'out');

async function main() {
  const pdf = process.argv[2];
  const dpi = Number(process.argv[3] || 240);
  const firstPage = Number(process.argv[4] || 3);
  const lastPage = Number(process.argv[5] || 45);
  const outPath = process.argv[6] || path.join(OUT, 'harvest.jsonl');

  fs.mkdirSync(path.dirname(outPath), { recursive: true });

  const lay = pageLayout(pdf, dpi, firstPage, lastPage);
  const done = new Set();
  if (fs.existsSync(outPath)) {
    for (const line of fs.readFileSync(outPath, 'utf8').split('\n')) {
      if (!line.trim()) continue;
      try { done.add(Number(JSON.parse(line).page)); } catch { /* skip a torn line */ }
    }
  }
  const todo = lay.pages.filter((p) => !done.has(p.page));
  console.log(`  ${lay.pages.length} card pages, ${lay.total} cards`);
  console.log(`  already harvested: ${done.size} pages; reading ${todo.length} more`);
  if (!todo.length) { console.log('  nothing to do'); return; }

  const worker = await createWorker('eng', 1, {
    cachePath: process.env.TESSDATA_PREFIX, gzip: false,
  });

  const t0 = Date.now();
  let cards = 0;

  for (const pl of todo) {
    const png = pagePng(pdf, pl.page, dpi);
    const { w, px } = readGray(png);

    const lines = [];
    for (let ci = 0; ci < pl.cards.length; ci++) {
      const r = await readCard(worker, png, w, px, pl.cards[ci]);
      lines.push(JSON.stringify({
        page: pl.page,
        card: ci,
        serial: pl.first + ci,
        vid: r.fields.voter_id || '',
        name: r.fields.name || '',
        father: r.fields.father || '',
        house: r.fields.house || '',
        age: r.fields.age || '',
        gender: r.fields.gender || '',
        raw: r.lines.map((l) => l.raw),
        unattributed: r.unattributed,
      }));
      cards++;
    }

    // Appended per page, so an interruption costs at most one page of work.
    fs.appendFileSync(outPath, lines.join('\n') + '\n');
    const el = (Date.now() - t0) / 1000;
    const rate = cards / Math.max(el, 0.001);
    process.stdout.write(`\r  page ${pl.page}: ${cards} cards, ${rate.toFixed(1)}/s,`
      + ` ${((lay.total - cards) / rate / 60).toFixed(1)} min left   `);
  }
  process.stdout.write('\n');

  await worker.terminate();
  console.log(`  wrote ${outPath}`);
  console.log(`  ${cards} cards in ${((Date.now() - t0) / 1000 / 60).toFixed(1)} min`);
}

/** Reads a harvest file back into rows, skipping any torn final line. */
function readHarvest(file) {
  return fs.readFileSync(file, 'utf8').split('\n')
    .filter((l) => l.trim())
    .map((l) => { try { return JSON.parse(l); } catch { return null; } })
    .filter(Boolean);
}

module.exports = { readHarvest, OUT };

if (require.main === module) {
  main().catch((e) => { console.error(e); process.exit(1); });
}