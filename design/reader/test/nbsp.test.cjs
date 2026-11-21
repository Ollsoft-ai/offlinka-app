// Unit test of the Czech/Slovak typesetting pass in inject.js (no browser): node design/reader/test/nbsp.test.cjs
const fs = require('fs'), path = require('path'), vm = require('vm'), assert = require('assert');
const src = fs.readFileSync(path.join(__dirname, '../inject.js'), 'utf8');

function run(lang, nodes) {
  const texts = nodes.map(([value, parent]) => ({ nodeValue: value, parentNode: { nodeName: parent || 'P', closest: () => null } }));
  let i = 0;
  const document = {
    readyState: 'complete', addEventListener() {}, getElementById() { return null }, querySelectorAll() { return [] },
    documentElement: { classList: { toggle() {}, remove() {}, add() {} }, style: { setProperty() {} }, className: '' },
    body: { classList: { contains: () => true } },
    head: { appendChild() {} },
    createElement() { return {} },
    createTreeWalker() { return { nextNode() { return i < texts.length ? texts[i++] : null } } },
  };
  const window = { __offlinka: { theme: 'light', scale: 1, lang } };
  vm.runInNewContext(src, { window, document, matchMedia: undefined, String });
  return { texts: texts.map(t => t.nodeValue), nbsp: window.offlinkaNbsp };
}

const N = ' ';
const { nbsp } = run('cs', []);
assert.strictEqual(nbsp('leží v Praze a v Brně'), `leží v${N}Praze a${N}v${N}Brně`);
assert.strictEqual(nbsp('K hradu vede 10 km cesta, 5 % lidí'), `K${N}hradu vede 10${N}km cesta, 5${N}% lidí`);
assert.strictEqual(nbsp('(o tom) „s láskou“'), `(o${N}tom) „s${N}láskou“`);
assert.strictEqual(nbsp('Praha je hlavní město'), 'Praha je hlavní město');   // nothing to do
assert.strictEqual(nbsp('rok 2026'), 'rok 2026');                             // a number at the end stays

// cs and sk only, and never inside code
assert.deepStrictEqual(run('cs', [['v Praze'], ['a v Brně', 'CODE']]).texts, [`v${N}Praze`, 'a v Brně']);
assert.deepStrictEqual(run('sk', [['v Bratislave']]).texts, [`v${N}Bratislave`]);
assert.deepStrictEqual(run('en', [['a v Praze']]).texts, ['a v Praze']);
console.log('nbsp OK');
