package com.proviservicios.registro;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.ServiceWorkerController;
import android.webkit.ServiceWorkerWebSettings;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String APP_URL = "https://provi.gobiernodigital.site/";
    private static final String PREFS_NAME = "proviservicios_web_cache";
    private static final String PREF_LAST_HTML = "last_html";
    private static final String PREF_LAST_URL = "last_url";
    private static final int PERMISSION_REQUEST = 10;
    private static final int FILE_CHOOSER_REQUEST = 20;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraPhotoUri;
    private SharedPreferences webCachePrefs;
    private boolean loadingOfflineSnapshot = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webCachePrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        requestAppPermissions();
        configureWebView();
        startMonitoringService();
        hideSystemBars();
        if (savedInstanceState == null) {
            loadInitialPage();
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startMonitoringService();
        if (webView != null && !isOnline() && isAndroidErrorPage(webView.getUrl())) {
            loadOfflineSnapshot();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) webView.saveState(outState);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        setContentView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setGeolocationEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setCacheMode(isOnline() ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_CACHE_ELSE_NETWORK);
        settings.setUserAgentString(settings.getUserAgentString() + " ProviserviciosApp/1.0");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }
        configureServiceWorker();

        CookieManager.getInstance().setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) {
                    return false;
                }
                openExternal(uri);
                return true;
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri uri = Uri.parse(url);
                if ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) {
                    return false;
                }
                openExternal(uri);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (loadingOfflineSnapshot || url == null || !url.startsWith(APP_URL) || isAndroidErrorPage(url)) {
                    return;
                }
                saveCurrentPageSnapshot(url);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && request != null && request.isForMainFrame()) {
                    loadOfflineSnapshot();
                }
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                loadOfflineSnapshot();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                requestAppPermissions();
                callback.invoke(origin, true, false);
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    requestAppPermissions();
                    request.grant(request.getResources());
                });
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    if (params.isCaptureEnabled()) {
                        startActivityForResult(buildCameraIntent(), FILE_CHOOSER_REQUEST);
                    } else {
                        startActivityForResult(buildFileChooserIntent(params), FILE_CHOOSER_REQUEST);
                    }
                } catch (ActivityNotFoundException e) {
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this, "No se encontro camara o selector de archivos.", Toast.LENGTH_LONG).show();
                    return false;
                }
                return true;
            }
        });
    }

    private void configureServiceWorker() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return;
        }
        ServiceWorkerWebSettings serviceWorkerSettings = ServiceWorkerController.getInstance().getServiceWorkerWebSettings();
        serviceWorkerSettings.setAllowContentAccess(true);
        serviceWorkerSettings.setAllowFileAccess(true);
        serviceWorkerSettings.setBlockNetworkLoads(false);
        serviceWorkerSettings.setCacheMode(isOnline() ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_CACHE_ELSE_NETWORK);
    }

    private void loadInitialPage() {
        if (!isOnline() && loadOfflineSnapshot()) {
            return;
        }
        loadingOfflineSnapshot = false;
        webView.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
        webView.loadUrl(APP_URL);
    }

    private boolean loadOfflineSnapshot() {
        if (webView == null) {
            return false;
        }
        String html = webCachePrefs.getString(PREF_LAST_HTML, "");
        String lastUrl = webCachePrefs.getString(PREF_LAST_URL, APP_URL);
        if (html == null || html.trim().isEmpty()) {
            loadingOfflineSnapshot = false;
            webView.loadDataWithBaseURL(
                    APP_URL,
                    buildNoCacheOfflineHtml(),
                    "text/html",
                    "UTF-8",
                    APP_URL
            );
            return false;
        }
        loadingOfflineSnapshot = true;
        webView.getSettings().setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        webView.loadDataWithBaseURL(
                APP_URL,
                html,
                "text/html",
                "UTF-8",
                lastUrl == null || lastUrl.isEmpty() ? APP_URL : lastUrl
        );
        Toast.makeText(this, "Modo offline activo. Los datos se guardaran en el telefono.", Toast.LENGTH_LONG).show();
        return true;
    }

    private void saveCurrentPageSnapshot(String url) {
        if (webView == null) {
            return;
        }
        webView.evaluateJavascript(
                "(function(){try{return JSON.stringify({html:'<!doctype html>\\n'+document.documentElement.outerHTML,url:location.href});}catch(e){return '';}})();",
                value -> {
                    try {
                        if (value == null || value.equals("null") || value.length() < 20) {
                            return;
                        }
                        String jsonText = new JSONObject("{\"value\":" + value + "}").optString("value", "");
                        if (jsonText.isEmpty()) {
                            return;
                        }
                        JSONObject payload = new JSONObject(jsonText);
                        String html = payload.optString("html", "");
                        String pageUrl = payload.optString("url", url);
                        if (html.contains("Sistema de informacion") && !html.contains("serverOfflineUser")) {
                            return;
                        }
                        if (html.length() < 2000) {
                            return;
                        }
                        webCachePrefs.edit()
                                .putString(PREF_LAST_HTML, html)
                                .putString(PREF_LAST_URL, pageUrl)
                                .apply();
                    } catch (Exception ignored) {
                        // Offline snapshot is only a fallback. Normal WebView cache still remains available.
                    }
                }
        );
    }

    private boolean isAndroidErrorPage(String url) {
        return url == null || url.startsWith("chrome-error://") || url.startsWith("about:");
    }

    @SuppressWarnings("deprecation")
    private boolean isOnline() {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (manager == null) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = manager.getActiveNetwork();
            if (network == null) {
                return false;
            }
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            return capabilities != null
                    && (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        }
        NetworkInfo info = manager.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    private String buildNoCacheOfflineHtml() {
        return "<!doctype html><html lang=\"es\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Proviservicios sin conexion</title>"
                + "<style>body{margin:0;font-family:Arial,sans-serif;background:#eef9fb;color:#0b2442;display:grid;min-height:100vh;place-items:center;padding:24px}"
                + ".card{max-width:460px;background:#fff;border:1px solid #cce7ef;border-radius:18px;padding:24px;box-shadow:0 20px 45px rgba(12,55,85,.12)}"
                + "img{max-width:240px;width:70%;display:block;margin:0 auto 18px}h1{font-size:24px;margin:0 0 12px}p{line-height:1.45;color:#46627c}"
                + "</style></head><body><div class=\"card\"><img src=\"logo-proviservicios.png\" alt=\"Proviservicios\"><h1>Modo offline pendiente</h1>"
                + "<p>Este telefono todavia no tiene una copia local del sistema. Abra la aplicacion una vez con internet e inicie sesion; despues podra volver a entrar sin conexion.</p>"
                + "</div></body></html>";
    }

    private Intent buildCameraIntent() {
        Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        cameraPhotoUri = createCameraPhotoUri();
        if (cameraPhotoUri == null) {
            throw new ActivityNotFoundException("No se pudo preparar la fotografia.");
        }
        cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri);
        cameraIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (cameraIntent.resolveActivity(getPackageManager()) == null) {
            throw new ActivityNotFoundException("No hay camara disponible.");
        }
        return cameraIntent;
    }

    private Intent buildFileChooserIntent(WebChromeClient.FileChooserParams params) {
        Intent galleryIntent;
        try {
            galleryIntent = params.createIntent();
        } catch (Exception e) {
            galleryIntent = new Intent(Intent.ACTION_GET_CONTENT);
            galleryIntent.addCategory(Intent.CATEGORY_OPENABLE);
            galleryIntent.setType("image/*");
        }

        Intent cameraIntent = null;
        try {
            cameraIntent = buildCameraIntent();
        } catch (ActivityNotFoundException e) {
            cameraIntent = null;
        }

        Intent chooser = Intent.createChooser(galleryIntent, "Tomar o seleccionar foto");
        if (cameraIntent != null) {
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cameraIntent});
        }
        return chooser;
    }

    private Uri createCameraPhotoUri() {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "PROVI_" + stamp + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Proviservicios");
        }
        return getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
    }

    private void requestAppPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        String[] permissions = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS}
                : new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};
        requestPermissions(permissions, PERMISSION_REQUEST);
    }

    private void startMonitoringService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Intent intent = new Intent(this, MonitoringService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST) startMonitoringService();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || filePathCallback == null) return;

        Uri[] result = null;
        if (resultCode == RESULT_OK) {
            result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            if ((result == null || result.length == 0) && cameraPhotoUri != null) {
                result = new Uri[]{cameraPhotoUri};
            }
        }
        filePathCallback.onReceiveValue(result);
        filePathCallback = null;
        cameraPhotoUri = null;
        hideSystemBars();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ignored) {
            Toast.makeText(this, "No se pudo abrir el enlace.", Toast.LENGTH_SHORT).show();
        }
    }

    private void hideSystemBars() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            }
        } catch (RuntimeException ignored) {
            // Some Android builds expose the insets controller late; legacy immersive flags below still work.
        }
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
            getWindow().setStatusBarColor(Color.TRANSPARENT);
        }
    }
}
