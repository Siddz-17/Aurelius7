package com.aurelius7.wiggle;

import android.graphics.Bitmap;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;

/** Minimal looping GIF89a encoder: one shared median-cut palette (no flicker between views) + LZW. */
public final class GifEncoder {
    private GifEncoder() {}

    public static void encode(Bitmap[] frames, int[] order, int delayCs, OutputStream out) throws IOException {
        int w = frames[0].getWidth(), h = frames[0].getHeight();
        int[][] pix = new int[frames.length][];
        int[] hist = new int[32768];
        for (int i = 0; i < frames.length; i++) {
            pix[i] = new int[w * h];
            frames[i].getPixels(pix[i], 0, w, 0, 0, w, h);
            for (int c : pix[i]) hist[bin(c)]++;
        }
        int[] pal = medianCut(hist, 256);
        byte[] map = new byte[32768];
        for (int bIdx = 0; bIdx < 32768; bIdx++) {
            if (hist[bIdx] == 0) continue;
            int r = ((bIdx >> 10) & 31) * 8 + 4, g = ((bIdx >> 5) & 31) * 8 + 4, b = (bIdx & 31) * 8 + 4;
            int best = 0, bd = Integer.MAX_VALUE;
            for (int p = 0; p < 256; p++) {
                int c = pal[p];
                int dr = ((c >> 16) & 255) - r, dg = ((c >> 8) & 255) - g, db = (c & 255) - b;
                int d = 2 * dr * dr + 4 * dg * dg + db * db;
                if (d < bd) { bd = d; best = p; }
            }
            map[bIdx] = (byte) best;
        }

        // Header + logical screen + global palette
        out.write(new byte[]{'G', 'I', 'F', '8', '9', 'a'});
        le16(out, w); le16(out, h);
        out.write(0xF7); out.write(0); out.write(0);
        for (int p = 0; p < 256; p++) {
            out.write((pal[p] >> 16) & 255); out.write((pal[p] >> 8) & 255); out.write(pal[p] & 255);
        }
        // NETSCAPE loop forever
        out.write(new byte[]{0x21, (byte) 0xFF, 0x0B, 'N', 'E', 'T', 'S', 'C', 'A', 'P', 'E', '2', '.', '0', 0x03, 0x01, 0, 0, 0});

        byte[][] idx = new byte[frames.length][];
        for (int i = 0; i < frames.length; i++) {
            idx[i] = new byte[w * h];
            int[] p = pix[i];
            for (int j = 0; j < p.length; j++) idx[i][j] = map[bin(p[j])];
            pix[i] = null;
        }
        for (int fi : order) {
            out.write(0x21); out.write(0xF9); out.write(4); out.write(0x04);
            le16(out, delayCs); out.write(0); out.write(0);
            out.write(0x2C); le16(out, 0); le16(out, 0); le16(out, w); le16(out, h); out.write(0);
            lzw(idx[fi], out);
        }
        out.write(0x3B);
        out.flush();
    }

    private static int bin(int c) {
        return (((c >> 19) & 31) << 10) | (((c >> 11) & 31) << 5) | ((c >> 3) & 31);
    }

    private static void le16(OutputStream o, int v) throws IOException { o.write(v & 255); o.write((v >> 8) & 255); }

    // ---------- median cut ----------
    private static int ch(int b, int c) { return c == 0 ? (b >> 10) & 31 : (c == 1 ? (b >> 5) & 31 : b & 31); }

