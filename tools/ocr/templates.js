// Glyph-level template matching, trained on the electoral roll's own serial
// field.
//
// Why this exists. Tesseract reads the serial field 30/30 and the voter id
// 29/30, but only 25/30 on the house number -- and the house number is the field
// the booklet is built on, since it decides who counts as one family. Its
// failures are shape confusions on clean, legible glyphs: "91-7-7/1" read as
// "1-1-6", "95-4-4/3" as "17-1-15", "96-4-4/4" as "95-6-6/14". Reading the whole
// card with a tighter crop and a different page-segmentation mode did not move
// it; nine preprocessing variants and a majority vote still left those five.
//
// Why templates work here. The roll is printed, not handwritten: one font, one
// size, and the same glyph repeated thousands of times. That makes it a
// closed-set problem, which is the kind a nearest-template classifier solves
// outright and a language model -- which is what tesseract is -- solves badly,
// because the language model is the part carrying the "6 looks like a 4" prior.
//
// Where the labels come from. The serial field. The roll prints serials in
// ascending order, so card i of page p has a *known* serial: its glyphs are
// labelled without anyone typing them. Digits are the same typeface as the house
// digits and, normalised by height, the same shape -- serial digits stand 75-79 px
// tall against the house number's 48-53 px, and 11-1-x repeats across the roll.
//
// Ambiguity is reported, not resolved. classify() returns the distance to the
// runner-up as well as to the winner. A glyph whose two best candidates are
// close is not guessed at; it is flagged, because a wrong house number invents a
// household and a flagged one costs the user ten seconds.

const { bands } = require('./grid.js');

const CANVAS_W = 40;      // glyphs are scaled to INK_H tall and centred here
const CANVAS_H = 32;
const INK_H = 28;
const MERGE_GAP = 5;      // runs closer than this are one glyph; real gaps are 9-21 px

// Column ink profile, as a boolean per column.
function columnInk(img, thr = 128) {
  const { w, h, px } = img;
  const out = new Uint8Array(w);
  for (let x = 0; x < w; x++) {
    let n = 0;
    for (let y = 0; y < h; y++) if (px[y * w + x] < thr) n++;
    out[x] = n > 0 ? 1 : 0;
  }
  return out;
}

// Glyph boxes across a line, as [x0, x1] pairs.
function glyphRuns(img, mergeGap = MERGE_GAP) {
  const runs = bands(columnInk(img), 1);
  const out = [];
  for (const r of runs) {
    const prev = out[out.length - 1];
    if (prev && r[0] - prev[1] <= mergeGap) prev[1] = r[1];
    else out.push([r[0], r[1]]);
  }
  return out;
}

// The line's ink extent, which sets the baseline everything else is judged
// against. A stray watermark speck inside the crop must not set this.
function lineInkBox(img) {
  const { w, h, px } = img;
  let x0 = Infinity, x1 = -1, y0 = Infinity, y1 = -1;
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      if (px[y * w + x] < 128) {
        if (x < x0) x0 = x;
        if (x > x1) x1 = x;
        if (y < y0) y0 = y;
        if (y > y1) y1 = y;
      }
    }
  }
  return x1 < 0 ? null : { x0, x1, y0, y1 };
}

// One glyph's own ink box: the rows carrying ink within its column range.
//
// This must be the glyph's own extent, not the line's. Normalising every glyph
// to the line's height makes a hyphen and a digit both come out 28 px tall, which
// throws away exactly the cue that tells them apart -- the first run of the
// matcher classified all thirty house numbers as strings of "1"s for that reason.
function glyphInkBox(img, x0, x1) {
  const { w, px } = img;
  let y0 = Infinity, y1 = -1;
  for (let y = 0; y < img.h; y++) {
    for (let x = x0; x <= x1; x++) {
      if (px[y * w + x] < 128) { if (y < y0) y0 = y; if (y > y1) y1 = y; break; }
    }
  }
  return y1 < 0 ? null : { y0, y1 };
}

// One glyph as a fixed-size bitmap.
//
// Scaled by *height only*, so aspect ratio -- a strong cue, since a "1" is narrow
// and tall while a hyphen is wide and flat -- is preserved rather than normalised
// away. Centred horizontally on a fixed canvas so two glyphs can be compared cell
// by cell.
function glyphBitmap(img, x0, x1) {
  const box = glyphInkBox(img, x0, x1);
  if (!box) return null;
  const gw = x1 - x0 + 1;
  const gh = box.y1 - box.y0 + 1;
  if (gw < 2 || gh < 2) return null;
  const scale = INK_H / gh;
  const tw = Math.max(1, Math.round(gw * scale));
  const canvas = new Uint8Array(CANVAS_W * CANVAS_H);
  const ox = Math.max(0, Math.round((CANVAS_W - tw) / 2));
  const oy = Math.max(0, Math.round((CANVAS_H - INK_H) / 2));

  // Nearest-neighbour resample. The source is already thresholded, so this is
  // cheap and introduces no new grey levels to blur the comparison.
  for (let ty = 0; ty < INK_H; ty++) {
    const sy = box.y0 + Math.min(gh - 1, Math.floor(ty / scale));
    for (let tx = 0; tx < tw; tx++) {
      const sx = x0 + Math.min(gw - 1, Math.floor(tx / scale));
      if (px_at(img, sy, sx) < 128) {
        const cx = ox + tx, cy = oy + ty;
        if (cx < CANVAS_W && cy < CANVAS_H) canvas[cy * CANVAS_W + cx] = 1;
      }
    }
  }
  return { bits: canvas, aspect: gw / gh, w: gw, h: gh, density: inkFraction(img, x0, x1, box) };
}

