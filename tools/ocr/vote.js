// Measures the house-number field properly: reads each card several times under
// different preprocessing and reports how often the reads agree, and how often
// the majority is right.
//
// A single read is not a measurement. The crop is small, the scan is soft, and
// the watermark has dark cores, so one configuration lands on a slightly
// different answer run to run -- the same card came out "95-4-4/3" at one left
// edge and "11-11-18" at another. Reading the same line under several
// preprocessings and taking the majority is both more accurate and, more
// usefully, self-reporting: when the reads disagree, that card gets flagged for
// a human rather than silently entering the booklet wrong.
//
// Ground truth for page 5 is the house number read off the page image by eye,
// serial 31..60 in card order.

const fs = require('fs');
const path = require('path');
const os = require('os');
const { execFileSync } = require('child_process');
const { createWorker } = require('tesseract.js');
const { detect } = require('./grid.js');
const { cropColumn, LOOK } = require('./house.js');
const F = require('./fields.js');

// Preprocessing variants. They differ in ways that actually change what the
// recogniser sees: the threshold sets how much of the watermark's grey survives
// into black, and the scale changes how much of each glyph's stem survives
// thresholding at all.
const VARIANTS = [
  { scale: 2, thr: 50 },
  { scale: 2, thr: 58 },
  { scale: 2, thr: 62 },
  { scale: 2, thr: 68 },
  { scale: 2, thr: 75 },
  { scale: 3, thr: 55 },
  { scale: 3, thr: 62 },
  { scale: 3, thr: 70 },
  { scale: 4, thr: 62 },
];

async function main() {
  const pagePng = process.argv[2];
  const truthArg = process.argv[3];
  const truth = truthArg ? truthArg.split(',') : null;
  const g = detect(pagePng);
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'vote-'));
  const worker = await createWorker('eng');
  const rows = [];
  try {
    for (let i = 0; i < g.cards.length; i++) {
      // Locate the line once; the variants only differ in how it is rendered.
      const loc = await cropColumn(pagePng, g.cards[i], path.join(tmp, `c${i}.png`), worker);
      const reads = [];
      for (const v of VARIANTS) {
        const f = path.join(tmp, `v${i}-${v.scale}-${v.thr}.png`);
        execFileSync('convert', [pagePng,
          '-crop', `${loc.rect.w}x${loc.rect.h}+${loc.rect.x0}+${loc.rect.y0}`, '+repage',
          '-colorspace', 'Gray', '-resize', `${v.scale * 100}%`,
          '-threshold', `${v.thr}%`,
          '-trim', '+repage', '-bordercolor', 'white', '-border', '24', f]);
        const { data } = await worker.recognize(f, LOOK, { text: true });
        reads.push({ ...v, text: data.text.replace(/\s+/g, '').replace(/^[^0-9A-Za-z]+/, '').replace(/[^0-9A-Za-z]+$/, '') });
      }
      const tally = new Map();
      for (const r of reads) tally.set(r.text, (tally.get(r.text) || 0) + 1);
      let win = '', winN = 0;
      for (const [t, n] of tally) if (n > winN) { win = t; winN = n; }
      rows.push({ idx: i, reads, majority: win, votes: winN, distinct: tally.size });
    }
  } finally {
    await worker.terminate();
  }

  console.log(`  ${VARIANTS.length} reads per card, ${rows.length} cards\n`);
  console.log('  card  maj/votes  distinct  majority        truth');
  let agree = 0, right = 0, rightWhenUnanimous = 0, unanimous = 0;
  rows.forEach((r, i) => {
    const t = truth ? truth[i] : '?';
    if (truth) {
      if (r.majority === t) right++;
      if (r.distinct === 1) { unanimous++; if (r.majority === t) rightWhenUnanimous++; }
    }
    if (r.distinct === 1) agree++;
    console.log(`  ${String(r.idx).padStart(4)}  ${String(r.votes + '/' + VARIANTS.length).padEnd(9)} ${String(r.distinct).padEnd(9)}  ${JSON.stringify(r.majority).padEnd(15)} ${t}`);
  });
  console.log('');
  console.log(`  all reads identical      : ${agree}/${rows.length}`);
  if (truth) {
    console.log(`  majority correct         : ${right}/${rows.length}`);
    console.log(`  unanimous AND correct    : ${rightWhenUnanimous}/${unanimous} of the unanimous`);
    console.log(`  majority correct when not unanimous: ${right - rightWhenUnanimous}/${rows.length - unanimous}`);
    console.log('');
    console.log('  --- every miss ---');
    rows.forEach((r, i) => {
      if (r.majority !== truth[i]) {
        console.log(`    card ${r.idx} (serial ${31 + i}): got ${JSON.stringify(r.majority)} want ${truth[i]}`);
        console.log(`       reads: ${r.reads.map((x) => `${x.text}@${x.scale}/${x.thr}`).join('  ')}`);
      }
    });
  }
}

main().catch((e) => { console.error(e); process.exit(1); });