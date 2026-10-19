package com.tsinghua.sample.media.sync;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Publishes timing evidence only after both encoders and capture callbacks have finished.
 * The caller must have checked that both cameras use SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME.
 * Invoke off the UI thread: MP4 inspection and lossless remuxing perform disk I/O.
 */
public final class DualRecordingResultWriter {
    private DualRecordingResultWriter() {}

    public static Result write(File sessionDir, File frontFile, File backFile,
            FrameTimeline frontTimeline, FrameTimeline backTimeline, long clockSensorNs,
            long clockWallMs, int frontOrientation, int backOrientation, String recordingError) {
        File reportFile = new File(sessionDir, "dual_sync_report.json");
        List<String> errors = new ArrayList<>();
        List<String> exportErrors = new ArrayList<>();
        JSONObject report = new JSONObject();
        boolean verified = false;
        PairedVideoExporter.Export exported = null;
        try {
            List<FrameTimeline.Frame> front = frontTimeline.snapshot();
            List<FrameTimeline.Frame> back = backTimeline.snapshot();
            FrameAlignment.Metrics metrics = FrameAlignment.analyze(front, back);
            if (recordingError != null && !recordingError.trim().isEmpty()) errors.add(recordingError);
            if (clockSensorNs < 0 || clockWallMs <= 0) errors.add("公共时钟快照无效");
            if (!metrics.mappingComplete) errors.add("存在缺失或歧义的相机帧到视频样本映射");

            PairedVideoExporter.Verification frontCheck = PairedVideoExporter.verify(frontFile, front);
            PairedVideoExporter.Verification backCheck = PairedVideoExporter.verify(backFile, back);
            for (String error : frontCheck.errors) errors.add("前摄: " + error);
            for (String error : backCheck.errors) errors.add("后摄: " + error);

            report.put("schema_version", 2);
            report.put("sample_identity_method", "ordered_canonical_avc_nal_sha256_and_key_flag");
            report.put("row_identity_status_note", "CSV sample_identity_status只描述payload摘要；完整验收还检查样本数、关键帧及PTS严格递增");
            report.put("video_pts_us_semantics", "Legacy video_pts_us and requested_video_pts_us are muxer input requests; actual_file_pts_us is read from the saved MP4");
            report.put("muxer_timing_note", "MediaMuxer stts may smooth requested timestamps. Preserve both clocks; establish frame identity using content hashes, not a fitted PTS offset.");
            report.put("timestamp_clock", "SENSOR_REALTIME_elapsedRealtimeNanos");
            report.put("clock_sensor_ns", clockSensorNs);
            report.put("clock_wall_ms", clockWallMs);
            report.put("wall_clock_note", "同一次快照将两路传感器时刻映射到毫秒；不宣称外部时钟校准");
            report.put("hardware_synchronized", false);
            report.put("content_and_participant_confirmed", false);
            report.put("front", streamJson(frontFile, frontTimeline.captureCount(), front, frontCheck));
            report.put("back", streamJson(backFile, backTimeline.captureCount(), back, backCheck));
            report.put("pairing", metricsJson(metrics));
            report.put("warnings", timingWarnings(metrics));
            try {
                writeFrames(new File(sessionDir, "front/video_timestamps.csv"), front, frontCheck);
                writeFrames(new File(sessionDir, "back/video_timestamps.csv"), back, backCheck);
                writePairs(new File(sessionDir, "sync_frame_pairs.csv"), metrics, front, back);
            } catch (Exception error) {
                errors.add("逐帧证据保存失败: " + error);
            }
            verified = errors.isEmpty() && metrics.mappingComplete && frontCheck.valid && backCheck.valid;
            report.put("verified", verified);
            report.put("export_ready", false);
            report.put("errors", new JSONArray(errors));
            report.put("export_errors", new JSONArray());
            // Persist the raw result before attempting derived output, including failure cases.
            writeJson(reportFile, report);
            if (verified) {
                try {
                    exported = PairedVideoExporter.export(sessionDir, frontFile, backFile, front, back,
                            frontCheck, backCheck, clockSensorNs, clockWallMs, frontOrientation,
                            backOrientation);
                    report.put("export_ready", true);
                    report.put("pair_manifest", new File(exported.directory, "pair_manifest.json").getAbsolutePath());
                    report.put("face_start_time_wall_ms", exported.faceStartWallMs);
                    report.put("finger_start_time_wall_ms", exported.fingerStartWallMs);
                } catch (Exception error) {
                    exportErrors.add("未导出推理片段: " + error.getMessage());
                }
            } else {
                exportErrors.add("原始视频或时间映射未通过校验，未生成推理片段");
            }
            report.put("export_errors", new JSONArray(exportErrors));
            String summary = summary(verified, metrics, errors, exportErrors, exported);
            report.put("summary", summary);
            writeJson(reportFile, report);
            return new Result(verified, exported != null, reportFile, summary);
        } catch (Exception error) {
            errors.add("同步结果保存失败: " + error);
            String failure = "同步核对未完成：" + error.getMessage() + "。原始录像已保留。";
            try {
                report.put("schema_version", 2);
                report.put("verified", false);
                report.put("export_ready", false);
                report.put("errors", new JSONArray(errors));
                report.put("summary", failure);
                writeJson(reportFile, report);
            } catch (Exception ignored) {
                // The returned error still reaches the caller if storage itself is unavailable.
            }
            return new Result(false, false, reportFile, failure);
        }
    }

