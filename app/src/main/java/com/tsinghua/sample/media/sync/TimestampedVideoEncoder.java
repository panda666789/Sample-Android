package com.tsinghua.sample.media.sync;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Hardware AVC recording with a timeline for the samples actually written to MP4.
 *
 * <p>The Camera2 output supplying {@link #getInputSurface()} must use the sensor timestamp
 * base. Codec PTS values are retained in that time domain; they are not wall-clock times.
 * Video PTS values are microseconds relative to the first written key frame. Camera capture
 * timestamps remain nanoseconds in {@link FrameTimeline}.</p>
 *
 * <p>Construction prepares and starts the encoder before the camera supplies frames. Stop
 * camera requests before calling {@link #finish()} on a background thread. After construction,
 * a single drain thread owns codec and muxer operations and resource release.</p>
 */
public final class TimestampedVideoEncoder {
    private static final String TAG = "TimestampedEncoder";
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final long DEQUEUE_TIMEOUT_US = 10_000L;
    private static final long FINISH_TIMEOUT_SECONDS = 5L;

    public interface Listener {
        /** Called after resources have been released, without any lifecycle lock held. */
        void onError(Exception error);
    }

    private final FrameTimeline timeline;
    private final Listener listener;
    private final CountDownLatch drainDone = new CountDownLatch(1);
    private final AtomicReference<Exception> failure = new AtomicReference<>();
    private final Thread drainThread;
    private final Surface inputSurface;
    private final MediaCodec codec;
    private final MediaMuxer muxer;

    private volatile boolean finishRequested;
    private volatile boolean abortRequested;
    private volatile int droppedBeforeFirstKeyFrame;

    // Accessed only by the drain thread after successful construction.
    private boolean muxerStarted;
    private int trackIndex = -1;
    private int writtenSamples;
    private long firstWrittenCodecPtsUs = -1L;
    private long previousCodecPtsUs = -1L;

    public TimestampedVideoEncoder(File outputFile, int width, int height,
                                   int orientationDegrees, FrameTimeline timeline,
                                   Listener listener) throws IOException {
        Objects.requireNonNull(outputFile, "outputFile");
        this.timeline = Objects.requireNonNull(timeline, "timeline");
        this.listener = listener;
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Video dimensions must be positive");
        }
        if (orientationDegrees != 0 && orientationDegrees != 90
                && orientationDegrees != 180 && orientationDegrees != 270) {
            throw new IllegalArgumentException("Orientation must be 0, 90, 180 or 270 degrees");
        }

        MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        // Bits per second: retain the ordinary recorder's 1080p / lower-resolution settings.
        format.setInteger(MediaFormat.KEY_BIT_RATE,
                (long) width * height >= 1920L * 1080L ? 10_000_000 : 6_000_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1); // seconds
        // Baseline has no B frames; this also covers API 26-28 without KEY_MAX_B_FRAMES.
        format.setInteger(MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        }

        MediaCodec preparedCodec = null;
        MediaMuxer preparedMuxer = null;
        Surface preparedSurface = null;
        boolean codecStarted = false;
        try {
            preparedCodec = createHardwareEncoder(format);
            preparedCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            preparedSurface = preparedCodec.createInputSurface();
            preparedMuxer = new MediaMuxer(outputFile.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            preparedMuxer.setOrientationHint(orientationDegrees);
            preparedCodec.start();
            codecStarted = true;
        } catch (Exception error) {
            releaseUnstarted(preparedCodec, preparedMuxer, preparedSurface, codecStarted, error);
            if (error instanceof IOException) {
                throw (IOException) error;
            }
            throw new IOException("Cannot prepare timestamped video encoder", error);
        }

        codec = preparedCodec;
        muxer = preparedMuxer;
        inputSurface = preparedSurface;
        drainThread = new Thread(this::drain, "VideoDrain-" + outputFile.getName());
        try {
            drainThread.start();
        } catch (RuntimeException error) {
            releaseUnstarted(codec, muxer, inputSurface, true, error);
            drainDone.countDown();
            throw new IOException("Cannot start video drain thread", error);
        }
    }

    public Surface getInputSurface() {
        if (finishRequested || abortRequested || drainDone.getCount() == 0) {
            throw new IllegalStateException("Video encoder is no longer accepting frames");
        }
        return inputSurface;
    }

    /**
     * Sends EOS, waits up to five seconds for drain and cleanup, and reports any recording error.
     * On timeout, cleanup is requested on the owner thread; resources are never released
     * concurrently with a native dequeue or muxer write.
     */
    public void finish() throws Exception {
        finishRequested = true;
        if (Thread.currentThread() == drainThread && drainDone.getCount() != 0) {
            throw new IllegalStateException("Cannot wait for the encoder on its drain thread");
        }
        try {
            if (!drainDone.await(FINISH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                IOException timeout = new IOException("Video encoder did not finish within 5 seconds");
                failure.compareAndSet(null, timeout);
                abortRequested = true;
                throw failure.get();
            }
        } catch (InterruptedException error) {
            failure.compareAndSet(null, error);
            abortRequested = true;
            Thread.currentThread().interrupt();
            throw error;
        }
        Exception error = failure.get();
        if (error != null) {
            throw error;
        }
        if (abortRequested) {
            throw new IOException("Video recording was aborted");
        }
    }

    /** Idempotent cancellation; call on a background thread after stopping camera requests. */
    public void abort() {
        if (drainDone.getCount() == 0) {
            return;
        }
        abortRequested = true;
        if (Thread.currentThread() == drainThread) {
            return;
        }
        try {
            if (!drainDone.await(FINISH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                failure.compareAndSet(null,
                        new IOException("Video encoder cleanup did not finish within 5 seconds"));
            }
        } catch (InterruptedException error) {
            failure.compareAndSet(null, error);
            Thread.currentThread().interrupt();
        }
    }

    public Exception getFailure() {
        return failure.get();
    }

    public int getDroppedBeforeFirstKeyFrame() {
        return droppedBeforeFirstKeyFrame;
    }

    private void drain() {
        boolean inputEnded = false;
        boolean outputEnded = false;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (!abortRequested && !outputEnded) {
                if (finishRequested && !inputEnded) {
                    codec.signalEndOfInputStream();
                    inputEnded = true;
                }
                int outputIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER
                        || outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    continue;
                }
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) {
                        throw new IOException("Encoder output format changed after muxer start");
                    }
                    trackIndex = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                    continue;
                }
                if (outputIndex < 0) {
                    throw new IOException("Unexpected encoder output status: " + outputIndex);
                }
                try {
                    // EOS may accompany a real final sample. Consume it before ending the loop.
                    if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        writeSample(outputIndex, info);
                    }
                    outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if (outputEnded && !inputEnded) {
                        throw new IOException("Video encoder ended before recording was stopped");
                    }
                } finally {
                    codec.releaseOutputBuffer(outputIndex, false);
                }
            }
            if (!abortRequested && (!outputEnded || writtenSamples == 0)) {
                throw new IOException("Video encoder produced no complete recording");
            }
        } catch (Exception error) {
            failure.compareAndSet(null, error);
        } finally {
            releaseResources();
            // A callback may trigger finish()/abort(); it must not wait for its own callback.
            drainDone.countDown();
            Exception error = failure.get();
            if (error != null && listener != null) {
                try {
                    listener.onError(error);
                } catch (RuntimeException callbackError) {
                    Log.e(TAG, "Encoder error listener failed", callbackError);
                }
            }
        }
    }

    private void writeSample(int outputIndex, MediaCodec.BufferInfo info) throws IOException {
        if (!muxerStarted) {
            throw new IOException("Encoder emitted video before output format");
        }
        long codecPtsUs = info.presentationTimeUs;
        if (codecPtsUs < 0 || codecPtsUs <= previousCodecPtsUs) {
            throw new IOException("Non-increasing encoder PTS: " + codecPtsUs
                    + " after " + previousCodecPtsUs);
        }
        previousCodecPtsUs = codecPtsUs;
        if ((info.flags & MediaCodec.BUFFER_FLAG_PARTIAL_FRAME) != 0) {
            throw new IOException("Partial encoded frames cannot be mapped as video samples");
        }
        boolean keyFrame = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        if (writtenSamples == 0 && !keyFrame) {
            droppedBeforeFirstKeyFrame++;
            return;
        }
        ByteBuffer data = codec.getOutputBuffer(outputIndex);
        if (data == null) {
            throw new IOException("Encoder returned an empty output buffer");
        }
        long firstPtsUs = writtenSamples == 0 ? codecPtsUs : firstWrittenCodecPtsUs;
        long videoPtsUs = codecPtsUs - firstPtsUs;
        MediaCodec.BufferInfo sampleInfo = new MediaCodec.BufferInfo();
        sampleInfo.set(info.offset, info.size, videoPtsUs,
                info.flags & ~MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        data.position(info.offset);
        data.limit(info.offset + info.size);
        // Hash compressed bytes with an independent cursor; the muxer still receives the
        // original buffer and offset. Content identity survives MP4 timestamp quantization.
        String sampleSha256 = AvcSampleIdentity.sha256(data.duplicate());
        muxer.writeSampleData(trackIndex, data, sampleInfo);
        // Only acknowledge samples accepted by the muxer; codec-config buffers are not frames.
        timeline.addSample(codecPtsUs, videoPtsUs, keyFrame, sampleSha256);
        firstWrittenCodecPtsUs = firstPtsUs;
        writtenSamples++;
    }

    private void releaseResources() {
        try {
            codec.stop();
        } catch (Exception error) {
            failure.compareAndSet(null, error);
        }
        try {
            codec.release();
        } catch (Exception error) {
            failure.compareAndSet(null, error);
        }
        try {
            inputSurface.release();
        } catch (Exception error) {
            failure.compareAndSet(null, error);
        }
        try {
            if (muxerStarted) {
                muxer.stop();
            }
        } catch (Exception error) {
            // An aborted/empty muxer may fail to stop; an ordinary finish must report it.
            if (!abortRequested || writtenSamples > 0) {
                failure.compareAndSet(null, error);
            }
        } finally {
            try {
                muxer.release();
            } catch (Exception error) {
                failure.compareAndSet(null, error);
            }
        }
    }

    private static MediaCodec createHardwareEncoder(MediaFormat format) throws IOException {
        for (MediaCodecInfo candidate : new MediaCodecList(MediaCodecList.REGULAR_CODECS)
                .getCodecInfos()) {
            if (!candidate.isEncoder()) {
                continue;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (!candidate.isHardwareAccelerated()) {
                    continue;
                }
            } else {
                String name = candidate.getName().toLowerCase(Locale.ROOT);
                if (name.startsWith("omx.google.") || name.startsWith("c2.android.")
                        || name.contains(".sw.")) {
                    continue;
                }
            }
            for (String type : candidate.getSupportedTypes()) {
                if (MIME.equalsIgnoreCase(type)
                        && candidate.getCapabilitiesForType(type).isFormatSupported(format)) {
                    return MediaCodec.createByCodecName(candidate.getName());
                }
            }
        }
        throw new IOException("No hardware AVC Surface encoder supports the recording format");
    }

    /** Constructor failure: ownership has not yet transferred to the drain thread. */
    private static void releaseUnstarted(MediaCodec codec, MediaMuxer muxer, Surface surface,
                                         boolean codecStarted, Exception original) {
        if (codec != null) {
            if (codecStarted) {
                try {
                    codec.stop();
                } catch (Exception error) {
                    original.addSuppressed(error);
                }
            }
            try {
                codec.release();
            } catch (Exception error) {
                original.addSuppressed(error);
            }
        }
        if (surface != null) {
            try {
                surface.release();
            } catch (Exception error) {
                original.addSuppressed(error);
            }
        }
        if (muxer != null) {
            try {
                muxer.release();
            } catch (Exception error) {
                original.addSuppressed(error);
            }
        }
    }
}
