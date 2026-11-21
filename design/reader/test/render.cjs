// Before/after screenshots of the reader stylesheet on real ZIM articles, at phone width.
//   1. extract articles (test helper, needs libzim in a scratch venv):
//        python3 design/reader/test/extract.py <book.zim> <dir> Sněžka Jan_Amos_Komenský …
//   2. source /srv/offlinka/env.sh; atd-render timeout 300 node design/reader/test/render.cjs <cases.json> <outDir>
// cases.json: [{ "id": "snezka", "dir": "/abs/extracted", "page": "Sněžka", "scrollTo": "ul.gallery" }, …]
// optional per case: "variants": ["after"], "schemes": ["light"], "height": 672 (viewport), "find": "lanov" (mark matches like
// in-page find, scroll to the 3rd), "scrollY": 0
// A throwaway HTTP server mimics the app: /a/<page> = article, /_offlinka/* = design files, and with ?ofl=1
// the two head lines the app inserts (reader.css + inject.js). It stops when the script ends.
const http = require('http'), fs = require('fs'), path = require('path');
const { chromium } = require(process.env.PLAYWRIGHT_DIR || '/srv/offlinka/drive/node_modules/playwright');
const DESIGN = path.resolve(__dirname, '../..');
const [casesFile, outDir] = process.argv.slice(2);
const cases = JSON.parse(fs.readFileSync(casesFile, 'utf8'));
fs.mkdirSync(outDir, { recursive: true });

const TYPES = { '.html': 'text/html; charset=utf-8', '.css': 'text/css', '.js': 'text/javascript', '.png': 'image/png',
  '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp', '.svg': 'image/svg+xml', '.gif': 'image/gif', '.ttf': 'font/ttf' };
let current = null;   // case being rendered
const server = http.createServer((req, res) => {
  const u = new URL(req.url, 'http://x');
  let file;
  if (u.pathname === '/_offlinka/reader.css') file = path.join(DESIGN, 'reader/offlinka-reader.css');
  else if (u.pathname === '/_offlinka/inject.js') file = path.join(DESIGN, 'reader/inject.js');
  else if (u.pathname.startsWith('/_offlinka/fonts/')) file = path.join(DESIGN, 'fonts', path.basename(u.pathname));
  else if (u.pathname.startsWith('/a/')) {
    const rel = decodeURIComponent(u.pathname.slice(3));
    file = path.join(current.dir, rel);
    if (!fs.existsSync(file) && fs.existsSync(file + '.html')) file += '.html';
  }
  if (!file || !fs.existsSync(file)) { res.writeHead(404); return res.end(); }
  let body = fs.readFileSync(file);
  const type = TYPES[path.extname(file).toLowerCase()] || 'application/octet-stream';
  if (type.startsWith('text/html') && u.searchParams.get('ofl') === '1') {
    body = Buffer.from(body.toString('utf8').replace('</head>',
      '<link id="offlinka-reader-css" rel="stylesheet" href="/_offlinka/reader.css"><script src="/_offlinka/inject.js"></script></head>'));
  }
  res.writeHead(200, { 'content-type': type });
  res.end(body);
});

(async () => {
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;
  const b = await chromium.launch();
  for (const c of cases) {
    current = c;
    for (const scheme of (c.schemes || ['light', 'dark'])) {
      for (const variant of (c.variants || ['before', 'after'])) {
        const ctx = await b.newContext({ viewport: { width: 393, height: c.height || 852 }, deviceScaleFactor: 2, colorScheme: scheme, locale: 'cs-CZ' });
        await ctx.route('**/*', r => r.request().url().startsWith(base) ? r.continue() : r.abort());
        const p = await ctx.newPage();
        await p.goto(`${base}/a/${encodeURIComponent(c.page)}${variant === 'after' ? '?ofl=1' : ''}`, { waitUntil: 'load' });
        await p.evaluate(() => document.fonts.ready);
        await p.waitForTimeout(300);
        if (c.find) {
          await p.evaluate(word => {
            const re = new RegExp(word, 'gi'), root = document.querySelector('.mw-parser-output'), hits = [];
            const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
            const nodes = []; while (walker.nextNode()) nodes.push(walker.currentNode);
            for (const n of nodes) {
              if (!re.test(n.data) || n.parentElement.closest('table, figure, style, script')) continue;
              re.lastIndex = 0; const f = document.createDocumentFragment(); let last = 0, m;
              while ((m = re.exec(n.data))) {
                f.append(n.data.slice(last, m.index));
                const mk = document.createElement('mark'); mk.textContent = m[0];
                mk.style.cssText = 'background:#FBDCC8;color:#17231C;border-radius:2px;padding:0 1px';
                f.append(mk); hits.push(mk); last = m.index + m[0].length;
              }
              f.append(n.data.slice(last)); n.replaceWith(f);
            }
            const cur = hits[2] || hits[0];
            if (cur) { cur.style.background = '#F26A1B'; cur.style.color = '#1A1206'; cur.scrollIntoView({ block: 'center' }); }
          }, c.find);
          await p.waitForTimeout(300);
        } else if (c.scrollY) { await p.evaluate(y => window.scrollTo(0, y), c.scrollY); await p.waitForTimeout(300); }
        await p.screenshot({ path: path.join(outDir, `${c.id}-${variant}-${scheme}.png`) });
        if (c.scrollTo) {
          const ok = await p.evaluate(sel => { document.querySelectorAll('img[loading=lazy]').forEach(i => { i.loading = 'eager'; }); const e = document.querySelector(sel); if (!e) return false; e.scrollIntoView({ block: 'start' }); window.scrollBy(0, -24); return true; }, c.scrollTo);
          if (ok) { await p.waitForTimeout(900); await p.screenshot({ path: path.join(outDir, `${c.id}-${variant}-${scheme}-2.png`) }); }
        }
        await ctx.close();
        console.log(`  ${c.id} ${variant} ${scheme}`);
      }
    }
  }
  await b.close();
  server.close();
})().catch(e => { console.error(e); server.close(); process.exit(1); });
