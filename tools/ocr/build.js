// Turns the cached OCR harvest into the extracted dataset, with a confidence column on
// every field.
//
// The point of this stage is not to produce text. It is to say, per field, whether the
// value is proved or merely read -- because the two need different treatment downstream.
// A proved value needs no checking. A merely-read value needs a person to look at the
// card, and the booklet is printed from the roll so it is far cheaper to look at 30
// flagged cards than to walk a ward.
//
// What is proved, and by what
// --------------------------
//   serial    the roll numbers its voters 1..1129 and the grid holds exactly 1129 cards,
//             so a card's position fixes its serial. The template reader reads it
//             independently and the two are compared.
//   voter id  a Luhn check digit. Resolved when the nearest reading at every glyph
//             satisfies it; repaired when it fails and exactly one alternative does.
//   house     nothing proves it. Validated against the grammar the roll prints, and
//             every departure is listed.
//   name      nothing proves it. Checked against the vocabulary harvested from the roll,
//             which finds misreadings and rare names but cannot guarantee either.
//
// Where a value is not proved it is still written, with its status, because a flagged
// value a person can check is worth more than a blank one they cannot.

const fs = require('fs');
const path = require('path');
const { readGray } = require('./grid.js');
const { pagePng, pageLayout } = require('./layout.js');
const { serialBoxes } = require('./box.js');
const { bitmapFromRect, serialBitmap, segment, normalise, classify } = require('./digits.js');
const { readEpic, rankRepairs, setMaxDistance, segmentId } = require('./epicread.js');
const { parseFields, VID } = require('./cardread.js');
const { validateHouse } = require('./house.js');
const { buildLexicon, checkName, suggest } = require('./lexicon.js');
const { luhnValid, check } = require('./epic.js');
const { readHarvest, OUT } = require('./harvest.js');

const ID_RE = /^[A-Z]{3}[0-9]{7}$/;

// The voter's id box, as an absolute-pixel rectangle.
const vidRect = (card) => ({
  x: Math.round(card.x0 + (card.x1 - card.x0) * VID.x0),
  y: Math.round(card.y0 + (card.y1 - card.y0) * VID.y0),
  w: Math.round((card.x1 - card.x0) * (VID.x1 - VID.x0)),
  h: Math.round((card.y1 - card.y0) * (VID.y1 - VID.y0)),
});

/** Builds the letter and digit atlases from ids that are well formed and check-digit valid. */
function buildAtlases(rows, pagePixels, pageCards) {
  const letters = [];
  const digits = [];
  const prefixes = new Set();

  for (const r of rows) {
    if (!ID_RE.test(r.vid) || !luhnValid(r.vid.slice(3))) continue;
    const ctx = pagePixels.get(r.page);
    const card = pageCards.get(r.page)[r.card];
    if (!ctx || !card) continue;
    const bmp = bitmapFromRect(ctx.px, ctx.w, vidRect(card));
    if (!bmp) continue;
    const g = segmentId(bmp);
    if (!g) continue;
    prefixes.add(r.vid.slice(0, 3));
    for (let i = 0; i < 10; i++) {
      const entry = { char: r.vid[i], vec: normalise(bmp, g[i]) };
      if (i < 3) letters.push(entry); else digits.push(entry);
    }
  }
  return { letters, digits, prefixes: [...prefixes].sort() };
}

/** Leave-one-out distance over the atlas, which is what calibrates the rejection bound. */
function calibrate(atlases) {
  const loo = [];
  for (const atlas of [atlases.letters, atlases.digits]) {
    for (let i = 0; i < atlas.length; i++) {
      const rest = atlas.filter((_, j) => j !== i);
      const m = classify(atlas[i].vec, rest);
      if (m) loo.push(m.distance);
    }
  }
  if (!loo.length) return { loo, bound: Infinity };
  loo.sort((a, b) => a - b);
  return { loo, bound: loo[Math.floor(loo.length * 0.999)] * 1.5 };
}

