// Render the app icon PNGs from design/appicon/*.svg (run after tools/appicon.py).
//   source /srv/offlinka/env.sh; atd-render timeout 120 node design/tools/render-icons.cjs
// PLAYWRIGHT_DIR may point at a playwright install (default: the drive repo's).
const path = require('path'), fs = require('fs');
const { chromium } = require(process.env.PLAYWRIGHT_DIR || '/srv/offlinka/drive/node_modules/playwright');
const D = path.resolve(__dirname, '..');
const A = path.join(D, 'appicon'), OA = path.join(D, 'out/android'), OI = path.join(D, 'out/ios');
// inline as data: URIs (a setContent page may not load file:// images)
const url = f => 'data:image/svg+xml;base64,' + fs.readFileSync(path.join(A, f)).toString('base64');

// crop = how many of the 108 canvas units fill the square (smaller = bigger mark)
function tile(svg, size, { crop = 108, radius = 0, round = false } = {}) {
  const scale = size / crop, img = 108 * scale, off = -(108 - crop) / 2 * scale;
  const r = round ? '50%' : radius ? `${radius * size}px` : '0';
  return `<div class="t" style="width:${size}px;height:${size}px;border-radius:${r}">` +
    `<img src="${url(svg)}" style="width:${img}px;height:${img}px;left:${off}px;top:${off}px"></div>`;
}

(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ deviceScaleFactor: 1 });
  async function shot(html, out, transparent = true) {
    await p.setContent(`<!doctype html><style>body{margin:0;background:transparent}.t{position:relative;overflow:hidden}` +
      `.t img{position:absolute;display:block}</style>${html}`);
    await p.waitForLoadState('load');
    fs.mkdirSync(path.dirname(out), { recursive: true });
    await p.locator('.t').first().screenshot({ path: out, omitBackground: transparent });
    console.log('  ' + path.relative(path.dirname(D), out));
  }
  // Android adaptive layers as 432 px PNGs (for tools that want bitmaps; the app uses the vector XMLs)
  for (const n of ['foreground', 'background', 'monochrome']) await shot(tile(n + '.svg', 432), path.join(A, `png/${n}-432.png`));
  // Android legacy launcher icons (API < 26): rounded square + round
  const dens = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };
  for (const [d, s] of Object.entries(dens)) {
    await shot(tile('full-light.svg', s, { crop: 80, radius: 0.18 }), path.join(OA, `mipmap-${d}/ic_launcher.png`));
    await shot(tile('full-light.svg', s, { crop: 80, round: true }), path.join(OA, `mipmap-${d}/ic_launcher_round.png`));
  }
  // Google Play listing icon: 512, full bleed, no transparency (Play applies its own mask)
  await shot(tile('full-light.svg', 512, { crop: 80 }), path.join(OA, 'play-store-512.png'), false);
  // iOS: 1024 light (opaque, no alpha), dark, tinted
  const set = path.join(OI, 'AppIcon.appiconset');
  await shot(tile('full-light.svg', 1024, { crop: 78 }), path.join(set, 'AppIcon-1024.png'), false);
  await shot(tile('full-dark.svg', 1024, { crop: 78 }), path.join(set, 'AppIcon-1024-dark.png'), false);
  await shot(tile('full-tinted.svg', 1024, { crop: 78 }), path.join(set, 'AppIcon-1024-tinted.png'), false);
  fs.writeFileSync(path.join(set, 'Contents.json'), JSON.stringify({
    images: [
      { filename: 'AppIcon-1024.png', idiom: 'universal', platform: 'ios', size: '1024x1024' },
      { appearances: [{ appearance: 'luminosity', value: 'dark' }], filename: 'AppIcon-1024-dark.png', idiom: 'universal', platform: 'ios', size: '1024x1024' },
      { appearances: [{ appearance: 'luminosity', value: 'tinted' }], filename: 'AppIcon-1024-tinted.png', idiom: 'universal', platform: 'ios', size: '1024x1024' },
    ],
    info: { author: 'xcode', version: 1 },
  }, null, 2) + '\n');

  // Review sheet: real launcher sizes on light and dark wallpapers, Android masks, themed icon, iOS variants
  const sizes = [48, 64, 96];
  const row = (svg, opt) => sizes.map(s => tile(svg, s, opt)).join('');
  const lbl = t => `<span>${t}</span>`;
  await p.setViewportSize({ width: 900, height: 700 });
  await p.setContent(`<!doctype html><style>body{margin:0;font:600 13px system-ui;color:#5F5B4B}
    .t{position:relative;overflow:hidden;flex:none}.t img{position:absolute;display:block}
    .w{display:flex;gap:28px;padding:22px 26px;align-items:center;flex-wrap:wrap}.w span{width:150px}
    .l{background:#E9DFC8}.d{background:#232a33;color:#c8c8c8}.k{background:#121212;color:#aaa}
    .mono .t{background:#D7E3D2}.mono img{filter:brightness(0) saturate(100%) invert(19%) sepia(15%) saturate(1100%) hue-rotate(95deg)}</style>
    <div class="sheet">
    <div class="w l">${lbl('Android circle')}${row('full-light.svg', { crop: 72, round: true })}</div>
    <div class="w l">${lbl('Android squircle')}${row('full-light.svg', { crop: 72, radius: 0.3 })}</div>
    <div class="w d">${lbl('Dark wallpaper')}${row('full-light.svg', { crop: 72, round: true })}</div>
    <div class="w l mono">${lbl('Themed (monochrome)')}${sizes.map(s => `<div class="t" style="width:${s}px;height:${s}px;border-radius:50%;background:#D7E3D2">` +
      `<img src="${url('monochrome.svg')}" style="width:${s * 108 / 72}px;height:${s * 108 / 72}px;left:${-s * 18 / 72}px;top:${-s * 18 / 72}px"></div>`).join('')}</div>
    <div class="w l">${lbl('iOS light')}${row('full-light.svg', { crop: 78, radius: 0.225 })}</div>
    <div class="w k">${lbl('iOS dark / tinted')}${tile('full-dark.svg', 64, { crop: 78, radius: 0.225 })}${tile('full-dark.svg', 96, { crop: 78, radius: 0.225 })}${tile('full-tinted.svg', 64, { crop: 78, radius: 0.225 })}${tile('full-tinted.svg', 96, { crop: 78, radius: 0.225 })}</div>
    </div>`);
  await p.waitForLoadState('load');
  await p.locator('.sheet').screenshot({ path: path.join(A, 'preview.png') });
  console.log('  design/appicon/preview.png');
  await b.close();
})().catch(e => { console.error(e); process.exit(1); });
