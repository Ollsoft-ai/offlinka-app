// Offlinka map bridge. The app answers every request to https://offlinka.local/ itself, but a web worker
// started from a blob: URL (MapLibre's tile worker) may not be routed through the app on every WebView
// version, and then tiles stay grey. This wraps Worker so that the worker's fetch() asks the page and the
// page fetches (always routed). Works the same whether or not the WebView routes worker requests.
(function () {
  if (!window.Worker || !window.Blob || !window.URL || window.__oflBridge) return;
  window.__oflBridge = true;
  var ORIGIN = location.origin + '/';
  var NativeWorker = window.Worker;

  // Runs inside the worker before the worker's own code.
  var shim = function () {
    var pending = {}, next = 1;
    self.addEventListener('message', function (e) {
      var d = e.data;
      if (!d || !d.__oflFetchResult) return;
      e.stopImmediatePropagation();
      var p = pending[d.__oflFetchResult];
      delete pending[d.__oflFetchResult];
      if (!p) return;
      if (d.error) p.reject(new TypeError(d.error));
      else p.resolve(new Response(d.body, { status: d.status, statusText: d.statusText, headers: d.headers }));
    });
    var nativeFetch = self.fetch;
    self.fetch = function (input, init) {
      var url = typeof input === 'string' ? input : (input && input.url) || String(input);
      var abs;
      try { abs = new URL(url, self.__oflBase).href; } catch (x) { return nativeFetch.apply(self, arguments); }
      if (abs.indexOf(self.__oflOrigin) !== 0) return nativeFetch.apply(self, arguments);
      var headers = {};
      var h = (init && init.headers) || (typeof input !== 'string' && input && input.headers);
      if (h && typeof h.forEach === 'function') h.forEach(function (v, k) { headers[k] = v; });
      else if (h) for (var k in h) headers[k] = h[k];
      var signal = (init && init.signal) || (typeof input !== 'string' && input && input.signal);
      return new Promise(function (resolve, reject) {
        var id = next++;
        pending[id] = { resolve: resolve, reject: reject };
        if (signal) signal.addEventListener('abort', function () {
          if (pending[id]) { delete pending[id]; reject(new DOMException('Aborted', 'AbortError')); }
        });
        self.postMessage({ __oflFetch: id, url: abs, headers: headers });
      });
    };
  };

  var Bridged = function (url, options) {
    if (options && options.type === 'module') return new NativeWorker(url, options);
    var boot = 'self.__oflBase=' + JSON.stringify(location.href) + ';self.__oflOrigin=' + JSON.stringify(ORIGIN) + ';(' +
      shim.toString() + ')();importScripts(' + JSON.stringify(new URL(String(url), location.href).href) + ');';
    var w;
    try {
      w = new NativeWorker(URL.createObjectURL(new Blob([boot], { type: 'text/javascript' })), options);
    } catch (e) {
      return new NativeWorker(url, options);
    }
    w.addEventListener('message', function (e) {
      var d = e.data;
      if (!d || !d.__oflFetch) return;
      e.stopImmediatePropagation();
      window.__oflBridged = (window.__oflBridged || 0) + 1;
      fetch(d.url, { headers: d.headers }).then(function (r) {
        var hdr = {};
        r.headers.forEach(function (v, k) { hdr[k] = v; });
        return r.arrayBuffer().then(function (body) {
          w.postMessage({ __oflFetchResult: d.__oflFetch, status: r.status, statusText: r.statusText, headers: hdr, body: body }, [body]);
        });
      }).catch(function (err) {
        w.postMessage({ __oflFetchResult: d.__oflFetch, error: String(err) });
      });
    });
    return w;
  };
  Bridged.prototype = NativeWorker.prototype;
  window.Worker = Bridged;
})();
