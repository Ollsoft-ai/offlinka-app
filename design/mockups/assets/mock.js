// Offlinka mockups runtime: fills icons and strings, switches states.
//   ?lang=cs|sk|en   ?theme=light|dark (else prefers-color-scheme)   ?state=<name>
//   <i class="ic" data-i="search"></i>             -> inline icon from design/icons/svg
//   <span data-s="home_title"></span>               -> string from design/strings/<lang>.json
//   <span data-s="library_books_count" data-n="14"> -> plural; data-p='{"name":"OFFLINKA"}' fills {name}
//   <div data-show="find textsize">                 -> visible only in those states; data-hide="…" the opposite
(function () {
  const q = new URLSearchParams(location.search);
  const lang = q.get('lang') || 'cs';
  const state = q.get('state') || 'default';
  const theme = q.get('theme');
  const root = document.documentElement;
  root.lang = lang;
  if (theme === 'dark') root.classList.add('offlinka-dark');
  if (theme === 'light') root.classList.add('offlinka-light');
  document.body.dataset.state = state;

  const S = (window.STRINGS || {})[lang] || {};
  const plural = new Intl.PluralRules(lang);
  const fmt = new Intl.NumberFormat(lang);
  function str(key, n, params) {
    let v = S[key];
    if (v === undefined) return '⟨' + key + '⟩';
    if (typeof v === 'object') v = v[plural.select(n)] || v.other;
    const p = Object.assign({}, params || {}, n !== undefined ? { n: fmt.format(n) } : {});
    return v.replace(/\{(\w+)\}/g, (m, k) => (k in p ? p[k] : m));
  }
  window.str = str;

  for (const el of document.querySelectorAll('[data-s]')) {
    const n = el.dataset.n !== undefined ? Number(el.dataset.n) : undefined;
    const p = el.dataset.p ? JSON.parse(el.dataset.p) : undefined;
    el.textContent = str(el.dataset.s, n, p);
  }
  for (const el of document.querySelectorAll('[data-s-aria]')) el.setAttribute('aria-label', str(el.dataset.sAria));
  for (const el of document.querySelectorAll('[data-i]')) {
    const body = (window.ICONS || {})[el.dataset.i];
    el.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + (body || '') + '</svg>';
  }
  for (const el of document.querySelectorAll('[data-show]')) {
    if (el.dataset.show.split(/\s+/).includes(state)) el.removeAttribute('data-show');
  }
  for (const el of document.querySelectorAll('[data-hide]')) {
    if (el.dataset.hide.split(/\s+/).includes(state)) el.remove();
  }
  document.documentElement.dataset.ready = '1';
})();
