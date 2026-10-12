// Renders the house-number line for every card on a page, magnified, as a contact sheet.
//
// This exists to let the printed house numbers be *looked at* rather than inferred from
// what the recogniser said. The point is to check the pipeline against the page, not the
// other way round.
//
// What comes out of it is a reading by a language model looking at a low-quality scan.
// That is weaker than a person reading magnified crops, and it is not ground truth. It
// is a second opinion, and anything it settles needs a human to confirm.
//
// Usage: node sheet.js <page.png> <out.png>

const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');
const { detect } = require('./grid.js');
const { cropColumn } = require('./house.js');
const F = require('./fields.js');

async function main() {
  const pagePng = process.argv[2];
  const outPng = process.argv[3];
  if (!pagePng || !outPng) { console.error('usage: node sheet.js <page.png> <out.png>'); process.exit(2); }

  const { createWorker } = require('tesseract.js');
  const worker = await createWorker('eng');
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'sheet-'));
  const g = detect(pagePng);
  const tiles = [];

  for (let i = 0; i < g.cards.length; i++) {
    const card = g.cards[i];
    try {
      const loc = await cropColumn(pagePng, card, path.join(tmp, `c${i}.png`), worker);
      if (!loc.rect) continue;
      const r = loc.rect;
      // 3x with no threshold: the grey levels matter to a reader in a way they do not
      // to a threshold, and a thresholded sheet throws away the evidence.
      const tile = path.join(tmp, `t${i}.png`);
      execFileSync('convert', [pagePng,
        '-crop', `${r.w}x${r.h}+${r.x0}+${r.y0}`, '+repage',
        '-colorspace', 'Gray', '-resize', '300%',
        '-bordercolor', '#808080', '-border', '6',
        '-background', '#808080', '-fill', 'white', '-pointsize', '40',
        '-gravity', 'north', `-annotate`, '+0+4', String(i),
        tile], { stdio: 'ignore' });
      tiles.push(tile);
    } catch (e) { /* one bad card should not lose the sheet */ }
  }
  await worker.terminate();

  // Ten per row, magnified. Ordered by card index, which is roll order.
  const args = tiles.length ? ['-background', '#808080'] : [];
  execFileSync('montage', [...args, ...tiles, '-tile', '5x', '-geometry', '+4+4', outPng],
    { stdio: 'ignore' });
  console.log(`  ${tiles.length} house-number crops -> ${outPng}`);
  fs.writeFileSync(outPng + '.index.txt',
    tiles.map((t, n) => `${n}\t${path.basename(t)}`).join('\n'));
  fs.rmSync(tmp, { recursive: true, force: true });
}

main().catch((e) => { console.error(e); process.exit(1); });