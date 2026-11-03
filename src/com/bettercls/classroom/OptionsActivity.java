package com.bettercls.classroom;

import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** The extension's own options page, served from its files like in Chrome. */
public class OptionsActivity extends BaseActivity {
    private WebView web;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        setContentView(web);
        configure(web);
        web.addJavascriptInterface(Hub.I.new Bridge("options"), "__bcNative");
        web.setWebChromeClient(chrome());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return Hub.I.serve(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (Hub.HOST.equals(r.getUrl().getHost())) return false;
                openExternally(r.getUrl().toString());
                return true;
            }
        });
        Hub.I.options = web;
        web.loadUrl(Hub.OPTIONS_URL);
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    protected void onDestroy() {
        if (Hub.I.options == web) Hub.I.options = null;
        web.destroy();
        super.onDestroy();
    }
}
