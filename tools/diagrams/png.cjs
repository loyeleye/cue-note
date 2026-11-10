// Renders the diagrams to PNG for the phone (Compose has no SVG decoder).
// Needs Playwright with Chromium. Run from the repository root after gen.py:
//   node tools/diagrams/png.cjs
// Writes android/feature/lesson/src/main/assets/diagrams/<id>.png (720x480)
// and a copy of index.json.
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '../..');
const src = path.join(root, 'web/static/diagrams');
const out = path.join(root, 'android/feature/lesson/src/main/assets/diagrams');

(async () => {
  fs.mkdirSync(out, { recursive: true });
  const index = JSON.parse(fs.readFileSync(path.join(src, 'index.json'), 'utf8'));
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 360, height: 240 }, deviceScaleFactor: 2 });
  for (const d of index) {
    const svg = fs.readFileSync(path.join(src, `${d.id}.svg`), 'utf8');
    await page.setContent(`<body style="margin:0">${svg}</body>`);
    await page.locator('svg').screenshot({ path: path.join(out, `${d.id}.png`), omitBackground: true });
  }
  await browser.close();
  fs.copyFileSync(path.join(src, 'index.json'), path.join(out, 'index.json'));
  console.log(`${index.length} diagrams`);
})();
