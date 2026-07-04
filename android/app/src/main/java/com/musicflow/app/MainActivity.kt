package com.musicflow.app

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private var filePickerCallback: String? = null
    private var scanCallback: String? = null

    private val handler = Handler(Looper.getMainLooper())

    // 文件选择器
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        handleFilePickerResult(uris)
    }

    // 通知权限
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startPlaybackService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 状态栏
        window.statusBarColor = 0xFFF5F5F7.toInt()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        window.navigationBarColor = 0xFFF5F5F7.toInt()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 创建 WebView
        webView = WebView(this).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
            }
            addJavascriptInterface(MusicBridge(), "NativeBridge")
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
        }
        setContentView(webView)

        // 连接播放服务
        PlaybackService.instance?.onPlaybackEvent = { event, data ->
            callJs("if(window.onPlaybackEvent)onPlaybackEvent('$event','${data ?: ""}')")
        }

        webView.loadUrl("file:///android_asset/public/index.html")
    }

    // ==================== JS Bridge ====================

    inner class MusicBridge {

        @JavascriptInterface
        fun pickAudioFiles() {
            filePickerCallback = null
            runOnUiThread {
                filePickerLauncher.launch(arrayOf("audio/*"))
            }
        }

        @JavascriptInterface
        fun pickAudioFilesWithCallback(callback: String) {
            filePickerCallback = callback
            runOnUiThread {
                filePickerLauncher.launch(arrayOf("audio/*"))
            }
        }

        @JavascriptInterface
        fun scanLocalMusic() {
            scanCallback = null
            scanLocalMusicInternal(null)
        }

        @JavascriptInterface
        fun scanLocalMusicWithCallback(callback: String) {
            scanCallback = callback
            scanLocalMusicInternal(callback)
        }

        private fun scanLocalMusicInternal(callback: String?) {
            Thread {
                try {
                    val songsArray = JSONArray()
                    val cr = contentResolver
                    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    } else {
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    }

                    val projection = arrayOf(
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.ARTIST,
                        MediaStore.Audio.Media.ALBUM,
                        MediaStore.Audio.Media.DURATION,
                        MediaStore.Audio.Media.DATA,
                        MediaStore.Audio.Media.SIZE,
                        MediaStore.Audio.Media.IS_MUSIC
                    )

                    val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
                    val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

                    cr.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                        val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                        val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                        val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                        val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                        val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)

                        while (cursor.moveToNext()) {
                            // 过滤短音频（铃声、通知音等）
                            val duration = cursor.getLong(durationCol)
                            if (duration < 30000) continue // 小于30秒跳过

                            val song = JSONObject().apply {
                                put("id", cursor.getLong(idCol))
                                put("name", cursor.getString(titleCol))
                                put("artist", cursor.getString(artistCol) ?: "未知艺术家")
                                put("album", cursor.getString(albumCol) ?: "未知专辑")
                                put("duration", duration)
                                put("path", cursor.getString(dataCol))
                                put("size", cursor.getLong(sizeCol))
                            }
                            songsArray.put(song)
                        }
                    }

                    val json = songsArray.toString()
                    val cb = callback ?: scanCallback
                    runOnUiThread {
                        if (cb != null) {
                            callJs("if(window.$cb)$cb($json)")
                        } else {
                            callJs("if(window.onNativeScanResult)onNativeScanResult($json)")
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        callJs("if(window.onNativeScanError)onNativeScanError('${e.message}')")
                    }
                }
            }.start()
        }

        @JavascriptInterface
        fun getAppVersion(): String = getCurrentVersionName()

        @JavascriptInterface
        fun getAppVersionCode(): Int = getCurrentVersionCode()

        @JavascriptInterface
        fun setDynamicIcon(dynamic: Boolean) {
            runOnUiThread {
                val animState = if (dynamic) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                val staticState = if (dynamic) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                packageManager.setComponentEnabledSetting(
                    ComponentName(this@MainActivity, "com.musicflow.app.MainActivityAnim"),
                    animState, PackageManager.DONT_KILL_APP
                )
                packageManager.setComponentEnabledSetting(
                    ComponentName(this@MainActivity, "com.musicflow.app.MainActivityStatic"),
                    staticState, PackageManager.DONT_KILL_APP
                )
            }
        }

        // ==================== 原生播放控制（通过 PlaybackService） ====================

        @JavascriptInterface
        fun play(url: String, title: String, artist: String) {
            startPlaybackService()
            PlaybackService.play(url, title, artist)
        }

        @JavascriptInterface
        fun pause() = PlaybackService.pause()

        @JavascriptInterface
        fun resume() = PlaybackService.resume()

        @JavascriptInterface
        fun seekTo(ms: Long) = PlaybackService.seekTo(ms)

        @JavascriptInterface
        fun getCurrentPosition(): Long = PlaybackService.getCurrentPosition()

        @JavascriptInterface
        fun getDuration(): Long = PlaybackService.getDuration()

        @JavascriptInterface
        fun isPlaying(): Boolean = PlaybackService.isPlaying()

        // ==================== 更新相关 ====================

        @JavascriptInterface
        fun installUpdate(path: String) {
            runOnUiThread { installApk(File(path)) }
        }

        @JavascriptInterface
        fun startRemoteDownload(downloadUrl: String) {
            Thread { downloadApkFromUrl(downloadUrl) }.start()
        }

        @JavascriptInterface
        fun checkUpdateFromGithub() {
            Thread { checkGitHubUpdate() }.start()
        }

        @JavascriptInterface
        fun openExternalBrowser(url: String) {
            runOnUiThread {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    callJs("if(window.onUpdateDownloadError)onUpdateDownloadError('无法打开浏览器')")
                }
            }
        }

        @JavascriptInterface
        fun openAppSettings() {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        }

        @JavascriptInterface
        fun openBatteryOptimization() {
            runOnUiThread {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "请手动关闭电池优化", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun openInstallPermission() {
            runOnUiThread {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "请手动开启安装权限", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ==================== 播放服务 ====================

    private fun startPlaybackService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        val intent = Intent(this, PlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // ==================== 文件选择 ====================

    private fun handleFilePickerResult(uris: List<Uri>) {
        if (uris.isEmpty()) return
        Thread {
            try {
                val arr = JSONArray()
                for (uri in uris) {
                    val song = readAudioMetadata(uri)
                    if (song != null) arr.put(song)
                }
                val json = arr.toString()
                val cb = filePickerCallback
                runOnUiThread {
                    if (cb != null) {
                        callJs("if(window.$cb)$cb($json)")
                    } else {
                        callJs("if(window.onNativeFilesResult)onNativeFilesResult($json)")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    callJs("if(window.onNativeFilesError)onNativeFilesError('${e.message}')")
                }
            }
        }.start()
    }

    private fun readAudioMetadata(uri: Uri): JSONObject? {
        try {
            val cr = contentResolver
            val projection = arrayOf(
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION
            )
            cr.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return JSONObject().apply {
                        put("id", uri.hashCode())
                        put("name", cursor.getString(0) ?: "未知歌曲")
                        put("artist", cursor.getString(1) ?: "未知艺术家")
                        put("album", cursor.getString(2) ?: "未知专辑")
                        put("duration", cursor.getLong(3))
                        put("path", uri.toString())
                        put("size", 0)
                    }
                }
            }
        } catch (e: Exception) {
            // fallback
        }
        return null
    }

    // ==================== 更新下载 ====================

    private fun checkGitHubUpdate() {
        try {
            val url = URL("https://api.github.com/repos/1rc2/musicapp/releases/latest")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")

            val body = conn.inputStream.bufferedReader().readText()
            val release = JSONObject(body)
            val tagName = release.optString("tag_name", "").replace("v", "").trim()
            val changelog = release.optString("body", "")

            val parts = tagName.split(".")
            val remoteCode = if (parts.size >= 3) {
                parts[0].toInt() * 10000 + parts[1].toInt() * 100 + parts[2].toInt()
            } else 0

            val hasUpdate = remoteCode > 0 && remoteCode > getCurrentVersionCode()

            var downloadUrl = ""
            var fileSize = 0L
            val assets = release.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.optString("name", "").endsWith(".apk")) {
                        downloadUrl = asset.optString("browser_download_url", "")
                        fileSize = asset.optLong("size", 0)
                        break
                    }
                }
            }

            val result = JSONObject().apply {
                put("hasUpdate", hasUpdate)
                put("remoteVersion", tagName)
                put("remoteCode", remoteCode)
                put("currentVersionCode", getCurrentVersionCode())
                put("downloadUrl", downloadUrl)
                put("fileSize", fileSize)
                put("changelog", changelog)
                put("currentVersion", getCurrentVersionName())
            }

            runOnUiThread {
                callJs("if(window.onUpdateCheckResult)onUpdateCheckResult($result)")
            }
        } catch (e: Exception) {
            runOnUiThread {
                callJs("if(window.onUpdateCheckError)onUpdateCheckError('${e.message}')")
            }
        }
    }

    private fun downloadApkFromUrl(downloadUrl: String) {
        try {
            // Android 16: 只能下载到私有目录
            val apkDir = File(filesDir, "updates")
            if (!apkDir.exists()) apkDir.mkdirs()
            val apkFile = File(apkDir, "update.apk")

            val url = URL(downloadUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            val totalSize = conn.contentLength

            conn.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        val progress = if (totalSize > 0) (downloaded * 100 / totalSize).toInt() else -1
                        runOnUiThread {
                            callJs("if(window.onUpdateDownloadProgress)onUpdateDownloadProgress($progress,$downloaded,$totalSize)")
                        }
                    }
                }
            }

            // MD5 校验
            val md5 = computeMd5(apkFile)
            runOnUiThread {
                callJs("if(window.onUpdateDownloadComplete)onUpdateDownloadComplete('${apkFile.absolutePath}','$md5')")
            }
        } catch (e: Exception) {
            runOnUiThread {
                callJs("if(window.onUpdateDownloadError)onUpdateDownloadError('${e.message}')")
            }
        }
    }

    private fun installApk(apkFile: File) {
        if (!apkFile.exists()) {
            Toast.makeText(this, "安装包不存在", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "安装失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun computeMd5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ==================== 工具方法 ====================

    private fun getCurrentVersionName(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"
        } catch (e: Exception) {
            "0.0.0"
        }
    }

    private fun getCurrentVersionCode(): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionCode
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun callJs(js: String) {
        webView.post {
            webView.evaluateJavascript(js, null)
        }
    }

    override fun onDestroy() {
        // 不销毁播放服务
        super.onDestroy()
    }
}