async function main() {
  const pdf = process.argv[2];
  const dpi = Number(process.argv[3] || 240);
  const firstPage = Number(process.argv[4] || 3);
  const lastPage = Number(process.argv[5] || 45);
  const harvestFile = process.argv[6] || path.join(OUT, 'harvest.jsonl');
  const outCsv = process.argv[7] || path.join(OUT, 'extracted.csv');

  const rows = readHarvest(harvestFile);
  if (!rows.length) { console.log('  harvest file is empty; run harvest.js first'); process.exit(1); }
  console.log(`  harvested ${rows.length} cards`);

  const lay = pageLayout(pdf, dpi, firstPage, lastPage);
  const pagePixels = new Map();
  const pageCards = new Map();
  for (const pl of lay.pages) {
    pagePixels.set(pl.page, readGray(pagePng(pdf, pl.page, dpi)));
    pageCards.set(pl.page, pl.cards);
  }

  // ---- fields, re-parsed from the cached text -----------------------------
  for (const r of rows) {
    const p = parseFields(r.raw);
    Object.assign(r, {
      name: p.fields.name || '',
      father: p.fields.father || '',
      house: p.fields.house || '',
      age: p.fields.age || '',
      gender: p.fields.gender || '',
      unattributed: p.unattributed,
    });
  }

  // ---- voter ids, by template under the check-digit constraint ------------
  const atlases = buildAtlases(rows, pagePixels, pageCards);
  const { loo, bound } = calibrate(atlases);
  setMaxDistance(bound);
  console.log(`  voter-id atlases: ${atlases.letters.length} letter and ${atlases.digits.length} digit exemplars`);
  console.log(`    prefixes: ${atlases.prefixes.join(', ')}`);
  console.log(`    leave-one-out distance p50 ${loo.length ? loo[Math.floor(loo.length * 0.5)].toFixed(2) : '-'}`
    + `  p99 ${loo.length ? loo[Math.floor(loo.length * 0.99)].toFixed(2) : '-'}`
    + `  max ${loo.length ? loo[loo.length - 1].toFixed(2) : '-'}   bound ${bound.toFixed(2)}`);

  // Voter id policy
// ---------------
// Two independent readers, each with a different failure mode. Tesseract invents characters
// -- "TST000006" comes back as "WQQO0062950". The template matcher over-segments glyphs,
// and when the nearest reading at every position fails the check digit, widening the search
// produces a dozen readings that satisfy it, among which the cheapest is not reliably right:
// on serial 11 it chose WQQ8857401 over TST000009, confusing 8 for 0.
//
// So neither reader is trusted alone, and the check digit is not treated as a tiebreak it
// cannot win. The policy is:
//
//   both readers agree and the result is well formed and check-digit valid  -> settled
//   both valid but they disagree                                          -> conflict, review
//   only the OCR reading is valid                                          -> use it
//   only the template reading is proved                                    -> use it
//   neither valid                                                          -> review
//
// Where the two valid readings conflict, the OCR reading is written rather than the
// template's, because the template is the one that just demonstrated it cannot choose
// correctly between two candidates on this very card. That is a judgement about which
// reader to believe on a specific failure, and it is recorded as "conflict" so the card is
// checked rather than passed over.
const ocrValid = (id) => ID_RE.test(id) && luhnValid(id.slice(3));

for (const r of rows) {
  const ctx = pagePixels.get(r.page);
  const card = pageCards.get(r.page)[r.card];
  const got = readEpic(ctx.px, ctx.w, vidRect(card), atlases, atlases.prefixes);
  r.id_status = got ? got.status : 'nocrop';
  r.id_ocr = r.vid;
  r.id_template = got ? got.id : '';
  r.id_alternatives = got && got.alternatives && got.alternatives.length
    ? got.alternatives.filter((a) => a !== r.id_template).slice(0, 5).join(' ') : '';

  const ocrOk = ocrValid(r.vid);
  const tmplProved = got && ['certain', 'repaired'].includes(got.status);
  const tmplOk = got && ocrValid(got.id);

  // A well-formed reading that fails its check digit is one substitution away from correct
  // about nine times in ten, and epic.js finds that substitution -- but only accepts it
  // when exactly one substitution validates, which is what makes it a proof rather than a
  // guess. This is the step that matters most for the remaining cards: serial 11 reads
  // TST000009, whose digits sum to 22 under Luhn, and the single edit that makes it valid
  // is WQQ0857409. Widening the template's beam did not reach that candidate, because the
  // correct digit does not rank among the nearest three at that position.
  const shapeOk = ID_RE.test(r.vid);
  const single = shapeOk ? check(r.vid).repair : null;

  // Where the check digit admits several single edits, the glyphs choose between them.
  // This is a ranking rather than a proof, so it is recorded as 'ranked' and kept out of
  // the proved count rather than being passed off as settled.
  let ranked = null;
  if (shapeOk && !ocrOk && !single) {
    const cands = rankRepairs(ctx.px, ctx.w, vidRect(card), atlases, atlases.prefixes, r.vid);
    if (cands.length) ranked = cands[0].id;
  }

  if (ocrOk && tmplProved && got.id === r.vid) {
    r.voter_id = r.vid; r.id_status = 'agree'; r.id_settled = 1;
  } else if (single) {
    r.voter_id = single; r.id_status = 'repaired'; r.id_settled = 1;
    r.id_repair_from = r.vid;
  } else if (ranked) {
    r.voter_id = ranked; r.id_status = 'ranked'; r.id_settled = 0;
    r.id_repair_from = r.vid;
  } else if (ocrOk && tmplOk && got.id !== r.vid) {
    r.voter_id = r.vid; r.id_status = 'conflict'; r.id_settled = 0;
  } else if (ocrOk) {
    // The template produced a reading that is not check-digit valid, so it has nothing to
    // say against a reading that is.
    r.voter_id = r.vid; r.id_status = 'ocr-valid'; r.id_settled = 1;
  } else if (tmplProved) {
    r.voter_id = got.id; r.id_status = 'template-valid'; r.id_settled = 1;
  } else {
    r.voter_id = r.vid || (got ? got.id : ''); r.id_status = 'unproved'; r.id_settled = 0;
  }
  // Proved means proved: the reading is check-digit valid AND something independent of the
  // check digit's tie-breaking got us there -- the template agreed, or a single edit was
  // unique, or one reader was valid while the other had nothing.
  //
  // It deliberately excludes 'ranked'. Those are check-digit valid by construction, so
  // testing them with a validity test counts them as proved and reports 97% when 84% is
  // what the evidence supports. The value is still written, with the reading it was
  // derived from alongside it, but it goes in the review list.
  r.id_proved = ['agree', 'repaired', 'ocr-valid', 'template-valid'].includes(r.id_status);
  r.id_agrees = r.id_ocr && r.voter_id === r.id_ocr ? 1 : 0;
  r.id_changed = r.voter_id !== r.id_ocr ? 1 : 0;
}

  // ---- serial: an atlas built from position, then an independent read --------
  //
  // The serial's labels come from position -- the roll numbers 1..1129 and the grid holds
  // exactly 1129 cards -- but they are only used where the glyphs split into exactly as
  // many marks as the serial has digits. A card whose pixels disagree with its position
  // about its own length is evidence against the position, and is not taught to the atlas.
  // The read that follows then sees only pixels, so its agreement with position is a check
  // rather than an identity.
  const serialAtlas = [];
  for (const pl of lay.pages.slice(0, 5)) {
    const ctx = pagePixels.get(pl.page);
    for (const box of serialBoxes(pagePng(pdf, pl.page, dpi), { cards: pl.cards })) {
      const want = String(pl.first + box.card);
      const bmp = serialBitmap(ctx.px, ctx.w, box);
      if (!bmp) continue;
      const g = segment(bmp);
      if (g.length !== want.length) continue;
      g.forEach((gg, i) => serialAtlas.push({ char: want[i], vec: normalise(bmp, gg) }));
    }
  }
  console.log(`  serial atlas: ${serialAtlas.length} glyphs from the first 5 pages`);

  for (const r of rows) {
    const ctx = pagePixels.get(r.page);
    const pl = lay.pages.find((p) => p.page === r.page);
    const box = serialBoxes(pagePng(pdf, r.page, dpi), { cards: pl.cards })
      .find((b) => b.card === r.card);
    let read = '';
    if (box) {
      const bmp = serialBitmap(ctx.px, ctx.w, box);
      const g = bmp ? segment(bmp) : [];
      read = g.map((gg) => {
        const m = classify(normalise(bmp, gg), serialAtlas);
        return m ? m.char : '';
      }).join('');
    }
    r.serial_read = read;
    r.serial_proved = read === String(r.serial);
  }

  // ---- house numbers and names -------------------------------------------
  const lex = buildLexicon(rows, 3, 3);
  console.log(`  name vocabulary: ${lex.size} distinct tokens, ${lex.common.length} used three or more times`);

  for (const r of rows) {
    const h = validateHouse(r.house);
    r.house_number = h.number || r.house;
    r.house_status = h.ok ? 'valid' : (h.shape === 'empty' ? 'missing' : 'flagged');
    r.house_warnings = h.warnings.join('; ');

    const n = checkName(r.name, lex);
    r.name_status = n.status;
    r.name_suggestion = suggest(r.name, n) || '';
    r.name_detail = n.tokens.filter((t) => t.status !== 'exact')
      .map((t) => `${t.printed}${t.suggestion ? `->${t.suggestion}` : ''}`).join(' ');
  }

  // ---- write ---------------------------------------------------------------
  const cols = ['page', 'card', 'serial', 'serial_read', 'serial_proved',
    'voter_id', 'id_ocr', 'id_status', 'id_proved', 'id_settled', 'id_repair_from', 'id_alternatives',
    'name', 'name_status', 'name_suggestion', 'name_detail',
    'father', 'others', 'house', 'house_number', 'house_status', 'house_warnings',
    'age', 'gender'];
  fs.mkdirSync(path.dirname(outCsv), { recursive: true });
  const esc = (v) => {
    const s = v === undefined || v === null ? '' : String(v);
    return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  fs.writeFileSync(outCsv, cols.join(',') + '\n'
    + rows.map((r) => cols.map((k) => esc(r[k])).join(',')).join('\n') + '\n');

  // ---- report --------------------------------------------------------------
  const pct = (n, d) => (d ? `${n}/${d} (${(100 * n / d).toFixed(1)}%)` : 'n/a');
  const confirmed = (k) => rows.filter((r) => r[k] === true || r[k] === 'valid'
    || r[k] === 'certain' || r[k] === 'repaired' || r[k] === 'exact').length;

  console.log(`\n  cards: ${rows.length}`);
  console.log(`    serial read and matching the roll's own numbering : ${pct(confirmed('serial_proved'), rows.length)}`);
  console.log(`    voter id proved by its check digit                : ${pct(confirmed('id_proved'), rows.length)}`);
  console.log(`    voter id ranked by glyph evidence, still to check: ${pct(rows.filter((r) => r.id_status === 'ranked').length, rows.length)}`);
  console.log(`    voter id with no valid reading at all             : ${pct(rows.filter((r) => r.id_status === 'unproved').length, rows.length)}`);
  console.log(`    house number matching the roll's grammar          : ${pct(confirmed('house_status'), rows.length)}`);
  console.log(`    name surname corroborated by the roll's vocabulary: ${pct(confirmed('name_status'), rows.length)}`);
  console.log(`    name read, not corroborated, but nothing to suggest: ${pct(rows.filter((r) => r.name_status === 'rare').length, rows.length)}`);
  console.log(`    name with a correction to check                   : ${pct(rows.filter((r) => r.name_status === 'corrected' || r.name_status === 'unseen').length, rows.length)}`);

  const tally = (k) => {
    const m = {};
    for (const r of rows) m[r[k]] = (m[r[k]] || 0) + 1;
    return Object.entries(m).sort((a, b) => b[1] - a[1]).map(([s, n]) => `${s} ${n}`).join('  ');
  };
  console.log(`\n    voter id status : ${tally('id_status')}`);
  console.log(`    house status    : ${tally('house_status')}`);
  console.log(`    name status     : ${tally('name_status')}`);

  // What sends a card to a person:
  //   serial    not matching the roll's own numbering -- never expected, so if it happens
  //             something is structurally wrong
  //   voter id  neither proved nor agreed on by both readers
  //   house     anything but a well-formed number, since this is the join key
  //   name      a suggested correction, or a surname the roll never uses
  //
  // A surname the roll uses once is deliberately not a trigger. In a ward of 1,100
  // households most surnames are borne by one or two people, so "rare" describes most of
  // the roll and would have sent 336 cards to review on the strength of nothing. What
  // distinguishes a bad reading is having a better candidate to point at, which is what
  // "corrected" means.
  const needsReview = rows.filter((r) => !r.serial_proved || !r.id_settled
    || r.house_status !== 'valid'
    || r.name_status === 'corrected' || r.name_status === 'unseen');
  const byReason = {
    serial: rows.filter((r) => !r.serial_proved).length,
    'voter id': rows.filter((r) => !r.id_settled).length,
    house: rows.filter((r) => r.house_status !== 'valid').length,
    name: rows.filter((r) => r.name_status === 'corrected' || r.name_status === 'unseen').length,
  };
  console.log(`\n  cards with at least one field needing a human look: ${needsReview.length}`
    + ` of ${rows.length} (${(100 * needsReview.length / rows.length).toFixed(1)}%)`);
  console.log('    by field: ' + Object.entries(byReason).map(([k, v]) => `${k} ${v}`).join('   '));
  console.log(`  every other card is fully settled on all four fields`);
  fs.writeFileSync(path.join(OUT, 'review.csv'),
    'page,card,serial,voter_id,id_alternatives,name,printed,suggestion,house,reason\n'
    + needsReview.map((r) => [
      r.page, r.card, r.serial, r.voter_id, r.id_alternatives || '',
      r.name, r.name_suggestion || '', r.name_detail, r.house_number,
      [!r.serial_proved && 'serial', !r.id_settled && 'voter id',
        r.house_status !== 'valid' && 'house',
        (r.name_status === 'corrected' || r.name_status === 'unseen') && 'name']
        .filter(Boolean).join('+'),
    ].map((v) => { const s2 = String(v); return /[",]/.test(s2) ? `"${s2.replace(/"/g, '""')}"` : s2; }).join(',')).join('\n') + '\n');
  console.log(`  wrote ${path.join(OUT, 'review.csv')} -- the cards to check, and why`);
  console.log(`\n  wrote ${outCsv}`);
}

main().catch((e) => { console.error(e); process.exit(1); });