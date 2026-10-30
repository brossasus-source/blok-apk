package tr.ugco.blokapp;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.ExifInterface;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Surface;
import android.view.TextureView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
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

public class MainActivity extends Activity implements TextToSpeech.OnInitListener, SensorEventListener {

    private FrameLayout root;
    private WebView web;
    private TextToSpeech tts;
    private boolean ttsHazir = false;
    private boolean geriKilitli = false;
    private SensorManager sm;
    private boolean sensorIstek = false;
    private volatile float ax = 0, ay = 0, az = 0;
    private long sonSalla = 0;
    // Kamera (uygulamanın içinde canlı önizleme)
    private TextureView kameraView;
    private Camera kamera;
    private boolean kameraOn = false;
    private boolean kameraBekliyor = false;
    private double kx, ky, kw, kh;
    private int kameraKimlik = 0;
    private static final int IZIN_KAMERA = 101;
    private static final int ISTEK_GALERI = 77;
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
        s.setMediaPlaybackRequiresUserGesture(false);   // ses dosyaları dokunmadan da çalabilsin
        s.setAllowFileAccess(false); // assets klasörü bundan etkilenmez

        tts = new TextToSpeech(this, this);

        // Bloklardan üretilen JS, "Android.toast(...)" gibi çağrılarla buraya ulaşır
        web.addJavascriptInterface(new Kopru(), "Android");
        web.setWebViewClient(new WebViewClient());
        web.loadUrl("file:///android_asset/index.html");
    }

    private void sensorAc() {
        if (sm == null) sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) return;
        Sensor s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (s != null) sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME);
    }

    private void sensorKapat() {
        if (sm != null) sm.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        ax = e.values[0];
        ay = e.values[1];
        az = e.values[2];
        double g = Math.sqrt(ax * ax + ay * ay + az * az);
        long simdi = System.currentTimeMillis();
        if (g > 16.0 && simdi - sonSalla > 800) {   // sallama eşiği (yaklaşık 1.6 G)
            sonSalla = simdi;
            runOnUiThread(new Runnable() { public void run() {
                web.evaluateJavascript("window.ugcoSalla && window.ugcoSalla()", null);
            } });
        }
    }

    @Override
    public void onAccuracyChanged(Sensor s, int hassasiyet) { }

    // ---------- Fotoğraf dosyaları ----------
    private File fotoKlasor() {
        File d = new File(getFilesDir(), "foto");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private String guvenliAd(String ad) {
        return ad == null ? "" : ad.replaceAll("[^A-Za-z0-9_-]", "");
    }

    private void jsCagir(String js) {
        web.evaluateJavascript(js, null);
    }

    // Ham JPEG verisini döndürüp küçülterek foto/<ad>.jpg olarak kaydeder
    private String fotoKaydet(byte[] ham, String ad) {
        try {
            File gecici = new File(fotoKlasor(), "_gecici.jpg");
            FileOutputStream f = new FileOutputStream(gecici);
            f.write(ham);
            f.close();
            int derece = 0;
            try {
                ExifInterface ex = new ExifInterface(gecici.getAbsolutePath());
                int o = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (o == ExifInterface.ORIENTATION_ROTATE_90) derece = 90;
                else if (o == ExifInterface.ORIENTATION_ROTATE_180) derece = 180;
                else if (o == ExifInterface.ORIENTATION_ROTATE_270) derece = 270;
            } catch (Exception e) { }
            BitmapFactory.Options sinir = new BitmapFactory.Options();
            sinir.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(gecici.getAbsolutePath(), sinir);
            int ornek = 1;
            while (Math.max(sinir.outWidth, sinir.outHeight) / ornek > 2400) ornek *= 2;
            BitmapFactory.Options sec = new BitmapFactory.Options();
            sec.inSampleSize = ornek;
            Bitmap bm = BitmapFactory.decodeFile(gecici.getAbsolutePath(), sec);
            if (bm == null) return null;
            int enb = Math.max(bm.getWidth(), bm.getHeight());
            if (enb > 1600) {
                float k = 1600f / enb;
                bm = Bitmap.createScaledBitmap(bm, Math.round(bm.getWidth() * k), Math.round(bm.getHeight() * k), true);
            }
            if (derece != 0) {
                Matrix m = new Matrix();
                m.postRotate(derece);
                bm = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            }
            FileOutputStream cikis = new FileOutputStream(new File(fotoKlasor(), ad + ".jpg"));
            bm.compress(Bitmap.CompressFormat.JPEG, 88, cikis);
            cikis.close();
            gecici.delete();
            return ad;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- Kamera ----------
    private int ekranDerece() {
        int r = getWindowManager().getDefaultDisplay().getRotation();
        return r == Surface.ROTATION_90 ? 90 : (r == Surface.ROTATION_180 ? 180 : (r == Surface.ROTATION_270 ? 270 : 0));
    }

    private void kameraKapat() {
        if (kamera != null) {
            try { kamera.stopPreview(); } catch (Exception e) { }
            try { kamera.release(); } catch (Exception e) { }
            kamera = null;
        }
    }

    private void kameraGoster(double x, double y, double w, double h) {
        if (kameraView != null) return;
        kameraView = new TextureView(this);
        int rw = root.getWidth();
        int rh = root.getHeight();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) (w * rw), (int) (h * rh));
        lp.leftMargin = (int) (x * rw);
        lp.topMargin = (int) (y * rh);
        root.addView(kameraView, lp);
        kameraView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w2, int h2) { kameraKur(st, w2, h2); }
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w2, int h2) { }
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { kameraKapat(); return true; }
            public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
    }

    private void kameraKur(SurfaceTexture st, int vw, int vh) {
        if (kameraView == null || st == null || vw == 0 || vh == 0) return;
        try {
            kameraKapat();
            int hedef = kameraOn ? Camera.CameraInfo.CAMERA_FACING_FRONT : Camera.CameraInfo.CAMERA_FACING_BACK;
            kameraKimlik = 0;
            Camera.CameraInfo bilgi = new Camera.CameraInfo();
            int n = Camera.getNumberOfCameras();
            for (int i = 0; i < n; i++) {
                Camera.getCameraInfo(i, bilgi);
                if (bilgi.facing == hedef) { kameraKimlik = i; break; }
            }
            kamera = Camera.open(kameraKimlik);
            Camera.getCameraInfo(kameraKimlik, bilgi);
            int derece = ekranDerece();
            int sonuc;
            if (bilgi.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                sonuc = (bilgi.orientation + derece) % 360;
                sonuc = (360 - sonuc) % 360;
            } else {
                sonuc = (bilgi.orientation - derece + 360) % 360;
            }
            kamera.setDisplayOrientation(sonuc);

            Camera.Parameters p = kamera.getParameters();
            boolean dik = derece % 180 == 0;
            double oran = (double) Math.max(vw, vh) / Math.min(vw, vh);
            Camera.Size en = null;
            double fark = 1e9;
            for (Camera.Size s : p.getSupportedPreviewSizes()) {
                if (s.width > 1920) continue;
                double f = Math.abs((double) s.width / s.height - oran) + Math.abs(s.width - 1280) / 100000.0;
                if (f < fark) { fark = f; en = s; }
            }
            if (en != null) p.setPreviewSize(en.width, en.height);
            if (en != null) {
                Camera.Size foto = null;
                double ff = 1e9;
                for (Camera.Size s : p.getSupportedPictureSizes()) {
                    if (s.width > 2600) continue;
                    double f = Math.abs((double) s.width / s.height - (double) en.width / en.height) + Math.abs(s.width - 2000) / 100000.0;
                    if (f < ff) { ff = f; foto = s; }
                }
                if (foto != null) p.setPictureSize(foto.width, foto.height);
            }
            List<String> odak = p.getSupportedFocusModes();
            if (odak != null && odak.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            }
            kamera.setParameters(p);
            kamera.setPreviewTexture(st);
            kamera.startPreview();

            // Görüntüyü bozmadan kutuyu dolduracak şekilde ortadan kırp
            if (en != null) {
                double pw = en.width;
                double ph = en.height;
                if (dik) { double t = pw; pw = ph; ph = t; }
                double olcek = Math.max(vw / pw, vh / ph);
                Matrix m = new Matrix();
                m.setScale((float) (pw * olcek / vw), (float) (ph * olcek / vh), vw / 2f, vh / 2f);
                kameraView.setTransform(m);
            }
        } catch (Exception e) {
            kameraKapat();
        }
    }

    private int fotoDonme() {
        Camera.CameraInfo bilgi = new Camera.CameraInfo();
        Camera.getCameraInfo(kameraKimlik, bilgi);
        int cihaz = (360 - ekranDerece()) % 360;
        if (bilgi.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) return (bilgi.orientation - cihaz + 360) % 360;
        return (bilgi.orientation + cihaz) % 360;
    }

    @Override
    public void onRequestPermissionsResult(int istek, String[] izinler, int[] sonuclar) {
        super.onRequestPermissionsResult(istek, izinler, sonuclar);
        if (istek == IZIN_KAMERA && kameraBekliyor) {
            kameraBekliyor = false;
            if (sonuclar.length > 0 && sonuclar[0] == PackageManager.PERMISSION_GRANTED) {
                kameraGoster(kx, ky, kw, kh);
            } else {
                Toast.makeText(this, "Kamera izni verilmedi", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ---------- Galeri sonucu ----------
    @Override
    protected void onActivityResult(int istek, int sonuc, Intent veri) {
        super.onActivityResult(istek, sonuc, veri);
        if (istek == ISTEK_GALERI && sonuc == RESULT_OK && veri != null && veri.getData() != null) {
            final Uri uri = veri.getData();
            new Thread(new Runnable() {
                public void run() {
                    String ad = null;
                    try {
                        InputStream giris = getContentResolver().openInputStream(uri);
                        ByteArrayOutputStream tampon = new ByteArrayOutputStream();
                        byte[] t = new byte[8192];
                        int n;
                        while ((n = giris.read(t)) > 0) tampon.write(t, 0, n);
                        giris.close();
                        ad = fotoKaydet(tampon.toByteArray(), "galeri" + System.currentTimeMillis());
                    } catch (Exception e) { }
                    final String sonucAd = ad;
                    runOnUiThread(new Runnable() {
                        public void run() {
                            jsCagir("window.ugcoGaleri && window.ugcoGaleri(" + (sonucAd == null ? "''" : "'" + sonucAd + "'") + ")");
                        }
                    });
                }
            }).start();
        }
    }

    @Override
    protected void onPause() {
        sensorKapat();
        kameraKapat();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sensorIstek) sensorAc();
        if (kameraView != null && kameraView.isAvailable()) {
            kameraKur(kameraView.getSurfaceTexture(), kameraView.getWidth(), kameraView.getHeight());
        }
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

        @JavascriptInterface
        public void kapat() {
            runOnUiThread(new Runnable() { public void run() { finish(); } });
        }

        @JavascriptInterface
        public void linkAc(final String url) {
            runOnUiThread(new Runnable() { public void run() {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { }
            } });
        }

        @JavascriptInterface
        public void paylas(final String metin) {
            runOnUiThread(new Runnable() { public void run() {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, metin);
                startActivity(Intent.createChooser(i, "Paylaş"));
            } });
        }

        @JavascriptInterface
        public void panoya(final String metin) {
            runOnUiThread(new Runnable() { public void run() {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("ugco", metin));
            } });
        }

        @JavascriptInterface
        public void ekranAcik(final boolean acik) {
            runOnUiThread(new Runnable() { public void run() { web.setKeepScreenOn(acik); } });
        }

        @JavascriptInterface
        public void kameraEkle(final String id, final boolean on, final double x, final double y, final double w, final double h) {
            runOnUiThread(new Runnable() {
                public void run() {
                    if (kameraView != null) return;
                    kameraOn = on;
                    if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        kx = x; ky = y; kw = w; kh = h;
                        kameraBekliyor = true;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, IZIN_KAMERA);
                        return;
                    }
                    kameraGoster(x, y, w, h);
                }
            });
        }

        @JavascriptInterface
        public void kameraGor(final boolean goster) {
            runOnUiThread(new Runnable() {
                public void run() {
                    if (kameraView != null) kameraView.setVisibility(goster ? View.VISIBLE : View.GONE);
                }
            });
        }

        @JavascriptInterface
        public void fotoCek() {
            runOnUiThread(new Runnable() {
                public void run() {
                    if (kamera == null) {
                        Toast.makeText(MainActivity.this, "Kamera açık değil", Toast.LENGTH_SHORT).show();
                        jsCagir("window.ugcoFoto && window.ugcoFoto('')");
                        return;
                    }
                    try {
                        Camera.Parameters p = kamera.getParameters();
                        p.setRotation(fotoDonme());
                        kamera.setParameters(p);
                        kamera.takePicture(null, null, new Camera.PictureCallback() {
                            public void onPictureTaken(final byte[] veri, Camera c) {
                                try { c.startPreview(); } catch (Exception e) { }
                                new Thread(new Runnable() {
                                    public void run() {
                                        final String ad = fotoKaydet(veri, "foto" + System.currentTimeMillis());
                                        runOnUiThread(new Runnable() {
                                            public void run() {
                                                jsCagir("window.ugcoFoto && window.ugcoFoto(" + (ad == null ? "''" : "'" + ad + "'") + ")");
                                            }
                                        });
                                    }
                                }).start();
                            }
                        });
                    } catch (Exception e) {
                        jsCagir("window.ugcoFoto && window.ugcoFoto('')");
                    }
                }
            });
        }

        @JavascriptInterface
        public void galeriAc() {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                        i.setType("image/*");
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        startActivityForResult(Intent.createChooser(i, "Resim seç"), ISTEK_GALERI);
                    } catch (Exception e) { }
                }
            });
        }

        // Kayıtlı fotoğrafı sayfada gösterilebilecek şekilde (data adresi) verir
        @JavascriptInterface
        public String resimOku(String ad) {
            try {
                File f = new File(fotoKlasor(), guvenliAd(ad) + ".jpg");
                if (!f.exists()) return "";
                BitmapFactory.Options sinir = new BitmapFactory.Options();
                sinir.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getAbsolutePath(), sinir);
                int ornek = 1;
                while (Math.max(sinir.outWidth, sinir.outHeight) / ornek > 1280) ornek *= 2;
                BitmapFactory.Options sec = new BitmapFactory.Options();
                sec.inSampleSize = ornek;
                Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath(), sec);
                if (bm == null) return "";
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bm.compress(Bitmap.CompressFormat.JPEG, 80, bos);
                return "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public boolean fotoVar(String ad) {
            return new File(fotoKlasor(), guvenliAd(ad) + ".jpg").exists();
        }

        @JavascriptInterface
        public void fotoSil(String ad) {
            new File(fotoKlasor(), guvenliAd(ad) + ".jpg").delete();
        }

        @JavascriptInterface
        public void galeriyeKaydet(final String ad) {
            new Thread(new Runnable() {
                public void run() {
                    boolean ok = false;
                    try {
                        File f = new File(fotoKlasor(), guvenliAd(ad) + ".jpg");
                        if (f.exists()) {
                            if (Build.VERSION.SDK_INT >= 29) {
                                ContentValues v = new ContentValues();
                                v.put(MediaStore.Images.Media.DISPLAY_NAME, "UGCO_" + guvenliAd(ad) + ".jpg");
                                v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                                v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/UGCO");
                                v.put(MediaStore.Images.Media.IS_PENDING, 1);
                                Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                                OutputStream os = getContentResolver().openOutputStream(u);
                                FileInputStream fi = new FileInputStream(f);
                                byte[] t = new byte[8192];
                                int n;
                                while ((n = fi.read(t)) > 0) os.write(t, 0, n);
                                fi.close();
                                os.close();
                                v.clear();
                                v.put(MediaStore.Images.Media.IS_PENDING, 0);
                                getContentResolver().update(u, v, null, null);
                                ok = true;
                            } else {
                                ok = MediaStore.Images.Media.insertImage(getContentResolver(), f.getAbsolutePath(), "UGCO_" + guvenliAd(ad), "") != null;
                            }
                        }
                    } catch (Exception e) { }
                    final boolean sonuc = ok;
                    runOnUiThread(new Runnable() {
                        public void run() {
                            Toast.makeText(MainActivity.this, sonuc ? "Galeriye kaydedildi" : "Galeriye kaydedilemedi", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }).start();
        }

        @JavascriptInterface
        public void sensorBaslat() {
            runOnUiThread(new Runnable() { public void run() {
                if (!sensorIstek) { sensorIstek = true; sensorAc(); }
            } });
        }

        @JavascriptInterface
        public double egim(String eksen) {
            return "x".equals(eksen) ? ax : ("y".equals(eksen) ? ay : az);
        }

        @JavascriptInterface
        public void geriKilit(boolean kilit) {
            geriKilitli = kilit;
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
        if (geriKilitli) {
            web.evaluateJavascript("window.ugcoGeri && window.ugcoGeri()", null);
            return;
        }
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        kameraKapat();
        for (WebView v : webler.values()) v.destroy();
        webler.clear();
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}
package tr.ugco.blokapp;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
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

public class MainActivity extends Activity implements TextToSpeech.OnInitListener, SensorEventListener {

    private FrameLayout root;
    private WebView web;
    private TextToSpeech tts;
    private boolean ttsHazir = false;
    private boolean geriKilitli = false;
    private SensorManager sm;
    private boolean sensorIstek = false;
    private volatile float ax = 0, ay = 0, az = 0;
    private long sonSalla = 0;
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

    private void sensorAc() {
        if (sm == null) sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) return;
        Sensor s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (s != null) sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME);
    }

    private void sensorKapat() {
        if (sm != null) sm.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        ax = e.values[0];
        ay = e.values[1];
        az = e.values[2];
        double g = Math.sqrt(ax * ax + ay * ay + az * az);
        long simdi = System.currentTimeMillis();
        if (g > 16.0 && simdi - sonSalla > 800) {   // sallama eşiği (yaklaşık 1.6 G)
            sonSalla = simdi;
            runOnUiThread(new Runnable() { public void run() {
                web.evaluateJavascript("window.ugcoSalla && window.ugcoSalla()", null);
            } });
        }
    }

    @Override
    public void onAccuracyChanged(Sensor s, int hassasiyet) { }

    @Override
    protected void onPause() {
        sensorKapat();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sensorIstek) sensorAc();
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

        @JavascriptInterface
        public void kapat() {
            runOnUiThread(new Runnable() { public void run() { finish(); } });
        }

        @JavascriptInterface
        public void linkAc(final String url) {
            runOnUiThread(new Runnable() { public void run() {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { }
            } });
        }

        @JavascriptInterface
        public void paylas(final String metin) {
            runOnUiThread(new Runnable() { public void run() {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, metin);
                startActivity(Intent.createChooser(i, "Paylaş"));
            } });
        }

        @JavascriptInterface
        public void panoya(final String metin) {
            runOnUiThread(new Runnable() { public void run() {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("ugco", metin));
            } });
        }

        @JavascriptInterface
        public void ekranAcik(final boolean acik) {
            runOnUiThread(new Runnable() { public void run() { web.setKeepScreenOn(acik); } });
        }

        @JavascriptInterface
        public void sensorBaslat() {
            runOnUiThread(new Runnable() { public void run() {
                if (!sensorIstek) { sensorIstek = true; sensorAc(); }
            } });
        }

        @JavascriptInterface
        public double egim(String eksen) {
            return "x".equals(eksen) ? ax : ("y".equals(eksen) ? ay : az);
        }

        @JavascriptInterface
        public void geriKilit(boolean kilit) {
            geriKilitli = kilit;
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
        if (geriKilitli) {
            web.evaluateJavascript("window.ugcoGeri && window.ugcoGeri()", null);
            return;
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
