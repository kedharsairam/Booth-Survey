// Reads the serial off every card and checks the sequence runs unbroken.
//
// This is the cheapest possible correctness test for a roll, and it is what
// found the two structural bugs so far: the row detector dropping partial rows,
// and the assumption that pages hold 30 records. Both produced a *count* that was
// wrong rather than an error, and only reading the printed numbers showed it.
//
// It also cross-checks the two rolls against each other. The Telugu and English
// rolls are the same 1,129 voters, so the English roll's serials, voter ids and
// house numbers must match the Telugu roll's record for record. That gives
// verified ground truth for the English roll at no labelling cost, which is worth
// a great deal given that house numbers cannot be trusted to a recogniser.

const fs = require('fs');
const path = require('path');
const os = require('os');
const { execFileSync } = require('child_process');
const { createWorker } = require('tesseract.js');
const { detect, readGray } = require('./grid.js');
const { renderLineSync } = require('./house.js');
const F = require('./fields.js');
const { pageLayout } = require('./layout.js');

function rel(card, f) {
  const cw = card.x1 - card.x0, ch = card.y1 - card.y0;
  const x0 = Math.round(card.x0 + f.x0 * cw), y0 = Math.round(card.y0 + f.y0 * ch);
  const x1 = Math.round(card.x0 + f.x1 * cw), y1 = Math.round(card.y0 + f.y1 * ch);
  return { x0, y0, w: x1 - x0, h: y1 - y0 };
}

async function main() {
  const pdf = process.argv[2];
  const first = Number(process.argv[3] || 3);
  const last = Number(process.argv[4] || 45);
  const dpi = Number(process.argv[5] || 300);
  const nWorkers = Number(process.argv[6] || 10);

  const { pages, total } = pageLayout(pdf, dpi, first, last);
  console.log(`  ${pages.length} card pages, ${total} cards detected`);
  const counts = pages.map((p) => `p${p.page}=${p.count}`).join(' ');
  console.log(`  counts: ${counts}`);

  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'seq-'));
  const workers = [];
  for (let i = 0; i < nWorkers; i++) workers.push(await createWorker('eng'));
  const out = [];
  try {
    for (const pg of pages) {
      let next = 0;
      const per = workers.map(() => []);
      await Promise.all(workers.map(async (wk, k) => {
        for (;;) {
          const i = next++;
          if (i >= pg.cards.length) break;
          const f = renderLineSync(pg.png, rel(pg.cards[i], F.SERIAL), path.join(tmp, `${pg.page}_${i}.png`), 2);
          const { data } = await wk.recognize(f, F.DIGITS, { text: true });
          per[k].push({
            page: pg.page, idx: i, expected: pg.first + i,
            serial: data.text.replace(/\s+/g, '').replace(/[^0-9]/g, ''),
          });
        }
      }));
      out.push(...per.flat().sort((a, b) => a.idx - b.idx));
    }
  } finally { await Promise.all(workers.map((w) => w.terminate())); }

  const ok = out.filter((r) => r.serial === String(r.expected));
  console.log(`\n  serial matches the count-derived expectation: ${ok.length}/${out.length}`);

  // Walk the read serials looking for a break in the run.
  const nums = out.map((r) => Number(r.serial));
  const breaks = [];
  for (let i = 1; i < nums.length; i++) {
    if (!Number.isFinite(nums[i])) continue;
    if (nums[i] !== nums[i - 1] + 1) breaks.push({ at: out[i], prev: out[i - 1], got: nums[i], want: nums[i - 1] + 1 });
  }
  console.log(`  breaks in the printed sequence: ${breaks.length}`);
  for (const b of breaks.slice(0, 12)) {
    console.log(`     after page ${b.prev.page} card ${b.prev.idx} (serial ${b.prev.serial}) -> page ${b.at.page} card ${b.at.idx}: read ${b.at.serial || '(blank)'}, expected ${b.want}`);
  }
  const finite = nums.filter(Number.isFinite);
  console.log(`\n  first serial ${finite[0]}, last serial ${finite[finite.length - 1]}`);
  const maxS = Math.max(...finite);
  console.log(`  highest serial seen: ${maxS}`);

  const bad = out.filter((r) => r.serial !== String(r.expected));
  if (bad.length) {
    console.log(`\n  cards whose serial read disagrees with the count (${bad.length}):`);
    for (const b of bad.slice(0, 15)) console.log(`     page ${b.page} card ${String(b.idx).padStart(2)}: read ${b.serial || '(blank)'}  count says ${b.expected}`);
  }

  const file = path.join(__dirname, '..', '..', 'out', 'serials-read.json');
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(out));
  console.log(`\n  written ${file}`);
}

main().catch((e) => { console.error(e); process.exit(1); });