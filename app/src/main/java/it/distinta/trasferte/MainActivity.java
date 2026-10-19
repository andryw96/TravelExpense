package it.distinta.trasferte;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/** Guscio nativo: mostra l'app web (assets/www) in una WebView, apre fotocamera/galleria e salva i file in Download. */
public class MainActivity extends Activity {
    private static final int REQ_CHOOSER = 1;
    private static final String START_URL = "https://appassets.androidplatform.net/assets/www/index.html";

    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    Intent intent;
                    if (params != null && params.isCaptureEnabled()) {
                        File dir = new File(getCacheDir(), "camera");
                        if (!dir.exists()) dir.mkdirs();
                        File f = File.createTempFile("scontrino_", ".jpg", dir);
                        cameraUri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", f);
                        intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                        intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
                        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        intent.setClipData(ClipData.newRawUri("", cameraUri));
                        startActivityForResult(intent, REQ_CHOOSER);
                    } else {
                        cameraUri = null;
                        intent = new Intent(Intent.ACTION_GET_CONTENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("image/*");
                        if (params != null && params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE)
                            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                        startActivityForResult(Intent.createChooser(intent, "Scegli le foto"), REQ_CHOOSER);
                    }
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    callback.onReceiveValue(null);
                    Toast.makeText(MainActivity.this, "Impossibile aprire fotocamera o galleria", Toast.LENGTH_LONG).show();
                    return true;
                }
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState); else web.loadUrl(START_URL);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CHOOSER || filePathCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK) {
            if (cameraUri != null && (data == null || (data.getData() == null && data.getClipData() == null))) {
                result = new Uri[]{cameraUri};
            } else if (data != null) {
                if (data.getClipData() != null) {
                    ClipData c = data.getClipData();
                    result = new Uri[c.getItemCount()];
                    for (int i = 0; i < c.getItemCount(); i++) result[i] = c.getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
        }
        filePathCallback.onReceiveValue(result);
        filePathCallback = null;
        cameraUri = null;
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    /** Tasto indietro: prima lo gestisce l'app (chiude fogli, torna all'elenco), poi si esce. */
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(function(){return !!(window.__androidBack && window.__androidBack());})()", value -> {
            if (!"true".equals(value)) MainActivity.super.onBackPressed();
        });
    }

    @Override
    protected void onDestroy() {
        if (web != null) { web.removeJavascriptInterface("AndroidBridge"); web.destroy(); }
        super.onDestroy();
    }

    /** Ponte JavaScript: salva PDF/Excel/CSV/backup in Download e li apre. */
    private class Bridge {
        @JavascriptInterface
        public String saveFile(String name, String mime, String base64) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
                Uri uri;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) return "err:impossibile creare il file";
                    try (OutputStream o = getContentResolver().openOutputStream(uri)) { o.write(bytes); }
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) dir = getCacheDir();
                    if (!dir.exists()) dir.mkdirs();
                    File f = new File(dir, safe);
                    try (FileOutputStream o = new FileOutputStream(f)) { o.write(bytes); }
                    uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", copyToShared(f, safe));
                }
                final Uri open = uri; final String type = mime;
                runOnUiThread(() -> {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setDataAndType(open, type);
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "File salvato nella cartella Download", Toast.LENGTH_LONG).show();
                    }
                });
                return "ok";
            } catch (Exception e) {
                return "err:" + e.getMessage();
            }
        }

        private File copyToShared(File src, String name) throws Exception {
            File dir = new File(getCacheDir(), "shared");
            if (!dir.exists()) dir.mkdirs();
            File dst = new File(dir, name);
            try (java.io.FileInputStream in = new java.io.FileInputStream(src); FileOutputStream o = new FileOutputStream(dst)) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            }
            return dst;
        }
    }
}
