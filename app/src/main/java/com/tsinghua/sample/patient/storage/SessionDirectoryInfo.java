package com.tsinghua.sample.patient.storage;

import java.io.File;
import java.io.IOException;

/** The actual on-disk session associated with a record, independent of editable patient fields. */
public final class SessionDirectoryInfo {
    public final File directory;
    public final String relativePath;
    public final String documentId;

    private SessionDirectoryInfo(File directory, String relativePath) {
        this.directory = directory;
        this.relativePath = relativePath;
        this.documentId = "primary:" + relativePath;
    }

    public static SessionDirectoryInfo resolve(PatientRecord record, File primaryStorage) throws IOException {
        if (record == null || record.getSessionDir() == null || primaryStorage == null) {
            throw new IOException("这条患者记录没有关联的会话目录。");
        }
        File directory = record.getSessionDir().getCanonicalFile();
        File storage = primaryStorage.getCanonicalFile();
        File collection = new File(storage, "Movies/FacialCollection").getCanonicalFile();
        if (!directory.toPath().startsWith(collection.toPath())) {
            throw new IOException("会话目录不在本机采集文件夹内，无法打开。");
        }
        java.nio.file.Path relative = collection.toPath().relativize(directory.toPath());
        if (relative.getNameCount() != 2 || !directory.getName().matches("Session_\\d+")) {
            throw new IOException("这条记录关联的会话目录格式不正确。");
        }
        if (!directory.isDirectory()) {
            throw new IOException("本次会话目录不存在，可能已移动或删除。");
        }
        if (!directory.canRead()) {
            throw new IOException("无法读取会话目录，请检查应用的文件访问权限。");
        }
        return new SessionDirectoryInfo(directory,
                storage.toPath().relativize(directory.toPath()).toString().replace(File.separatorChar, '/'));
    }

    public String videoLocationMessage() {
        return "前置摄像头视频：front 文件夹\n文件名以 front_camera_ 开头。\n\n"
                + "后置摄像头视频：back 文件夹\n文件名以 back_camera_ 开头。\n\n"
                + "进入文件夹后，点击 MP4 视频即可检查画面。未启用的摄像头可能没有视频。\n\n"
                + "本次会话位置：\n内部存储/" + relativePath;
    }
}
