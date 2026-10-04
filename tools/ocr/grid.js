// Locate the voter-card rectangles on a rendered electoral-roll page.
//
// The page is a plain 3 x 10 grid of bordered cards. Rather than hard-coding
// the geometry (which shifts with scan DPI and page trim), find the rules that
// draw the card borders and intersect them. That makes the crop boxes
// self-calibrating: any page, any DPI.
//
// Two properties make this reliable and keep it from firing on text or on the
// diagonal watermark:
//   * a card rule is *near-black* (value < 60), while glyph strokes and the
//     watermark sit well above that;
//   * a card rule spans nearly the whole page width (three cards plus two thin
//     gaps), so a coverage test on the full page width rejects short strokes.

const fs = require('fs');
const { execFileSync } = require('child_process');

// Reads a PGM into { w, h, px } with px[y * w + x] as 0..255 grayscale.
function readGray(file) {
  const buf = execFileSync('convert', [file, '-colorspace', 'Gray', '-depth', '8', 'pgm:-'],
    { maxBuffer: 1 << 30 });
  let p = 0;
  const fields = [];
  for (let i = 0; i < 4; i++) {                       // P5, width, height, maxval
    while (buf[p] === 0x20 || buf[p] === 0x0a || buf[p] === 0x0d || buf[p] === 0x09) p++;
    if (buf[p] === 0x23) { while (buf[p] !== 0x0a) p++; continue; }        // # comment
    const s = p;
    while (buf[p] > 0x20) p++;
    fields.push(parseInt(buf.toString('ascii', s, p), 10));
  }
  p++;                                                                       // one whitespace
  return { w: fields[1], h: fields[2], px: buf.slice(p, p + fields[1] * fields[2]) };
}

// Collapse runs of marked rows/cols into their centres.
function bands(flags, minRun) {
  const out = [];
  let cur = null;
  for (let i = 0; i < flags.length; i++) {
    if (flags[i]) { if (!cur) cur = [i, i]; else cur[1] = i; }
    else if (cur) { if (cur[1] - cur[0] + 1 >= minRun) out.push([cur[0], cur[1]]); cur = null; }
  }
  if (cur && cur[1] - cur[0] + 1 >= minRun) out.push(cur);
  return out;
}

const BLACK = 60;             // pixel value below this counts as rule ink

