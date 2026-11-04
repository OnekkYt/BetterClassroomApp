package com.bettercls.classroom;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Everything the extension needs from a browser, supplied here: its files (kept
 * current from the Chrome Web Store), chrome.storage, and a hidden WebView that
 * runs the extension's own service worker so cloud sync, licences and badges
 * behave exactly as in Chrome.
 */
final class Hub {
    static final String HOST = "bcext.local";
    static final String ORIGIN = "https://" + HOST;
    static final String BG_URL = ORIGIN + "/_bg.html";
    static final String OPTIONS_URL = ORIGIN + "/src/options/options.html";
    static final String CRX_URL = "https://clients2.google.com/service/update2/crx"
            + "?response=redirect&prod=chromecrx&prodchannel=&prodversion=130.0.0.0"
            + "&acceptformat=crx2,crx3"
            + "&x=id%3Dnldhifdlnmmkgngbnnmkoppinhafblbi%26installsource%3Dondemand%26uc";
    static final String UA_CHROME = "Chrome/130.0.0.0";
    private static final long CHECK_EVERY_MS = 30L * 60 * 1000;

    static Hub I;
    /** A dev build carries its own extension and never touches the Web Store. */
    final boolean dev;

    final Context ctx;
    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService io = Executors.newCachedThreadPool();
    private final File root, storeFile;
    private File cur;
    private final JSONObject store;
    private final SharedPreferences prefs;
    private boolean saveQueued;

    WebView bg, page, options;
    private boolean updating;

    static void init(Context app) {
        if (I == null) I = new Hub(app);
    }

    private Hub(Context app) {
        ctx = app;
        boolean flag;
        try { app.getAssets().open("dev.flag").close(); flag = true; } catch (IOException e) { flag = false; }
        dev = flag;
        prefs = app.getSharedPreferences("hub", Context.MODE_PRIVATE);
        root = new File(app.getFilesDir(), "ext");
        cur = new File(root, prefs.getString("dir", "current"));
        storeFile = new File(app.getFilesDir(), "storage.json");
        store = loadStore();
        try {
            installBundled();
        } catch (IOException e) {
            android.util.Log.e("BC", "bundled install failed", e);
        }
        // an update downloaded last session is live now; drop everything else
        File[] stale = root.listFiles();
        if (stale != null) for (File f : stale) if (!f.equals(cur)) deleteRec(f);
        startBackground();
    }

    /* --------------------------------------------------------- extension files */

    private void installBundled() throws IOException {
        String bundled = versionOf(new String(readAsset("ext/manifest.json"), StandardCharsets.UTF_8));
        File m = new File(cur, "manifest.json");
        String have = m.isFile() ? versionOf(new String(readFile(m), StandardCharsets.UTF_8)) : null;
        // a different kind of build (store/dev) or a new app version always reinstalls what it carries
        int code = 0;
        try { code = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode; } catch (Exception ignored) { }
        String key = (dev ? "dev" : "store") + code;
        boolean forced = !key.equals(prefs.getString("bundleKey", ""));
        if (have == null || forced || compare(bundled, have) > 0) {
            prefs.edit().putString("bundleKey", key).commit();
            File dest = new File(root, "bundled-" + bundled);
            deleteRec(dest);
            copyAsset("ext", dest);
            activate(dest);
        }
    }

    private void copyAsset(String path, File dest) throws IOException {
        String[] kids = ctx.getAssets().list(path);
        if (kids != null && kids.length > 0) {
            dest.mkdirs();
            for (String k : kids) copyAsset(path + "/" + k, new File(dest, k));
        } else {
            try (InputStream in = ctx.getAssets().open(path); OutputStream out = new FileOutputStream(dest)) {
                pipe(in, out);
            }
        }
    }

    byte[] readAsset(String path) throws IOException {
        try (InputStream in = ctx.getAssets().open(path)) {
            return readAll(in);
        }
    }

