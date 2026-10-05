// Tallies which field labels the roll actually prints.
//
// The relation-line bug was found by reading one card. Reading one card tells you what
// that card prints; it does not tell you what the other 1128 print. A married woman's
// card says "Husbands Name" -- does a widow's say "Husbands Name", "Father's Name", or
// something else again? A label form that goes unrecognised does not merely get missed,
// it falls through to the generic name matcher and overwrites the person's own name,
// which is the bug this whole thing is about.
//
// So: crop the label strip off every card on a page, recognise just the label, and count
// the distinct forms. Cheap, and it replaces a guess with a measurement.
//
// Usage: node labelscan.js <page.png> [more pages...]

const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');
const { detect } = require('./grid.js');
const F = require('./fields.js');

async function main() {
  const pages = process.argv.slice(2);
  if (!pages.length) { console.error('usage: node labelscan.js <page.png>...'); process.exit(2); }
  const { createWorker } = require('tesseract.js');
  const worker = await createWorker('eng');
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'labelscan-'));

  // Normalised form, so "Husband's Name", "Husbands Name:" and "HUSBANDS NAME" all
  // collapse to one bucket. Enumerating the variants is the point.
  const norm = (s) => s.toLowerCase().replace(/[^a-z ]/g, ' ').replace(/\s+/g, ' ').trim();

  const tally = new Map();
  let cards = 0;

  for (const page of pages) {
    const g = detect(page);
    for (let i = 0; i < g.cards.length; i++) {
      const c = g.cards[i];
      const cw = c.x1 - c.x0, ch = c.y1 - c.y0;
      // The label strip: the left part of the text region, where the printed labels sit.
      const x0 = Math.round(c.x0 + F.TEXT_REGION.x0 * cw);
      const x1 = Math.round(c.x0 + Math.min(F.TEXT_REGION.x1, 0.155) * cw);
      const y0 = Math.round(c.y0 + F.TEXT_REGION.y0 * ch);
      const y1 = Math.round(c.y0 + F.TEXT_REGION.y1 * ch);
      if (x1 <= x0 || y1 <= y0) continue;
      const strip = path.join(tmp, `s${i}.png`);
      execFileSync('convert', [page,
        '-crop', `${x1 - x0}x${y1 - y0}+${x0}+${y0}`, '+repage',
        '-colorspace', 'Gray', '-resize', '150%',
        '-bordercolor', 'white', '-border', '10', strip], { stdio: 'ignore' });
      const { data } = await worker.recognize(strip, {}, { text: true });
      for (const raw of (data.text || '').split('\n')) {
        // Only what precedes the colon is the label. Without this the value that follows
        // it is recognised too and every name becomes its own "distinct label".
        const line = raw.split(':')[0].replace(/\s+/g, ' ').trim();
        const k = norm(line);
        if (!k || !/[a-z]{3}/.test(k)) continue;
        tally.set(k, (tally.get(k) || 0) + 1);
      }
      cards++;
    }
  }
  await worker.terminate();

  console.log(`  ${cards} cards scanned`);
  console.log('  distinct label strings, most common first:');
  for (const [k, n] of [...tally.entries()].sort((a, b) => b[1] - a[1]).slice(0, 25)) {
    console.log(`    ${String(n).padStart(5)}  ${k}`);
  }
  fs.rmSync(tmp, { recursive: true, force: true });
}

main().catch((e) => { console.error(e); process.exit(1); });