    private static JSONObject streamJson(File file, int captureCount,
            List<FrameTimeline.Frame> frames, PairedVideoExporter.Verification check) throws Exception {
        JSONObject stream = new JSONObject();
        int matched = 0, noCapture = 0, ambiguous = 0;
        for (FrameTimeline.Frame frame : frames) {
            if (frame.matched) matched++;
            else if (frame.matchStatus == FrameTimeline.MatchStatus.NO_CAPTURE) noCapture++;
            else ambiguous++;
        }
        stream.put("file", file.getAbsolutePath());
        stream.put("capture_callback_count", captureCount);
        stream.put("written_sample_count", frames.size());
        stream.put("matched_sample_count", matched);
        stream.put("no_capture_sample_count", noCapture);
        stream.put("ambiguous_sample_count", ambiguous);
        stream.put("capture_callbacks_without_unique_sample", captureCount - matched);
        stream.put("mapping_complete", !frames.isEmpty() && matched == frames.size());
        stream.put("mp4_verification", check.toJson());
        if (!frames.isEmpty()) {
            FrameTimeline.Frame first = frames.get(0);
            FrameTimeline.Frame last = frames.get(frames.size() - 1);
            stream.put("first_sensor_timestamp_ns", first.matched ? first.sensorTimestampNs : JSONObject.NULL);
            stream.put("last_sensor_timestamp_ns", last.matched ? last.sensorTimestampNs : JSONObject.NULL);
            stream.put("first_encoder_pts_us", first.codecPtsUs);
            stream.put("last_encoder_pts_us", last.codecPtsUs);
            stream.put("first_video_pts_us", first.videoPtsUs);
            stream.put("last_video_pts_us", last.videoPtsUs);
            stream.put("first_requested_video_pts_us", first.videoPtsUs);
            stream.put("last_requested_video_pts_us", last.videoPtsUs);
            stream.put("first_actual_file_pts_us", check.actualPtsUs[0] >= 0 ? check.actualPtsUs[0] : JSONObject.NULL);
            stream.put("last_actual_file_pts_us", check.actualPtsUs[frames.size() - 1] >= 0
                    ? check.actualPtsUs[frames.size() - 1] : JSONObject.NULL);
            if (first.matched && last.matched && last.sensorTimestampNs > first.sensorTimestampNs) {
                stream.put("average_fps", (frames.size() - 1) * 1_000_000_000.0
                        / (last.sensorTimestampNs - first.sensorTimestampNs));
            }
        }
        return stream;
    }

