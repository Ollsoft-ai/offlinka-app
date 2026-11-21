// Checks android/app/src/main/assets/reader/map-bridge.js in headless Chromium: a blob: worker's fetch() goes
// through the page and gets the right bytes, and the worker's own messages still arrive.
// Run: PLAYWRIGHT_BROWSERS_PATH=/srv/offlinka/tools/ms-playwright atd-render timeout 120 node android/tools/map-bridge-check/run.cjs
const http = require('http'), fs = require('fs'), path = require('path');
const { chromium } = require(process.env.PLAYWRIGHT_DIR || '/srv/offlinka/drive/node_modules/playwright');
const DATA = Buffer.from(Array.from({ length: 3000 }, (_, i) => (i * 7) % 256));
const srv = http.createServer((req, res) => {
  if (req.url === '/data.bin') { res.writeHead(200, { 'Content-Type': 'application/octet-stream' }); return res.end(DATA); }
  const f = req.url === '/map-bridge.js' ? path.join(__dirname, '../../app/src/main/assets/reader/map-bridge.js') : path.join(__dirname, req.url === '/' ? 'index.html' : req.url.slice(1));
  if (!fs.existsSync(f)) { res.writeHead(404); return res.end(); }
  res.writeHead(200, {'Content-Type': f.endsWith('.js') ? 'text/javascript' : f.endsWith('.html') ? 'text/html' : 'application/octet-stream'});
  res.end(fs.readFileSync(f));
}).listen(0, async () => {
  const port = srv.address().port;
  const b = await chromium.launch(); const p = await b.newPage();
  await p.goto(`http://127.0.0.1:${port}/`); await p.waitForTimeout(1500);
  const r = await p.evaluate(() => ({ result: window.result, bridged: window.__oflBridged }));
  await b.close(); srv.close();
  const got = (r.result || []).find((m) => m.len !== undefined);
  const ok = r.bridged === 1 && got && got.len === DATA.length && got.first === DATA[0] && r.result.some((m) => m.echo === 'ping');
  console.log(ok ? 'map-bridge OK' : 'map-bridge FAILED ' + JSON.stringify(r));
  process.exit(ok ? 0 : 1);
});
