package tr.ugco.blokapp;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {

    private WebView web;
    private TextToSpeech tts;
    private boolean ttsHazir = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);

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
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}
