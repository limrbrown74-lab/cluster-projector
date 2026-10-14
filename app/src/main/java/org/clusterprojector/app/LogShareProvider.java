package org.clusterprojector.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.text.TextUtils;

import java.io.File;
import java.io.FileNotFoundException;

/** 最小化的只读日志 FileProvider，避免为旧工程引入 AndroidX 依赖。 */
public class LogShareProvider extends ContentProvider {

    private static final String PATH_DIAGNOSTIC = "diagnostic";

    @Override public boolean onCreate() {
        return true;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        File file = resolveFile(uri);
        if (file == null || !file.isFile()) return null;

        String[] columns = projection != null && projection.length > 0
                ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            if (OpenableColumns.DISPLAY_NAME.equals(column)) {
                row.add(file.getName());
            } else if (OpenableColumns.SIZE.equals(column)) {
                row.add(file.length());
            } else if ("_id".equals(column)) {
                row.add(1);
            } else {
                row.add(null);
            }
        }
        return cursor;
    }

    @Override public String getType(Uri uri) {
        // 使用二进制附件 MIME，避免微信/车机分享器把 Intent 当成纯文本消息。
        // 文件扩展名仍为 .log，接收端可直接用文本编辑器打开。
        return "application/octet-stream";
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("diagnostic log is read-only");
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        return 0;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Only read mode is supported");
        File file = resolveFile(uri);
        if (file == null || !file.isFile()) throw new FileNotFoundException("Unknown log: " + uri);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode)
            throws FileNotFoundException {
        File file = resolveFile(uri);
        if (file == null || !file.isFile()) throw new FileNotFoundException("Unknown log: " + uri);
        return new AssetFileDescriptor(openFile(uri, "r"), 0, file.length());
    }

    private File resolveFile(Uri uri) {
        if (getContext() == null || uri == null) return null;
        if (!PATH_DIAGNOSTIC.equals(uri.getPathSegments().isEmpty()
                ? null : uri.getPathSegments().get(0))) {
            return null;
        }
        if (uri.getPathSegments().size() != 2) return null;

        String name = uri.getPathSegments().get(1);
        if (TextUtils.isEmpty(name) || name.contains("/") || name.contains("\\")
                || name.contains("..") || !name.matches("[A-Za-z0-9._-]+")) {
            return null;
        }

        File root = new File(getContext().getCacheDir(), PATH_DIAGNOSTIC);
        File file = new File(root, name);
        try {
            String rootPath = root.getCanonicalPath();
            String filePath = file.getCanonicalPath();
            if (!filePath.startsWith(rootPath + File.separator)) return null;
        } catch (Exception e) {
            return null;
        }
        return file;
    }
}
