// Extracts every card's fields from the English electoral roll.
//
// Per card this reads: voter serial, voter id, full name, father's name, house number,
// age, gender. Serial comes from the template reader in digits.js, the rest from the
// label-anchored reader in cardread.js.
//
// Nothing here is guessed. A field that cannot be read is written empty and counted in
// the summary, because a plausible-looking wrong house number invents a household and a
// wrong name is harder to notice than a blank one. The one repair applied is to the voter
// id, and only when the check digit identifies the error and a single substitution fixes
// it -- the id has a check digit, so it can be proven right rather than assumed.
//
// Outputs a CSV for review. The name field is expected to need correction on a small
// fraction of cards: names are open-vocabulary, so unlike a voter id or a serial they
// have no check digit to catch a misread, and the accuracy that can be measured on them
// is not the same as the accuracy that can be guaranteed.

const fs = require('fs');
const path = require('path');
const { createWorker } = require('tesseract.js');
const { readGray } = require('./grid.js');
const { pagePng, pageLayout } = require('./layout.js');
const { serialBoxes } = require('./box.js');
const { serialBitmap, segment, normalise, classify } = require('./digits.js');
const { readCard } = require('./cardread.js');
const { check } = require('./epic.js');

const OUT = path.join(__dirname, '..', '..', 'out');

// Pages the serial atlas is built from. Kept small so most of the roll is unseen by it.
const ATLAS_PAGES = 5;

async function main() {
  const pdf = process.argv[2];
  const dpi = Number(process.argv[3] || 240);
  const firstPage = Number(process.argv[4] || 3);
  const lastPage = Number(process.argv[5] || 45);
  const only = process.argv[6] ? process.argv[6].split(',').map(Number) : null;
  const outCsv = process.argv[7] || path.join(OUT, 'extracted.csv');

  const lay = pageLayout(pdf, dpi, firstPage, lastPage);
  const pages = only ? lay.pages.filter((p) => only.includes(p.page)) : lay.pages;

  // ---- serial atlas, from the first pages of the roll ---------------------
  const atlas = [];
  for (const pl of lay.pages.slice(0, ATLAS_PAGES)) {
    const { w, px } = readGray(pagePng(pdf, pl.page, dpi));
    for (const b of serialBoxes(pagePng(pdf, pl.page, dpi), { cards: pl.cards })) {
      const want = String(pl.first + b.card);
      const bmp = serialBitmap(px, w, b);
      if (!bmp) continue;
      const glyphs = segment(bmp);
      if (glyphs.length !== want.length) continue;
      glyphs.forEach((g, i) => atlas.push({ digit: want[i], vec: normalise(bmp, g) }));
    }
  }
  console.log(`  serial atlas: ${atlas.length} glyphs from ${ATLAS_PAGES} pages`);

  const worker = await createWorker('eng', 1, {
    cachePath: process.env.TESSDATA_PREFIX, gzip: false,
  });

  const rows = [];
  const problems = { serial: [], voter_id: [], house: [], name: [], missing: [] };
  const t0 = Date.now();
  let idRepaired = 0;

  for (const pl of pages) {
    const png = pagePng(pdf, pl.page, dpi);
    const { w, px } = readGray(png);
    const boxes = serialBoxes(png, { cards: pl.cards });

    for (let cardIndex = 0; cardIndex < pl.cards.length; cardIndex++) {
      const c = pl.cards[cardIndex];
      // Serial, by template match on the box's own interior. serialBoxes keys boxes by
      // the card's position on the page, which is this loop's index.
      const box = boxes.find((b) => b.card === cardIndex);
      let serial = '';
      if (box) {
        const bmp = serialBitmap(px, w, box);
        const glyphs = bmp ? segment(bmp) : [];
        serial = glyphs.map((g) => {
          const m = classify(normalise(bmp, g), atlas);
          return m ? m.digit : '';
        }).join('');
      }
      if (!/^\d+$/.test(serial)) problems.serial.push({ page: pl.page, card: cardIndex, got: serial });

      const r = await readCard(worker, png, w, px, c);
      const f = r.fields;

      // Voter id, validated by its check digit.
      //
      // check() returns { ok, reason, repair } and already offers exactly the repair
      // wanted: a corrected id when exactly one substitution makes the number validate,
      // and nothing otherwise. It is used rather than a hand-rolled Luhn call because
      // luhnValid() takes the seven digits alone, not the whole id -- passing it the
      // letters marks every id invalid, which is exactly the bug this replaced.
      let vid = f.voter_id || '';
      let vidNote = '';
      const v = check(vid);
      if (v.ok) {
        // nothing to note
      } else if (v.repair) {
        vid = v.repair;
        vidNote = 'repaired';
        idRepaired++;
      } else {
        vidNote = v.reason;
        problems.voter_id.push({ page: pl.page, card: cardIndex, vid, reason: v.reason });
      }

      const row = {
        page: pl.page, card: cardIndex,
        serial, voter_id: vid,
        name: f.name || '', father: f.father || '',
        house: f.house || '', age: f.age || '', gender: f.gender || '',
      };
      if (vidNote && vidNote !== 'repaired') row.voter_id_note = vidNote;
      if (!row.house) problems.house.push({ page: pl.page, card: cardIndex });
      if (!row.name) problems.name.push({ page: pl.page, card: cardIndex });
      if (!row.voter_id) problems.missing.push({ page: pl.page, card: cardIndex });
      if (r.unattributed.length) row.unattributed = r.unattributed.join(' | ');
      rows.push(row);
    }
    process.stdout.write(`\r  ${rows.length} cards   `);
  }
  process.stdout.write('\n');
  await worker.terminate();

  // ---- write --------------------------------------------------------------
  fs.mkdirSync(path.dirname(outCsv), { recursive: true });
  const cols = ['page', 'card', 'serial', 'voter_id', 'name', 'father', 'house', 'age', 'gender',
    'voter_id_note', 'unattributed'];
  fs.writeFileSync(outCsv, cols.join(',') + '\n'
    + rows.map((r) => cols.map((k) => {
      const v = r[k] === undefined ? '' : String(r[k]);
      return /[",\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v;
    }).join(',')).join('\n') + '\n');

  const filled = (k) => rows.filter((r) => r[k]).length;
  console.log(`\n  cards written : ${rows.length}`);
  for (const k of ['serial', 'voter_id', 'name', 'father', 'house', 'age', 'gender']) {
    const n = filled(k);
    console.log(`    ${k.padEnd(9)} ${String(n).padStart(5)} / ${rows.length}  (${(100 * n / rows.length).toFixed(1)}%)`);
  }
  console.log(`  voter ids repaired by check digit : ${idRepaired}`);
  console.log(`  unreadable serials : ${problems.serial.length}`);
  console.log(`  unreadable voter ids : ${problems.voter_id.length}`);
  console.log(`  missing house numbers : ${problems.house.length}`);
  console.log(`  missing names : ${problems.name.length}`);
  console.log(`\n  wrote ${outCsv}`);
  console.log(`  elapsed ${((Date.now() - t0) / 1000).toFixed(1)} s  (${((Date.now() - t0) / rows.length).toFixed(0)} ms per card)`);
}

main().catch((e) => { console.error(e); process.exit(1); });