    private static int[] medianCut(int[] hist, int maxColors) {
        int cnt = 0;
        for (int v : hist) if (v > 0) cnt++;
        int[] bins = new int[cnt];
        int k = 0;
        for (int i = 0; i < hist.length; i++) if (hist[i] > 0) bins[k++] = i;
        ArrayList<int[]> boxes = new ArrayList<int[]>(); // {start, end}
        boxes.add(new int[]{0, cnt});
        while (boxes.size() < maxColors) {
            int bestBox = -1; long bestScore = 0; int bestCh = 0;
            for (int bi = 0; bi < boxes.size(); bi++) {
                int[] bx = boxes.get(bi);
                if (bx[1] - bx[0] < 2) continue;
                long pop = 0;
                int[] mn = {31, 31, 31}, mx = {0, 0, 0};
                for (int i = bx[0]; i < bx[1]; i++) {
                    int b = bins[i]; pop += hist[b];
                    for (int c = 0; c < 3; c++) { int v = ch(b, c); if (v < mn[c]) mn[c] = v; if (v > mx[c]) mx[c] = v; }
                }
                int c = 0, range = mx[0] - mn[0];
                if (mx[1] - mn[1] > range) { c = 1; range = mx[1] - mn[1]; }
                if (mx[2] - mn[2] > range) { c = 2; range = mx[2] - mn[2]; }
                if (range == 0) continue;
                long score = pop * range;
                if (score > bestScore) { bestScore = score; bestBox = bi; bestCh = c; }
            }
            if (bestBox < 0) break;
            int[] bx = boxes.get(bestBox);
            long[] tmp = new long[bx[1] - bx[0]];
            for (int i = bx[0]; i < bx[1]; i++) tmp[i - bx[0]] = ((long) ch(bins[i], bestCh) << 16) | bins[i];
            Arrays.sort(tmp);
            long total = 0;
            for (int i = 0; i < tmp.length; i++) { bins[bx[0] + i] = (int) (tmp[i] & 0xFFFF); total += hist[bins[bx[0] + i]]; }
            long acc = 0; int split = bx[0] + 1;
            for (int i = bx[0]; i < bx[1] - 1; i++) {
                acc += hist[bins[i]];
                if (acc * 2 >= total) { split = i + 1; break; }
                split = i + 2;
            }
            if (split >= bx[1]) split = bx[1] - 1;
            boxes.set(bestBox, new int[]{bx[0], split});
            boxes.add(new int[]{split, bx[1]});
        }
        int[] pal = new int[maxColors];
        for (int bi = 0; bi < boxes.size(); bi++) {
            int[] bx = boxes.get(bi);
            long r = 0, g = 0, b = 0, pop = 0;
            for (int i = bx[0]; i < bx[1]; i++) {
                int bb = bins[i], hc = hist[bb];
                r += (long) (ch(bb, 0) * 8 + 4) * hc; g += (long) (ch(bb, 1) * 8 + 4) * hc; b += (long) (ch(bb, 2) * 8 + 4) * hc;
                pop += hc;
            }
            if (pop == 0) continue;
            pal[bi] = (int) ((r / pop) << 16 | (g / pop) << 8 | (b / pop));
        }
        return pal;
    }

    // ---------- LZW (direct port of the classic compress/AnimatedGifEncoder logic) ----------
    private static final int HSIZE = 5003;

    private static final class Lzw {
        final int initBits = 9, clearCode = 256, eofCode = 257, maxMaxCode = 4096;
        int nBits = initBits, maxCode = (1 << initBits) - 1, freeEnt = 258;
        boolean clearFlg = false;
        final BitOut bo;
        Lzw(BitOut bo) { this.bo = bo; }
        void output(int code) throws IOException {
            bo.write(code, nBits);
            if (freeEnt > maxCode || clearFlg) {
                if (clearFlg) { nBits = initBits; maxCode = (1 << nBits) - 1; clearFlg = false; }
                else { nBits++; maxCode = nBits == 12 ? maxMaxCode : (1 << nBits) - 1; }
            }
            if (code == eofCode) bo.flush();
        }
    }

    private static void lzw(byte[] data, OutputStream out) throws IOException {
        out.write(8);
        BitOut bo = new BitOut(out);
        Lzw z = new Lzw(bo);
        int[] htab = new int[HSIZE], codetab = new int[HSIZE];
        Arrays.fill(htab, -1);
        z.output(z.clearCode);
        int ent = data[0] & 255;
        outer:
        for (int k = 1; k < data.length; k++) {
            int c = data[k] & 255;
            int fcode = (c << 12) + ent;
            int i = (c << 4) ^ ent;
            if (htab[i] == fcode) { ent = codetab[i]; continue; }
            if (htab[i] >= 0) {
                int disp = i == 0 ? 1 : HSIZE - i;
                do {
                    i -= disp;
                    if (i < 0) i += HSIZE;
                    if (htab[i] == fcode) { ent = codetab[i]; continue outer; }
                } while (htab[i] >= 0);
            }
            z.output(ent);
            ent = c;
            if (z.freeEnt < z.maxMaxCode) {
                codetab[i] = z.freeEnt++;
                htab[i] = fcode;
            } else {
                Arrays.fill(htab, -1);
                z.freeEnt = z.clearCode + 2;
                z.clearFlg = true;
                z.output(z.clearCode);
            }
        }
        z.output(ent);
        z.output(z.eofCode);
        out.write(0);
    }

    private static final class BitOut {
        final OutputStream out; final byte[] block = new byte[255]; int blen = 0; int acc = 0, accBits = 0;
        BitOut(OutputStream o) { out = o; }
        void write(int code, int bits) throws IOException {
            acc |= code << accBits; accBits += bits;
            while (accBits >= 8) { put(acc & 255); acc >>>= 8; accBits -= 8; }
        }
        void put(int b) throws IOException {
            block[blen++] = (byte) b;
            if (blen == 255) { out.write(255); out.write(block, 0, 255); blen = 0; }
        }
        void flush() throws IOException {
            if (accBits > 0) { put(acc & 255); acc = 0; accBits = 0; }
            if (blen > 0) { out.write(blen); out.write(block, 0, blen); blen = 0; }
        }
    }
}