    String manifestText() {
        try {
            return new String(readFile(new File(cur, "manifest.json")), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "{}";
        }
    }

    String version() {
        return versionOf(manifestText());
    }

    String readText(String rel) {
        try {
            File f = new File(cur, rel).getCanonicalFile();
            if (!f.getPath().startsWith(cur.getCanonicalPath() + File.separator)) return null;
            return new String(readFile(f), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Scripts to run in a Classroom page for one phase ("start" or "idle"), in
     * manifest order: the shim first, then each matching entry's CSS and JS.
     */
    private final Map<String, List<String>> scriptCache = new HashMap<>();

    synchronized List<String> contentScripts(String url, String phase) {
        String key = cur.getName() + "|" + phase;
        List<String> hit = scriptCache.get(key);
        if (hit != null) return hit;
        List<String> built = buildScripts(url, phase);
        scriptCache.put(key, built);
        return built;
    }

    private List<String> buildScripts(String url, String phase) {
        List<String> out = new ArrayList<>();
        try {
            JSONObject mf = new JSONObject(manifestText());
            JSONArray cs = mf.optJSONArray("content_scripts");
            out.add("(window.__bcPh=window.__bcPh||{})['" + phase + "']=1;");
            out.add("window.__BC_ROLE='content';");
            out.add(new String(readAsset("shim.js"), StandardCharsets.UTF_8));
            if (cs == null) return out;
            for (int i = 0; i < cs.length(); i++) {
                JSONObject e = cs.getJSONObject(i);
                boolean start = "document_start".equals(e.optString("run_at", "document_idle"));
                if (start != "start".equals(phase)) continue;
                if (!matches(e.optJSONArray("matches"), url)) continue;
                JSONArray css = e.optJSONArray("css");
                for (int j = 0; css != null && j < css.length(); j++) {
                    String text = readText(css.getString(j));
                    if (text == null) continue;
                    out.add("(function(){var s=document.createElement('style');s.textContent="
                            + JSONObject.quote(text)
                            + ";(document.head||document.documentElement).appendChild(s);})();");
                }
                JSONArray js = e.optJSONArray("js");
                for (int j = 0; js != null && j < js.length(); j++) {
                    String text = readText(js.getString(j));
                    if (text != null) out.add(text + "\n//# sourceURL=bc/" + js.getString(j));
                }
            }
        } catch (Exception e) {
            android.util.Log.e("BC", "content scripts", e);
        }
        return out;
    }

    private static boolean matches(JSONArray patterns, String url) {
        if (patterns == null) return false;
        for (int i = 0; i < patterns.length(); i++) {
            String[] parts = patterns.optString(i).split("\\*", -1);
            StringBuilder re = new StringBuilder();
            for (int k = 0; k < parts.length; k++) {
                if (k > 0) re.append(".*");
                re.append(Pattern.quote(parts[k]));
            }
            if (Pattern.matches(re.toString(), url)) return true;
        }
        return false;
    }

    /** Answers requests for the extension's own origin from its files. */
    WebResourceResponse serve(Uri u) {
        if (!HOST.equals(u.getHost())) return null;
        String p = u.getPath();
        if (p == null || p.equals("/")) p = "/_bg.html";
        try {
            byte[] data;
            String mime;
            if (p.equals("/_bg.html")) {
                String sw = new JSONObject(manifestText()).getJSONObject("background").getString("service_worker");
                data = ("<!doctype html><meta charset=utf-8><script>window.__BC_ROLE='bg'</script>"
                        + "<script src='/_shim.js'></script>"
                        + "<script type='module' src='/" + sw + "'></script>").getBytes(StandardCharsets.UTF_8);
                mime = "text/html";
            } else if (p.equals("/_shim.js")) {
                data = readAsset("shim.js");
                mime = "text/javascript";
            } else {
                File f = new File(cur, p.substring(1)).getCanonicalFile();
                if (!f.getPath().startsWith(cur.getCanonicalPath() + File.separator) || !f.isFile()) {
                    return respond("text/plain", new byte[0], 404, "Not Found");
                }
                data = readFile(f);
                mime = mimeOf(f.getName());
                if (mime.equals("text/html")) {
                    String html = new String(data, StandardCharsets.UTF_8);
                    String tags = "<script>window.__BC_ROLE='options'</script><script src='/_shim.js'></script>";
                    java.util.regex.Matcher m = Pattern.compile("(?i)<head[^>]*>").matcher(html);
                    html = m.find() ? html.substring(0, m.end()) + tags + html.substring(m.end()) : tags + html;
                    data = html.getBytes(StandardCharsets.UTF_8);
                }
            }
            return respond(mime, data, 200, "OK");
        } catch (Exception e) {
            return respond("text/plain", new byte[0], 500, "Error");
        }
    }

    private static WebResourceResponse respond(String mime, byte[] data, int code, String reason) {
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Cache-Control", "no-store");
        WebResourceResponse r = new WebResourceResponse(mime, "utf-8", new ByteArrayInputStream(data));
        r.setStatusCodeAndReasonPhrase(code, reason);
        r.setResponseHeaders(h);
        return r;
    }

    private static String mimeOf(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".html")) return "text/html";
        if (n.endsWith(".js") || n.endsWith(".mjs")) return "text/javascript";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    /* ---------------------------------------------------------------- updating */

    void maybeUpdate(boolean force) {
        if (dev) {
            if (force) toast("Dev build: updates are off, using the bundled extension (v" + version() + ")");
            return;
        }
        long last = prefs.getLong("lastCheck", 0);
        if (!force && System.currentTimeMillis() - last < CHECK_EVERY_MS) return;
        synchronized (this) {
            if (updating) return;
            updating = true;
        }
        io.execute(() -> {
            String msg;
            try {
                msg = updateNow();
                prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply();
            } catch (Exception e) {
                msg = "Extension update check failed: " + e.getMessage();
            }
            synchronized (this) { updating = false; }
            if (force || msg.startsWith("Extension updated")) toast(msg);
        });
    }

    /** The newest version already on disk: the running one, or one staged for the next start. */
    private String newestVersion() {
        File m = new File(root, prefs.getString("dir", cur.getName()) + "/manifest.json");
        try {
            if (m.isFile()) return versionOf(new String(readFile(m), StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
        return version();
    }

    /** A tiny Web Store query: the newest version, or null when none is newer than ours (or it can't tell). */
    private String remoteNewer(String have) {
        try {
            byte[] b = download("https://clients2.google.com/service/update2/crx?prodversion=130.0.0.0&acceptformat=crx3"
                    + "&x=id%3Dnldhifdlnmmkgngbnnmkoppinhafblbi%26v%3D" + have + "%26uc");
            java.util.regex.Matcher m = Pattern.compile("<updatecheck[^>]*\\sversion=\"([^\"]+)\"").matcher(new String(b, StandardCharsets.UTF_8));
            return m.find() && compare(m.group(1), have) > 0 ? m.group(1) : null;
        } catch (Exception e) {
            return "?";   // couldn't tell: do the full download
        }
    }

    private String updateNow() throws IOException {
        String cv = newestVersion();
        String rv = remoteNewer(cv);
        if (rv == null) return "Extension is up to date (v" + cv + ")";
        File next = new File(root, "next");
        deleteRec(next);
        byte[] crx = download(CRX_URL);
        int off = zipOffset(crx);
        unzip(crx, off, next);
        File nm = new File(next, "manifest.json");
        String nv = nm.isFile() ? versionOf(new String(readFile(nm), StandardCharsets.UTF_8)) : null;
        if (nv == null || nv.isEmpty()) {
            deleteRec(next);
            throw new IOException("downloaded package has no manifest");
        }
        if (compare(nv, cv) <= 0) {
            deleteRec(next);
            return "Extension is up to date (v" + cv + ")";
        }
        File dest = new File(root, "v-" + nv);
        deleteRec(dest);
        if (!next.renameTo(dest)) {
            unzip(crx, off, dest);
            deleteRec(next);
        }
        // stage it: the running copy stays untouched until the app is restarted
        prefs.edit().putString("dir", dest.getName()).commit();
        File[] all = root.listFiles();
        if (all != null) for (File f : all) if (!f.equals(dest) && !f.equals(cur)) deleteRec(f);
        return "Extension updated to v" + nv + " (was v" + cv + "). Restart the app to use it.";
    }

    /** Point at a new extension folder and drop every other one. */
    private synchronized void activate(File dir) {
        prefs.edit().putString("dir", dir.getName()).commit();
        File old = cur;
        cur = dir;
        File[] all = root.listFiles();
        if (all != null) for (File f : all) if (!f.equals(dir) && !f.equals(old)) deleteRec(f);
        if (old != null && !old.equals(dir)) deleteRec(old);
    }

    private static byte[] download(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                + UA_CHROME + " Safari/537.36");
        if (c.getResponseCode() != 200) throw new IOException("Web Store answered " + c.getResponseCode());
        try (InputStream in = c.getInputStream()) {
            byte[] b = readAll(in);
            if (b.length > 40 * 1024 * 1024) throw new IOException("package too large");
            return b;
        } finally {
            c.disconnect();
        }
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24;
    }

    private static int zipOffset(byte[] b) throws IOException {
        if (b.length < 16 || b[0] != 'C' || b[1] != 'r' || b[2] != '2' || b[3] != '4') {
            throw new IOException("not a CRX file");
        }
        int ver = le32(b, 4);
        int off;
        if (ver == 3) off = 12 + le32(b, 8);
        else if (ver == 2) off = 16 + le32(b, 8) + le32(b, 12);
        else throw new IOException("unknown CRX version " + ver);
        if (off < 0 || off + 4 > b.length || b[off] != 'P' || b[off + 1] != 'K') {
            throw new IOException("CRX has no zip body");
        }
        return off;
    }

    private static void unzip(byte[] crx, int off, File dest) throws IOException {
        String base = dest.getCanonicalPath() + File.separator;
        dest.mkdirs();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(crx, off, crx.length - off))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String name = e.getName();
                if (name.startsWith("_metadata/")) continue;
                File f = new File(dest, name).getCanonicalFile();
                if (!f.getPath().startsWith(base)) throw new IOException("bad entry " + name);
                if (e.isDirectory()) {
                    f.mkdirs();
                    continue;
                }
                f.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(f)) {
                    pipe(z, out);
                }
            }
        }
    }

    static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private static String versionOf(String manifestJson) {
        try { return new JSONObject(manifestJson).optString("version", "0"); } catch (Exception e) { return "0"; }
    }

    void toast(String msg) {
        ui.post(() -> Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show());
    }

    /* ------------------------------------------------------------- background */

    private void startBackground() {
        bg = new WebView(ctx);
        BaseActivity.configure(bg);
        bg.addJavascriptInterface(new Bridge("bg"), "__bcNative");
        bg.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return serve(r.getUrl());
            }
        });
        bg.loadUrl(BG_URL);
    }

    void reloadBackground() {
        if (bg != null) bg.loadUrl(BG_URL);
    }

    /** Run JS in the page / options WebView, if one is showing. */
    void evalIn(String role, String js) {
        ui.post(() -> {
            WebView w = role.equals("bg") ? bg : role.equals("page") ? page : options;
            if (w != null) w.evaluateJavascript(js, null);
        });
    }

    /* ---------------------------------------------------------------- storage */

    private JSONObject loadStore() {
        try {
            if (storeFile.isFile()) return new JSONObject(new String(readFile(storeFile), StandardCharsets.UTF_8));
        } catch (Exception e) {
            android.util.Log.e("BC", "storage unreadable", e);
        }
        return new JSONObject();
    }

    private void scheduleSave() {
        if (saveQueued) return;
        saveQueued = true;
        ui.postDelayed(() -> io.execute(() -> {
            String snapshot;
            synchronized (store) {
                saveQueued = false;
                snapshot = store.toString();
            }
            try (OutputStream out = new FileOutputStream(storeFile)) {
                out.write(snapshot.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                android.util.Log.e("BC", "storage save failed", e);
            }
        }), 1500);
    }

    private void announce(JSONObject changes) {
        if (changes.length() == 0) return;
        // the service worker only needs the key names (and the one value it reads),
        // so it never receives banner and background images
        JSONObject slim = new JSONObject();
        try {
            for (Iterator<String> it = changes.keys(); it.hasNext(); ) {
                String key = it.next();
                slim.put(key, key.equals("pageIdentity") ? changes.get(key) : new JSONObject());
            }
        } catch (Exception ignored) { }
        evalIn("bg", "window.__bcChanged&&window.__bcChanged(" + slim + ")");
        String js = "window.__bcChanged&&window.__bcChanged(" + changes + ")";
        evalIn("page", js);
        evalIn("options", js);
    }

    private String storageGet(String keysJson) throws Exception {
        Object k = keysJson == null ? null : new JSONTokener(keysJson).nextValue();
        JSONObject out = new JSONObject();
        synchronized (store) {
            if (k == null || k == JSONObject.NULL) {
                for (Iterator<String> it = store.keys(); it.hasNext(); ) {
                    String key = it.next();
                    out.put(key, store.get(key));
                }
            } else if (k instanceof String) {
                if (store.has((String) k)) out.put((String) k, store.get((String) k));
            } else if (k instanceof JSONArray) {
                JSONArray a = (JSONArray) k;
                for (int i = 0; i < a.length(); i++) {
                    String key = a.getString(i);
                    if (store.has(key)) out.put(key, store.get(key));
                }
            } else if (k instanceof JSONObject) {
                JSONObject d = (JSONObject) k;
                for (Iterator<String> it = d.keys(); it.hasNext(); ) {
                    String key = it.next();
                    out.put(key, store.has(key) ? store.get(key) : d.get(key));
                }
            }
        }
        return out.toString();
    }

    private void storageSet(String itemsJson) throws Exception {
        JSONObject items = new JSONObject(itemsJson);
        JSONObject changes = new JSONObject();
        synchronized (store) {
            for (Iterator<String> it = items.keys(); it.hasNext(); ) {
                String key = it.next();
                if (store.has(key) && store.get(key).toString().equals(items.get(key).toString())) continue;
                JSONObject c = new JSONObject();
                if (store.has(key)) c.put("oldValue", store.get(key));
                c.put("newValue", items.get(key));
                changes.put(key, c);
                store.put(key, items.get(key));
            }
        }
        if (changes.length() == 0) return;
        scheduleSave();
        announce(changes);
    }

    private void storageRemove(String keysJson) throws Exception {
        Object k = new JSONTokener(keysJson).nextValue();
        List<String> keys = new ArrayList<>();
        if (k instanceof String) keys.add((String) k);
        else if (k instanceof JSONArray) {
            for (int i = 0; i < ((JSONArray) k).length(); i++) keys.add(((JSONArray) k).getString(i));
        }
        JSONObject changes = new JSONObject();
        synchronized (store) {
            for (String key : keys) {
                if (!store.has(key)) continue;
                changes.put(key, new JSONObject().put("oldValue", store.get(key)));
                store.remove(key);
            }
        }
        scheduleSave();
        announce(changes);
    }

    private void storageClear() throws Exception {
        List<String> keys = new ArrayList<>();
        synchronized (store) {
            for (Iterator<String> it = store.keys(); it.hasNext(); ) keys.add(it.next());
        }
        storageRemove(new JSONArray(keys).toString());
    }

    /* ----------------------------------------------------------------- bridge */

    /** What the shim in each WebView calls. One instance per WebView, tagged by role. */
    final class Bridge {
        private final String role;

        Bridge(String role) { this.role = role; }

        @JavascriptInterface
        public String storageGet(String keys) {
            try { return Hub.this.storageGet(keys); } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public void storageSet(String items) {
            try { Hub.this.storageSet(items); } catch (Exception e) { android.util.Log.e("BC", "set", e); }
        }

        @JavascriptInterface
        public void storageRemove(String keys) {
            try { Hub.this.storageRemove(keys); } catch (Exception e) { android.util.Log.e("BC", "remove", e); }
        }

        @JavascriptInterface
        public void storageClear() {
            try { Hub.this.storageClear(); } catch (Exception e) { android.util.Log.e("BC", "clear", e); }
        }

        @JavascriptInterface
        public String manifest() { return manifestText(); }

        /** page/options -> service worker */
        @JavascriptInterface
        public void send(String id, String msgJson) {
            if (role.equals("bg")) return;
            evalIn("bg", "window.__bcDeliver&&window.__bcDeliver(" + JSONObject.quote(role + ":" + id) + "," + msgJson + ")");
        }

        /** service worker -> whoever asked */
        @JavascriptInterface
        public void respond(String key, String json) {
            if (!role.equals("bg")) return;
            int c = key.indexOf(':');
            if (c < 0) return;
            evalIn(key.substring(0, c), "window.__bcReply&&window.__bcReply(" + key.substring(c + 1) + "," + json + ")");
        }

        /** The service worker's fetch(): the WebView's origin rules would block the sync server. */
        @JavascriptInterface
        public void fetch(String id, String url, String method, String headersJson, String body) {
            if (!role.equals("bg") || !url.startsWith("https://")) return;
            io.execute(() -> {
                JSONObject res = new JSONObject();
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(60000);
                    c.setRequestMethod(method);
                    JSONObject h = new JSONObject(headersJson);
                    for (Iterator<String> it = h.keys(); it.hasNext(); ) {
                        String k = it.next();
                        c.setRequestProperty(k, h.getString(k));
                    }
                    if (!body.isEmpty() && !method.equals("GET") && !method.equals("HEAD")) {
                        c.setDoOutput(true);
                        try (OutputStream out = c.getOutputStream()) {
                            out.write(body.getBytes(StandardCharsets.UTF_8));
                        }
                    }
                    int code = c.getResponseCode();
                    InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    String text = in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8);
                    JSONObject rh = new JSONObject();
                    for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                        if (e.getKey() != null && !e.getValue().isEmpty()) {
                            rh.put(e.getKey().toLowerCase(), e.getValue().get(0));
                        }
                    }
                    res.put("status", code).put("headers", rh).put("body", text);
                    c.disconnect();
                } catch (Exception e) {
                    try { res.put("error", String.valueOf(e.getMessage())); } catch (Exception ignored) { }
                }
                evalIn("bg", "window.__bcFetchDone(" + id + "," + res + ")");
            });
        }

        @JavascriptInterface
        public void openOptions() {
            ui.post(() -> {
                Intent i = new Intent(ctx, OptionsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            });
        }

        @JavascriptInterface
        public void openExternal(String url) {
            if (url == null || !url.startsWith("https://")) return;
            ui.post(() -> {
                try {
                    ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public void log(String s) { android.util.Log.d("BC/" + role, s); }
    }

    /* ---------------------------------------------------------------- helpers */

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        pipe(in, out);
        return out.toByteArray();
    }

    static byte[] readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        }
    }

    static void pipe(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    static void deleteRec(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRec(k);
        f.delete();
    }
}
