package com.musicflow.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.text.TextUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal FileProvider implementation (no AndroidX dependency).
 * Supports external storage paths defined in res/xml/file_paths.xml.
 */
public class GenericFileProvider extends ContentProvider {

    private static final String[] COLUMNS = {
        OpenableColumns.DISPLAY_NAME,
        OpenableColumns.SIZE
    };

    private static final Map<String, File> sPathMap = new HashMap<>();

    @Override
    public boolean onCreate() {
        // 预初始化路径映射，避免后续无 Context 调用时 NPE
        ensurePathsInitialized(getContext());
        return true;
    }

    /**
     * Parse paths from file_paths.xml and cache root directories.
     * Called lazily when needed.
     */
    private static synchronized void ensurePathsInitialized(Context context) {
        if (!sPathMap.isEmpty()) return;
        if (context == null) return;
        // external-path → Environment.getExternalStorageDirectory()
        File externalDir = android.os.Environment.getExternalStorageDirectory();
        sPathMap.put("external-path", externalDir);
        // external-files-path → context.getExternalFilesDir(null)
        sPathMap.put("external-files-path", context.getExternalFilesDir(null));
        // files-path → context.getFilesDir()
        sPathMap.put("files-path", context.getFilesDir());
        // cache-path → context.getCacheDir()
        sPathMap.put("cache-path", context.getCacheDir());
    }

    static File getFileForUri(Uri uri) {
        String path = uri.getPath();
        // Path format: /<tag>/<name>/<sub-path>/<filename>
        // e.g., /external-files-path/updates/MusicFlow_update.apk
        if (path == null) throw new IllegalArgumentException("Invalid URI: " + uri);

        String[] segments = path.split("/", 4);
        if (segments.length < 3) throw new IllegalArgumentException("Invalid URI: " + uri);

        String tag = segments[1]; // e.g., "external-files-path"

        ensurePathsInitialized(null);
        File root = sPathMap.get(tag);
        if (root == null) throw new IllegalArgumentException("Unknown path tag: " + tag);

        // Everything after tag is the relative path: name/filename
        String relativePath = path.substring(("/" + tag + "/").length());
        File file = new File(root, relativePath);
        if (!file.exists()) throw new IllegalArgumentException("File not found: " + file);
        return file;
    }

    /**
     * Generate a content:// URI for the given file.
     * Mirrors FileProvider.getUriForFile() without AndroidX dependency.
     */
    public static Uri getUriForFile(Context context, String authority, File file) {
        // Try to match file against known root paths
        ensurePathsInitialized(context);

        String absolutePath = file.getAbsolutePath();
        for (Map.Entry<String, File> entry : sPathMap.entrySet()) {
            String rootPath = entry.getValue().getAbsolutePath();
            if (absolutePath.startsWith(rootPath)) {
                String relativePath = absolutePath.substring(rootPath.length());
                if (relativePath.startsWith("/")) relativePath = relativePath.substring(1);
                return Uri.parse("content://" + authority + "/" +
                    entry.getKey() + "/" + relativePath);
            }
        }

        throw new IllegalArgumentException("File is outside allowed paths: " + file);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File file = getFileForUri(uri);
        String[] cols = projection != null ? projection : COLUMNS;
        MatrixCursor cursor = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) {
                row[i] = file.getName();
            } else if (OpenableColumns.SIZE.equals(cols[i])) {
                row[i] = file.length();
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "application/octet-stream";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = getFileForUri(uri);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
