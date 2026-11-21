// Store screenshots: app screens (Paparazzi goldens + real pages from the drive's ZIMs) composed into branded frames.
// Run through build.sh, which calls these steps one after another, each under `atd-render timeout 120`:
//   node render.cjs content            real pages from the ZIMs (article, guide, map) -> build/content/<id>.png
//   node render.cjs frames <target>    store frames -> out/play/... or out/appstore/... (targets in frames.json)
//   node render.cjs graphics           Play feature graphics (1024x500) + Play icon (512x512)
// Layout and look of every frame: frame.html.
// One Chromium, one page at a time. Config: frames.json. Env: SNAPSHOTS, SNAPSHOT_PAGES, OFFLINKA_CLI, OFFLINKA_DRIVE_FILES, OUT_DIR, BUILD_DIR.
const http = require('http'), fs = require('fs'), path = require('path'), os = require('os');
const { execFile } = require('child_process');
const { chromium } = require(process.env.PLAYWRIGHT_DIR || '/srv/offlinka/drive/node_modules/playwright');

const HERE = __dirname, REPO = path.resolve(HERE, '../..'), DESIGN = path.join(REPO, 'design');
const CFG = JSON.parse(fs.readFileSync(path.join(HERE, 'frames.json'), 'utf8'));
const SNAPS = process.env.SNAPSHOTS || path.join(REPO, 'android/ui/src/test/snapshots/images');
// SNAPSHOT_PAGES=app: the snapshots already show the real pages (iOS simulator captures on the review sample
// drive), so no page is drawn into their WebView area.
const PAGES_IN_SNAPS = process.env.SNAPSHOT_PAGES === 'app';
const BUILD = process.env.BUILD_DIR || path.join(HERE, 'build');
const OUT = process.env.OUT_DIR || path.join(HERE, 'out');
const DRIVE = process.env.OFFLINKA_DRIVE_FILES || '/srv/offlinka/drive/release/current/files';
const CONTENT_VIEW = { width: 393, height: 820 };   // dp, taller than the phone's WebView area (the frame crops the bottom)
const warnings = [];
const warn = m => { warnings.push(m); console.log('  WARN ' + m); };

const dataUrl = f => `data:${f.endsWith('.svg') ? 'image/svg+xml' : 'image/png'};base64,` + fs.readFileSync(f).toString('base64');
const snapFile = name => {
  const f = path.join(SNAPS, 'cz.offlinka.ui_' + name + '.png');
  return fs.existsSync(f) ? f : null;
};

/** First source of a screen list whose files exist, for one UI language. */
function resolveScreen(list, loc, what) {
  for (const s of list) {
    const snap = s.snap && snapFile(s.snap.replace('{loc}', loc));
    if (s.snap && !snap) continue;
    const content = !(PAGES_IN_SNAPS && snap) && s.content && path.join(BUILD, 'content', s.content + '.png');
    if (content && !fs.existsSync(content)) throw new Error(`build/content/${s.content}.png missing: run the content step first`);
    // the page alone, without the app's bars, means the Paparazzi state for this language is missing
    if (!s.snap && list[0].snap) warn(`${what} [${loc}]: no ${list.filter(x => x.snap).map(x => x.snap.replace('{loc}', loc)).join(' / ')}, showing the page without the app bars`);
    return { snap: snap && dataUrl(snap), content: content && dataUrl(content), name: s.snap ? s.snap.replace('{loc}', loc) : s.content };
  }
  throw new Error(`${what} [${loc}]: none of the screen sources exist in ${SNAPS}`);
}

// ---------------------------------------------------------------- content: a throwaway server that mimics the app's reader
function findZim(prefix) {
  const dir = path.join(DRIVE, path.dirname(prefix)), base = path.basename(prefix);
  const hit = fs.readdirSync(dir).filter(f => f.startsWith(base) && f.endsWith('.zim')).sort().pop();
  if (!hit) throw new Error(`no ${prefix}*.zim in ${DRIVE}`);
  return path.join(dir, hit);
}

function cliGet(zim, p) {
  return new Promise(resolve => {
    const tmp = path.join(os.tmpdir(), `offl-store-${process.pid}-${Math.random().toString(36).slice(2)}`);
    execFile(process.env.OFFLINKA_CLI, ['get', zim, p, '-o', tmp], { maxBuffer: 1 << 20 }, (err, stdout, stderr) => {
      let out = null;   // offlinka-cli prints "<path> <mime> (<n> bytes)" to stderr
      const m = !err && /^(.*) (\S+) \((\d+) bytes\)/m.exec(stderr + '\n' + stdout);
      if (m && fs.existsSync(tmp)) out = { mime: m[2], body: fs.readFileSync(tmp) };
      fs.rmSync(tmp, { force: true });
      resolve(out);
    });
  });
}