    static JSONObject metricsJson(FrameAlignment.Metrics metrics) throws Exception {
        JSONObject json = new JSONObject();
        json.put("mapping_complete", metrics.mappingComplete);
        json.put("method", "front_to_nearest_back_within_common_sensor_window");
        json.put("delta_sign", "back_minus_front");
        json.put("pair_count", metrics.pairs.size());
        json.put("reused_back_frame_pair_count", metrics.repeatedBackFrames);
        json.put("start_offset_back_minus_front_ns", metrics.mappingComplete ? metrics.startOffsetNs : JSONObject.NULL);
        json.put("start_offset_back_minus_front_ms", metrics.mappingComplete ? metrics.startOffsetNs / 1_000_000.0 : JSONObject.NULL);
        json.put("common_start_sensor_ns", metrics.mappingComplete ? metrics.commonStartNs : JSONObject.NULL);
        json.put("common_end_sensor_ns", metrics.mappingComplete ? metrics.commonEndNs : JSONObject.NULL);
        json.put("common_duration_ms", metrics.mappingComplete
                ? Math.max(0, metrics.commonEndNs - metrics.commonStartNs) / 1_000_000.0 : JSONObject.NULL);
        putDuration(json, "median_abs_pair_error", metrics.medianAbsNs);
        putDuration(json, "p95_abs_pair_error", metrics.p95AbsNs);
        putDuration(json, "max_abs_pair_error", metrics.maxAbsNs);
        putDuration(json, "max_front_frame_gap", metrics.maxFrontGapNs);
        putDuration(json, "max_back_frame_gap", metrics.maxBackGapNs);
        json.put("p95_definition", "nearest_rank");
        return json;
    }

    private static void putDuration(JSONObject json, String prefix, long ns) throws Exception {
        json.put(prefix + "_ns", ns >= 0 ? ns : JSONObject.NULL);
        json.put(prefix + "_us", ns >= 0 ? ns / 1000.0 : JSONObject.NULL);
        json.put(prefix + "_ms", ns >= 0 ? ns / 1_000_000.0 : JSONObject.NULL);
    }

    static JSONArray timingWarnings(FrameAlignment.Metrics metrics) {
        JSONArray warnings = new JSONArray();
        if (metrics.repeatedBackFrames > 0) warnings.put("最近帧配对复用了部分后摄帧，不是严格一对一曝光同步");
        if (metrics.maxFrontGapNs > 100_000_000L || metrics.maxBackGapNs > 100_000_000L) {
            warnings.put("存在超过 100 ms 的帧间空隙，请检查遮挡、帧率或丢帧");
        }
        if (metrics.maxAbsNs > 100_000_000L) warnings.put("部分配对帧时间差超过 100 ms");
        warnings.put("时间映射校验不验证人脸、手指拍摄规范或临床有效性");
        return warnings;
    }

    private static String summary(boolean verified, FrameAlignment.Metrics metrics,
            List<String> errors, List<String> exportErrors, PairedVideoExporter.Export export) {
        if (!verified) return "双摄时间映射未通过核对，原始录像已保留：" + String.join("；", errors);
        String value = metrics.pairs.isEmpty() ? "双摄逐帧映射与 MP4 已核对，但没有共同配对区间。"
                : String.format(Locale.CHINA,
                "双摄逐帧映射与 MP4 已核对；原始首帧差 %.3f ms，配对 P95 %.3f ms。",
                metrics.startOffsetNs / 1_000_000.0, metrics.p95AbsNs / 1_000_000.0);
        if (export != null) {
            return value + String.format(Locale.US,
                    "\n推理片段：%s\n人脸起始时间 ms：%.3f\n手指起始时间 ms：%.3f",
                    export.directory.getAbsolutePath(), export.faceStartWallMs, export.fingerStartWallMs);
        }
        return value + "\n" + String.join("；", exportErrors);
    }

