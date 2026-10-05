// Renders the house-number crop for a chosen set of cards as a contact sheet, so the
// printed values can be *looked at* rather than inferred from what the recogniser said.
// The point is to check the pipeline against the page, not the other way round.
//
// What comes out of it is a reading by whoever looks at a low-quality scan. That is a
// second opinion, not ground truth -- but unlike a language model guessing from the whole
// page, a person reading a magnified crop of one value is about as good a source as this
// task allows, and it is the only ground truth this field is going to get. Every number
// measured against it should say so.
//
// Usage:
//   node sheet.js <roll.pdf> <run.jsonl> <out.png> [selection]
//
//   selection is:
//     disagree          the cards where the two readers differ -- the sample
//                       that decides which reader to trust, and the cheapest one to score
//     split             every card whose vote was not unanimous
//     single            cards whose leading group came out as one digit, the shape whose
//                       repair is most tempting and least safe
//     random:N          a spread of N across the roll rather than the first N
//     all               everything with a located value
//     or a comma-separated list of serials
//
// Why serials and not card indices
// --------------------------------
// The roll is printed in parts and each part ends on a partial page, so card index i on
// page p does not fix a serial -- the arithmetic that assumed 30 cards to a page was wrong
// twice on this roll. A serial is printed on the card, so it is the one label that cannot
// be misaligned. Sheets are labelled by serial for that reason alone.
//
// What each tile shows
// --------------------
// Above the crop: the serial, and the vote's candidates with their counts. Below it: the
// read the pipeline would print. That last one is deliberately present, because a sheet
// that shows only the crop invites scoring against the crop and a sheet that shows only the
// answer is not a check at all.

const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');

const GRAY = '#8a8a8a';

function loadRun(file) {
  const rows = fs.readFileSync(file, 'utf8').split('\n').filter(Boolean)
    .map((l) => JSON.parse(l));
  // Only rows that carry a located value are scorable: a card with no house_rect has no
  // crop to show, and quietly substituting a blank tile would let it be scored as a blank
  // house number rather than as a card the pipeline never resolved.
  return rows.filter((r) => Array.isArray(r.house_rect) && r.house_rect.length === 4);
}

// The candidates worth putting in front of a reader, in order. The primary read comes first
// and is marked, because it is the one the pipeline will print; the vote's variants follow
// with their counts, so a contested card shows what it was chosen over.
function candidates(r) {
  const out = [];
  const primary = (r.house || '').replace(/\s+/g, '');
  if (primary) out.push([primary, 'printed']);
  const voted = (r.house_voted || '').replace(/\s+/g, '');
  if (voted && voted !== primary) out.push([voted, 'vote']);
  for (const [t, n] of (Array.isArray(r.house_alternatives) ? r.house_alternatives : [])) {
    if (t && !out.some(([x]) => x === t)) out.push([t, String(n)]);
  }
  return out.filter(([t]) => t).slice(0, 3);
}

function select(rows, spec) {
  if (!spec || spec === 'all') return rows;
  if (spec === 'split') return rows.filter((r) => !r.house_unanimous);
  if (spec.startsWith('random:')) {
    const n = Number(spec.slice(7));
    // A spread across the roll rather than the first n, so the sample is not all one
    // page's layout. Deterministic: the same run always yields the same sheet, so a
    // score can be re-derived rather than re-asked.
    const step = Math.max(1, Math.floor(rows.length / n));
    const out = [];
    for (let i = 0; out.length < n && i < rows.length; i += step) out.push(rows[i]);
    return out;
  }
  if (spec === 'single') return rows.filter((r) => r.house_final.split('-')[0].length === 1);
  // The cards where the two readers disagree. This is the sample that decides anything:
  // everywhere they agree, it does not matter which one is used, and everywhere they differ
  // is exactly the case that needs a person. Scoring these settles the choice without needing
  // ground truth on the easy majority.
  if (spec === 'disagree') {
    return rows.filter((r) => r.house_agrees === false);
  }
  const want = new Set(spec.split(',').map((s) => s.trim()).filter(Boolean));
  return rows.filter((r) => want.has(String(r.serial)));
}

// The tile, built as three images stacked rather than as one image annotated twice.
//
// An earlier version drew the header onto the crop and then ran -extent to pad it to a
// fixed box. -extent is not a canvas for text: the annotation is placed relative to the
// *crop's* top edge, so the header landed on the digits and -extent filled the rest of the
// square with grey, which came out as a sheet of single digits on grey. Stacking real
// images means each part is sized by its own content and nothing can overlap.
const TILE_W = 900;      // wide enough for the candidate line on one row
const HEAD_H = 40;
const MID_H = 34;
const CROP_H = 190;


