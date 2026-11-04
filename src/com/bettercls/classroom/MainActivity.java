package com.bettercls.classroom;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.view.MenuItem;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.List;

public class MainActivity extends BaseActivity {
    private static final String HOME = "https://classroom.google.com/";
    private static final int M_SETTINGS = 1, M_OPTIONS = 2, M_DESKTOP = 3, M_UPDATE = 4, M_RELOAD = 5, M_PERF = 6, M_LOGIN = 7;

    private Creds creds;
    private WebView web;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("main", MODE_PRIVATE);
        creds = new Creds(this);
        getWindow().setStatusBarColor(0xFFFFFFFF);
        getWindow().setNavigationBarColor(0xFFFFFFFF);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        web = new WebView(this);
        FrameLayout frame = new FrameLayout(this);
        frame.addView(web, new FrameLayout.LayoutParams(-1, -1));
        frame.addView(gear(), gearParams());
        setContentView(frame);
        configure(web);
        applyUserAgent(false);
        web.addJavascriptInterface(Hub.I.new Bridge("page"), "__bcNative");
        web.setWebChromeClient(chrome());
        web.setDownloadListener((url, ua, cd, mime, len) -> openExternally(url));
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String host = u.getHost() == null ? "" : u.getHost();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                // intent:, market: and the Play Store are how a page pushes the official app
                if (!scheme.equals("https") && !scheme.equals("http")) {
                    if (scheme.equals("mailto") || scheme.equals("tel")) openExternally(u.toString());
                    else Hub.I.toast("Blocked app redirect: " + shorten(u.toString()));
                    return true;
                }
                if (host.equals("play.google.com") || host.equals("market.android.com")) {
                    Hub.I.toast("Blocked app redirect: " + shorten(u.toString()));
                    return true;
                }
                if (inApp(host)) return false;
                // sign-in hops through other hosts without a tap; only a real tap leaves the app
                if (!r.hasGesture()) return false;
                openExternally(u.toString());
                return true;
            }
            @Override
            public void onPageStarted(WebView v, String url, Bitmap icon) {
                inject(v, url, false);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                inject(v, url, true);
                autoLogin(v, url);
            }
        });
        Hub.I.page = web;

        Uri data = getIntent().getData();
        String start = data != null && "classroom.google.com".equals(data.getHost()) ? data.toString() : HOME;
        // open at once with the installed extension; a newer one downloads in the background (onResume) and is used from the next start
        web.loadUrl(start);
    }

    private static boolean inApp(String host) {
        return host.matches("(.+\\.)?google\\.[a-z.]+") || host.endsWith("gstatic.com")
                || host.endsWith("googleusercontent.com") || host.endsWith("googleapis.com")
                || host.endsWith("youtube.com");
    }

    private static String shorten(String s) {
        return s.length() > 90 ? s.substring(0, 90) + "\u2026" : s;
    }

    /** Fill Google's sign-in pages from the saved details, a couple of tries at most. */
    private void autoLogin(WebView v, String url) {
        if (url == null || !url.startsWith("https://accounts.google.com/") || !creds.has()) return;
        String pw = creds.password();
        if (pw.isEmpty()) return;
        String js;
        try {
            js = new String(Hub.I.readAsset("autologin.js"), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return;
        }
        v.evaluateJavascript("(" + js + ")(" + org.json.JSONObject.quote(creds.email()) + ","
                + org.json.JSONObject.quote(pw) + ")", null);
    }

    private void loginDialog() {
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(48, 24, 48, 0);
        android.widget.EditText email = new android.widget.EditText(this);
        email.setHint("Google email (optional if the page already shows it)");
        email.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        email.setText(creds.email());
        android.widget.EditText pass = new android.widget.EditText(this);
        pass.setHint(creds.has() ? "Password (leave empty to keep)" : "Password");
        pass.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(email);
        box.addView(pass);
        android.app.AlertDialog.Builder d = new android.app.AlertDialog.Builder(this)
                .setTitle("Auto-login")
                .setMessage("Stored encrypted on this phone only. Used to sign in when Google asks again.")
                .setView(box)
                .setPositiveButton("Save", (x, i) -> {
                    String e = email.getText().toString().trim();
                    String p = pass.getText().toString();
                    if (p.isEmpty() && creds.has()) p = creds.password();
                    if (p.isEmpty() || !creds.save(e, p)) {
                        Hub.I.toast("Could not save the login");
                    } else {
                        Hub.I.toast("Auto-login saved");
                    }
                })
                .setNegativeButton("Cancel", null);
        if (creds.has()) d.setNeutralButton("Remove", (x, i) -> { creds.clear(); Hub.I.toast("Auto-login removed"); });
        d.show();
    }

    /** Run the extension's content scripts once per phase in this document. */
    private void inject(WebView v, String url, boolean idle) {
        if (url == null || !url.startsWith(HOME)) return;
        run(v, url, "start");
        if (idle) run(v, url, "idle");
    }

    private void run(WebView v, String url, String phase) {
        v.evaluateJavascript("(window.__bcPh&&window.__bcPh['" + phase + "'])?1:0", ans -> {
            if ("1".equals(ans)) return;
            boolean perf = prefs.getBoolean("perf", true);
            v.evaluateJavascript("window.__BC_PERF=" + perf + ";", null);
            if (perf && phase.equals("start")) {
                // frosted-glass blur is repainted on every scroll frame, which phone GPUs hate
                v.evaluateJavascript("(function(){var s=document.createElement('style');"
                        + "s.textContent='*,*::before,*::after{-webkit-backdrop-filter:none!important;backdrop-filter:none!important}';"
                        + "(document.head||document.documentElement).appendChild(s);})();", null);
            }
            // the extension reads this when its scripts start, so set it first
            if (phase.equals("start")) {
                v.evaluateJavascript("try{localStorage.setItem('tmcb-mobile','1')}catch(e){}", null);
            }
            List<String> scripts = Hub.I.contentScripts(url, phase);
            for (String s : scripts) v.evaluateJavascript(s, null);
            // the extension has registered its listener by now: tell it this is a phone app
            if (phase.equals("idle")) {
                v.evaluateJavascript("(function(){try{localStorage.setItem('tmcb-mobile','1')}catch(e){}"
                        + "var tries=0;(function go(){"
                        + "if(window.betterclassroom&&window.betterclassroom.mobile){window.betterclassroom.mobile(true);}"
                        + "else{document.dispatchEvent(new CustomEvent('betterclassroom:mobile',{detail:true}));"
                        + "if(++tries<20)setTimeout(go,500);}})();})();", null);
            }
        });
    }

    private void applyUserAgent(boolean reload) {
        String def = WebSettings.getDefaultUserAgent(this);
        // Google refuses sign-in from anything that calls itself a WebView.
        String mobile = def.replace("; wv", "").replace("Version/4.0 ", "");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Chrome/[\\d.]+").matcher(def);
        String chrome = m.find() ? m.group() : Hub.UA_CHROME;
        String desktop = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " + chrome + " Safari/537.36";
        web.getSettings().setUserAgentString(prefs.getBoolean("desktop", false) ? desktop : mobile);
        if (reload) web.reload();
    }

    /** The small settings button that replaces the app bar. */
    private View gear() {
        TextView g = new TextView(this);
        g.setText("\u2699");
        g.setTextSize(18);
        g.setGravity(Gravity.CENTER);
        g.setTextColor(0x99FFFFFF);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x883C4043);
        g.setBackground(bg);
        g.setOnClickListener(v -> showMenu(v));
        return g;
    }

    private FrameLayout.LayoutParams gearParams() {
        int size = (int) (34 * getResources().getDisplayMetrics().density);
        int gap = (int) (10 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(size, size, Gravity.BOTTOM | Gravity.END);
        p.setMargins(0, 0, gap, gap * 6);
        return p;
    }

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu menu = pm.getMenu();
        menu.add(0, M_SETTINGS, 0, "Better Classroom settings");
        menu.add(0, M_OPTIONS, 1, "Full options page");
        menu.add(0, M_DESKTOP, 2, "Desktop site").setCheckable(true).setChecked(prefs.getBoolean("desktop", false));
        menu.add(0, M_PERF, 2, "Smooth scrolling (no blur)").setCheckable(true).setChecked(prefs.getBoolean("perf", true));
        menu.add(0, M_UPDATE, 3, "Extension v" + Hub.I.version() + (Hub.I.dev ? " (dev, bundled)" : " - check for update"));
        menu.add(0, M_LOGIN, 4, creds.has() ? "Auto-login: on" + (creds.email().isEmpty() ? "" : " (" + creds.email() + ")") : "Auto-login\u2026");
        menu.add(0, M_RELOAD, 5, "Reload");
        pm.setOnMenuItemClickListener(this::onOptionsItemSelected);
        pm.show();
    }
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case M_SETTINGS:
                web.evaluateJavascript("window.__bcMessage&&window.__bcMessage({type:'open-settings'})", null);
                return true;
            case M_OPTIONS:
                startActivity(new Intent(this, OptionsActivity.class));
                return true;
            case M_DESKTOP:
                prefs.edit().putBoolean("desktop", !prefs.getBoolean("desktop", false)).apply();
                applyUserAgent(true);
                return true;
            case M_PERF:
                prefs.edit().putBoolean("perf", !prefs.getBoolean("perf", true)).apply();
                web.reload();
                return true;
            case M_LOGIN:
                loginDialog();
                return true;
            case M_UPDATE:
                Hub.I.maybeUpdate(true);
                return true;
            case M_RELOAD:
                web.reload();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Hub.I.maybeUpdate(false);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (Hub.I.page == web) Hub.I.page = null;
        web.destroy();
        super.onDestroy();
    }
}