    private static void writeFrames(File destination, List<FrameTimeline.Frame> frames,
            PairedVideoExporter.Verification check) throws Exception {
        writeText(destination, writer -> {
            writer.write("sample_index,camera_frame,sensor_timestamp_ns,encoder_pts_us,video_pts_us,requested_video_pts_us,actual_file_pts_us,file_minus_requested_pts_us,key_frame,match_status,sample_sha256,actual_file_sample_sha256,sample_identity_status\n");
            for (FrameTimeline.Frame frame : frames) {
                int index = frame.sampleIndex;
                long actualPtsUs = check.actualPtsUs[index];
                writer.write(frame.sampleIndex + "," + (frame.matched ? frame.cameraFrameNumber : "")
                        + "," + (frame.matched ? frame.sensorTimestampNs : "") + "," + frame.codecPtsUs
                        + "," + frame.videoPtsUs + "," + frame.videoPtsUs
                        + "," + (actualPtsUs >= 0 ? actualPtsUs : "")
                        + "," + (actualPtsUs >= 0 ? actualPtsUs - frame.videoPtsUs : "")
                        + "," + frame.keyFrame + "," + frame.matchStatus.name()
                        + "," + safeHash(frame.sampleSha256) + "," + safeHash(check.actualHashes[index])
                        + "," + check.identityStatus(index) + "\n");
            }
        });
    }

    static void writeClipFrames(File destination, List<FrameTimeline.Frame> source, int start,
            int end, PairedVideoExporter.Verification sourceCheck,
            PairedVideoExporter.Verification clipCheck) throws Exception {
        writeText(destination, writer -> {
            writer.write("sample_index,source_sample_index,camera_frame,sensor_timestamp_ns,encoder_pts_us,source_requested_video_pts_us,source_actual_file_pts_us,requested_video_pts_us,actual_file_pts_us,file_minus_requested_pts_us,key_frame,sample_sha256,actual_file_sample_sha256,sample_identity_status\n");
            for (int index = start; index <= end; index++) {
                FrameTimeline.Frame frame = source.get(index);
                int clipIndex = index - start;
                writer.write(clipIndex + "," + frame.sampleIndex + "," + frame.cameraFrameNumber
                        + "," + frame.sensorTimestampNs + "," + frame.codecPtsUs
                        + "," + frame.videoPtsUs + "," + sourceCheck.actualPtsUs[index]
                        + "," + clipCheck.requestedPtsUs[clipIndex] + "," + clipCheck.actualPtsUs[clipIndex]
                        + "," + (clipCheck.actualPtsUs[clipIndex] - clipCheck.requestedPtsUs[clipIndex])
                        + "," + frame.keyFrame + "," + safeHash(frame.sampleSha256)
                        + "," + safeHash(clipCheck.actualHashes[clipIndex])
                        + "," + clipCheck.identityStatus(clipIndex) + "\n");
            }
        });
    }

    private static String safeHash(String hash) { return hash == null ? "" : hash; }

    private static void writePairs(File destination, FrameAlignment.Metrics metrics,
            List<FrameTimeline.Frame> front, List<FrameTimeline.Frame> back) throws Exception {
        writeText(destination, writer -> {
            writer.write("front_sample_index,back_sample_index,front_sensor_timestamp_ns,back_sensor_timestamp_ns,back_minus_front_ns\n");
            for (FrameAlignment.Pair pair : metrics.pairs) {
                writer.write(pair.frontIndex + "," + pair.backIndex + ","
                        + front.get(pair.frontIndex).sensorTimestampNs + ","
                        + back.get(pair.backIndex).sensorTimestampNs + "," + pair.deltaNs + "\n");
            }
        });
    }

    static void writeJson(File destination, JSONObject value) throws Exception {
        writeText(destination, writer -> writer.write(value.toString(2) + "\n"));
    }

    private interface TextContent { void write(BufferedWriter writer) throws Exception; }

    private static void writeText(File destination, TextContent content) throws Exception {
        File parent = destination.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("无法创建目录: " + parent);
        }
        File temporary = File.createTempFile(destination.getName() + ".", ".partial", parent);
        try {
            try (FileOutputStream stream = new FileOutputStream(temporary);
                    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8))) {
                content.write(writer);
                writer.flush();
                stream.getFD().sync();
            }
            try {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            temporary.delete();
        }
    }

    public static final class Result {
        public final boolean verified;
        public final boolean exportReady;
        public final File report;
        public final String summary;

        Result(boolean verified, boolean exportReady, File report, String summary) {
            this.verified = verified;
            this.exportReady = exportReady;
            this.report = report;
            this.summary = summary;
        }
    }
}