// Draws one text line onto a fixed-width strip. -size with a canvas is the reliable way to
// get a known-height label image; -extent alone is not, because it does not create pixels
// and there is nothing to extend into unless the input is already that shape.
function textStrip(text, size, h, out) {
  execFileSync('convert', ['-size', `${TILE_W}x${h}`, 'xc:' + GRAY,
    '-fill', 'white', '-pointsize', String(size), '-gravity', 'center',
    '-annotate', '+0+0', text, out], { stdio: 'ignore' });
  return out;
}

// The tile. Magnified with no threshold: the grey levels matter to a reader in a way they
// do not to a threshold, and a thresholded sheet throws away the evidence.
function tile(pagePng, r, out, tmp) {
  const [x, y, w, h] = r.house_rect;
  const cands = candidates(r);
  // "label" is the value read off the whole printed line, with the label's own letters
  // still in it; the numbered ones are the vote's variants on the tightened crop. Both are
  // shown because they disagree on about one card in nine and it is not settled which is
  // right -- that is what the sheet is for.
  const mid = cands.length
    ? cands.map(([t, n]) => `${t} (${n})`).join('   vs   ')
    : '(no candidate read)';

  // Every intermediate gets its own name. Reusing one path across tiles worked and then did
// not, depending on whether montage had finished reading a file before the next tile
// overwrote it -- a race that produces a sheet with the right number of tiles and the
// wrong contents in some of them.
const stamp = `${process.pid}-${Math.random().toString(36).slice(2, 8)}`;
const scratch = (name) => path.join(tmp, `${stamp}-${name}`);

const head = textStrip(`serial ${r.serial}`, 34, HEAD_H, scratch('h.png'));
  const line = textStrip(mid, 28, MID_H, scratch('m.png'));
  const crop = scratch('c.png');
  // Resized to *fit* the tile, then padded to it. An earlier version resized to 400% and
  // then ran -extent, which crops anything wider than the tile: a 276 px crop at 400% is
  // 1104 px, so 204 px was cut off, 102 each side, which is the leading digit of the house
  // number. Every tile read "-1-1" where the value is "95-6-6/1", and the pipeline's own
  // rectangle was fine throughout. -resize WxH without a bang preserves the aspect ratio and
  // fits inside, so -extent only ever pads.
  execFileSync('convert', [pagePng,
    '-crop', `${Math.round(w)}x${Math.round(h)}+${Math.round(x)}+${Math.round(y)}`, '+repage',
    '-colorspace', 'Gray', '-resize', `${TILE_W - 40}x${CROP_H - 20}`,
    '-background', GRAY, '-gravity', 'center', '-extent', `${TILE_W}x${CROP_H}`,
    crop], { stdio: 'ignore' });

  const foot = textStrip(`pipeline prints: ${r.house_final || '(blank)'}`, 26, 34,
    scratch('f.png'));
  execFileSync('convert', [head, line, crop, foot, '-append',
    '-bordercolor', GRAY, '-border', '6', out], { stdio: 'ignore' });
  return out;
}

function main() {
  const [pdf, runFile, outPng, spec] = process.argv.slice(2);
  if (!pdf || !runFile || !outPng) {
    console.error("usage: node sheet.js <roll.pdf> <run.jsonl> <out.png> [disagree|split|single|random:N|all|serials]");
    process.exit(2);
  }
  const rows = loadRun(runFile);
  const chosen = select(rows, spec || 'random:60');
  if (!chosen.length) { console.error('  nothing selected'); process.exit(1); }

  const { pagePng } = require('./layout.js');
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'sheet-'));
  const tiles = [];
  const byPage = new Map();
  for (const r of chosen) {
    if (!byPage.has(r.page)) byPage.set(r.page, []);
    byPage.get(r.page).push(r);
  }
  for (const [p, rs] of byPage) {
    const png = pagePng(pdf, p);
    rs.forEach((r, i) => {
      const t = path.join(tmp, `t${p}-${i}.png`);
      try { tile(png, r, t, tmp); tiles.push(t); } catch (e) { /* keep the sheet */ }
    });
  }

  // Two to a row. Each tile is 900 px wide and carries three text lines, so a wider row
  // would shrink the crop -- and the crop is the evidence. Two across also puts a card's
  // crop and its candidates in one glance without the reader having to track rows.
  execFileSync('montage', ['-background', GRAY, '-tile', '2x', '-geometry', '+6+6', ...tiles, outPng],
    { stdio: 'ignore' });

  const index = chosen.map((r, i) => {
    const cands = candidates(r).map(([t, n]) => `${t}(${n})`).join('|');
    return `${i}\tserial ${r.serial}\tpage ${r.page}\tread ${r.house_final}\tcands ${cands}`;
  }).join('\n');
  fs.writeFileSync(outPng + '.index.tsv', index + '\n');
  console.log(`  ${tiles.length} tiles -> ${outPng}`);
  console.log(`  index     -> ${outPng}.index.tsv   (row 0 is the first tile, in order)`);
  fs.rmSync(tmp, { recursive: true, force: true });
}

main();