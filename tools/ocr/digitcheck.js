// Reads all 1129 serials by template matching and checks them against the roll's own
// numbering.
//
// The template reader is independent of the positional expectation: it sees only pixels.
// The two agreeing is the check. The atlas is built from the first few pages and applied
// to all of them, so the roll is not merely matched against itself -- a page the atlas
// never saw still has to come out right.
//
// Cards where the pixels disagree with the position are reported, never corrected. A
// serial that the template reads differently from where it sits is either a genuine
// misprint in the roll or a genuine template error, and telling those apart is a
// decision for a person looking at the card.

const { readGray } = require('./grid.js');
const { pagePng, pageLayout } = require('./layout.js');
const { serialBoxes } = require('./box.js');
const { serialBitmap, segment, normalise, classify } = require('./digits.js');

// Pages the atlas is built from. Kept small so that most of the roll is unseen by it.
const ATLAS_PAGES = 5;

function main() {
  const pdf = process.argv[2];
  const dpi = Number(process.argv[3] || 240);
  const firstPage = Number(process.argv[4] || 3);
  const lastPage = Number(process.argv[5] || 45);
  const outCsv = process.argv[6];

  const lay = pageLayout(pdf, dpi, firstPage, lastPage);

  // ---- build the atlas from the first pages -------------------------------
  const atlas = [];
  let labelled = 0, skipped = 0;
  for (const pl of lay.pages.slice(0, ATLAS_PAGES)) {
    const png = pagePng(pdf, pl.page, dpi);
    const { w, px } = readGray(png);
    for (const b of serialBoxes(png, { cards: pl.cards })) {
      const want = String(pl.first + b.card);
      const bmp = serialBitmap(px, w, b);
      if (!bmp) { skipped++; continue; }
      const glyphs = segment(bmp);
      // A card whose pixels do not split into as many glyphs as its serial has digits is
      // not labelled. Forcing it would teach the atlas that two merged marks are one
      // digit, which is how a reader learns to be confidently wrong.
      if (glyphs.length !== want.length) { skipped++; continue; }
      glyphs.forEach((g, i) => atlas.push({ digit: want[i], vec: normalise(bmp, g) }));
      labelled++;
    }
  }
  const counts = {};
  for (const e of atlas) counts[e.char] = (counts[e.char] || 0) + 1;
  console.log(`  atlas: ${atlas.length} glyphs from ${labelled} serials on the first`
    + ` ${ATLAS_PAGES} pages (${skipped} skipped)`);
  console.log(`  per digit: ${Object.entries(counts).sort().map(([d, n]) => d + ':' + n).join('  ')}`);

  // ---- apply it to every page --------------------------------------------
  const rows = [];
  let ok = 0, disagree = [], unreadable = 0;
  const confusion = {};

  for (const pl of lay.pages) {
    const png = pagePng(pdf, pl.page, dpi);
    const { w, px } = readGray(png);
    for (const b of serialBoxes(png, { cards: pl.cards })) {
      const want = String(pl.first + b.card);
      const bmp = serialBitmap(px, w, b);
      const glyphs = bmp ? segment(bmp) : [];
      const digits = [];
      for (const g of glyphs) {
        const m = classify(normalise(bmp, g), atlas);
        digits.push(m ? m.char : '?');
      }
      const got = digits.join('');
      if (got === want) {
        ok++;
      } else if (glyphs.length !== want.length) {
        unreadable++;
        disagree.push({ page: pl.page, card: b.card, want, got, note: `${glyphs.length} glyphs for ${want.length} digits` });
      } else {
        disagree.push({ page: pl.page, card: b.card, want, got, note: 'same glyph count, different digits' });
      }
      if (got.length === want.length) {
        for (let i = 0; i < want.length; i++) {
          const k = want[i] + '->' + got[i];
          confusion[k] = (confusion[k] || 0) + 1;
        }
      }
      rows.push({ page: pl.page, card: b.card, want, got });
    }
  }

  const total = rows.length;
  console.log(`\n  read and matching position : ${ok}/${total}  (${(100 * ok / total).toFixed(2)}%)`);
  console.log(`  glyph count wrong         : ${unreadable}`);
  console.log(`  disagreeing with position : ${disagree.length}`);

  const errors = Object.entries(confusion).filter(([k]) => k[0] !== k[3]);
  if (errors.length) {
    console.log('\n  digit confusions (expected->got):');
    for (const [k, n] of errors.sort((a, b) => b[1] - a[1])) console.log(`    ${k}  x${n}`);
  }

  if (disagree.length) {
    console.log(`\n  disagreements (${disagree.length}) -- reported, not corrected:`);
    for (const d of disagree.slice(0, 30)) {
      console.log(`    page ${String(d.page).padStart(2)} card ${String(d.card).padStart(2)}`
        + `  position ${d.want.padStart(4)}  pixels ${d.got.padStart(5)}   ${d.note}`);
    }
    if (disagree.length > 30) console.log(`    ... and ${disagree.length - 30} more`);
  }

  if (outCsv) {
    const fs = require('fs');
    const path = require('path');
    fs.mkdirSync(path.dirname(outCsv), { recursive: true });
    fs.writeFileSync(outCsv, 'page,card,position_serial,template_serial\n'
      + rows.map((r) => `${r.page},${r.card},${r.want},${r.got}`).join('\n') + '\n');
    console.log(`\n  wrote ${outCsv}`);
  }
}

main();