// Fraction of a glyph's own bounding box that carries ink. Digits in this font
// are chunky -- roughly a third to a half filled -- while a slash is a thin
// diagonal stroke sitting at well under half. That separates the slash from the
// digits without a template for it, which matters because the templates only
// cover digits.
function inkFraction(img, x0, x1, box) {
  const { px, w } = img;
  let n = 0;
  const area = (x1 - x0 + 1) * (box.y1 - box.y0 + 1);
  for (let y = box.y0; y <= box.y1; y++) {
    for (let x = x0; x <= x1; x++) if (px[y * w + x] < 128) n++;
  }
  return n / area;
}

const px_at = (img, y, x) => img.px[y * img.w + x];

// Distance between two glyph bitmaps: the fraction of canvas pixels on which the
// two disagree, minimised over a small shift.
//
// Shift tolerance is not a refinement, it is what makes this work. Every glyph is
// scaled by its own height and centred on its bounding box, so two instances of
// the same digit routinely land a pixel or two apart. Compared strictly, that
// reads as a large disagreement -- the first version managed 30% on held-out
// serials, glyphs of the same font, size and field, for exactly this reason.
// Allowing the template to slide two pixels in each direction fixes it.
//
// Jaccard distance (1 - |A n B| / |A u B|) was tried instead, on the theory that
// dividing by the union would forgive a half-pixel-wide glyph. It is worse: the
// house digits score 0.09-0.66 under Jaccard against 0.006-0.17 here, because the
// two fields' glyphs genuinely differ in shape once normalised -- the house digits
// are roughly two thirds the height of the serial digits and are upscaled from a
// smaller original. That difference is real, not a metric artefact, and dividing by
// the union hides it instead of exposing it.
function distance(a, b, maxShift = 2) {
  const w = CANVAS_W;
  let best = Infinity;
  for (let dy = -maxShift; dy <= maxShift; dy++) {
    for (let dx = -maxShift; dx <= maxShift; dx++) {
      let d = 0;
      for (let y = 0; y < CANVAS_H; y++) {
        const sy = y - dy;
        if (sy < 0 || sy >= CANVAS_H) continue;
        for (let x = 0; x < w; x++) {
          const sx = x - dx;
          const bv = (sx < 0 || sx >= w) ? 0 : b[sy * w + sx];
          if (a[y * w + x] !== bv) d++;
        }
        if (d >= best) break;                        // this shift is already worse
      }
      if (d < best) best = d;
    }
  }
  return best / (CANVAS_W * CANVAS_H);
}

// Keeps every labelled glyph rather than averaging them into one composite.
//
// Averaging was tried first and is wrong here. Blurring fifty samples together
// and re-thresholding at 34% widens every stroke, so "2" and "3" collapse toward
// each other and "0" becomes a blob -- the rendered template sheet shows it
// plainly. A real rendered glyph, used as it is, keeps the stroke width the font
// actually has.
function buildTemplates(samples) {
  const out = {};
  for (const [ch, list] of Object.entries(samples)) if (list.length) out[ch] = list;
  return out;
}

// Classifies one glyph by its nearest labelled exemplar.
//
// Nearest-exemplar rather than nearest-average: each class keeps the whole
// distribution of how that digit renders on this scan, and a glyph that sits
// between two classes -- degraded, or a letter where a digit was expected --
// shows up as a small margin instead of being silently absorbed into one of them.
function classify(bitmap, templates, { maxShift = 2 } = {}) {
  const scored = [];
  for (const [ch, list] of Object.entries(templates)) {
    let best = Infinity;
    for (const t of list) {
      const d = distance(bitmap.bits, t, maxShift);
      if (d < best) best = d;
      if (best === 0) break;
    }
    scored.push({ ch, d: best });
  }
  scored.sort((a, b) => a.d - b.d);
  const best = scored[0];
  const second = scored[1];
  return {
    ch: best ? best.ch : null,
    dist: best ? best.d : 1,
    second: second ? second.ch : null,
    secondDist: second ? second.d : 1,
    margin: best && second ? second.d - best.d : 1,
  };
}

// Rejects an outcome that is not confidently a single digit.
function decide(result, minMargin, maxDist) {
  if (!result.ch) return { ok: false, why: 'no template matched' };
  if (result.dist > maxDist) return { ok: false, why: `best distance ${result.dist.toFixed(3)} over ${maxDist}` };
  if (result.margin < minMargin) {
    return { ok: false, why: `ambiguous: ${result.ch} vs ${result.second} (margin ${result.margin.toFixed(4)})` };
  }
  return { ok: true, ch: result.ch };
}

// Blanks out columns that are almost entirely ink.
//
// Those are the card's own printed rules -- the serial number is drawn inside a
// box, and the box's right-hand border runs the full height of the crop. A digit's
// column never does. Left in, the border is not merely ignored: tesseract reads
// "11" followed by a full-height bar as one token and returns "1", so serial 11
// came back as 1. That cost 1006 of 1128 serials on the English roll before the
// rule was removed, and no crop edge avoids it -- the digits sit close enough to
// the border that any edge tight enough to exclude it clips them.
//
// Mutates the image in place and returns the number of columns blanked.
function stripRules(img, frac = 0.7) {
  const { w, h, px } = img;
  let n = 0;
  for (let x = 0; x < w; x++) {
    let ink = 0;
    for (let y = 0; y < h; y++) if (255 - px[y * w + x] > 100) ink++;
    if (ink > h * frac) {
      n++;
      for (let y = 0; y < h; y++) px[y * w + x] = 255;
    }
  }
  return n;
}

module.exports = {
  CANVAS_W, CANVAS_H, INK_H, MERGE_GAP,
  columnInk, glyphRuns, lineInkBox, glyphInkBox, glyphBitmap,
  distance, buildTemplates, classify, decide, stripRules,
};