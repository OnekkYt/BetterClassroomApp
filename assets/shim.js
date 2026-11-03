/* chrome.* for a WebView. Runs before the extension's own scripts in three
 * places, told apart by window.__BC_ROLE: 'content' (the Classroom page),
 * 'options' (the options page) and 'bg' (the hidden page that runs the service
 * worker). Storage and messaging go through the native bridge. */
(() => {
  if (window.__bcShimmed) return;
  window.__bcShimmed = true;

  const B = window.__bcNative;
  const role = window.__BC_ROLE;
  const ORIGIN = 'https://bcext.local/';

  const Ev = () => {
    const l = [];
    return {
      _l: l,
      addListener: (f) => { l.push(f); },
      removeListener: (f) => { const i = l.indexOf(f); if (i >= 0) l.splice(i, 1); },
      hasListener: (f) => l.includes(f),
      _fire: (...a) => l.slice().forEach((f) => { try { f(...a); } catch (e) { console.error(e); } }),
    };
  };

  const reply = (value, cb) => {
    if (typeof cb === 'function') { Promise.resolve().then(() => cb(value)); return undefined; }
    return Promise.resolve(value);
  };

  const area = {
    get: (keys, cb) => {
      if (typeof keys === 'function') { cb = keys; keys = null; }
      return reply(JSON.parse(B.storageGet(JSON.stringify(keys === undefined ? null : keys))), cb);
    },
    set: (items, cb) => { B.storageSet(JSON.stringify(items)); return reply(undefined, cb); },
    remove: (keys, cb) => { B.storageRemove(JSON.stringify(keys)); return reply(undefined, cb); },
    clear: (cb) => { B.storageClear(); return reply(undefined, cb); },
  };

  const storage = { local: area, onChanged: Ev() };
  window.__bcChanged = (changes) => storage.onChanged._fire(changes, 'local');

  let manifest = null;
  const runtime = {
    id: 'bettercls',
    lastError: null,
    onMessage: Ev(),
    onStartup: Ev(),
    onInstalled: Ev(),
    getURL: (p) => ORIGIN + String(p).replace(/^\//, ''),
    getManifest: () => manifest || (manifest = JSON.parse(B.manifest())),
    openOptionsPage: (cb) => { B.openOptions(); return reply(undefined, cb); },
    sendMessage: undefined,
  };

  window.chrome = window.chrome || {};
  Object.assign(window.chrome, { storage, runtime });

  /* Performance mode (set by the app). The extension redraws on every scroll
   * event; on a phone that is what makes scrolling stutter. Its own scroll
   * listeners - and only those, found by the file name in the stack - are run
   * at most every 250ms and once more when scrolling stops. */
  if (role === 'content' && window.__BC_PERF) {
    const add = EventTarget.prototype.addEventListener;
    EventTarget.prototype.addEventListener = function (type, fn, opts) {
      if (type === 'scroll' && typeof fn === 'function' && /bc\/src\//.test(new Error().stack || '')) {
        let timer = 0, last = 0;
        const slow = function (...a) {
          const wait = 250 - (Date.now() - last);
          clearTimeout(timer);
          if (wait <= 0) { last = Date.now(); fn.apply(this, a); return; }
          timer = setTimeout(() => { last = Date.now(); fn.apply(this, a); }, wait + 50);
        };
        return add.call(this, type, slow, opts);
      }
      return add.call(this, type, fn, opts);
    };
  }

  if (role !== 'bg') {
    let seq = 0;
    const pending = {};
    runtime.sendMessage = (a, b, c) => {
      let msg = a, cb = b;
      if (typeof a === 'string' && b !== undefined && typeof b !== 'function') { msg = b; cb = c; }
      const p = new Promise((res) => {
        const id = ++seq;
        pending[id] = res;
        B.send(String(id), JSON.stringify(msg === undefined ? null : msg));
      });
      if (typeof cb === 'function') {
        p.then((r) => { runtime.lastError = null; cb(r === null ? undefined : r); });
        return undefined;
      }
      return p.then((r) => (r === null ? undefined : r));
    };
    window.__bcReply = (id, r) => { const f = pending[id]; delete pending[id]; if (f) f(r); };
    /* the toolbar button, from the app's menu */
    window.__bcMessage = (msg) => runtime.onMessage._fire(msg, { id: 'bettercls' }, () => {});
    return;
  }

  /* ------------------------------------------------------------ service worker */

  const ready = new Promise((res) => {
    window.addEventListener('load', () => setTimeout(res, 250));
  });

  window.__bcDeliver = (key, msg) => ready.then(() => {
    let async = false, answered = false;
    const send = (r) => {
      if (answered) return;
      answered = true;
      B.respond(key, JSON.stringify(r === undefined ? null : r));
    };
    for (const f of runtime.onMessage._l) {
      try { if (f(msg, { id: 'bettercls' }, send) === true) async = true; } catch (e) { console.error(e); }
    }
    if (!async) send(undefined);
  });

  const alarmEv = Ev();
  const timers = {};
  window.chrome.alarms = {
    onAlarm: alarmEv,
    create: (name, info = {}) => {
      clearInterval(timers[name]);
      const ms = Math.max(1, info.periodInMinutes || info.delayInMinutes || 1) * 60000;
      timers[name] = setInterval(() => alarmEv._fire({ name }), ms);
    },
    clear: (name, cb) => { clearInterval(timers[name]); return reply(true, cb); },
  };
  window.chrome.action = { onClicked: Ev() };
  window.chrome.tabs = {
    create: (o) => { B.openExternal(o && o.url); return Promise.resolve({}); },
    sendMessage: () => Promise.reject(new Error('no tab')),
    reload: () => Promise.resolve(),
  };

  setTimeout(() => { runtime.onStartup._fire(); }, 1500);

  /* The WebView's origin rules would block the sync server; the app does it. */
  const realFetch = window.fetch.bind(window);
  const waiting = {};
  let fid = 0;
  window.fetch = (input, init = {}) => {
    const url = typeof input === 'string' ? input : input.url;
    if (url.startsWith(ORIGIN)) return realFetch(input, init);
    return new Promise((res, rej) => {
      const id = ++fid;
      waiting[id] = { res, rej };
      if (init.signal) {
        init.signal.addEventListener('abort', () => {
          delete waiting[id];
          rej(new DOMException('Aborted', 'AbortError'));
        });
      }
      const headers = {};
      new Headers(init.headers || {}).forEach((v, k) => { headers[k] = v; });
      B.fetch(String(id), url, (init.method || 'GET').toUpperCase(), JSON.stringify(headers),
        typeof init.body === 'string' ? init.body : '');
    });
  };
  window.__bcFetchDone = (id, r) => {
    const w = waiting[id];
    if (!w) return;
    delete waiting[id];
    if (r.error) { w.rej(new TypeError('Failed to fetch')); return; }
    const noBody = [101, 204, 205, 304].includes(r.status);
    w.res(new Response(noBody ? null : r.body, { status: r.status, headers: r.headers }));
  };
})();
