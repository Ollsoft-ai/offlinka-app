/* Offlinka reader hook.
 * Best: when the app serves an HTML page of a MediaWiki ZIM, it inserts these two lines before </head>
 * (no flash of the unstyled page):
 *   <link id="offlinka-reader-css" rel="stylesheet" href="/_offlinka/reader.css">
 *   <script src="/_offlinka/inject.js"></script>
 * Then call offlinkaReader({ theme, scale }) whenever the user changes theme or text size
 * (Android: evaluateJavascript, iOS: evaluateJavaScript). Before the first call it uses the values in
 * window.__offlinka (the app may prepend "window.__offlinka={theme:'dark',scale:1.1};" to the script).
 *   theme: "light" | "dark" | "system"     scale: 0.8 … 1.6 (1 = 17 px body text)
 * The stylesheet is scoped to body.mediawiki, so maps, games and our own guides keep their look. */
(function () {
  var CSS = "/_offlinka/reader.css";
  var state = { theme: "system", scale: 1 };
  if (window.__offlinka) {
    if (window.__offlinka.theme) state.theme = window.__offlinka.theme;
    if (window.__offlinka.scale) state.scale = window.__offlinka.scale;
  }

  function systemDark() { return !!(window.matchMedia && matchMedia("(prefers-color-scheme: dark)").matches); }

  function applyClasses() {
    var html = document.documentElement;
    var dark = state.theme === "dark" || (state.theme === "system" && systemDark());
    html.classList.toggle("offlinka-dark", state.theme === "dark");
    html.classList.toggle("offlinka-light", state.theme === "light");
    // keep Vector's own night mode in step with ours (harmless on non-MediaWiki pages)
    if (/skin-theme-clientpref-/.test(html.className)) {
      html.classList.remove("skin-theme-clientpref-os", "skin-theme-clientpref-day", "skin-theme-clientpref-night");
      html.classList.add(dark ? "skin-theme-clientpref-night" : "skin-theme-clientpref-day");
    }
    html.style.setProperty("--ofl-scale", String(state.scale));
  }

  function ensureCss() {   // fallback when the app did not insert the <link> itself
    if (document.getElementById("offlinka-reader-css")) return;
    if (!(document.body && document.body.classList.contains("mediawiki"))) return;
    var link = document.createElement("link");
    link.id = "offlinka-reader-css"; link.rel = "stylesheet"; link.href = CSS;
    document.head.appendChild(link);
  }

  window.offlinkaReader = function (opts) {
    if (opts && opts.theme) state.theme = opts.theme;
    if (opts && opts.scale) state.scale = opts.scale;
    applyClasses();
    ensureCss();
  };
  if (window.matchMedia) {
    var mq = matchMedia("(prefers-color-scheme: dark)");
    var onChange = function () { if (state.theme === "system") applyClasses(); };
    if (mq.addEventListener) mq.addEventListener("change", onChange); else if (mq.addListener) mq.addListener(onChange);
  }
  // Pictures that are not in the ZIM: the mini editions replace them with an inline SVG that only shows the alt
  // text, and a broken file errors. Hide the picture together with its caption instead of an empty box.
  var BOX = "figure, .thumb, .gallerybox, .location-map, .infobox td[colspan]";
  function hideMissing(img) { (img.closest(BOX) || img).classList.add("ofl-missing"); }
  document.addEventListener("error", function (e) {
    var img = e.target;
    if (img && img.tagName === "IMG" && document.body && document.body.classList.contains("mediawiki")) hideMissing(img);
  }, true);
  function hidePlaceholders() {
    if (!(document.body && document.body.classList.contains("mediawiki"))) return;
    var ph = document.querySelectorAll('img.mw-file-element[src^="data:image/svg+xml"]');
    for (var i = 0; i < ph.length; i++) hideMissing(ph[i]);
  }

  applyClasses();
  function onReady() { ensureCss(); hidePlaceholders(); }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", onReady); else onReady();
})();
