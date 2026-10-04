// Crop, enlarge, threshold, trim and border -- in process, no ImageMagick.
//
// Why this exists
// ---------------
// Profiling read.js over one 30-card page at 300 dpi on one worker:
//
//     total wall                491 s
//     inside renderLine         459 s   (360 calls, 1275 ms each)
//     everything else            32 s   <- this is Tesseract, actually recognising
//
// So 93.5% of the run was ImageMagick startup, spent producing twelve small
// crops per card. At 43 pages that is about five and a half hours of fork/exec
// and PNG encode-decode, and the recognition it was wrapping took well under a
// minute of it.
//
// The work itself is trivial arithmetic on a byte array: copy a rectangle, scale
// it, compare each pixel against a cutoff, find the ink bounding box, pad with
// white. Every one of those steps is something a general-purpose image library
// spawns a process to do.
//
// Fidelity
// --------
// The output has to be the same *image*, not merely a similar one, or every
// accuracy number the project has ever recorded stops meaning anything. So the
// transform is a deliberate port of the exact command line:
//
//   convert page -crop WxH+X+Y +repage -colorspace Gray
//           -resize S% -threshold T% -trim +repage
//           -bordercolor white -border B out.png
//
// with these ImageMagick semantics reproduced rather than approximated:
//
//   -threshold T%   output is pure white where input >= T% of full range, pure
//                   black below it. Not a soft threshold.
//   -trim           removes edge rows and columns whose pixels are all exactly
//                   the colour of the top-left pixel. After a threshold that is
//                   almost always white, so it is trimming the white margin.
//   -resize S%      the Mitchell-Netravali filter, which is what ImageMagick's
//                   default resolves to for magnification. This was measured
//                   rather than assumed: of thirty-one candidate filters tried
//                   against ImageMagick's own output on a real crop, mitchell is
//                   the only one byte-identical (0 differing pixels), robidoux
//                   differs by 13 and triangle by 40, and nearest-neighbour gets
//                   the size wrong outright. Mitchell is cubic with B = C = 1/3
//                   and support 2. Nearest would be cheaper still and is wrong:
//                   it aliases thin stems away and changes what the recogniser
//                   sees.
//
// verify.js checks this module against ImageMagick output byte for byte, and
// selftest.js checks that a full page's readings are unchanged.

const fs = require('fs');
const zlib = require('zlib');
const { execFileSync } = require('child_process');
const { readGray } = require('./grid.js');

// Page decodes are memoised. readGray shells out to convert, and without this
// the page would be re-decoded once per crop -- twelve times per card, which
// would replace one process spawn with a different one.
const pageCache = new Map();

function pageGray(pagePng) {
  let g = pageCache.get(pagePng);
  if (!g) {
    g = readGray(pagePng);
    pageCache.set(pagePng, g);
  }
  return g;
}

function clearPageCache() { pageCache.clear(); }

/**
 * Mitchell-Netravali cubic (B = C = 1/3), support 2, which is what ImageMagick's
 * default -resize uses for magnification. Weights and source indices are
 * precomputed per destination column and row, since the scale is fixed for a whole
 * crop and this runs twelve times per card.
 *
 * Edges clamp rather than extend, matching ImageMagick.
 */
function mitchell(t) {
  const a = Math.abs(t);
  const B = 1 / 3, C = 1 / 3;
  if (a < 1) {
    return ((12 - 9 * B - 6 * C) * a ** 3 + (-18 + 12 * B + 6 * C) * a ** 2 + (6 - 2 * B)) / 6;
  }
  if (a < 2) {
    return ((-B - 6 * C) * a ** 3 + (6 * B + 30 * C) * a ** 2 +
      (-12 * B - 48 * C) * a + (8 * B + 24 * C)) / 6;
  }
  return 0;
}

function scaler(nSrc, nDst) {
  const idx = [], wts = [];
  // Source pixels per destination pixel. For magnification this is below 1, and
  // the filter keeps its natural support of two *source* pixels -- the kernel
  // does not shrink along with the image. That is why this is not simply
  // 2 * nSrc / nDst, which was the first version and quietly made every "2x"
  // render behave like 1x: the ink came out at half the expected extent, and the
  // trim that follows produced an image a third smaller than ImageMagick's.
  const ratio = nSrc / nDst;
  const support = 2 * Math.max(1, ratio);
  for (let d = 0; d < nDst; d++) {
    const centre = (d + 0.5) * ratio - 0.5;        // destination centre in source coords
    let lo = Math.ceil(centre - support), hi = Math.floor(centre + support);
    lo = Math.max(0, lo); hi = Math.min(nSrc - 1, hi);
    const ids = [], ws = [];
    let sum = 0;
    for (let s = lo; s <= hi; s++) {
      const w = mitchell((s - centre) / support);
      // Negative weights are kept. Mitchell's whole character is its negative
      // lobes -- the ringing that stops a thin stem greying out -- and
      // ImageMagick includes them. Dropping them brightens the result and moves
      // the ink bounding box, which is exactly what -trim then acts on, so a
      // real crop came out a different size from ImageMagick's.
      if (w !== 0) { ids.push(s); ws.push(w); sum += w; }
    }
    if (!ids.length) {
      const s = Math.max(0, Math.min(nSrc - 1, Math.round(centre)));
      ids.push(s); ws.push(1); sum = 1;
    }
    idx.push(Int32Array.from(ids));
    wts.push(Float32Array.from(ws.map(w => w / sum)));
  }
  return { idx, wts };
}

