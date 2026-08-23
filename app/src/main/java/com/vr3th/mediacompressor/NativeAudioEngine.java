package com.vr3th.mediacompressor;

import android.os.Build;
import android.media.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Platform-only audio engine. Normalizes decoded PCM to signed 16-bit before AAC encoding. */
final class NativeAudioEngine {
    private NativeAudioEngine() {}

    static void trim(File input, File output, long fromUs, long toUs, boolean reverse,
                      AtomicBoolean cancel) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null, enc = null;
        MediaMuxer mux = null;
        try {
            ex.setDataSource(input.getAbsolutePath());
            int track = -1;
            MediaFormat sf = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) { track = i; sf = f; break; }
            }
            if (track < 0 || sf == null) throw new IOException("No audio track found");

            String inMime = sf.getString(MediaFormat.KEY_MIME);
            dec = MediaCodec.createDecoderByType(inMime);
            dec.configure(sf, null, null, 0);
            dec.start();
            ex.selectTrack(track);

            int rate = sf.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? sf.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 48000;
            int inCh = sf.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? Math.max(1, sf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)) : 2;
            int outCh = Math.min(2, inCh);
            long safeStart = Math.max(0, fromUs);
            long safeEnd = Math.max(safeStart, toUs);
            ex.seekTo(safeStart, MediaExtractor.SEEK_TO_NEXT_SYNC);

            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            boolean inputEos = false, outputEos = false;
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            int pcmEncoding = sf.containsKey(MediaFormat.KEY_PCM_ENCODING)
                    ? sf.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    : AudioFormat.ENCODING_PCM_16BIT;
            long lastPts = -1;

            while (!outputEos) {
                if (cancel.get()) throw new IOException("cancelled");
                if (!inputEos) {
                    int ii = dec.dequeueInputBuffer(10_000);
                    if (ii >= 0) {
                        ByteBuffer ib = dec.getInputBuffer(ii);
                        if (ib == null) throw new IOException("audio decoder input unavailable");
                        ib.clear();
                        int n = ex.readSampleData(ib, 0);
                        long pts = ex.getSampleTime();
                        if (n < 0 || pts < 0 || pts >= safeEnd) {
                            dec.queueInputBuffer(ii, 0, 0, Math.max(0, pts), MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEos = true;
                        } else {
                            dec.queueInputBuffer(ii, 0, n, Math.max(0, pts), ex.getSampleFlags());
                            lastPts = pts;
                            ex.advance();
                        }
                    }
                }

                int oi = dec.dequeueOutputBuffer(bi, 10_000);
                if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) continue;
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat actual = dec.getOutputFormat();
                    if (actual.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (actual.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        inCh = Math.max(1, actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                        outCh = Math.min(2, inCh);
                    }
                    if (actual.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = actual.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    continue;
                }
                if (oi >= 0) {
                    ByteBuffer ob = dec.getOutputBuffer(oi);
                    if (ob != null && bi.size > 0) {
                        ob.position(Math.max(0, bi.offset));
                        ob.limit(Math.min(ob.capacity(), bi.offset + bi.size));
                        byte[] raw = new byte[ob.remaining()];
                        ob.get(raw);
                        byte[] norm = normalizePcm16(raw, pcmEncoding, inCh, outCh);
                        pcm.write(norm, 0, norm.length);
                    }
                    outputEos = (bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    dec.releaseOutputBuffer(oi, false);
                }
            }

            byte[] all = pcm.toByteArray();
            int bytesPerFrame = 2 * outCh;
            // The extractor was already seeked to safeStart, so the decoded buffer
            // begins at (or very near) the requested start. Do not apply safeStart
            // a second time; that was the main cause of trim failures/empty output.
            int first = 0;
            long requestedFrames = safeEnd == Long.MAX_VALUE
                    ? Long.MAX_VALUE
                    : Math.max(1, (safeEnd - safeStart) * rate / 1_000_000L);
            long maxFrames = all.length / Math.max(1, bytesPerFrame);
            long frames = Math.min(maxFrames, requestedFrames);
            int last = (int)Math.min((long)Integer.MAX_VALUE, frames * bytesPerFrame);
            byte[] slice = Arrays.copyOfRange(all, first, last);
            if (slice.length == 0) throw new IOException("No audio samples in requested range");
            if (reverse) reverseFrames(slice, bytesPerFrame);

            MediaFormat ef = MediaFormat.createAudioFormat("audio/mp4a-latm", rate, outCh);
            ef.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
            if (Build.VERSION.SDK_INT >= 21) ef.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            ef.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.max(4096, Math.min(slice.length, 65536)));
            enc = MediaCodec.createEncoderByType("audio/mp4a-latm");
            enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();

            mux = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int at = -1;
            boolean started = false;
            int pos = 0;
            long pts = 0;
            MediaCodec.BufferInfo eo = new MediaCodec.BufferInfo();

            while (pos < slice.length) {
                if (cancel.get()) throw new IOException("cancelled");
                int ii = enc.dequeueInputBuffer(10_000);
                if (ii >= 0) {
                    ByteBuffer ib = enc.getInputBuffer(ii);
                    if (ib == null) throw new IOException("AAC encoder input unavailable");
                    ib.clear();
                    int n = Math.min(ib.remaining(), slice.length - pos);
                    // Keep complete PCM frames only.
                    n -= n % bytesPerFrame;
                    if (n <= 0) throw new IOException("AAC encoder buffer too small");
                    ib.put(slice, pos, n);
                    enc.queueInputBuffer(ii, 0, n, pts, 0);
                    pts += (long)(n / bytesPerFrame) * 1_000_000L / rate;
                    pos += n;
                }
                drainEncoder(enc, mux, eo, Holder.of(at, started));
                if (!started && Holder.lastStarted) { at = Holder.lastTrack; started = true; }
            }

            int ii;
            while ((ii = enc.dequeueInputBuffer(10_000)) < 0) drainEncoder(enc, mux, eo, Holder.of(at, started));
            enc.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            boolean eos = false;
            while (!eos) {
                int oo = enc.dequeueOutputBuffer(eo, 10_000);
                if (oo == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!started) { at = mux.addTrack(enc.getOutputFormat()); mux.start(); started = true; }
                } else if (oo >= 0) {
                    if (started && eo.size > 0) write(enc, mux, at, eo, oo);
                    eos = (eo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    enc.releaseOutputBuffer(oo, false);
                }
            }
            if (!started) throw new IOException("AAC encoder produced no output");
            mux.stop();
            mux.release();
            mux = null;
        } finally {
            try { ex.release(); } catch (Exception ignored) {}
            try { if (dec != null) { dec.stop(); dec.release(); } } catch (Exception ignored) {}
            try { if (enc != null) { enc.stop(); enc.release(); } } catch (Exception ignored) {}
            try { if (mux != null) mux.release(); } catch (Exception ignored) {}
        }
    }

    private static final class Holder {
        static boolean lastStarted;
        static int lastTrack;
        static Holder of(int track, boolean started) { lastStarted = started; lastTrack = track; return new Holder(); }
    }

    private static void drainEncoder(MediaCodec enc, MediaMuxer mux, MediaCodec.BufferInfo info, Holder h) {
        while (true) {
            int oo = enc.dequeueOutputBuffer(info, 0);
            if (oo == MediaCodec.INFO_TRY_AGAIN_LATER) return;
            if (oo == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!h.lastStarted) {
                    h.lastTrack = mux.addTrack(enc.getOutputFormat());
                    mux.start();
                    h.lastStarted = true;
                }
                continue;
            }
            if (oo >= 0) {
                if (h.lastStarted && info.size > 0) write(enc, mux, h.lastTrack, info, oo);
                enc.releaseOutputBuffer(oo, false);
            }
        }
    }

    private static byte[] normalizePcm16(byte[] raw, int encoding, int inCh, int outCh) {
        if (inCh <= 0) inCh = 1;
        int bytesPerSample;
        if (encoding == AudioFormat.ENCODING_PCM_8BIT) bytesPerSample = 1;
        else if (encoding == AudioFormat.ENCODING_PCM_FLOAT) bytesPerSample = 4;
        else if (Build.VERSION.SDK_INT >= 24 && encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED) bytesPerSample = 3;
        else if (Build.VERSION.SDK_INT >= 23 && encoding == AudioFormat.ENCODING_PCM_32BIT) bytesPerSample = 4;
        else bytesPerSample = 2;
        int frameBytes = bytesPerSample * inCh;
        int frames = raw.length / frameBytes;
        byte[] out = new byte[frames * outCh * 2];
        ByteBuffer dst = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < outCh; c++) {
                double sum = 0;
                int count = (inCh <= 2) ? 1 : (c == 0 ? (inCh + 1) / 2 : inCh / 2);
                if (inCh <= 2) {
                    int src = c;
                    if (src >= inCh) src = inCh - 1;
                    sum = sample16(raw, (f * inCh + src) * bytesPerSample, encoding);
                } else {
                    int seen = 0;
                    for (int src = c; src < inCh; src += 2) {
                        sum += sample16(raw, (f * inCh + src) * bytesPerSample, encoding);
                        seen++;
                    }
                    if (seen > 0) sum /= seen;
                }
                int v = (int)Math.max(-32768, Math.min(32767, Math.round(sum)));
                dst.putShort((short)v);
            }
        }
        return out;
    }

    private static int sample16(byte[] b, int off, int enc) {
        if (off < 0 || off >= b.length) return 0;
        if (enc == AudioFormat.ENCODING_PCM_8BIT) return ((b[off] & 0xff) - 128) << 8;
        if (enc == AudioFormat.ENCODING_PCM_FLOAT) {
            if (off + 3 >= b.length) return 0;
            float x = ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
            return (int)Math.max(-32768, Math.min(32767, x * 32767f));
        }
        if (Build.VERSION.SDK_INT >= 24 && enc == AudioFormat.ENCODING_PCM_24BIT_PACKED) {
            if (off + 2 >= b.length) return 0;
            int x = (b[off] & 0xff) | ((b[off+1] & 0xff) << 8) | ((b[off+2]) << 16);
            if ((x & 0x800000) != 0) x |= 0xff000000;
            return x >> 8;
        }
        if (Build.VERSION.SDK_INT >= 23 && enc == AudioFormat.ENCODING_PCM_32BIT) {
            if (off + 3 >= b.length) return 0;
            int x = (b[off] & 0xff) | ((b[off+1] & 0xff) << 8) | ((b[off+2] & 0xff) << 16) | (b[off+3] << 24);
            return x >> 16;
        }
        if (off + 1 >= b.length) return 0;
        return (short)((b[off] & 0xff) | (b[off+1] << 8));
    }

    private static void reverseFrames(byte[] b, int frame) {
        for (int l = 0, r = b.length - frame; l < r; l += frame, r -= frame) {
            for (int i = 0; i < frame; i++) { byte t = b[l+i]; b[l+i] = b[r+i]; b[r+i] = t; }
        }
    }

    private static void write(MediaCodec e, MediaMuxer m, int tr, MediaCodec.BufferInfo bi, int index) {
        ByteBuffer b = e.getOutputBuffer(index);
        if (b == null || bi.size <= 0) return;
        b.position(Math.max(0, bi.offset));
        b.limit(Math.min(b.capacity(), bi.offset + bi.size));
        m.writeSampleData(tr, b, bi);
    }
}
