// Print the bench mat sheets to PDF, the artwork's own way.
//
// The mat is a vector design study in explainer/prototype-bench-mat.html, drawn as SVG in
// millimetres and sized by its own @page rule. A printed measuring surface is only worth
// something if a millimetre in the artwork is a millimetre on the paper, so nothing here
// names A4, scales, or re-lays anything out: Firefox prints the page and the page decides
// the paper.
//
// Firefox over WebDriver BiDi, through the harness the browser checks already use, so this
// costs no dependency. OpenCV is not needed to make the sheets, only to check them
// afterwards, which is a separate job run by hand.
//
//   node scripts/print-mats.mjs                 all six sheets into explainer/mats/
//   node scripts/print-mats.mjs --references tetris   the corner-shape alternative instead
//
// Each sheet's geometry is written beside the PDFs as geometry.json, taken from the page's
// own window.DeskcamMat rather than measured off the render, so a future detector reads
// what the artwork declared.
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { browser } from '../backend/test/webui/bidi.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const PAGE = join(ROOT, 'explainer', 'prototype-bench-mat.html');
const OUT = join(ROOT, 'explainer', 'mats');

const VARIANTS = ['hybrid', 'quiet', 'angles'];
const ORIENTATIONS = ['landscape', 'portrait'];

// A4 in millimetres, which is what the page must have asked the printer for.
const PAGE_MM = { landscape: [297, 210], portrait: [210, 297] };

const arg = name => {
  const i = process.argv.indexOf(name);
  return i === -1 ? null : process.argv[i + 1];
};
const references = arg('--references') === 'tetris' ? 'tetris' : 'coded';

/** Waits for the sheet to be drawn, not merely for the document to load. */
async function drawn(b) {
  for (let i = 0; i < 100; i++) {
    const ready = await b.json(
      `window.DeskcamMat && document.fonts.status === 'loaded'
         ? document.getElementById('sheet').childElementCount : 0`,
    );
    if (ready > 50) return ready;
    await new Promise(r => setTimeout(r, 100));
  }
  throw new Error('the sheet never drew');
}

const b = await browser({ width: 1400, height: 1000 });
const manifest = { generator: 'scripts/print-mats.mjs', references, sheets: {} };
try {
  mkdirSync(OUT, { recursive: true });
  for (const orientation of ORIENTATIONS) {
    for (const variant of VARIANTS) {
      const url = `file://${PAGE}?orientation=${orientation}&variant=${variant}&references=${references}`;
      await b.navigate(url);
      const nodes = await drawn(b);

      // The page is the authority on what it drew. Trust it only after it agrees with what
      // was asked for, so a typo in a parameter cannot quietly print the default sheet.
      const mat = await b.json('window.DeskcamMat');
      const [w, h] = PAGE_MM[orientation];
      if (mat.page[0] !== w || mat.page[1] !== h) {
        throw new Error(`${orientation}: the page says ${mat.page} and A4 is ${[w, h]}`);
      }
      if (mat.referenceMode !== references) {
        throw new Error(`${orientation}/${variant}: references are ${mat.referenceMode}`);
      }
      const shown = await b.json(`document.getElementById('variant-name').textContent`);

      const stem = `deskcam-mat-a4-${orientation}-${variant}${references === 'tetris' ? '-tetris' : ''}`;
      await b.print(join(OUT, `${stem}.pdf`));
      manifest.sheets[stem] = { orientation, variant, shown, nodes, ...mat };
      console.log(`${stem}.pdf  ${mat.revision}  ${mat.page.join(' x ')} mm  ${nodes} nodes`);
    }
  }

  const errors = b.errors();
  if (errors.length) throw new Error(`the page logged errors: ${JSON.stringify(errors)}`);

  writeFileSync(join(OUT, 'geometry.json'), `${JSON.stringify(manifest, null, 2)}\n`);
  console.log(`\ngeometry.json: ${Object.keys(manifest.sheets).length} sheets, no page errors`);
} finally {
  await b.close();
}