/** Two-pass separable resize of a single-channel crop, carried in Q16. */
function resample(src, w, h, w2, h2, out) {
  const sx = scaler(w, w2), sy = scaler(h, h2);
  const tmp = new Float64Array(w2 * h);
  for (let y = 0; y < h; y++) {
    const row = y * w;
    for (let x = 0; x < w2; x++) {
      const ids = sx.idx[x], ws = sx.wts[x];
      let acc = 0;
      for (let k = 0; k < ids.length; k++) acc += src[row + ids[k]] * ws[k];
      tmp[y * w2 + x] = acc;
    }
  }
  for (let y = 0; y < h2; y++) {
    const ids = sy.idx[y], ws = sy.wts[y];
    const orow = y * w2;
    for (let x = 0; x < w2; x++) {
      let acc = 0;
      for (let k = 0; k < ids.length; k++) acc += tmp[ids[k] * w2 + x] * ws[k];
      // Held at Q16 and rounded once, rather than clamped to 8 bits between the
      // passes. Resampling in 8-bit and resampling in Q16 stop being the same
      // operation as soon as a -threshold sits on the boundary: a value within
      // 1/256 of the cutoff falls on the other side of it, and one flipped column
      // moves the trimmed bounding box by a pixel -- which is enough to cost a
      // house number its leading "1".
      let q = Math.round(acc * 257);              // 0..255 -> 0..65535
      if (q < 0) q = 0; else if (q > 65535) q = 65535;
      out[orow + x] = q;
    }
  }
}

/**
 * The same trim ImageMagick performs: drop edge rows and columns made entirely of
 * the corner pixel's colour. Not a fuzz-based trim -- IM's default fuzz is 0, so
 * this is an exact comparison, and the crop stays as-is when everything matches
 * (a uniform crop must not trim to nothing).
 */
function trim(px, w, h) {
  const corner = px[0];
  const isBg = (v) => v === corner;
  let x0 = w, y0 = h, x1 = -1, y1 = -1;
  for (let y = 0; y < h; y++) {
    const row = y * w;
    let any = false;
    for (let x = 0; x < w; x++) if (!isBg(px[row + x])) { any = true; break; }
    if (!any) continue;
    if (y < y0) y0 = y;
    y1 = y;
    for (let x = 0; x < w; x++) {
      if (!isBg(px[row + x])) {
        if (x < x0) x0 = x;
        if (x > x1) x1 = x;
        any = true;
      }
    }
  }
  // ImageMagick warns "geometry does not contain image" and collapses a uniform
  // image to a single pixel, which then becomes 1x1 plus the border. Leaving it
  // unchanged -- the safer-looking choice -- is not what the original does, and it
  // showed up as a 2864x1640 crop where ImageMagick produced 5x5.
  if (x1 < 0) return { w: 1, h: 1, px: Buffer.from([px[0]]) };
  const nw = x1 - x0 + 1, nh = y1 - y0 + 1;
  const out = Buffer.allocUnsafe(nw * nh);
  for (let y = 0; y < nh; y++) {
    const src = (y + y0) * w + x0;
    out.set(px.subarray(src, src + nw), y * nw);
  }
  return { w: nw, h: nh, px: out };
}

/** Wrap a crop in a white border. ImageMagick's -bordercolour white -border N. */
function border(px, w, h, b) {
  const nw = w + 2 * b, nh = h + 2 * b;
  const out = Buffer.alloc(nw * nh, 0xff);
  for (let y = 0; y < h; y++) px.copy(out, (y + b) * nw + b, y * w, y * w + w);
  return { w: nw, h: nh, px: out };
}

// --- minimal 8-bit greyscale PNG writer -------------------------------------
// Tesseract is handed a file path, so something has to be written. Hand-rolling
// this is cheaper than the alternative: a spawn per crop was the problem.

const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
    t[n] = c;
  }
  return t;
})();

function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body), 0);
  return Buffer.concat([len, body, crc]);
}

