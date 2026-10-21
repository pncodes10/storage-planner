package com.storageplanner.platform.android;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.storageplanner.tools.ocr.TextRules;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Offline text reader: Tesseract compiled to WebAssembly (tesseract.js), running in an invisible WebView.
 * Everything the page asks for is answered from the app's own assets (and the one picture being read);
 * any other address gets an empty "404", and the app has no INTERNET permission, so nothing can leave the phone.
 * Created and driven on the main thread; read() is called from the analysis thread and waits for the result.
 */
final class OcrEngine {
    static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final int WORKERS = Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() / 3));

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private final CountDownLatch started = new CountDownLatch(1);
    private volatile boolean ok;
    private volatile String error;
    private int nextId;
    private final Map<Integer, byte[]> images = new ConcurrentHashMap<Integer, byte[]>();
    private final Map<Integer, String[]> results = new ConcurrentHashMap<Integer, String[]>();
    private final Map<Integer, CountDownLatch> waiting = new ConcurrentHashMap<Integer, CountDownLatch>();

    OcrEngine(Context c) { ctx = c.getApplicationContext(); }

    /** Starts the reader (loads the engine and language data); false if this phone's WebView cannot run it. */
    boolean start(long timeoutMs) {
        main.post(new Runnable() { public void run() { create(); } });
        try { started.await(timeoutMs, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return ok;
    }

    String error() { return error; }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void create() {
        try {
            web = new WebView(ctx);
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);                 // the reader is JavaScript + WebAssembly
            s.setAllowFileAccess(false);
            s.setAllowContentAccess(false);
            s.setDomStorageEnabled(false);
            s.setGeolocationEnabled(false);
            s.setSaveFormData(false);
            s.setCacheMode(WebSettings.LOAD_NO_CACHE);
            web.addJavascriptInterface(new Bridge(), "Native");
            web.setWebViewClient(new WebViewClient() {
                @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) { return serve(r.getUrl().toString()); }
                @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; }   // never navigate
                @Override public void onPageFinished(WebView v, String url) { v.evaluateJavascript("start(" + WORKERS + ")", null); }
            });
            web.loadUrl(ORIGIN + "/ocr/ocr.html");
        } catch (Throwable t) {
            error = "WebView unavailable: " + t;
            started.countDown();
        }
    }

    /** Answers every request from inside the app; anything else is refused. */
    private WebResourceResponse serve(String url) {
        try {
            if (url.startsWith(ORIGIN + "/img/")) {
                byte[] b = images.get(Integer.parseInt(url.substring((ORIGIN + "/img/").length())));
                if (b != null) return response("image/jpeg", new ByteArrayInputStream(b));
            } else if (url.startsWith(ORIGIN + "/ocr/")) {
                String name = url.substring((ORIGIN + "/ocr/").length());
                int q = name.indexOf('?'); if (q >= 0) name = name.substring(0, q);
                name = name.replace("relaxedsimd", "simd");          // newer engines ask for this build; the SIMD one runs there too
                if (name.matches("[A-Za-z0-9_.-]+")) {
                    String type = name.endsWith(".html") ? "text/html" : name.endsWith(".js") ? "application/javascript"
                            : name.endsWith(".wasm") ? "application/wasm" : "application/octet-stream";
                    return response(type, ctx.getAssets().open("ocr/" + name));
                }
            }
        } catch (Throwable ignored) {}
        WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
        r.setStatusCodeAndReasonPhrase(404, "Not available offline");
        return r;
    }

    private static WebResourceResponse response(String type, InputStream in) {
        WebResourceResponse r = new WebResourceResponse(type, type.startsWith("text/") ? "utf-8" : null, in);
        Map<String, String> h = new HashMap<String, String>();
        h.put("Access-Control-Allow-Origin", ORIGIN);
        r.setResponseHeaders(h);
        return r;
    }

    /** Reads the text of a JPEG picture. Null if the reader is not running or took longer than timeoutMs. */
    TextRules.Words read(byte[] jpeg, long timeoutMs) {
        if (!ok) return null;
        final int id;
        synchronized (this) { id = ++nextId; }
        CountDownLatch done = new CountDownLatch(1);
        images.put(id, jpeg);
        waiting.put(id, done);
        main.post(new Runnable() { public void run() { if (web != null) web.evaluateJavascript("read(" + id + ")", null); } });
        try { done.await(timeoutMs, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        images.remove(id); waiting.remove(id);
        String[] r = results.remove(id);
        if (r == null || r[0] == null) return null;
        return parse(r[0]);
    }

    void close() {
        ok = false;
        main.post(new Runnable() {
            public void run() {
                if (web != null) { try { web.stopLoading(); web.removeJavascriptInterface("Native"); web.destroy(); } catch (Throwable ignored) {} web = null; }
            }
        });
    }

    /** Called by the page (on a WebView thread). */
    private final class Bridge {
        @JavascriptInterface public void ready(int workers) { ok = true; started.countDown(); }
        @JavascriptInterface public void done(int id, String json) {
            results.put(id, new String[]{json});
            CountDownLatch l = waiting.get(id); if (l != null) l.countDown();
        }
        @JavascriptInterface public void failed(int id, String msg) {
            if (id < 0) { error = msg; started.countDown(); return; }
            results.put(id, new String[]{null});
            CountDownLatch l = waiting.get(id); if (l != null) l.countDown();
        }
    }

    static TextRules.Words parse(String json) { return TextRules.parseJson(json); }
}
