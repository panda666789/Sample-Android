package com.tsinghua.sample.media.sync;

import android.annotation.TargetApi;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Validates actual MP4 samples and remuxes a bounded pair without decoding or altering pixels. */
@TargetApi(33)
final class PairedVideoExporter {
    // Diagnostic boundary only: sample identity does not depend on requested-vs-container PTS.
    static final long PTS_ADJUSTMENT_NOTICE_US = 100;
    private static final int MAX_SAMPLE_BYTES = 16 * 1024 * 1024;

    static Verification verify(File source, List<FrameTimeline.Frame> frames) {
        long[] times = new long[frames.size()];
        boolean[] keys = new boolean[frames.size()];
        String[] hashes = new String[frames.size()];
        for (int i = 0; i < frames.size(); i++) {
            times[i] = frames.get(i).videoPtsUs;
            keys[i] = frames.get(i).keyFrame;
            hashes[i] = frames.get(i).sampleSha256;
        }
        return verify(source, times, keys, hashes);
    }

    private static Verification verify(File source, long[] expectedPts, boolean[] expectedKeys,
            String[] expectedHashes) {
        Verification result = new Verification(expectedPts, expectedKeys, expectedHashes);
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(source.getAbsolutePath());
            int track = videoTrack(extractor);
            MediaFormat trackFormat = extractor.getTrackFormat(track);
            if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                result.durationUs = trackFormat.getLong(MediaFormat.KEY_DURATION);
            }
            extractor.selectTrack(track);
            ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 1024);
            while (extractor.getSampleTrackIndex() >= 0) {
                long pts = extractor.getSampleTime();
                boolean key = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
                long size = extractor.getSampleSize();
                if (size <= 0 || size > MAX_SAMPLE_BYTES) throw new IOException("不支持的视频样本大小: " + size);
                if (size > buffer.capacity()) buffer = ByteBuffer.allocateDirect((int) size);
                buffer.clear();
                int read = extractor.readSampleData(buffer, 0);
                if (read != size) throw new IOException("视频样本读取不完整");
                buffer.position(0);
                buffer.limit(read);
                String hash = AvcSampleIdentity.sha256(buffer.asReadOnlyBuffer());
                result.acceptSample(pts, key, extractor.getSampleFlags() & ~MediaExtractor.SAMPLE_FLAG_SYNC, hash);
                extractor.advance();
            }
        } catch (Exception error) {
            result.errors.add("无法读取 MP4: " + error);
        } finally {
            extractor.release();
        }
        result.finish();
        return result;
    }

    static Export export(File sessionDir, File frontFile, File backFile,
            List<FrameTimeline.Frame> front, List<FrameTimeline.Frame> back,
            Verification frontVerified, Verification backVerified, long clockSensorNs,
            long clockWallMs, int frontOrientation, int backOrientation) throws Exception {
        if (!frontVerified.valid || !backVerified.valid) {
            throw new IOException("原始视频尚未通过逐样本核对");
        }
        PairedClipSelection.Selection clip = PairedClipSelection.select(front, back);
        File inference = new File(sessionDir, "inference");
        if (!inference.isDirectory() && !inference.mkdirs()) throw new IOException("无法创建推理目录");
        File target = new File(inference, "dual_sync");
        if (target.exists()) throw new IOException("派生目录已存在，未覆盖此前结果: " + target);
        File staging = new File(inference, ".dual_sync." + UUID.randomUUID() + ".partial");
        if (!staging.mkdir()) throw new IOException("无法创建派生临时目录");
        boolean published = false;
        try {
            File facePartial = new File(staging, "face.mp4.partial");
            File fingerPartial = new File(staging, "finger.mp4.partial");
            Verification faceCheck = writeClip(frontFile, facePartial, front, frontVerified,
                    clip.frontStart, clip.frontEnd, frontOrientation);
            Verification fingerCheck = writeClip(backFile, fingerPartial, back, backVerified,
                    clip.backStart, clip.backEnd, backOrientation);
            DualRecordingResultWriter.writeClipFrames(new File(staging, "clip_video_timestamps_face.csv"),
                    front, clip.frontStart, clip.frontEnd, frontVerified, faceCheck);
            DualRecordingResultWriter.writeClipFrames(new File(staging, "clip_video_timestamps_finger.csv"),
                    back, clip.backStart, clip.backEnd, backVerified, fingerCheck);
            Files.move(facePartial.toPath(), new File(staging, "face.mp4").toPath());
            Files.move(fingerPartial.toPath(), new File(staging, "finger.mp4").toPath());
            List<FrameTimeline.Frame> frontClip = front.subList(clip.frontStart, clip.frontEnd + 1);
            List<FrameTimeline.Frame> backClip = back.subList(clip.backStart, clip.backEnd + 1);
            FrameAlignment.Metrics metrics = FrameAlignment.analyze(frontClip, backClip);
            double faceWallMs = wallMs(frontClip.get(0).sensorTimestampNs, clockSensorNs, clockWallMs);
            double fingerWallMs = wallMs(backClip.get(0).sensorTimestampNs, clockSensorNs, clockWallMs);
            JSONObject manifest = new JSONObject();
            manifest.put("schema_version", 2);
            manifest.put("export_ready", true);
            manifest.put("timing_verified", true);
            manifest.put("hardware_synchronized", false);
            manifest.put("content_and_participant_confirmed", false);
            manifest.put("method", "keyframe_remux_no_reencoding");
            manifest.put("sample_identity_method", "ordered_canonical_avc_nal_sha256_and_key_flag");
            manifest.put("muxer_timing_note", "Source and clip containers may smooth requested PTS; actual file timestamps are preserved separately in the per-clip CSVs. Sensor correspondence is established by sample content identity.");
            manifest.put("timestamp_clock", "SENSOR_REALTIME_elapsedRealtimeNanos");
            manifest.put("clock_sensor_ns", clockSensorNs);
            manifest.put("clock_wall_ms", clockWallMs);
            manifest.put("wall_clock_note", "两路共用同一次时钟快照；毫秒值用于同机相对对齐，不代表外部校时精度");
            manifest.put("start_offset_back_minus_front_ns", clip.startOffsetNs);
            manifest.put("start_offset_back_minus_front_ms", clip.startOffsetNs / 1_000_000.0);
            manifest.put("common_start_sensor_ns", clip.commonStartNs);
            manifest.put("common_end_sensor_ns", clip.commonEndNs);
            manifest.put("common_duration_ms", (clip.commonEndNs - clip.commonStartNs) / 1_000_000.0);
            manifest.put("maximum_frames_per_video", PairedClipSelection.MAX_FRAMES);
            manifest.put("face", clipJson("face.mp4", frontFile, front, clip.frontStart,
                    clip.frontEnd, clip.frontFps, faceWallMs, frontOrientation, frontVerified,
                    faceCheck, "clip_video_timestamps_face.csv"));
            manifest.put("finger", clipJson("finger.mp4", backFile, back, clip.backStart,
                    clip.backEnd, clip.backFps, fingerWallMs, backOrientation, backVerified,
                    fingerCheck, "clip_video_timestamps_finger.csv"));
            manifest.put("pairing", DualRecordingResultWriter.metricsJson(metrics));
            manifest.put("errors", new JSONArray());
            manifest.put("warnings", DualRecordingResultWriter.timingWarnings(metrics));
            DualRecordingResultWriter.writeJson(new File(staging, "pair_manifest.json"), manifest);
            Files.move(staging.toPath(), target.toPath());
            published = true;
            return new Export(target, faceWallMs, fingerWallMs, manifest);
        } finally {
            // Only this invocation's uniquely created staging directory can be removed.
            if (!published) deleteStaging(staging);
        }
    }

    private static Verification writeClip(File source, File destination,
            List<FrameTimeline.Frame> frames, Verification verified, int start, int end,
            int orientation) throws Exception {
        if (!frames.get(start).keyFrame) throw new IOException("裁切起点不是关键帧");
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean started = false;
        int count = end - start + 1;
        long[] expectedPts = new long[count];
        boolean[] expectedKeys = new boolean[count];
        String[] expectedHashes = new String[count];
        try {
            extractor.setDataSource(source.getAbsolutePath());
            int track = videoTrack(extractor);
            MediaFormat format = extractor.getTrackFormat(track);
            extractor.selectTrack(track);
            muxer = new MediaMuxer(destination.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            muxer.setOrientationHint(orientation);
            int outputTrack = muxer.addTrack(format);
            muxer.start();
            started = true;
            ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long originPtsUs = verified.actualPtsUs[start];
            for (int index = 0; index <= end; index++) {
                if (extractor.getSampleTrackIndex() < 0) throw new IOException("源视频在导出时提前结束");
                if (index >= start) {
                    long pts = extractor.getSampleTime();
                    boolean key = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
                    if (pts != verified.actualPtsUs[index] || key != frames.get(index).keyFrame
                            || (extractor.getSampleFlags() & ~MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        throw new IOException("源视频在验证后发生变化");
                    }
                    long size = extractor.getSampleSize();
                    if (size <= 0 || size > MAX_SAMPLE_BYTES) throw new IOException("不支持的视频样本大小: " + size);
                    if (size > buffer.capacity()) buffer = ByteBuffer.allocateDirect((int) size);
                    buffer.clear();
                    int read = extractor.readSampleData(buffer, 0);
                    if (read != size) throw new IOException("源视频样本读取不完整");
                    buffer.position(0);
                    buffer.limit(read);
                    String sampleHash = AvcSampleIdentity.sha256(buffer.asReadOnlyBuffer());
                    if (!sampleHash.equals(frames.get(index).sampleSha256)) {
                        throw new IOException("源视频样本内容在验证后发生变化");
                    }
                    long relativePtsUs = pts - originPtsUs;
                    info.set(0, read, relativePtsUs, key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                    muxer.writeSampleData(outputTrack, buffer, info);
                    expectedPts[index - start] = relativePtsUs;
                    expectedKeys[index - start] = key;
                    expectedHashes[index - start] = sampleHash;
                }
                extractor.advance();
            }
            muxer.stop();
            started = false;
        } finally {
            extractor.release();
            if (muxer != null) {
                if (started) {
                    try { muxer.stop(); } catch (RuntimeException ignored) { /* Preserve original error. */ }
                }
                muxer.release();
            }
        }
        Verification result = verify(destination, expectedPts, expectedKeys, expectedHashes);
        if (!result.valid) throw new IOException("派生视频复核失败: " + result.errors);
        if (!PairedClipSelection.acceptsInferenceFps(result.averageFps)) {
            throw new IOException("派生视频实际 PTS 平均帧率不在 25–35 FPS: " + result.averageFps);
        }
        if (result.durationUs <= 0 || result.durationUs > PairedClipSelection.MAX_DURATION_NS / 1000) {
            throw new IOException("派生视频时长缺失或超过 30 秒: " + result.durationUs + " us");
        }
        return result;
    }

    private static int videoTrack(MediaExtractor extractor) throws IOException {
        int video = -1;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                if (video != -1) throw new IOException("源文件包含多条视频轨道");
                video = i;
            }
        }
        if (video == -1) throw new IOException("文件没有视频轨道");
        return video;
    }

    private static JSONObject clipJson(String file, File source, List<FrameTimeline.Frame> frames,
            int start, int end, double fps, double wallMs, int orientation,
            Verification sourceCheck, Verification check, String timestampsCsv) throws Exception {
        JSONObject json = new JSONObject();
        json.put("file", file);
        json.put("source_file", source.getAbsolutePath());
        json.put("source_sample_start_index", start);
        json.put("source_sample_end_index_inclusive", end);
        json.put("sample_count", end - start + 1);
        json.put("source_average_fps", fps);
        json.put("first_sensor_timestamp_ns", frames.get(start).sensorTimestampNs);
        json.put("last_sensor_timestamp_ns", frames.get(end).sensorTimestampNs);
        json.put("source_first_video_pts_us", frames.get(start).videoPtsUs);
        json.put("source_last_video_pts_us", frames.get(end).videoPtsUs);
        json.put("source_video_pts_semantics", "requested muxer input timestamps; actual file timestamps are separate");
        json.put("source_first_actual_file_pts_us", sourceCheck.actualPtsUs[start]);
        json.put("source_last_actual_file_pts_us", sourceCheck.actualPtsUs[end]);
        json.put("first_actual_file_pts_us", check.actualPtsUs[0]);
        json.put("last_actual_file_pts_us", check.actualPtsUs[check.actualPtsUs.length - 1]);
        json.put("video_timestamps_csv", timestampsCsv);
        json.put("start_time_wall_ms", wallMs);
        json.put("orientation_degrees", orientation);
        json.put("mp4_verification", check.toJson());
        return json;
    }

    static double wallMs(long sensorNs, long anchorSensorNs, long anchorWallMs) {
        return anchorWallMs + (sensorNs - anchorSensorNs) / 1_000_000.0;
    }

    private static void deleteStaging(File directory) {
        File[] children = directory.listFiles();
        if (children != null) for (File child : children) child.delete();
        directory.delete();
    }

    static final class Verification {
        final List<String> errors = new ArrayList<>();
        final long[] requestedPtsUs;
        final long[] actualPtsUs;
        final String[] expectedHashes;
        final String[] actualHashes;
        private final boolean[] expectedKeys;
        int sampleCount, ptsAdjustedCount, ptsAdjustmentOver100UsCount, keyFrameMismatchCount;
        int nonMonotonicCount, unsupportedFlagsCount, missingHashCount, hashMismatchCount;
        long maxPtsErrorUs;
        long minPtsAdjustmentUs = Long.MAX_VALUE;
        long maxPtsAdjustmentUs = Long.MIN_VALUE;
        long durationUs = -1;
        double averageFps = Double.NaN;
        boolean firstKeyFrame, valid;
        private long firstPts = -1;
        private long previousPts = -1;

        Verification(long[] requestedPts, boolean[] keys, String[] hashes) {
            if (keys.length != requestedPts.length || hashes.length != requestedPts.length) {
                throw new IllegalArgumentException("Sample evidence lengths differ");
            }
            requestedPtsUs = requestedPts.clone();
            expectedKeys = keys.clone();
            expectedHashes = hashes.clone();
            actualPtsUs = new long[requestedPts.length];
            Arrays.fill(actualPtsUs, -1);
            actualHashes = new String[requestedPts.length];
        }

        void acceptSample(long pts, boolean key, int unsupportedFlags, String hash) {
            int index = sampleCount++;
            if (index == 0) {
                firstKeyFrame = key;
                firstPts = pts;
            }
            if (pts <= previousPts) nonMonotonicCount++;
            previousPts = pts;
            if (unsupportedFlags != 0) unsupportedFlagsCount++;
            if (index >= actualPtsUs.length) return;
            actualPtsUs[index] = pts;
            actualHashes[index] = hash;
            long adjustmentUs = pts - requestedPtsUs[index];
            minPtsAdjustmentUs = Math.min(minPtsAdjustmentUs, adjustmentUs);
            maxPtsAdjustmentUs = Math.max(maxPtsAdjustmentUs, adjustmentUs);
            maxPtsErrorUs = Math.max(maxPtsErrorUs, Math.abs(adjustmentUs));
            if (adjustmentUs != 0) ptsAdjustedCount++;
            if (Math.abs(adjustmentUs) > PTS_ADJUSTMENT_NOTICE_US) ptsAdjustmentOver100UsCount++;
            if (key != expectedKeys[index]) keyFrameMismatchCount++;
            if (expectedHashes[index] == null || expectedHashes[index].isEmpty()
                    || hash == null || hash.isEmpty()) missingHashCount++;
            else if (!expectedHashes[index].equals(hash)) hashMismatchCount++;
        }

        void finish() {
            if (sampleCount >= 2 && previousPts > firstPts) {
                averageFps = (sampleCount - 1) * 1_000_000.0 / (previousPts - firstPts);
            }
            if (sampleCount != actualPtsUs.length) errors.add("MP4 样本数与时间映射不一致");
            if (sampleCount == 0) errors.add("MP4 没有视频样本");
            if (missingHashCount > 0) errors.add("MP4 样本缺少编码或反读 SHA-256 摘要");
            if (hashMismatchCount > 0) errors.add("MP4 样本 SHA-256 与实际写入的编码样本不一致");
            if (keyFrameMismatchCount > 0) errors.add("MP4 关键帧标志与记录不一致");
            if (!firstKeyFrame) errors.add("MP4 首帧不是关键帧");
            if (nonMonotonicCount > 0) errors.add("MP4 PTS 没有严格递增");
            if (unsupportedFlagsCount > 0) errors.add("MP4 包含不支持的加密或分片样本");
            valid = errors.isEmpty();
        }

        String identityStatus(int index) {
            if (index >= sampleCount || actualPtsUs[index] < 0) return "NOT_READ";
            if (expectedHashes[index] == null || expectedHashes[index].isEmpty()) return "MISSING_EXPECTED_HASH";
            if (actualHashes[index] == null || actualHashes[index].isEmpty()) return "MISSING_ACTUAL_HASH";
            return expectedHashes[index].equals(actualHashes[index]) ? "MATCHED" : "HASH_MISMATCH";
        }

        JSONObject toJson() throws Exception {
            JSONObject json = new JSONObject();
            json.put("valid", valid);
            json.put("expected_samples", actualPtsUs.length);
            json.put("observed_samples", sampleCount);
            json.put("sample_identity_method", "ordered_canonical_avc_nal_sha256_and_key_flag");
            json.put("missing_sample_hash_count", missingHashCount);
            json.put("sample_hash_mismatch_count", hashMismatchCount);
            json.put("pts_source", "MediaExtractor_actual_file_pts");
            json.put("pts_adjustment_note", "MediaMuxer stts may adjust requested PTS; sample identity is verified by content, never by fitting a timestamp offset");
            json.put("pts_adjustment_is_identity_failure", false);
            json.put("max_abs_file_minus_requested_pts_us", maxPtsErrorUs);
            json.put("min_file_minus_requested_pts_us", minPtsAdjustmentUs != Long.MAX_VALUE ? minPtsAdjustmentUs : JSONObject.NULL);
            json.put("max_file_minus_requested_pts_us", maxPtsAdjustmentUs != Long.MIN_VALUE ? maxPtsAdjustmentUs : JSONObject.NULL);
            json.put("pts_adjusted_sample_count", ptsAdjustedCount);
            json.put("pts_adjustment_over_100us_count", ptsAdjustmentOver100UsCount);
            json.put("key_frame_mismatch_count", keyFrameMismatchCount);
            json.put("non_monotonic_pts_count", nonMonotonicCount);
            json.put("unsupported_sample_flags_count", unsupportedFlagsCount);
            json.put("first_sample_is_key_frame", firstKeyFrame);
            json.put("track_duration_us", durationUs >= 0 ? durationUs : JSONObject.NULL);
            json.put("sample_interval_average_fps", Double.isFinite(averageFps) ? averageFps : JSONObject.NULL);
            json.put("fps_within_inference_range", PairedClipSelection.acceptsInferenceFps(averageFps));
            json.put("errors", new JSONArray(errors));
            return json;
        }
    }

    static final class Export {
        final File directory;
        final double faceStartWallMs, fingerStartWallMs;
        final JSONObject manifest;

        Export(File directory, double faceStartWallMs, double fingerStartWallMs, JSONObject manifest) {
            this.directory = directory;
            this.faceStartWallMs = faceStartWallMs;
            this.fingerStartWallMs = fingerStartWallMs;
            this.manifest = manifest;
        }
    }
}