const READER_FILES = { '/_offlinka/reader.css': ['reader/offlinka-reader.css', 'text/css'], '/_offlinka/inject.js': ['reader/inject.js', 'text/javascript'] };
function startServer() {
  const zims = Object.fromEntries(Object.entries(CFG.books).map(([id, p]) => [id, findZim(p)]));
  const server = http.createServer(async (req, res) => {
    const u = new URL(req.url, 'http://x');
    if (READER_FILES[u.pathname]) {
      const [f, type] = READER_FILES[u.pathname];
      res.writeHead(200, { 'content-type': type });
      return res.end(fs.readFileSync(path.join(DESIGN, f)));
    }
    if (u.pathname.startsWith('/_offlinka/fonts/')) {
      const f = path.join(DESIGN, 'fonts', path.basename(u.pathname));
      if (fs.existsSync(f)) { res.writeHead(200, { 'content-type': 'font/ttf' }); return res.end(fs.readFileSync(f)); }
    }
    const m = /^\/z\/([^/]+)\/(.+)$/.exec(u.pathname);
    const r = m && zims[m[1]] && await cliGet(zims[m[1]], decodeURIComponent(m[2]));
    if (!r) { res.writeHead(404); return res.end(); }
    let body = r.body;
    // the two head lines the app inserts into every page (reader stylesheet + hook), like ContentServer/HtmlInjector
    if (r.mime.startsWith('text/html') && u.searchParams.get('ofl') === '1') {
      body = Buffer.from(body.toString('utf8').replace('</head>',
        '<link id="offlinka-reader-css" rel="stylesheet" href="/_offlinka/reader.css"><script src="/_offlinka/inject.js"></script></head>'));
    }
    res.writeHead(200, { 'content-type': r.mime });
    res.end(body);
  });
  return new Promise(ok => server.listen(0, '127.0.0.1', () => ok(server)));
}

async function renderContent(browser) {
  if (!process.env.OFFLINKA_CLI || !fs.existsSync(process.env.OFFLINKA_CLI)) throw new Error('OFFLINKA_CLI (path to offlinka-cli) not set');
  const server = await startServer(), base = `http://127.0.0.1:${server.address().port}`;
  const dir = path.join(BUILD, 'content');
  fs.mkdirSync(dir, { recursive: true });
  for (const c of CFG.content) {
    // a case with its own height (the map) is drawn to fit the WebView area exactly, so its attribution stays visible
    const ctx = await browser.newContext({ viewport: { ...CONTENT_VIEW, height: c.height || CONTENT_VIEW.height }, deviceScaleFactor: 3, colorScheme: c.scheme, locale: 'cs-CZ' });
    await ctx.route('**/*', r => r.request().url().startsWith(base) ? r.continue() : r.abort());   // offline, like the app
    const p = await ctx.newPage();
    const errs = [];
    p.on('pageerror', e => errs.push(e.message));
    await p.goto(`${base}/z/${c.book}/${encodeURIComponent(c.path)}?ofl=1`, { waitUntil: 'load', timeout: 30000 });
    await p.evaluate(() => document.fonts.ready);
    if (c.map) {
      await p.waitForFunction(() => window.__openzim_map && window.__openzim_map.isStyleLoaded(), null, { timeout: 30000 });
      await p.evaluate(v => new Promise(ok => {
        const m = window.__openzim_map;
        m.once('idle', ok);
        m.jumpTo({ center: v.center, zoom: v.zoom });
        setTimeout(ok, 20000);
      }), c.map);
      await p.waitForTimeout(500);
    } else {
      await p.evaluate(() => document.querySelectorAll('img[loading=lazy]').forEach(i => { i.loading = 'eager'; }));
      await p.waitForLoadState('networkidle').catch(() => {});
      await p.waitForTimeout(400);
    }
    await p.screenshot({ path: path.join(dir, c.id + '.png') });
    console.log(`  content/${c.id}.png${errs.length ? '  page error: ' + errs[0] : ''}`);
    await ctx.close();
  }
  server.close();
}

// ---------------------------------------------------------------- frames
function brandAssets() {
  return {
    topoDark: dataUrl(path.join(DESIGN, 'brand/topo-d.svg')),
    logo: dataUrl(path.join(DESIGN, 'brand/logo.svg')),
  };
}

