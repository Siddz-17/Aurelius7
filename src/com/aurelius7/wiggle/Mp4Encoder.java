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

/** Encodes the loop to an H.264 MP4 (repeated to >= minMs so Instagram/Reels accept it). */
public final class Mp4Encoder {
    private Mp4Encoder() {}

    public static void encode(Bitmap[] frames, int[] seq, int frameMs, int minMs, File out) throws Exception {
        int w = frames[0].getWidth(), h = frames[0].getHeight();
        float sc = Math.min(1f, 720f / w);
        int vw = Math.max(16, Math.round(w * sc / 16f) * 16);
        int vh = Math.max(16, Math.round(h * sc / 16f) * 16);

        MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", vw, vh);
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 8000000);
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        MediaCodec enc = MediaCodec.createEncoderByType("video/avc");
        MediaMuxer mux = null;
        Surface in = null;
        try {
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            in = enc.createInputSurface();
            enc.start();
            mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            int cycles = Math.max(1, (int) Math.ceil(minMs / (double) (seq.length * frameMs)));
            int total = cycles * seq.length;
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            Rect dst = new Rect(0, 0, vw, vh);
            State st = new State();
            st.frameUs = frameMs * 1000L;
            for (int i = 0; i < total; i++) {
                Canvas c = in.lockHardwareCanvas();
                c.drawColor(0xFF000000);
                c.drawBitmap(frames[seq[i % seq.length]], null, dst, p);
                in.unlockCanvasAndPost(c);
                st.posted++;
                // throttle so the encoder never drops a surface frame
                long t0 = System.currentTimeMillis();
                while (st.written < st.posted - 1 && System.currentTimeMillis() - t0 < 1500) drain(enc, mux, st, false);
                drain(enc, mux, st, false);
            }
            enc.signalEndOfInputStream();
            drain(enc, mux, st, true);
        } finally {
            try { enc.stop(); } catch (Exception ignored) {}
            enc.release();
            if (in != null) in.release();
            if (mux != null) { try { mux.stop(); } catch (Exception ignored) {} mux.release(); }
        }
    }

    private static final class State { int track = -1; boolean started; int posted, written; long frameUs; }

    private static void drain(MediaCodec enc, MediaMuxer mux, State st, boolean eos) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long deadline = System.currentTimeMillis() + (eos ? 5000 : 0);
        while (true) {
            int idx = enc.dequeueOutputBuffer(info, eos ? 10000 : 2000);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!eos || System.currentTimeMillis() > deadline) return;
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                st.track = mux.addTrack(enc.getOutputFormat());
                mux.start();
                st.started = true;
            } else if (idx >= 0) {
                ByteBuffer buf = enc.getOutputBuffer(idx);
                boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (!config && info.size > 0 && st.started && buf != null) {
                    buf.position(info.offset);
                    buf.limit(info.offset + info.size);
                    info.presentationTimeUs = st.written * st.frameUs; // exact loop timing
                    mux.writeSampleData(st.track, buf, info);
                    st.written++;
                }
                enc.releaseOutputBuffer(idx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
                if (!eos) return;
            }
        }
    }
}
