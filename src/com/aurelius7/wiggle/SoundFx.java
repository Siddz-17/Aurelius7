package com.aurelius7.wiggle;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.util.Random;

/** Mechanical camera sounds, synthesised at runtime (no audio files): shutter clack + film advance. */
public final class SoundFx {
    private static final int SR = 44100;
    private final AudioManager am;
    private AudioTrack shutter, advance;

    public SoundFx(Context c) {
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        try {
            shutter = track(makeShutter());
            advance = track(makeAdvance());
        } catch (Exception ignored) {}
    }

    private static AudioTrack track(short[] pcm) {
        @SuppressWarnings("deprecation")
        AudioTrack t = new AudioTrack(AudioManager.STREAM_SYSTEM, SR, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, pcm.length * 2, AudioTrack.MODE_STATIC);
        t.write(pcm, 0, pcm.length);
        return t;
    }

    /** One mechanical click: noise transient + a short low body resonance. */
    private static void click(float[] buf, int at, float amp, float bodyHz, Random r) {
        int n = (int) (SR * 0.045f);
        for (int i = 0; i < n && at + i < buf.length; i++) {
            float t = i / (float) SR;
            float noise = (r.nextFloat() * 2 - 1) * (float) Math.exp(-t * 900);
            float body = (float) (Math.sin(2 * Math.PI * bodyHz * t) * Math.exp(-t * 120));
            float ring = (float) (Math.sin(2 * Math.PI * 2900 * t) * Math.exp(-t * 400)) * 0.25f;
            buf[at + i] += amp * (noise * 0.9f + body * 0.6f + ring);
        }
    }

    private static short[] toPcm(float[] f) {
        short[] s = new short[f.length];
        for (int i = 0; i < f.length; i++) {
            float v = Math.max(-1f, Math.min(1f, f[i] * 0.85f));
            s[i] = (short) (v * 32767);
        }
        return s;
    }

    private static short[] makeShutter() {
        Random r = new Random(11);
        float[] b = new float[(int) (SR * 0.16f)];
        click(b, 0, 0.9f, 140, r);                       // curtain open
        click(b, (int) (SR * 0.052f), 0.75f, 190, r);    // curtain close
        return toPcm(b);
    }

    private static short[] makeAdvance() {
        Random r = new Random(23);
        float[] b = new float[(int) (SR * 0.42f)];
        for (int k = 0; k < 8; k++) click(b, (int) (SR * (0.02f + k * 0.042f)), 0.35f - k * 0.025f, 320 + k * 15, r);
        // soft lever whir underneath
        for (int i = 0; i < b.length; i++) {
            float t = i / (float) SR;
            float env = (float) Math.sin(Math.PI * Math.min(1f, t / 0.38f));
            b[i] += (r.nextFloat() * 2 - 1) * 0.05f * env;
        }
        return toPcm(b);
    }

    private boolean audible() {
        return am == null || am.getRingerMode() == AudioManager.RINGER_MODE_NORMAL;
    }

    private void play(AudioTrack t) {
        if (t == null || !audible()) return;
        try { t.stop(); t.reloadStaticData(); t.play(); } catch (Exception ignored) {}
    }

    public void shutter() { play(shutter); }
    public void advance() { play(advance); }

    public void release() {
        try { if (shutter != null) shutter.release(); } catch (Exception ignored) {}
        try { if (advance != null) advance.release(); } catch (Exception ignored) {}
    }
}
