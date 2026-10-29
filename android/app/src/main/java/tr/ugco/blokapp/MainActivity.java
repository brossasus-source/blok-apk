package tr.ugco.blokapp;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Locale;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {

    private FrameLayout root;
    private WebView web;
    private TextToSpeech tts;
    private boolean ttsHazir = false;
    // Tasarımdaki "Web görüntüleyici" bileşenleri: bileşen adı -> gerçek WebView
    private final HashMap<String, WebView> webler = new HashMap<String, WebView>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false); // assets klasörü bundan etkilenmez

        tts = new TextToSpeech(this, this);

        // Bloklardan üretilen JS, "Android.toast(...)" gibi çağrılarla buraya ulaşır
        web.addJavascriptInterface(new Kopru(), "Android");
        web.setWebViewClient(new WebViewClient());
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onInit(int durum) {
        if (durum == TextToSpeech.SUCCESS) {
            tts.setLanguage(new Locale("tr", "TR"));
            ttsHazir = true;
        }
    }

    private class Kopru {
        @JavascriptInterface
        public void toast(final String mesaj) {
            runOnUiThread(new Runnable() {
                public void run() {
                    Toast.makeText(MainActivity.this, mesaj, Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void vibrate(int ms) {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate((long) ms);
        }

        @JavascriptInterface
        public void speak(String metin) {
            if (ttsHazir) tts.speak(metin, TextToSpeech.QUEUE_FLUSH, null, "ugco");
        }

        // Web görüntüleyiciyi ekrandaki yerine koyar (konum ve boyut, ekranın oranı olarak gelir: 0..1)
        @JavascriptInterface
        public void webEkle(final String id, final String url, final double x, final double y, final double w, final double h) {
            runOnUiThread(new Runnable() {
                public void run() {
                    WebView v = webler.get(id);
                    if (v == null) {
                        v = new WebView(MainActivity.this);
                        WebSettings ws = v.getSettings();
                        ws.setJavaScriptEnabled(true);
                        ws.setDomStorageEnabled(true);
                        v.setWebViewClient(new WebViewClient());
                        webler.put(id, v);
                        root.addView(v);
                    }
                    int rw = root.getWidth();
                    int rh = root.getHeight();
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) (w * rw), (int) (h * rh));
                    lp.leftMargin = (int) (x * rw);
                    lp.topMargin = (int) (y * rh);
                    v.setLayoutParams(lp);
                    if (url != null && url.length() > 0) v.loadUrl(url);
                }
            });
        }

        @JavascriptInterface
        public void webGit(final String id, final String url) {
            runOnUiThread(new Runnable() {
                public void run() {
                    WebView v = webler.get(id);
                    if (v != null && url != null) v.loadUrl(url);
                }
            });
        }

        @JavascriptInterface
        public void webGor(final String id, final boolean goster) {
            runOnUiThread(new Runnable() {
                public void run() {
                    WebView v = webler.get(id);
                    if (v != null) v.setVisibility(goster ? View.VISIBLE : View.GONE);
                }
            });
        }
    }

    @Override
    public void onBackPressed() {
        for (WebView v : webler.values()) {
            if (v.getVisibility() == View.VISIBLE && v.canGoBack()) {
                v.goBack();
                return;
            }
        }
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        for (WebView v : webler.values()) v.destroy();
        webler.clear();
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}
