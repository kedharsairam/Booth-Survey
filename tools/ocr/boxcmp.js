// Dumps the top-left corner of a card from both rolls, side by side, so the two
// layouts can be compared by eye rather than inferred from pixel scans.
//
// The serial crop that worked on one roll is not evidence about the other: the two
// documents are laid out independently by different software, and a field position
// measured on one is only valid on the other by coincidence.

const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');
const { detect } = require('./grid.js');
const { pagePng } = require('./layout.js');

const OUT = '/tmp/opencode/voter/eng';
fs.mkdirSync(OUT, { recursive: true });

// tag, pdf, page, card indices to show
const ROLLS = [
  ['eng', process.argv[2], 3, [0, 9, 10, 29]],
  ['tel', process.argv[3], 5, [0, 9, 10, 29]],
];

const files = [];
for (const [tag, pdf, page, idxs] of ROLLS) {
  const png = pagePng(pdf, page, 300);
  const g = detect(png);
  console.log(`  ${tag}: page ${page} has ${g.cards.length} cards`);
  for (const i of idxs) {
    const c = g.cards[i];
    if (!c) continue;
    const x0 = Math.round(c.x0 + 10), y0 = Math.round(c.y0 + 10);
    const out = path.join(OUT, `${tag}_box${i}.png`);
    execFileSync('convert', [png, '-crop', `900x180+${x0}+${y0}`, '+repage',
      '-colorspace', 'Gray', '-threshold', '62%', '-resize', '150%',
      '-bordercolor', 'gray', '-border', '2', out]);
    files.push(out);
  }
}
execFileSync('montage', [...files, '-tile', '1x' + files.length, '-geometry', '+4+4',
  '-background', 'white', path.join(OUT, 'boxcmp.png')]);
console.log(`  wrote ${path.join(OUT, 'boxcmp.png')} (${files.length} tiles, english first in each pair)`);