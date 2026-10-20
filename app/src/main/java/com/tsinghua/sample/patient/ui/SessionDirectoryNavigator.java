package com.tsinghua.sample.patient.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.tsinghua.sample.patient.storage.PatientRecord;
import com.tsinghua.sample.patient.storage.SessionDirectoryInfo;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;

/** Opens the system's file browser at the selected record, with a video-picker fallback. */
public final class SessionDirectoryNavigator {
    private final AppCompatActivity activity;
    private final ActivityResultLauncher<Intent> videoPicker;

    public SessionDirectoryNavigator(AppCompatActivity activity) {
        this.activity = activity;
        videoPicker = activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
            Uri video = result.getData().getData();
            if (video == null || !"content".equals(video.getScheme())) return;
            try {
                // This grant is for the video explicitly selected by the user, never the directory.
                activity.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(video, "video/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            } catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(activity, "无法打开所选视频，请用系统相册或视频播放器查看。", Toast.LENGTH_LONG).show();
            }
        });
    }

    public void show(PatientRecord record) {
        try {
            SessionDirectoryInfo info = resolve(record);
            new AlertDialog.Builder(activity)
                    .setTitle("视频存放位置")
                    .setMessage(info.videoLocationMessage())
                    .setPositiveButton("打开会话目录", (dialog, which) -> open(record))
                    .setNegativeButton("取消", null)
                    .show();
        } catch (IOException e) {
            showError(e.getMessage());
        }
    }

    private SessionDirectoryInfo resolve(PatientRecord record) throws IOException {
        return SessionDirectoryInfo.resolve(record, Environment.getExternalStorageDirectory());
    }

    private void open(PatientRecord record) {
        try {
            // Recheck after confirmation: another screen or file manager may have removed it.
            SessionDirectoryInfo info = resolve(record);
            Uri directory = DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents", info.documentId);
            Intent browse = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(directory, DocumentsContract.Document.MIME_TYPE_DIR);
            // DocumentsUI owns its provider access. Do not try to grant a hand-built directory URI.
            // Only dispatch this document URI to a system file handler, not unrelated user apps.
            List<ResolveInfo> handlers = activity.getPackageManager().queryIntentActivities(browse,
                    PackageManager.MATCH_DEFAULT_ONLY | PackageManager.MATCH_SYSTEM_ONLY
                            | PackageManager.GET_RESOLVED_FILTER);
            for (ResolveInfo handler : handlers) {
                if (!handlesDirectoriesExplicitly(handler)) continue;
                try {
                    Intent targeted = new Intent(browse).setComponent(new ComponentName(
                            handler.activityInfo.packageName, handler.activityInfo.name));
                    activity.startActivity(targeted);
                    return;
                } catch (ActivityNotFoundException | SecurityException ignored) {
                    // An OEM may omit the system folder browser. Use its standard picker below.
                }
            }
            try {
                videoPicker.launch(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("video/*")
                        .putExtra(DocumentsContract.EXTRA_INITIAL_URI, directory));
                Toast.makeText(activity, "进入 front 或 back 文件夹，选择视频即可播放。", Toast.LENGTH_LONG).show();
            } catch (ActivityNotFoundException | SecurityException e) {
                showError("此设备无法直接打开目录。请在文件管理器中进入：\n内部存储/"
                        + info.relativePath + "\n\n前置视频在 front，后置视频在 back。");
            }
        } catch (IOException e) {
            showError(e.getMessage());
        }
    }

    private static boolean handlesDirectoriesExplicitly(ResolveInfo handler) {
        // Some preinstalled media apps declare */*. They are not folder browsers.
        Iterator<String> types = handler.filter == null ? null : handler.filter.typesIterator();
        while (types != null && types.hasNext()) {
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(types.next())) return true;
        }
        return false;
    }

    private void showError(String message) {
        new AlertDialog.Builder(activity).setTitle("无法打开会话目录")
                .setMessage(message).setPositiveButton("知道了", null).show();
    }
}