function detect(file) {
  const { w, h, px } = readGray(file);

  const hFlag = new Uint8Array(h);
  for (let y = 0; y < h; y++) {
    const row = y * w;
    let n = 0;
    for (let x = 0; x < w; x++) if (px[row + x] < BLACK) n++;
    hFlag[y] = n > w * 0.80 ? 1 : 0;
  }
  // minRun 1, not 2: a card rule is four or five pixels thick once the page has
  // been rendered at 300 dpi, but only ONE pixel thick in the image the PDF
  // actually embeds. Requiring two consecutive rows found no rules at all on the
  // native image and the detector returned zero cards.
  const hs = bands(hFlag, 1).map(([a, b]) => (a + b) / 2);

  // Find the column rules inside the *first card row only*. A card's own side
  // rules run the full height of that row, so they score 100%. The serial box
  // and the photo box drawn inside the card are shorter and score well under
  // that, which is what separates them from the card edges.
  let vs = [];
  let colSpan = null;
  if (hs.length >= 2) {
    const yTop = Math.round(hs[0]), yBot = Math.round(hs[1]);
    const span = yBot - yTop;
    const vFlag = new Uint8Array(w);
    for (let x = 0; x < w; x++) {
      let n = 0;
      for (let y = yTop; y <= yBot; y++) if (px[y * w + x] < BLACK) n++;
      vFlag[x] = n > span * 0.90 ? 1 : 0;
    }
    // minRun 1 for the same reason as the horizontal rules: a card's side border is
    // one pixel wide in the embedded image, so a two-column minimum rejects every
    // one of them and the detector finds no columns at all.
    vs = bands(vFlag, 1).map(([a, b]) => (a + b) / 2);
    if (vs.length >= 2) colSpan = [vs[0], vs[1]];
  }

  // Now find *every* row, including partial ones, by looking for horizontal
  // rules inside the first column alone.
  //
  // The first pass measured each rule against the whole page width and demanded
  // 80%. That only ever holds for a full row of three cards: a two-card row spans
  // about 66% and a one-card row about 33%, so both were dropped. Page 6 ends
  // with a row holding serials 85 and 86 and those two records were silently
  // lost -- which then shifted every derived serial after it. Restricting the
  // search to one column's x-range makes a row detectable whenever that column
  // has a card in it, whatever the rest of the row is doing.
  // Both sources are used, and neither replaces the other.
  //
  // The page-wide test needs a rule to span 80% of the page, which only a full row
  // of three cards does -- so it finds every row except the short ones. The
  // column-restricted test only needs a rule within the first card's width, so it
  // can find short rows too, but at native resolution a one-pixel rule does not
  // survive the threshold across a whole card width and it finds nothing at all.
  // Taking the page-wide rules first and adding the column-restricted ones after
  // gets full rows from the reliable source and short rows from the other.
  const rowRules = hs.slice();
  if (colSpan) {
    const cx0 = Math.round(colSpan[0]), cx1 = Math.round(colSpan[1]);
    const cw2 = cx1 - cx0;
    if (cw2 > 20) {
      const hFlag2 = new Uint8Array(h);
      for (let y = 0; y < h; y++) {
        const row = y * w;
        let n = 0;
        for (let x = cx0; x <= cx1; x++) if (px[row + x] < BLACK) n++;
        hFlag2[y] = n > cw2 * 0.55 ? 1 : 0;
      }
      for (const [a, b] of bands(hFlag2, 1)) {
        const c = (a + b) / 2;
        if (!rowRules.some((v) => Math.abs(v - c) < 4)) rowRules.push(c);
      }
      rowRules.sort((a, b) => a - b);
    }
  }

  // Cards lie between consecutive rules of matching pitch. Take the modal span
  // and keep only pairs that match it; that discards header/footer rules and
  // any stray box drawn inside a card.
  const pick = (r) => {
    const sp = [];
    for (let i = 0; i + 1 < r.length; i++) sp.push([r[i], r[i + 1], r[i + 1] - r[i]]);
    // Modal span, found by counting neighbours rather than by bucketing.
    //
    // Bucketing to a fixed width was wrong in a way that only showed up on the
    // native image, and it was wrong on the 300 dpi image too. Card rules produce
    // two populations: the card's own height (249 px at native) and the gap to the
    // next card (15 px). Round-to-20 puts 249 and 250 in different buckets -- 240
    // and 260 -- so the ten card spans split 7/3 while the nine gaps all land in
    // one bucket of 20. The mode therefore picks the *gap*, and the 15% tolerance
    // around 20 then excludes the very gaps that won, leaving nothing.
    //
    // Counting how many spans fall within a tolerance of each candidate has no
    // bucket boundary to fall on: the 249/250 pair counts together and the card
    // span wins on merit.
    let bestK = 0, bestN = -1;
    for (const [, , d] of sp) {
      const tol = Math.max(2, d * 0.06);
      let n = 0;
      for (const [, , e] of sp) if (Math.abs(e - d) <= tol) n++;
      if (n > bestN) { bestN = n; bestK = d; }
    }
    const tol = Math.max(3, bestK * 0.12);
    const target = sp.filter(([, , d]) => Math.abs(d - bestK) <= tol);
    return { target, median: bestK, count: bestN };
  };

  const rows = pick(rowRules), cols = pick(vs);

  // The grid is rows x columns, but the last row of a page can be short: page 6
  // ends with two cards where three would fit, and a plain cross product
  // manufactures a third, empty one. An empty cell is easy to recognise and
  // impossible to ship -- it would become a voter with a garbage serial -- so
  // every cell is checked for the one thing a real card always has: black ink
  // where the serial number is. The watermark is grey and does not qualify.
  const cards = [];
  for (const [y0, y1] of rows.target) {
    for (const [x0, x1] of cols.target) {
      const sx0 = Math.round(x0 + (x1 - x0) * (555 / 2576));
      const sx1 = Math.round(x0 + (x1 - x0) * (820 / 2576));
      const sy0 = Math.round(y0 + (y1 - y0) * (58 / 1038));
      const sy1 = Math.round(y0 + (y1 - y0) * (168 / 1038));
      let black = 0;
      for (let y = sy0; y <= sy1; y++) {
        const row = y * w;
        for (let x = sx0; x <= sx1; x++) if (px[row + x] < BLACK) black++;
      }
      // Scaled by the card's own area, not a fixed count. Ink is proportional to
      // area, so an absolute threshold means different things at native and at
      // 300 dpi: 40 black pixels is a comfortable margin for a two-digit serial
      // upsampled to 75 px tall and an impossible one for the same serial at
      // native 18 px, which silently dropped the four one-digit serials off page 3.
      if (black >= Math.max(6, (x1 - x0) * (y1 - y0) * 2.5e-5)) cards.push({ x0, y0, x1, y1 });
    }
  }
  return { w, h, hs, vs, rowBands: rows.target, colBands: cols.target, cards };
}

module.exports = { readGray, bands, detect, BLACK };

if (require.main === module) {
  const g = detect(process.argv[2]);
  console.log(`page ${g.w} x ${g.h}`);
  console.log(`horizontal rules ${g.hs.length} -> ${g.rowBands.length} card rows (pitch ${g.rowBands.length ? (g.rowBands[0][1] - g.rowBands[0][0]).toFixed(0) : '-'})`);
  console.log(`vertical rules   ${g.vs.length} -> ${g.colBands.length} card cols (pitch ${g.colBands.length ? (g.colBands[0][1] - g.colBands[0][0]).toFixed(0) : '-'})`);
  console.log(`cards: ${g.cards.length}`);
  for (const c of g.cards.slice(0, 3)) {
    console.log(`   x ${c.x0.toFixed(0)}..${c.x1.toFixed(0)}  y ${c.y0.toFixed(0)}..${c.y1.toFixed(0)}` +
      `  ${(c.x1 - c.x0).toFixed(0)} x ${(c.y1 - c.y0).toFixed(0)}`);
  }
  const yTop = [...new Set(g.cards.map((c) => c.y0.toFixed(0)))];
  const xL = [...new Set(g.cards.map((c) => c.x0.toFixed(0)))];
  console.log(`distinct row tops ${yTop.length}: ${yTop.join(' ')}`);
  console.log(`distinct col lefts ${xL.length}: ${xL.join(' ')}`);
}