/** Write an 8-bit greyscale PNG. Filter 0 on every row: these crops are tiny. */
function writeGrayPng(file, px, w, h) {
  const raw = Buffer.alloc(h * (w + 1));
  for (let y = 0; y < h; y++) {
    raw[y * (w + 1)] = 0;
    px.copy(raw, y * (w + 1) + 1, y * w, y * w + w);   // already one byte per sample here
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8;    // bit depth
  ihdr[9] = 0;    // colour type 0 = greyscale
  const png = Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 6 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
  fs.writeFileSync(file, png);
  return file;
}

/**
 * The in-process equivalent of renderLine's ImageMagick pipeline.
 *
 * @param pagePng  path to the rendered page
 * @param rect     {x0, y0, w, h} in page pixels
 * @param file     where to write the result
 * @param scale    magnification factor (2, 3, 4)
 * @param thr      ImageMagick-style threshold percentage
 * @param borderPx white margin added after trimming
 * @param wantTrim whether to trim the white margin
 */
function renderCrop(pagePng, rect, file, scale = 2, thr = 62, borderPx = 24, wantTrim = true) {
  const page = pageGray(pagePng);
  const { x0, y0, w, h } = rect;

  // Clamp to the page. ImageMagick would pad; here it means a bad rect, and
  // silently padding it would hide that.
  const cw = Math.min(w, page.w - x0);
  const ch = Math.min(h, page.h - y0);
  if (cw <= 0 || ch <= 0) throw new Error(`crop ${w}x${h}+${x0}+${y0} is outside ${page.w}x${page.h}`);

  // Q16 throughout the arithmetic, because ImageMagick's default build is Q16
  // and the threshold that follows sits on a boundary where 8 bits is not
  // enough to agree with it. Two bytes per sample until the threshold, at which
  // point everything is 0 or 255 and one byte is exact again.
  let px = Buffer.alloc(cw * ch * 2);
  for (let y = 0; y < ch; y++) {
    const src = (y0 + y) * page.w + x0;
    for (let x = 0; x < cw; x++) {
      // 8-bit 0..255 widened to Q16 0..65535 by 257. Widening without scaling
      // leaves every sample at 0..255, which is under any threshold above 0.4%
      // -- the crop comes out solid black and the recogniser returns noise.
      const v = page.px[src + x] * 257;
      const j = (y * cw + x) * 2;
      px[j] = v & 0xff;
      px[j + 1] = v >> 8;
    }
  }
  let cw2 = cw, ch2 = ch;

  // scale <= 1 means: do not interpolate at all. The magnification is not
  // adding information -- the roll is a 300 dpi scan, so these glyphs are
  // already at native resolution and enlarging them resamples the watermark
  // along with the text -- but it is also the one step here that cannot be
  // reproduced exactly, so the option exists to measure whether it earns its
  // cost.
  if (scale > 1) {
    const nw = Math.max(1, Math.round(cw * scale));
    const nh = Math.max(1, Math.round(ch * scale));
    const big = Buffer.alloc(nw * nh * 2);
    resample(px, cw, ch, nw, nh, big);
    px = big; cw2 = nw; ch2 = nh;
  }

  // -threshold, at Q16: >= thr% of full range becomes pure white, below it pure
  // black. Narrowed to one byte here, which is lossless.
  const cut = Math.round((thr / 100) * 65535);
  const bin = Buffer.allocUnsafe(cw2 * ch2);
  for (let i = 0, j = 0; j < bin.length; i += 2, j++) {
    const q = px[i] | (px[i + 1] << 8);
    bin[j] = q >= cut ? 0xff : 0x00;
  }
  px = bin;

  if (wantTrim) {
    const t = trim(px, cw2, ch2);
    px = t.px; cw2 = t.w; ch2 = t.h;
  }
  if (borderPx > 0) {
    const b = border(px, cw2, ch2, borderPx);
    px = b.px; cw2 = b.w; ch2 = b.h;
  }
  return writeGrayPng(file, px, cw2, ch2);
}

/** The original ImageMagick path, kept so the two can be compared byte for byte. */
function renderCropViaImageMagick(pagePng, rect, file, scale = 2, thr = 62, borderPx = 24, wantTrim = true) {
  const args = [pagePng,
    '-crop', `${rect.w}x${rect.h}+${rect.x0}+${rect.y0}`, '+repage',
    '-colorspace', 'Gray', '-resize', `${scale * 100}%`,
    '-threshold', `${thr}%`];
  if (wantTrim) args.push('-trim', '+repage');
  if (borderPx > 0) args.push('-bordercolor', 'white', '-border', String(borderPx));
  args.push(file);
  execFileSync('convert', args);
  return file;
}

module.exports = {
  renderCrop, renderCropViaImageMagick, pageGray, clearPageCache,
  resample, trim, border, writeGrayPng,
};