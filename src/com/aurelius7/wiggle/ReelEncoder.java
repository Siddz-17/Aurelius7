package com.aurelius7.wiggle;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;

/** Streaming H.264 writer: post bitmaps (all exactly w x h) with a hold time each; used by the reel editor. */
public final class ReelEncoder {
    private final MediaCodec enc;
    private final MediaMuxer mux;
    private final Surface in;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect dst;
    private final ArrayList<Long> pts = new ArrayList<Long>();
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private long tUs;
    private int track = -1, written;
    private boolean started, closed;

    public ReelEncoder(File out, int w, int h) throws Exception {
        MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", w, h);
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 8000000);
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        dst = new Rect(0, 0, w, h);
        MediaCodec e = MediaCodec.createEncoderByType("video/avc");
        MediaMuxer m = null; Surface s = null;
        try {
            e.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            s = e.createInputSurface();
            e.start();
            m = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (Exception ex) {
            try { e.release(); } catch (Exception ignored) {}
            if (s != null) s.release();
            throw ex;
        }
        enc = e; mux = m; in = s;
    }

    /** Show this bitmap for holdMs milliseconds. */
    public void add(Bitmap b, int holdMs) {
        Canvas c = in.lockHardwareCanvas();
        c.drawColor(0xFF000000);
        c.drawBitmap(b, null, dst, paint);
        in.unlockCanvasAndPost(c);
        pts.add(tUs);
        tUs += Math.max(1, holdMs) * 1000L;
        long t0 = System.currentTimeMillis();
        while (written < pts.size() - 1 && System.currentTimeMillis() - t0 < 1500) drain(false);
        drain(false);
    }

    public void finish() {
        if (closed) return;
        try {
            enc.signalEndOfInputStream();
            drain(true);
            if (!started || written == 0) throw new RuntimeException("Video encoder produced no frames");
        } finally { close(); }
    }

    public void close() {
        if (closed) return;
        closed = true;
        try { enc.stop(); } catch (Exception ignored) {}
        enc.release();
        in.release();
        try { mux.stop(); } catch (Exception ignored) {}
        mux.release();
    }

    private void drain(boolean eos) {
        long deadline = System.currentTimeMillis() + (eos ? 5000 : 0);
        while (true) {
            int idx = enc.dequeueOutputBuffer(info, eos ? 10000 : 2000);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!eos || System.currentTimeMillis() > deadline) return;
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = mux.addTrack(enc.getOutputFormat());
                mux.start();
                started = true;
            } else if (idx >= 0) {
                ByteBuffer buf = enc.getOutputBuffer(idx);
                boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (!config && info.size > 0 && started && buf != null && written < pts.size()) {
                    buf.position(info.offset);
                    buf.limit(info.offset + info.size);
                    info.presentationTimeUs = pts.get(written);
                    mux.writeSampleData(track, buf, info);
                    written++;
                }
                enc.releaseOutputBuffer(idx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
                if (!eos) return;
            }
        }
    }
}
