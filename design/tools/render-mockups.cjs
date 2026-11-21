// Render the screen mockups to design/mockups/png/<name>-<light|dark>.png (393 x 852 @2x).
//   source /srv/offlinka/env.sh; atd-render timeout 300 node design/tools/render-mockups.cjs [name-filter]
// One Chromium, one page after another (never in parallel).
const path = require('path'), fs = require('fs');
const { chromium } = require(process.env.PLAYWRIGHT_DIR || '/srv/offlinka/drive/node_modules/playwright');
const M = path.resolve(__dirname, '../mockups');
const OUT = path.join(M, 'png');
// [file, png name, query, options]
const SCREENS = [
  ['01-connect.html', 'connect', ''],
  ['01-connect.html', 'connect-wrong-folder', 'state=wrongfolder'],
  ['01-connect.html', 'connect-help', 'state=help'],
  ['01-connect.html', 'unplugged', 'state=unplugged'],
  ['02-home.html', 'home', ''],
  ['02-home.html', 'home-full', '', { full: true, schemes: ['light'] }],
  ['02-home.html', 'home-en', 'lang=en', { schemes: ['light'] }],
  ['02-home.html', 'home-sk', 'lang=sk', { schemes: ['light'] }],
  ['03-search.html', 'search', 'state=typing'],
  ['03-search.html', 'search-recent', 'state=recent'],
  ['04-reader.html', 'reader', ''],
  ['04-reader.html', 'reader-find', 'state=find'],
  ['04-reader.html', 'reader-text-size', 'state=textsize'],
  ['04-reader.html', 'reader-offline-link', 'state=external'],
  ['05-library.html', 'library', ''],
  ['05-library.html', 'library-details', 'state=details'],
  ['05-library.html', 'library-full', '', { full: true, schemes: ['light'] }],
  ['06-saved.html', 'saved', ''],
  ['06-saved.html', 'history', 'state=history'],
  ['07-settings.html', 'settings', ''],
  ['08-about.html', 'about', ''],
];
const filter = process.argv[2];

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const b = await chromium.launch();
  for (const [file, name, query, opt = {}] of SCREENS) {
    if (filter && !name.startsWith(filter)) continue;
    for (const scheme of opt.schemes || ['light', 'dark']) {
      const ctx = await b.newContext({ viewport: { width: 393, height: 852 }, deviceScaleFactor: 2, colorScheme: scheme, locale: 'cs-CZ' });
      await ctx.route('**/*', r => /^(file|data):/.test(r.request().url()) ? r.continue() : r.abort());
      const p = await ctx.newPage();
      const errs = [];
      p.on('pageerror', e => errs.push(e.message));
      await p.goto('file://' + path.join(M, file) + (query ? '?' + query : ''), { waitUntil: 'load' });
      await p.waitForSelector('html[data-ready]');
      await p.evaluate(() => document.fonts.ready);
      await p.waitForTimeout(150);
      const out = path.join(OUT, `${name}-${scheme}.png`);
      if (opt.full) {
        await p.evaluate(() => document.querySelector('.phone').classList.add('full'));
        await p.locator('.phone').screenshot({ path: out });
      }
      else await p.screenshot({ path: out });
      const missing = await p.evaluate(() => document.body.innerText.match(/⟨[a-z0-9_]+⟩/g));
      console.log(`  ${name}-${scheme}.png${errs.length ? '  ERR ' + errs[0] : ''}${missing ? '  MISSING ' + missing.join(' ') : ''}`);
      await ctx.close();
    }
  }
  await b.close();
})().catch(e => { console.error(e); process.exit(1); });