async function renderFrames(browser, targetName) {
  const t = CFG.targets[targetName];
  if (!t) throw new Error(`unknown target ${targetName}; known: ${Object.keys(CFG.targets).join(', ')}`);
  const page = await browser.newPage({ viewport: { width: t.w, height: t.h }, deviceScaleFactor: 1 });
  page.on('console', m => { if (m.type() === 'warning') warn(m.text()); });
  await page.goto('file://' + path.join(HERE, 'frame.html'));
  const assets = brandAssets();
  const frames = CFG.frames.filter(f => f.stores.includes(t.store));
  for (const [loc, codes] of Object.entries(CFG.locales)) {
    const dir = t.store === 'play' ? path.join(OUT, 'play', codes.play, 'images', 'phoneScreenshots') : path.join(OUT, 'appstore', codes.appstore);
    fs.mkdirSync(dir, { recursive: true });
    for (const [i, f] of frames.entries()) {
      const screen = resolveScreen(f.screen, loc, f.id);
      await page.evaluate(cfg => window.renderFrame(cfg), {
        w: t.w, h: t.h, tablet: !!t.tablet, dark: !!f.dark, headline: f.headline[loc], lang: loc, screen, assets, label: `${targetName} ${loc} ${f.id}`,
      });
      await page.waitForFunction(() => document.body.dataset.ready === '1', null, { timeout: 20000 });
      const name = `${String(i + 1).padStart(2, '0')}-${f.id}` + (t.store === 'appstore' ? `-${targetName}` : '') + '.png';
      await page.screenshot({ path: path.join(dir, name) });
      console.log(`  ${path.relative(OUT, path.join(dir, name))}  <- ${screen.name}`);
    }
  }
  await page.close();
}

async function renderGraphics(browser) {
  const assets = brandAssets();
  const page = await browser.newPage({ viewport: { width: 1024, height: 500 }, deviceScaleFactor: 1 });
  page.on('console', m => { if (m.type() === 'warning') warn(m.text()); });
  await page.goto('file://' + path.join(HERE, 'frame.html'));
  for (const [loc, codes] of Object.entries(CFG.locales)) {
    const screen = resolveScreen(CFG.feature.screen, loc, 'feature');
    await page.evaluate(cfg => window.renderFeature(cfg), { headline: CFG.feature.headline[loc], lang: loc, screen, assets });
    await page.waitForFunction(() => document.body.dataset.ready === '1', null, { timeout: 20000 });
    const dir = path.join(OUT, 'play', codes.play, 'images');
    fs.mkdirSync(dir, { recursive: true });
    await page.screenshot({ path: path.join(dir, 'featureGraphic.png') });
    console.log(`  ${path.relative(OUT, path.join(dir, 'featureGraphic.png'))}`);
  }
  await page.close();
  // Play icon: 512 px, full bleed (Play applies its own mask), 32-bit PNG. Same crop as design/tools/render-icons.cjs.
  const icon = await browser.newPage({ viewport: { width: 512, height: 512 }, deviceScaleFactor: 1 });
  const crop = 80, scale = 512 / crop, img = 108 * scale, off = -(108 - crop) / 2 * scale;
  await icon.setContent(`<!doctype html><style>html,body{margin:0;background:transparent}div{position:relative;width:512px;height:512px;overflow:hidden}
    img{position:absolute;width:${img}px;height:${img}px;left:${off}px;top:${off}px}</style><div><img src="${dataUrl(path.join(DESIGN, 'appicon/full-light.svg'))}"></div>`);
  await icon.waitForLoadState('load');
  for (const codes of Object.values(CFG.locales)) {
    const out = path.join(OUT, 'play', codes.play, 'images', 'icon.png');
    await icon.locator('div').screenshot({ path: out, omitBackground: true });
  }
  console.log('  play/<locale>/images/icon.png');
  await icon.close();
}

(async () => {
  const [mode, arg] = process.argv.slice(2);
  const browser = await chromium.launch();
  try {
    if (mode === 'content') await renderContent(browser);
    else if (mode === 'frames') await renderFrames(browser, arg);
    else if (mode === 'graphics') await renderGraphics(browser);
    else throw new Error('usage: node render.cjs content | frames <target> | graphics');
  } finally { await browser.close(); }
  if (warnings.length) fs.mkdirSync(BUILD, { recursive: true }), fs.appendFileSync(path.join(BUILD, 'warnings.txt'), warnings.map(w => `${mode} ${arg || ''}: ${w}\n`).join(''));
})().catch(e => { console.error(e.stack || e); process.exit(1); });
