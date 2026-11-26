package jp.midori.kiroku;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.net.Uri;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.GeolocationPermissions;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public final class MainActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 1042;
    private static final int REQUEST_LOCATION = 1043;
    private static final String CHANNEL_ID = "watering-reminders";
    private static final String APP_URL = "https://appassets.androidplatform.net/assets/index.html";

    private WebView webView;
    private final ArrayList<String> pendingPermissionCallbacks = new ArrayList<>();
    private final ArrayList<GeolocationPermissions.Callback> pendingLocationCallbacks = new ArrayList<>();
    private final ArrayList<String> pendingLocationOrigins = new ArrayList<>();
    private String notificationShim;
    private boolean documentStartScriptSupported;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createNotificationChannel();

        WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView = new WebView(this);
        webView.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setGeolocationEnabled(true);
        webView.getSettings().setAllowFileAccess(false);
        webView.getSettings().setAllowContentAccess(false);
        webView.addJavascriptInterface(new NotificationBridge(), "AndroidAppBridge");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (!("https://appassets.androidplatform.net").equals(origin)) {
                    callback.invoke(origin, false, false);
                    return;
                }
                if (hasLocationPermission()) {
                    callback.invoke(origin, true, false);
                    return;
                }
                pendingLocationCallbacks.add(callback);
                pendingLocationOrigins.add(origin);
                if (pendingLocationCallbacks.size() == 1) {
                    requestPermissions(new String[]{
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    }, REQUEST_LOCATION);
                }
            }
        });
        try {
            notificationShim = readAsset("notification-bridge.js");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load the Android notification bridge.", exception);
        }
        documentStartScriptSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
        if (documentStartScriptSupported) {
            WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    notificationShim,
                    java.util.Collections.singleton("https://appassets.androidplatform.net"));
        }
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleExternalNavigation(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleExternalNavigation(Uri.parse(url));
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!documentStartScriptSupported && url.startsWith("https://appassets.androidplatform.net/")) {
                    view.evaluateJavascript(notificationShim, null);
                }
            }
        });
        setContentView(webView);
        webView.loadUrl(APP_URL);
    }

    private boolean isAppUrl(Uri uri) {
        return "https".equals(uri.getScheme())
                && "appassets.androidplatform.net".equals(uri.getHost());
    }

    private boolean handleExternalNavigation(Uri uri) {
        if (isAppUrl(uri)) {
            return false;
        }
        if (!"https".equals(uri.getScheme())) {
            return true;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, "リンクを開けるアプリが見つかりません。", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private String readAsset(String name) throws IOException {
        try (InputStream input = getAssets().open(name)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(getString(R.string.notification_channel_description));
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private String notificationPermission() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !manager.areNotificationsEnabled()) {
            return "denied";
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return "granted";
        }
        return getPreferences(MODE_PRIVATE).getBoolean("notification_permission_requested", false)
                ? "denied"
                : "default";
    }

    private void openNotificationSettings() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        } else {
            intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", getPackageName(), null));
        }
        startActivity(intent);
    }

    private void finishPermissionRequest(String callbackId, String permission) {
        if (webView == null) {
            return;
        }
        String script = "window.__nativeNotificationPermissionResult("
                + JSONObject.quote(callbackId) + ","
                + JSONObject.quote(permission) + ")";
        webView.evaluateJavascript(script, null);
    }

    private void finishNotificationRequest(String callbackId, String error) {
        if (webView == null) {
            return;
        }
        String script = "window.__nativeNotificationResult("
                + JSONObject.quote(callbackId) + ","
                + (error == null ? "null" : JSONObject.quote(error)) + ")";
        webView.evaluateJavascript(script, null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS && !pendingPermissionCallbacks.isEmpty()) {
            String permission = notificationPermission();
            for (String callbackId : pendingPermissionCallbacks) {
                finishPermissionRequest(callbackId, permission);
            }
            pendingPermissionCallbacks.clear();
        }
        if (requestCode == REQUEST_LOCATION && !pendingLocationCallbacks.isEmpty()) {
            boolean granted = hasLocationPermission();
            for (int index = 0; index < pendingLocationCallbacks.size(); index++) {
                pendingLocationCallbacks.get(index).invoke(pendingLocationOrigins.get(index), granted, false);
            }
            pendingLocationCallbacks.clear();
            pendingLocationOrigins.clear();
        }
    }

    private boolean hasLocationPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    public final class NotificationBridge {
        @JavascriptInterface
        public String permission() {
            return notificationPermission();
        }

        @JavascriptInterface
        public void requestPermission(String callbackId) {
            runOnUiThread(() -> {
                if (notificationPermission().equals("granted")) {
                    finishPermissionRequest(callbackId, "granted");
                    return;
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    openNotificationSettings();
                    finishPermissionRequest(callbackId, "denied");
                    return;
                }
                if (getPreferences(MODE_PRIVATE).getBoolean("notification_permission_requested", false)
                        && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                    openNotificationSettings();
                    finishPermissionRequest(callbackId, "denied");
                    return;
                }
                getPreferences(MODE_PRIVATE).edit()
                        .putBoolean("notification_permission_requested", true)
                        .apply();
                pendingPermissionCallbacks.add(callbackId);
                if (pendingPermissionCallbacks.size() == 1) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
                }
            });
        }

        @JavascriptInterface
        public void showNotification(String title, String body, String tag, String callbackId) {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    finishNotificationRequest(callbackId, "Notification permission is not granted.");
                    return;
                }
                NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !manager.areNotificationsEnabled()) {
                    finishNotificationRequest(callbackId, "Notifications are disabled for this app.");
                    return;
                }
                Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? new Notification.Builder(MainActivity.this, CHANNEL_ID)
                        : new Notification.Builder(MainActivity.this);
                Intent intent = new Intent(MainActivity.this, MainActivity.class);
                PendingIntent contentIntent = PendingIntent.getActivity(
                        MainActivity.this,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                Notification notification = builder
                        .setSmallIcon(android.R.drawable.ic_popup_reminder)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setStyle(new Notification.BigTextStyle().bigText(body))
                        .setContentIntent(contentIntent)
                        .setAutoCancel(true)
                        .build();
                try {
                    manager.notify(tag == null ? title.hashCode() : tag.hashCode(), notification);
                    if (tag != null && tag.matches("watering-\\d{4}-\\d{2}-\\d{2}")) {
                        getSharedPreferences(WateringPlanReceiver.PREFS_NAME, MODE_PRIVATE).edit()
                                .putString(WateringPlanReceiver.PREF_LAST_NOTIFIED_DATE, tag.substring("watering-".length()))
                                .apply();
                    }
                    finishNotificationRequest(callbackId, null);
                } catch (SecurityException | IllegalArgumentException exception) {
                    finishNotificationRequest(callbackId, exception.getMessage() == null
                            ? exception.getClass().getSimpleName()
                            : exception.getMessage());
                }
            });
        }

        @JavascriptInterface
        public void syncWateringPlan(String planJson) {
            runOnUiThread(() -> WateringPlanReceiver.updatePlan(MainActivity.this, planJson));
        }

        @JavascriptInterface
        public String lastNotifiedDate() {
            return getSharedPreferences(WateringPlanReceiver.PREFS_NAME, MODE_PRIVATE)
                    .getString(WateringPlanReceiver.PREF_LAST_NOTIFIED_DATE, "");
        }
    }
}
