package it.distinta.trasferte;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewFeature;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Guscio nativo dell'app: mostra la pagina dentro una WebView, apre fotocamera e galleria
 * per i campi "scegli foto" e salva PDF, Excel e CSV nella cartella Download.
 * Tutti i dati restano sul telefono (localStorage della WebView).
 */
public class MainActivity extends Activity {

    private static final int REQ_FILE = 1001;
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/www/index.html";

    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setTextZoom(100);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            // il tema chiaro/scuro lo gestisce già la pagina
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false);
        }

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (HOST.equals(uri.getHost())) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException ignored) {
                    // nessuna app per aprire il collegamento
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;

                boolean image = false;
                String[] accept = params.getAcceptTypes();
                if (accept != null) {
                    for (String a : accept) {
                        if (a != null && a.startsWith("image")) {
                            image = true;
                        }
                    }
                }
                try {
                    if (image && params.isCaptureEnabled()) {
                        // campo con capture="environment": apre direttamente la fotocamera
                        startActivityForResult(cameraIntent(), REQ_FILE);
                        return true;
                    }
                    Intent content = new Intent(Intent.ACTION_GET_CONTENT);
                    content.addCategory(Intent.CATEGORY_OPENABLE);
                    content.setType(image ? "image/*" : "*/*");
                    if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        content.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    }
                    Intent chooser = Intent.createChooser(content, "Scegli");
                    if (image) {
                        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cameraIntent()});
                    }
                    startActivityForResult(chooser, REQ_FILE);
                } catch (Exception e) {
                    filePathCallback = null;
                    callback.onReceiveValue(null);
                    Toast.makeText(MainActivity.this, "Impossibile aprire il selettore", Toast.LENGTH_LONG).show();
                }
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> result.confirm())
                        .setNegativeButton(android.R.string.cancel, (dialog, which) -> result.cancel())
                        .setOnCancelListener(dialog -> result.cancel())
                        .show();
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.loadUrl(START_URL);
    }

    /** Intent per scattare una foto: il risultato viene scritto in un file temporaneo dell'app. */
    private Intent cameraIntent() throws IOException {
        File dir = new File(getCacheDir(), "camera");
        dir.mkdirs();
        File file = File.createTempFile("scan_", ".jpg", dir);
        cameraUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_FILE || filePathCallback == null) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        Uri[] results = null;
        if (resultCode == RESULT_OK) {
            if (data != null && data.getClipData() != null) {
                ClipData clip = data.getClipData();
                results = new Uri[clip.getItemCount()];
                for (int i = 0; i < results.length; i++) {
                    results[i] = clip.getItemAt(i).getUri();
                }
            } else if (data != null && data.getData() != null) {
                results = new Uri[]{data.getData()};
            } else if (cameraUri != null) {
                results = new Uri[]{cameraUri};
            }
        }
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
        cameraUri = null;
    }

    /** Ponte chiamato dalla pagina per salvare PDF, Excel, CSV e backup. */
    private class Bridge {
        @JavascriptInterface
        public String saveFile(String name, final String mime, String base64) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                final Uri uri = store(name, mime, bytes);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        openSaved(uri, mime);
                    }
                });
                return "ok";
            } catch (Exception e) {
                return "err:" + e.getMessage();
            }
        }
    }

    private Uri store(String name, String mime, byte[] bytes) throws IOException {
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("impossibile creare il file in Download");
            }
            OutputStream out = getContentResolver().openOutputStream(uri);
            if (out == null) {
                throw new IOException("impossibile scrivere il file");
            }
            try {
                out.write(bytes);
            } finally {
                out.close();
            }
            return uri;
        }
        File dir = new File(getCacheDir(), "shared");
        dir.mkdirs();
        File file = new File(dir, safe);
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
        return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
    }

    private void openSaved(Uri uri, String mime) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                Toast.makeText(this, "File salvato nella cartella Download", Toast.LENGTH_LONG).show();
                Intent view = new Intent(Intent.ACTION_VIEW);
                view.setDataAndType(uri, mime);
                view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(view);
            } else {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType(mime);
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(send, "Salva o condividi"));
            }
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Nessuna app per aprire il file: lo trovi in Download", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        // prima lascia gestire il tasto indietro alla pagina (chiude i fogli, torna all'elenco)
        web.evaluateJavascript(
                "(function(){try{return !!(window.__androidBack && window.__androidBack());}catch(e){return false;}})()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        if (!"true".equals(value)) {
                            finish();
                        }
                    }
                });
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
