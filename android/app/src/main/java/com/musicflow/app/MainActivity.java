package com.musicflow.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {

    private WebView webView;
    private static final int FILE_CHOOSER_REQ = 1001;
    private static final int PERMISSION_REQ = 2001;
    private static final int INSTALL_REQ = 3001;

    /** 运行时读取 APK 版本号（不硬编码，避免每次发版忘记同步） */
    private int getCurrentVersionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception e) { return 0; }
    }

    private String getCurrentVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) { return "0.0.0"; }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(0xFFF5F5F7);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        getWindow().setNavigationBarColor(0xFFF5F5F7);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        webView = new WebView(this);
        webView.setBackgroundColor(0xFFFFFFFF);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(false);

        webView.addJavascriptInterface(new MusicBridge(), "NativeBridge");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // 兼容旧方式注入版本号，新 JS 优先用 NativeBridge
                view.evaluateJavascript(
                    "window.__APP_VERSION__='" + getCurrentVersionName() + "';" +
                    "window.__APP_VERSION_CODE__=" + getCurrentVersionCode() + ";", null);
            }
        });

        requestPermissions();
        webView.loadUrl("file:///android_asset/public/index.html");
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{
                android.Manifest.permission.READ_MEDIA_AUDIO,
                android.Manifest.permission.POST_NOTIFICATIONS
            }, PERMISSION_REQ);
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, PERMISSION_REQ);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ && grantResults.length > 0) {
            boolean allGranted = true;
            for (int r : grantResults) {
                if (r != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (!allGranted) {
                callJs("if(window.onNativeFilesResult)onNativeFilesResult('{\"files\":[]}')");
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQ && resultCode == RESULT_OK && data != null) {
            handleFileResult(data);
        }
    }

    private void handleFileResult(Intent data) {
        new Thread(() -> {
            try {
                JSONArray filesArray = new JSONArray();
                if (data.getClipData() != null) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                        Uri uri = data.getClipData().getItemAt(i).getUri();
                        JSONObject fileObj = buildFileObject(uri);
                        if (fileObj != null) filesArray.put(fileObj);
                    }
                } else if (data.getData() != null) {
                    Uri uri = data.getData();
                    JSONObject fileObj = buildFileObject(uri);
                    if (fileObj != null) filesArray.put(fileObj);
                }
                JSONObject result = new JSONObject();
                result.put("files", filesArray);
                final String json = result.toString();
                runOnUiThread(() -> {
                    webView.evaluateJavascript("if(window.onNativeFilesResult)onNativeFilesResult(" +
                        jsString(json) + ")", null);
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private JSONObject buildFileObject(Uri uri) {
        try {
            JSONObject obj = new JSONObject();
            String name = "未知歌曲";
            String artist = "未知艺术家";
            long duration = 0;

            ContentResolver cr = getContentResolver();
            Cursor cursor = null;
            try {
                cursor = cr.query(uri, new String[]{
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.DURATION
                }, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int titleIdx = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE);
                    int artistIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST);
                    int durIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION);
                    if (titleIdx >= 0) name = cursor.getString(titleIdx);
                    if (artistIdx >= 0) artist = cursor.getString(artistIdx);
                    if (durIdx >= 0) duration = cursor.getLong(durIdx);
                }
            } finally {
                if (cursor != null) cursor.close();
            }

            if (name == null || "null".equals(name)) {
                String lastSegment = uri.getLastPathSegment();
                if (lastSegment != null) {
                    name = lastSegment.replaceFirst("\\.[^.]+$", "");
                }
            }
            if (artist == null || "<unknown>".equals(artist) || "null".equals(artist)) {
                artist = "未知艺术家";
            }

            obj.put("name", name);
            obj.put("title", name);
            obj.put("artist", artist);
            obj.put("url", uri.toString());
            obj.put("duration", duration / 1000.0);
            return obj;
        } catch (Exception e) {
            return null;
        }
    }

    private File getUpdateDir() {
        File dir = new File(getExternalFilesDir(null), "updates");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private String getLatestApkPath() {
        File dir = getUpdateDir();
        File[] files = dir.listFiles();
        if (files != null && files.length > 0) {
            File latest = null;
            for (File f : files) {
                if (f.getName().endsWith(".apk") && (latest == null || f.lastModified() > latest.lastModified())) {
                    latest = f;
                }
            }
            return latest != null ? latest.getAbsolutePath() : null;
        }
        return null;
    }

    private void callJs(String js) {
        runOnUiThread(() -> {
            try {
                webView.evaluateJavascript(js, null);
            } catch (Exception e) {}
        });
    }

    /** 将字符串安全转义为 JavaScript 字符串字面量（处理所有特殊字符） */
    private String jsString(String s) {
        return org.json.JSONObject.quote(s != null ? s : "");
    }

    private void installApk(String path) {
        File apkFile = new File(path);
        runOnUiThread(() -> {
            try {
                if (!apkFile.exists()) {
                    callJs("if(window.onUpdateDownloadError)onUpdateDownloadError(" + jsString("APK 文件不存在") + ")");
                    return;
                }

                Uri apkUri;
                if (Build.VERSION.SDK_INT >= 24) {
                    // Android 7+: 通过 FileProvider 获取 content:// URI
                    apkUri = GenericFileProvider.getUriForFile(this,
                        "com.musicflow.app.fileprovider", apkFile);
                } else {
                    apkUri = Uri.fromFile(apkFile);
                }

                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (Build.VERSION.SDK_INT >= 24) {
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
                startActivity(intent);

            } catch (Exception e) {
                // FileProvider 失败 → 复制到 Downloads 目录提示手动安装
                try {
                    File downloadDir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                    if (!downloadDir.exists()) downloadDir.mkdirs();
                    File publicApk = new File(downloadDir, "MusicFlow_update.apk");
                    copyFile(apkFile, publicApk);
                    callJs("if(window.onUpdateDownloadError)onUpdateDownloadError(" +
                        jsString("请到 Download 目录手动点击 MusicFlow_update.apk 安装") + ")");
                } catch (Exception e2) {
                    callJs("if(window.onUpdateDownloadError)onUpdateDownloadError(" +
                        jsString("安装失败: " + (e.getMessage() != null ? e.getMessage() : "")) + ")");
                }
            }
        });
    }

    private void copyFile(File src, File dst) throws Exception {
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[4096];
        int len;
        while ((len = in.read(buf)) != -1) {
            out.write(buf, 0, len);
        }
        in.close();
        out.close();
    }

    /** 格式化文件大小为可读字符串 */
    private String formatFileSize(long bytes) {
        if (bytes <= 0) return "未知大小";
        if (bytes < 1024 * 1024) return String.format("%.0f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    class MusicBridge {

        @JavascriptInterface
        public void pickAudioFiles() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("audio/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                MainActivity.this.startActivityForResult(
                    Intent.createChooser(intent, "选择音乐文件"), FILE_CHOOSER_REQ);
            });
        }

        @JavascriptInterface
        public void scanLocalMusic() {
            new Thread(() -> {
                try {
                    JSONArray songsArray = new JSONArray();
                    ContentResolver cr = getContentResolver();
                    Uri collection;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
                    } else {
                        collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                    }

                    String[] projection = {
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.ARTIST,
                        MediaStore.Audio.Media.DURATION
                    };
                    String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0 AND " +
                        MediaStore.Audio.Media.DURATION + " > 10000";
                    String sortOrder = MediaStore.Audio.Media.TITLE + " ASC";

                    Cursor cursor = cr.query(collection, projection, selection, null, sortOrder);
                    if (cursor != null) {
                        int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                        int titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                        int artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                        int durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);

                        while (cursor.moveToNext()) {
                            long id = cursor.getLong(idCol);
                            String title = cursor.getString(titleCol);
                            String artist = cursor.getString(artistCol);
                            long duration = cursor.getLong(durCol);

                            if (title == null || title.trim().isEmpty()) continue;
                            if ("<unknown>".equals(artist) || artist == null) artist = "未知艺术家";

                            Uri contentUri = ContentUris.withAppendedId(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);

                            JSONObject songObj = new JSONObject();
                            songObj.put("title", title);
                            songObj.put("artist", artist);
                            songObj.put("url", contentUri.toString());
                            songObj.put("duration", duration / 1000.0);
                            songsArray.put(songObj);
                        }
                        cursor.close();
                    }

                    JSONObject result = new JSONObject();
                    result.put("songs", songsArray);
                    final String json = result.toString();

                    runOnUiThread(() -> {
                        try {
                            webView.evaluateJavascript(
                                "if(window.onNativeScanResult)onNativeScanResult(" + jsString(json) + ")",
                                null);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                    runOnUiThread(() -> {
                        webView.evaluateJavascript(
                            "if(window.onNativeScanResult)onNativeScanResult('{\"songs\":[]}');",
                            null);
                    });
                }
            }).start();
        }

        @JavascriptInterface
        public void checkLocalUpdate() {
            new Thread(() -> {
                try {
                    String apkPath = getLatestApkPath();
                    if (apkPath != null) {
                        File apkFile = new File(apkPath);
                        long sizeMB = apkFile.length() / (1024 * 1024);
                        String sizeStr = (sizeMB > 0 ? sizeMB : 1) + " MB";

                        JSONObject result = new JSONObject();
                        result.put("hasUpdate", true);
                        result.put("newVersion", "检测到新版本");
                        result.put("fileSize", sizeStr);
                        result.put("apkPath", apkPath);
                        result.put("changelog", "将安装更新版本的 MusicFlow。\n\n文件: " + apkFile.getName() + "\n大小: " + sizeStr);

                        final String json = result.toString();
                        callJs("if(window.onLocalUpdateCheckResult)onLocalUpdateCheckResult(" + jsString(json) + ")");
                    } else {
                        callJs("if(window.onLocalUpdateCheckResult)onLocalUpdateCheckResult('{\"hasUpdate\":false}')");
                    }
                } catch (Exception e) {
                    callJs("if(window.onLocalUpdateCheckResult)onLocalUpdateCheckResult('{\"hasUpdate\":false}')");
                }
            }).start();
        }

        @JavascriptInterface
        public void startLocalUpdate() {
            new Thread(() -> {
                try {
                    callJs("if(window.onUpdateDownloadProgress)onUpdateDownloadProgress('50')");
                    Thread.sleep(500);
                    callJs("if(window.onUpdateDownloadProgress)onUpdateDownloadProgress('100')");
                    Thread.sleep(300);

                    String apkPath = getLatestApkPath();
                    if (apkPath != null) {
                        callJs("if(window.onUpdateDownloadComplete)onUpdateDownloadComplete(" + jsString(apkPath) + ")");
                        Thread.sleep(500);
                        installApk(apkPath);
                    } else {
                        callJs("if(window.onUpdateDownloadError)onUpdateDownloadError(" + jsString("未找到 APK 文件。请先将新 APK 放到 Download/MusicFlow/updates/ 目录") + ")");
                    }
                } catch (Exception e) {
                    callJs("if(window.onUpdateDownloadError)onUpdateDownloadError(" + jsString(e.getMessage() != null ? e.getMessage() : "未知错误") + ")");
                }
            }).start();
        }

        @JavascriptInterface
        public void installUpdate(String path) {
            runOnUiThread(() -> installApk(path));
        }

        @JavascriptInterface
        public void startRemoteDownload(String downloadUrl) {
            new Thread(() -> downloadApkFromUrl(downloadUrl)).start();
        }

        @JavascriptInterface
        public String getAppVersion() {
            return getCurrentVersionName();
        }

        @JavascriptInterface
        public int getAppVersionCode() {
            return getCurrentVersionCode();
        }

        @JavascriptInterface
        public void setDynamicIcon(boolean dynamic) {
            runOnUiThread(() -> {
                int animState = dynamic ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
                int staticState = dynamic ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
                getPackageManager().setComponentEnabledSetting(
                    new ComponentName(MainActivity.this, "com.musicflow.app.MainActivityAnim"),
                    animState, PackageManager.DONT_KILL_APP);
                getPackageManager().setComponentEnabledSetting(
                    new ComponentName(MainActivity.this, "com.musicflow.app.MainActivityStatic"),
                    staticState, PackageManager.DONT_KILL_APP);
            });
        }

        @JavascriptInterface
        public void openExternalBrowser(String url) {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception e) {
                    callJs("if(window.onUpdateDownloadError)onUpdateDownloadError('无法打开浏览器')");
                }
            });
        }

        @JavascriptInterface
        public void checkRemoteUpdate(String serverUrl) {
            new Thread(() -> {
                HttpURLConnection conn = null;
                try {
                    if (serverUrl == null || serverUrl.isEmpty()) {
                        callJs("if(window.onLocalUpdateCheckResult)onLocalUpdateCheckResult('{\"hasUpdate\":false}')");
                        return;
                    }

                    URL url = new URL(serverUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    conn.setRequestProperty("Accept", "application/json");
                    conn.setInstanceFollowRedirects(true);

                    if (conn.getResponseCode() == 200) {
                        InputStream is = conn.getInputStream();
                        StringBuilder sb = new StringBuilder();
                        byte[] buffer = new byte[1024];
                        int len;
                        while ((len = is.read(buffer)) != -1) {
                            sb.append(new String(buffer, 0, len, "UTF-8"));
                        }
                        is.close();

                        JSONObject release = new JSONObject(sb.toString());
                        String tagName = release.optString("tag_name", "");
                        String body = release.optString("body", "");

                        String remoteVersion = tagName.replace("v", "").trim();
                        String[] parts = remoteVersion.split("\\.");
                        int remoteCode = 0;
                        if (parts.length >= 3) {
                            remoteCode = Integer.parseInt(parts[0]) * 10000 +
                                Integer.parseInt(parts[1]) * 100 +
                                Integer.parseInt(parts[2]);
                        }

                        boolean hasUpdate = remoteCode > 0 && remoteCode > getCurrentVersionCode();

                        String downloadUrl = "";
                        long fileSize = 0;
                        JSONArray assets = release.optJSONArray("assets");
                        if (assets != null) {
                            for (int i = 0; i < assets.length(); i++) {
                                JSONObject asset = assets.getJSONObject(i);
                                if (asset.getString("name").endsWith(".apk")) {
                                    downloadUrl = asset.getString("browser_download_url");
                                    fileSize = asset.optLong("size", 0);
                                    break;
                                }
                            }
                        }

                        JSONObject result = new JSONObject();
                        result.put("hasUpdate", hasUpdate);
                        result.put("newVersion", remoteVersion);
                        result.put("changelog", body);
                        result.put("downloadUrl", downloadUrl);
                        result.put("fileSize", formatFileSize(fileSize));
                        result.put("currentVersion", getCurrentVersionName());
                        result.put("currentVersionCode", getCurrentVersionCode());

                        final String json = result.toString();
                        callJs("if(window.onLocalUpdateCheckResult)onLocalUpdateCheckResult(" + jsString(json) + ")");
                    } else {
                        callJs("if(window.onUpdateDownloadError)onUpdateDownloadError(" + jsString("服务器返回: " + conn.getResponseCode()) + ")");
                    }
                } catch (Exception e) {
                    callJs("if(window.onUpdateDownloadError)onUpdateDownloadError(" + jsString("网络错误: " + (e.getMessage() != null ? e.getMessage() : "")) + ")");
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }).start();
        }

        private void downloadApkFromUrl(String downloadUrl) {
            HttpURLConnection conn = null;
            try {
                callJs("if(window.onUpdateDownloadProgress)onUpdateDownloadProgress('0')");

                URL url = new URL(downloadUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(30000);
                conn.setInstanceFollowRedirects(true);

                int totalSize = conn.getContentLength();
                InputStream is = conn.getInputStream();

                File outFile = new File(getUpdateDir(), "MusicFlow_update.apk");
                FileOutputStream fos = new FileOutputStream(outFile);

                byte[] buffer = new byte[8192];
                int total = 0;
                int len;
                long lastProgress = 0;

                while ((len = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, len);
                    total += len;
                    if (totalSize > 0) {
                        int progress = Math.min(99, (int) ((total * 100) / totalSize));
                        long now = System.currentTimeMillis();
                        if (now - lastProgress > 500) {
                            lastProgress = now;
                            final int p = progress;
                            callJs("if(window.onUpdateDownloadProgress)onUpdateDownloadProgress('" + p + "')");
                        }
                    }
                }

                fos.close();
                is.close();

                callJs("if(window.onUpdateDownloadProgress)onUpdateDownloadProgress('100')");

                final String path = outFile.getAbsolutePath();
                callJs("if(window.onUpdateDownloadComplete)onUpdateDownloadComplete(" + jsString(path) + ")");

                Thread.sleep(500);
                installApk(path);

            } catch (Exception e) {
                callJs("if(window.onUpdateDownloadError)onUpdateDownloadError(" + jsString("下载失败: " + (e.getMessage() != null ? e.getMessage() : "")) + ")");
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}