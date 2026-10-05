// One-time extraction of labelled serial glyphs, cached on disk.
//
// Extracting them means roughly 1,200 ImageMagick crops plus 1,200 PGM reads, and
// it took twenty minutes -- which is far too slow to pay on every iteration while
// the classifier is being tuned. The output never changes, so it is written once
// and read back thereafter.
//
// The cache stores, per glyph: the label character and the packed bitmap. Nothing
// about the classifier depends on how it was produced, so a stale cache is only a
// problem if the crops change, which is why the cache records the DPI and the page
// set it was built from.

const fs = require('fs');
const path = require('path');
const os = require('os');
const { detect, readGray } = require('./grid.js');
const { renderLineSync } = require('./house.js');
const T = require('./templates.js');
const F = require('./fields.js');
const { pageLayout, FIRST_LIST_PAGE, LAST_LIST_PAGE } = require('./layout.js');

const CACHE = path.join(os.tmpdir(), 'rollcache', 'serial-glyphs.bin');

function reconcileRuns(runs, expected, lineH) {
  const out = runs.map((r) => [r[0], r[1]]);
  let guard = 0;
  while (out.length < expected && guard++ < 40) {
    let bi = 0, bw = -1;
    for (let i = 0; i < out.length; i++) {
      const w = out[i][1] - out[i][0] + 1;
      if (w > bw) { bw = w; bi = i; }
    }
    if (bw < lineH * 0.5) break;
    const mid = Math.round((out[bi][0] + out[bi][1]) / 2);
    out.splice(bi, 1, [out[bi][0], mid], [mid, out[bi][1]]);
  }
  return out;
}

// Every serial glyph on the roll, labelled by the serial it belongs to.
//
// One bitmap per glyph is packed as bits, eight to a byte, so the whole roll comes
// to well under a megabyte.
function build(pdf, dpi = 300, force = false) {
  const stamp = `v1 dpi=${dpi} pages=${FIRST_LIST_PAGE}-${LAST_LIST_PAGE} canvas=${T.CANVAS_W}x${T.CANVAS_H} ink=${T.INK_H} gap=${T.MERGE_GAP}`;
  if (!force && fs.existsSync(CACHE)) {
    const head = fs.readFileSync(CACHE).subarray(0, stamp.length + 1).toString();
    if (head === stamp + '\n') return read(CACHE, stamp.length + 1);
  }

  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'glyphcache-'));
  const { pages } = pageLayout(pdf, dpi);
  const records = [];                              // [label, packed bitmap]
  let serials = 0, skipped = 0;

  for (const pg of pages) {
    pg.cards.forEach((card, i) => {
      const serial = String(pg.first + i);
      const f = renderLineSync(pg.png, rel(card, F.SERIAL), path.join(tmp, `${pg.page}_${i}.png`), 1);
      const img = readGray(f);
      const line = T.lineInkBox(img);
      if (!line) { skipped++; return; }
      const lineH = line.y1 - line.y0 + 1;
      const runs = reconcileRuns(T.glyphRuns(img), serial.length, lineH);
      if (runs.length !== serial.length) { skipped++; return; }
      serials++;
      runs.forEach((r, k) => {
        const b = T.glyphBitmap(img, r[0], r[1]);
        if (b) records.push([serial[k], b.bits]);
      });
    });
  }

  const bytesPerGlyph = Math.ceil(T.CANVAS_W * T.CANVAS_H / 8);
  const head = Buffer.from(stamp + '\n', 'ascii');
  const out = Buffer.alloc(head.length + records.length * (1 + bytesPerGlyph));
  head.copy(out, 0);
  let off = head.length;
  for (const [ch, bits] of records) {
    out[off++] = ch.charCodeAt(0);
    const packed = Buffer.alloc(bytesPerGlyph);
    for (let i = 0; i < bits.length; i++) if (bits[i]) packed[i >> 3] |= 0x80 >> (i & 7);
    packed.copy(out, off);
    off += bytesPerGlyph;
  }
  fs.mkdirSync(path.dirname(CACHE), { recursive: true });
  fs.writeFileSync(CACHE, out);
  console.error(`  cached ${records.length} glyphs from ${serials} serials (${skipped} skipped) -> ${CACHE}`);
  return { records, serials, skipped };
}

function read(file, offset) {
  const buf = fs.readFileSync(file);
  const bytesPerGlyph = Math.ceil(T.CANVAS_W * T.CANVAS_H / 8);
  const records = [];
  for (let off = offset; off + 1 + bytesPerGlyph <= buf.length; off += 1 + bytesPerGlyph) {
    const ch = String.fromCharCode(buf[off]);
    const bits = new Uint8Array(T.CANVAS_W * T.CANVAS_H);
    const base = off + 1;
    for (let i = 0; i < bits.length; i++) {
      const byte = buf[base + (i >> 3)];
      bits[i] = (byte >> (7 - (i & 7))) & 1;
    }
    records.push([ch, bits]);
  }
  return { records };
}

function rel(card, f) {
  const cw = card.x1 - card.x0, ch = card.y1 - card.y0;
  const x0 = Math.round(card.x0 + f.x0 * cw), y0 = Math.round(card.y0 + f.y0 * ch);
  const x1 = Math.round(card.x0 + f.x1 * cw), y1 = Math.round(card.y0 + f.y1 * ch);
  return { x0, y0, w: x1 - x0, h: y1 - y0 };
}

module.exports = { build, CACHE };

if (require.main === module) {
  build(process.argv[2], Number(process.argv[3] || 300), process.argv[4] === 'force');
}