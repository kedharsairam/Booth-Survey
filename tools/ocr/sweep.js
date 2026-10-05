// Sweeps the serial recognition settings and reports what actually works.
//
// The serial is the field most sensitive to how it is presented, and the settings
// that present it best are not the ones intuition suggests. Measured on English
// page 3 so far: nearest-neighbour magnification, which preserves the hard edge the
// binarised crop has, reads 25 of 30 because it aliases the thin strokes; the default
// interpolating filter, which softens that edge, reads 28. Both are below what the
// research on this failure mode reported, so the right answer is measured rather than
// picked.
//
// Two axes are independent and both matter:
//   render  -- magnification filter and factor, which change the glyph raster
//   psm     -- how the page is segmented, which changes what counts as one token
//
// Rendering is the slow half, so each render setting is produced once and then read
// under every psm with a single reused worker.

const { createWorker } = require('tesseract.js');
const { detect } = require('./grid.js');
const { pagePng, pageLayout } = require('./layout.js');
const { serialBoxes, renderSerial } = require('./box.js');

const RENDERS = [
  { geom: '300%x300%' },
  { geom: '400%x300%' },
  { geom: '500%x300%' },
  { geom: '400%x300%', filter: 'point' },
  { geom: '400%x300%', filter: 'mitchell' },
];
const PSMS = ['7', '8', '13'];

async function main() {
  const pdf = process.argv[2];
  const page = Number(process.argv[3] || 3);
  const dpi = Number(process.argv[4] || 240);
  const firstPage = Number(process.argv[5] || 3);
  const lastPage = Number(process.argv[6] || 45);

  const png = pagePng(pdf, page, dpi);
  const grid = detect(png);
  const boxes = serialBoxes(png, grid);
  const pl = pageLayout(pdf, dpi, firstPage, lastPage).pages.find((p) => p.page === page);
  const expected = Array.from({ length: pl.count }, (_, i) => pl.first + i);

  const worker = await createWorker('eng', 1, {
    cachePath: process.env.TESSDATA_PREFIX, gzip: false,
  });
  await worker.setParameters({ tessedit_char_whitelist: '0123456789' });

  const results = [];
  for (const r of RENDERS) {
    const files = boxes.map((b) =>
      renderSerial(png, b, `/tmp/opencode/voter/ocr/sw_${b.card}.png`, r));

    for (const psm of PSMS) {
      let ok = 0;
      const wrong = [];
      for (let i = 0; i < boxes.length; i++) {
        const res = await worker.recognize(files[i],
          { tessedit_pageseg_mode: psm }, { text: true });
        const got = res.data.text.replace(/[^0-9]/g, '');
        const want = String(expected[boxes[i].card]);
        if (got === want) ok++;
        else wrong.push(`${want}->${got || 'empty'}`);
      }
      results.push({ ...r, psm, ok, total: boxes.length, wrong });
    }
  }
  await worker.terminate();

  results.sort((a, b) => b.ok - a.ok);
  console.log(`  page ${page}, ${boxes.length} cards\n`);
  console.log('  geometry            filter      psm   correct');
  for (const r of results) {
    console.log(`  ${r.geom.padEnd(18)}  ${(r.filter || 'default').padEnd(10)}  ${r.psm.padStart(3)}`
      + `   ${String(r.ok).padStart(2)}/${r.total}`
      + (r.ok === r.total ? '   all' : `   ${r.wrong.join(' ')}`));
  }
}

main().catch((e) => { console.error(e); process.exit(1); });