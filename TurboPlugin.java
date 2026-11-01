package app.onelly.edit;

import android.content.ContentValues;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import java.nio.ByteBuffer;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.net.Uri;
import android.os.Handler;
import android.content.pm.ServiceInfo;
import android.view.WindowManager;
import android.os.PowerManager;
import android.os.IBinder;
import android.content.Intent;
import android.content.Context;
import android.app.PendingIntent;
import android.app.NotificationManager;
import android.app.NotificationChannel;
import android.app.Notification;
import android.os.SystemClock;
import android.os.Looper;

import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.GlProgram;
import androidx.media3.effect.GlShaderProgram;
import androidx.media3.effect.GlEffect;
import android.opengl.GLUtils;
import android.opengl.GLES20;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.effect.RgbMatrix;
import androidx.media3.effect.GlMatrixTransformation;
import androidx.media3.common.audio.SonicAudioProcessor;
import androidx.media3.common.audio.ChannelMixingAudioProcessor;
import androidx.media3.common.audio.ChannelMixingMatrix;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.Brightness;
import androidx.media3.effect.Contrast;
import androidx.media3.effect.HslAdjustment;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.common.collect.ImmutableList;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Onelly Edit - TURBO export (separate from Fast / Standard export; shares no state with FastRenderPlugin).
 * Level 1: ONE clip (cut) + still overlay pictures (text / sticker / photo) + color filter.
 * Hardware decode -> GPU (Media3 effects) -> hardware H.264 encode -> MP4.
 * Every overlay picture is decoded ONCE and stays one texture while it is on screen.
 * Anything this level does not know is refused (reject), and the web side falls back to the normal export.
 * NOT compiled / tested on a device.
 */
@UnstableApi
@CapacitorPlugin(name = "Turbo")
public class TurboPlugin extends Plugin {
    private final Handler main = new Handler(Looper.getMainLooper());
    private Transformer transformer;
    private PluginCall active;
    private Layer layer;
    private final List<TurboOverlayVideo> vids = new ArrayList<>();
    private OverlayMix mixer;

    /** Turns the presentation time an effect receives into TIMELINE time (some Media3 builds hand over absolute source time). One per export piece, shared by all its effects. */
    private static final class Clock {
        final long inA, inB, offMs;
        boolean first = true, abs = false;
        long base = 0;

        Clock(long inA, long inB, long offMs) {
            this.inA = inA;
            this.inB = inB;
            this.offMs = offMs;
        }

        synchronized long relUs(long us) {
            if (first) {
                first = false;
                long m = us / 1000;
                if (inA > 2000 && Math.abs(m - inA) < 1500) {
                    abs = true;
                    base = inA;
                } else if (inB > 2000 && Math.abs(m - inB) < 1500) {
                    abs = true;
                    base = inB;
                }
            }
            return (abs ? us - base * 1000L : us) + offMs * 1000L;
        }
    }

    /* ---- ONE overlay layer: every overlay (still / moving / video) is drawn into a single frame bitmap, so the GPU gets ONE upload per frame ---- */
    /** A track of key frames (time in ms + `dim` numbers per key), linear in between. Any length: a 10 hour overlay is a handful of keys. */
    private static final class KeyTrack {
        final double[] t;
        final float[] v;
        final int off, n, dim;
        int ci = 0;

        KeyTrack(double[] t, float[] v, int off, int n, int dim) {
            this.t = t;
            this.v = v;
            this.off = off;
            this.n = Math.max(1, n);
            this.dim = dim;
        }

        /** flat = [t, v0 .. v(dim-1)] repeated per key */
        static KeyTrack fromJson(JSONArray flat, int dim) throws Exception {
            int stride = dim + 1, n = flat.length() / stride;
            if (n < 1) throw new Exception("bad key table");
            double[] t = new double[n];
            float[] v = new float[n * dim];
            for (int i = 0; i < n; i++) {
                t[i] = flat.getDouble(i * stride);
                for (int d = 0; d < dim; d++) v[i * dim + d] = (float) flat.getDouble(i * stride + 1 + d);
            }
            return new KeyTrack(t, v, 0, n, dim);
        }

        void at(double ms, float[] out) {
            int last = off + n - 1;
            if (n == 1 || ms <= t[off]) {
                System.arraycopy(v, off * dim, out, 0, dim);
                return;
            }
            if (ms >= t[last]) {
                System.arraycopy(v, last * dim, out, 0, dim);
                return;
            }
            if (ci < off) ci = off;
            if (ci > last - 1) ci = last - 1;
            while (ci < last - 1 && t[ci + 1] < ms) ci++;
            while (ci > off && t[ci] > ms) ci--;
            int i0 = ci, i1 = ci + 1;
            double span = t[i1] - t[i0];
            float k = span > 0 ? (float) ((ms - t[i0]) / span) : 0f;
            for (int d = 0; d < dim; d++) {
                float a = v[i0 * dim + d], b = v[i1 * dim + d];
                out[d] = a + (b - a) * k;
            }
        }
    }

    private interface LayerItem {
        boolean active(double ms);

        boolean ended(double ms);

        boolean dynamic();

        void draw(Canvas c, double ms, long usRel) throws Exception;

        void release();

        long drawn();
    }

    private static final class StillItem implements LayerItem {
        final String path;
        final int x, y;
        final long fromMs, toMs;
        Bitmap pic;
        long n = 0;

        StillItem(String path, int x, int y, long fromMs, long toMs) {
            this.path = path;
            this.x = x;
            this.y = y;
            this.fromMs = fromMs;
            this.toMs = toMs;
        }

        public boolean active(double ms) {
            return ms >= fromMs && ms < toMs;
        }

        public boolean ended(double ms) {
            return ms >= toMs;
        }

        public boolean dynamic() {
            return false;
        }

        public void draw(Canvas c, double ms, long usRel) {
            if (pic == null) pic = BitmapFactory.decodeFile(path);
            if (pic != null) c.drawBitmap(pic, x, y, null);
            n++;
        }

        public void release() {
            pic = null;
        }

        public long drawn() {
            return n;
        }
    }

    /** A still picture that MOVES / fades / pops / slides. It comes in tiles; only the tiles that are on screen are decoded. */
    private static final class AnimItem implements LayerItem {
        final String[] paths;
        final float[] ox, oy, tw, th;
        final int fw, fh;
        final long fromMs, toMs;
        final KeyTrack kt; // keys: x, y, scale, rotation (deg), alpha
        final float[] cur = new float[5];
        final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        final Bitmap[] bmp;
        long n = 0;

        AnimItem(String[] paths, float[] ox, float[] oy, float[] tw, float[] th, int fw, int fh, long fromMs, long toMs, KeyTrack kt) {
            this.paths = paths;
            this.ox = ox;
            this.oy = oy;
            this.tw = tw;
            this.th = th;
            this.fw = fw;
            this.fh = fh;
            this.fromMs = fromMs;
            this.toMs = toMs;
            this.kt = kt;
            this.bmp = new Bitmap[paths.length];
        }

        public boolean active(double ms) {
            return ms >= fromMs && ms < toMs;
        }

        public boolean ended(double ms) {
            return ms >= toMs;
        }

        public boolean dynamic() {
            return true;
        }

        public void draw(Canvas c, double ms, long usRel) {
            kt.at(ms, cur);
            float x = cur[0], y = cur[1], s = cur[2], r = cur[3], a = cur[4];
            paint.setAlpha(Math.max(0, Math.min(255, Math.round(a * 255f))));
            boolean plain = Math.abs(s - 1f) < 0.003f && Math.abs(r) < 0.05f;
            double rad = Math.toRadians(r), cs = Math.cos(rad), sn = Math.sin(rad);
            boolean any = false;
            for (int q = 0; q < paths.length; q++) {
                // is this tile on screen? (circle around the tile's centre, after scale + turn)
                double lx = (-ox[q] + tw[q] / 2f) * s, ly = (-oy[q] + th[q] / 2f) * s;
                double cx = x + lx * cs - ly * sn, cy = y + lx * sn + ly * cs;
                double R = Math.hypot(tw[q], th[q]) / 2.0 * Math.abs(s) + 2;
                if (cx + R < 0 || cx - R > fw || cy + R < 0 || cy - R > fh) {
                    bmp[q] = null; // off screen: give the memory back
                    continue;
                }
                if (bmp[q] == null) bmp[q] = BitmapFactory.decodeFile(paths[q]);
                if (bmp[q] == null) continue;
                if (plain) { // plain move / fade: a simple copy (much cheaper than a scaled, turned draw)
                    paint.setFilterBitmap(false);
                    c.drawBitmap(bmp[q], Math.round(x - ox[q]), Math.round(y - oy[q]), paint);
                    paint.setFilterBitmap(true);
                } else {
                    c.save();
                    c.translate(x, y);
                    c.rotate(r);
                    c.scale(s, s);
                    c.drawBitmap(bmp[q], -ox[q], -oy[q], paint);
                    c.restore();
                }
                any = true;
            }
            if (any) n++;
        }

        public void release() {
            for (int q = 0; q < bmp.length; q++) bmp[q] = null;
        }

        public long drawn() {
            return n;
        }
    }

    /** Wipe transition: a black cover that comes in from the right edge, sampled per frame. */
    private static final class WipeItem implements LayerItem {
        final long fromMs, toMs;
        final double t0Ms, stepMs;
        final float[] w;
        final int fw, fh;
        final Paint paint = new Paint();
        long n = 0;

        WipeItem(int fw, int fh, long fromMs, long toMs, double t0Ms, double stepMs, float[] w) {
            this.fw = fw;
            this.fh = fh;
            this.fromMs = fromMs;
            this.toMs = toMs;
            this.t0Ms = t0Ms;
            this.stepMs = Math.max(1, stepMs);
            this.w = w;
            paint.setColor(Color.BLACK);
        }

        public boolean active(double ms) {
            return ms >= fromMs && ms < toMs;
        }

        public boolean ended(double ms) {
            return ms >= toMs;
        }

        public boolean dynamic() {
            return true;
        }

        public void draw(Canvas c, double ms, long usRel) {
            double f = (ms - t0Ms) / stepMs;
            if (f < 0) f = 0;
            if (f > w.length - 1) f = w.length - 1;
            int i0 = (int) Math.floor(f), i1 = Math.min(w.length - 1, i0 + 1);
            float v = w[i0] + (w[i1] - w[i0]) * (float) (f - i0);
            if (v < 1f) c.drawRect(fw * Math.max(0f, v), 0, fw, fh, paint);
            n++;
        }

        public void release() {
        }

        public long drawn() {
            return n;
        }
    }

    private static final class VidItem implements LayerItem {
        final TurboOverlayVideo vo;
        final long fromMs, toMs;
        final KeyTrack kt; // optional movement keys (x, y, scale, rotation, alpha) for a moving overlay video
        final float[] cur = new float[5];
        long n = 0;

        VidItem(TurboOverlayVideo vo, long fromMs, long toMs, KeyTrack kt) {
            this.vo = vo;
            this.fromMs = fromMs;
            this.toMs = toMs;
            this.kt = kt;
        }

        public boolean active(double ms) {
            return ms >= fromMs && ms < toMs;
        }

        public boolean ended(double ms) {
            return ms >= toMs;
        }

        public boolean dynamic() {
            return true;
        }

        public void draw(Canvas c, double ms, long usRel) throws Exception {
            if (kt == null) {
                if (vo.drawOnto(c, usRel)) n++;
                return;
            }
            kt.at(ms, cur);
            if (vo.drawOntoXf(c, usRel, cur[0], cur[1], cur[2], cur[3], cur[4])) n++;
        }

        public void release() {
            vo.closeDecoder();
        }

        public long drawn() {
            return n;
        }
    }

    private static final class Layer extends BitmapOverlay {
        final List<LayerItem> items;
        final int fw, fh;
        final Clock clock;
        final Bitmap blank = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        final boolean[] released;
        final Object lock = new Object();
        // pipeline: while the GPU / encoder work on frame N, a helper thread already draws frame N+1 (turned off by itself when the guess is often wrong)
        final java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread th = new Thread(r, "turbo-layer");
            th.setDaemon(true);
            return th;
        });
        java.util.concurrent.Future<Object[]> fut;
        boolean pipe = true;
        double prevMs = -1, stepMs = 33.3;
        long predHit = 0, predMiss = 0;
        Bitmap bufA, bufB, last;
        boolean flip = false;
        long lastSig = Long.MIN_VALUE, calls = 0, hits = 0;
        long drawNs = 0, stillNs = 0, animNs = 0, vidNs = 0, waitNs = 0;

        Layer(List<LayerItem> items, int fw, int fh, Clock clock) {
            this.items = items;
            this.fw = fw;
            this.fh = fh;
            this.clock = clock;
            this.released = new boolean[items.size()];
        }

        /** Everything that makes one frame (decides what is visible, draws it). Only one thread at a time (lock). */
        private Bitmap renderFrame(double ms, long usRel) throws Exception {
            boolean any = false, dyn = false;
            long sig = 17;
            for (int i = 0; i < items.size(); i++) {
                LayerItem it = items.get(i);
                if (it.active(ms)) {
                    any = true;
                    if (it.dynamic()) dyn = true;
                    sig = sig * 1000003L + (i + 1);
                } else if (!released[i] && it.ended(ms)) {
                    released[i] = true;
                    it.release(); // free picture / decoder as soon as the overlay is over
                }
            }
            if (!any) {
                last = null;
                lastSig = Long.MIN_VALUE;
                return blank;
            }
            if (!dyn && sig == lastSig && last != null) {
                hits++;
                return last;
            }
            long t0 = System.nanoTime();
            if (bufA == null) {
                bufA = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888);
                bufB = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888);
            }
            flip = !flip; // a NEW instance whenever the picture changes, so the GPU texture is refreshed
            Bitmap out = flip ? bufA : bufB;
            out.eraseColor(0);
            Canvas c = new Canvas(out);
            for (int i = 0; i < items.size(); i++) {
                LayerItem it = items.get(i);
                if (!it.active(ms)) continue;
                long s0 = System.nanoTime();
                it.draw(c, ms, usRel);
                long d = System.nanoTime() - s0;
                if (it instanceof StillItem) stillNs += d;
                else if (it instanceof AnimItem) animNs += d;
                else vidNs += d;
            }
            last = out;
            lastSig = sig;
            hits++;
            drawNs += System.nanoTime() - t0;
            return out;
        }

        @Override
        public Bitmap getBitmap(long presentationTimeUs) throws VideoFrameProcessingException {
            long usRel = clock.relUs(presentationTimeUs);
            double ms = usRel / 1000.0;
            calls++;
            try {
                Bitmap res = null;
                if (fut != null) { // the frame the helper thread prepared: is it the one that is needed now?
                    long w0 = System.nanoTime();
                    Object[] fr = null;
                    try {
                        fr = fut.get();
                    } catch (Throwable e) {
                        pipe = false;
                    }
                    fut = null;
                    waitNs += System.nanoTime() - w0;
                    if (fr != null && Math.abs((Double) fr[0] - ms) <= Math.max(0.6, stepMs * 0.2)) {
                        res = (Bitmap) fr[1];
                        predHit++;
                    } else {
                        predMiss++;
                    }
                }
                if (res == null) {
                    synchronized (lock) {
                        res = renderFrame(ms, usRel);
                    }
                }
                if (prevMs >= 0) {
                    double d = ms - prevMs;
                    if (d > 0.5 && d < 500) stepMs = stepMs * 0.7 + d * 0.3;
                }
                prevMs = ms;
                if (pipe && predMiss > 40 && predMiss > predHit) pipe = false; // the guess is often wrong: stop guessing
                if (pipe) {
                    final double nms = ms + stepMs;
                    final long nus = usRel + (long) (stepMs * 1000);
                    fut = ex.submit(() -> {
                        synchronized (lock) {
                            Bitmap b = renderFrame(nms, nus);
                            return new Object[]{nms, b};
                        }
                    });
                }
                return res;
            } catch (Throwable e) {
                throw new VideoFrameProcessingException(e);
            }
        }

        void close() {
            try {
                ex.shutdownNow();
            } catch (Throwable ignore) {
            }
            fut = null;
        }

        String diag() {
            long[] c = counts();
            return c[0] + "/" + c[1] + " S" + c[2] + " A" + c[3];
        }

        /** calls, hits, stills drawn, moving drawn, drawNs, stillNs, animNs, vidNs, waitNs, predHit, predMiss */
        long[] counts() {
            long s = 0, a = 0;
            for (LayerItem it : items) {
                if (it instanceof StillItem) s += it.drawn();
                else if (it instanceof AnimItem) a += it.drawn();
            }
            return new long[]{calls, hits, s, a, drawNs, stillNs, animNs, vidNs, waitNs, predHit, predMiss};
        }

        /** Where the time went (ms): whole draw / stills / moving pictures / overlay videos / waiting for the helper thread; pipeline hits / misses. */
        String prof() {
            return "draw " + drawNs / 1000000 + " still " + stillNs / 1000000 + " anim " + animNs / 1000000 + " vid " + vidNs / 1000000
                    + " wait " + waitNs / 1000000 + " pipe " + predHit + "/" + predMiss + (pipe ? "" : " off");
        }
    }

    private static boolean hasHardwareH264() {
        try {
            MediaCodecInfo[] list = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
            for (MediaCodecInfo i : list) {
                if (!i.isEncoder()) continue;
                String n = i.getName();
                if (n.startsWith("OMX.google.") || n.startsWith("c2.android.")) continue;
                for (String t : i.getSupportedTypes()) if (t.equalsIgnoreCase("video/avc")) return true;
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    @PluginMethod
    public void caps(PluginCall call) {
        JSObject o = new JSObject();
        o.put("level", 1);
        o.put("hw", hasHardwareH264());
        call.resolve(o);
    }

    @PluginMethod
    public void render(final PluginCall call) {
        main.post(() -> doRender(call));
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        main.post(() -> {
            try {
                if (transformer != null) transformer.cancel();
            } catch (Throwable ignore) {
            }
            setPaused(false);
            mixCancel = true;
            if (par != null) {
                ParRun pr = par;
                par = null;
                pr.failed = true;
                pr.cancelAll();
                pr.cleanup();
            }
            if (layer != null) layer.close();
            transformer = null;
            for (TurboOverlayVideo v : vids) v.closeDecoder();
            PluginCall c = active;
            active = null;
            if (c != null) {
                jobState = "idle";
                stopKeepAlive();
                c.reject("cancelled");
            }
            call.resolve();
        });
    }

    /** Fade in / out of the whole video's sound (the app's Fade tool). */
    static final class FadeGain extends BaseAudioProcessor {
        private final double fi, fo, total, itemStartSec;
        private int ch = 2, sr = 48000;
        private long frame = 0;

        FadeGain(double fi, double fo, double total, double itemStartSec) {
            this.fi = fi;
            this.fo = fo;
            this.total = total;
            this.itemStartSec = itemStartSec;
        }

        @Override
        protected AudioFormat onConfigure(AudioFormat in) throws UnhandledAudioFormatException {
            if (in.encoding != C.ENCODING_PCM_16BIT) throw new UnhandledAudioFormatException(in);
            ch = in.channelCount;
            sr = in.sampleRate;
            return in;
        }

        @Override
        protected void onFlush() {
            frame = 0;
        }

        @Override
        public void queueInput(ByteBuffer input) {
            int frames = input.remaining() / (2 * ch);
            ByteBuffer out = replaceOutputBuffer(frames * 2 * ch);
            int p = input.position();
            for (int f = 0; f < frames; f++, frame++) {
                double t = itemStartSec + frame / (double) sr, g = 1;
                if (fi > 0 && t < fi) g = Math.min(g, t / fi);
                if (fo > 0 && t > total - fo) g = Math.min(g, (total - t) / fo);
                if (g < 0) g = 0;
                for (int c = 0; c < ch; c++) {
                    int v = Math.round(input.getShort(p) * (float) g);
                    p += 2;
                    out.putShort((short) Math.max(-32768, Math.min(32767, v)));
                }
            }
            input.position(p);
            out.flip();
        }
    }

    /** Adds a ready-made sound track (raw 16-bit stereo 48 kHz file, made by the web side: overlay videos + extra audio) onto the clip's own sound.
     *  The file is read in blocks, so a track of any length works. */
    static final class OverlayMix extends BaseAudioProcessor {
        private static final int BLOCK = 262144; // frames per cached block
        private final RandomAccessFile raf;
        private final long startFrame, totalFrames, itemStartFrame;
        private final byte[] raw = new byte[BLOCK * 4];
        private final short[] blk = new short[BLOCK * 2];
        private long blkIdx = -1;
        private int ch = 2, sr = 48000;
        private long frame = 0;
        long touched = 0;

        OverlayMix(File f, long startFrame) throws Exception {
            this(f, startFrame, 0);
        }

        /** itemStartFrame: where the sound this processor sees starts on the whole timeline (48 kHz frames) - for exports made of several clips */
        OverlayMix(File f, long startFrame, long itemStartFrame) throws Exception {
            this.raf = new RandomAccessFile(f, "r");
            this.startFrame = startFrame;
            this.itemStartFrame = itemStartFrame;
            this.totalFrames = f.length() / 4;
        }

        private void load(long bi) {
            try {
                raf.seek(bi * BLOCK * 4L);
                int n = raf.read(raw, 0, raw.length), got = Math.max(0, n);
                java.util.Arrays.fill(blk, (short) 0);
                ByteBuffer.wrap(raw, 0, got).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(blk, 0, got / 2);
            } catch (Exception e) {
                java.util.Arrays.fill(blk, (short) 0);
            }
            blkIdx = bi;
        }

        @Override
        protected AudioFormat onConfigure(AudioFormat in) throws UnhandledAudioFormatException {
            if (in.encoding != C.ENCODING_PCM_16BIT) throw new UnhandledAudioFormatException(in);
            ch = in.channelCount;
            sr = in.sampleRate;
            return in;
        }

        @Override
        protected void onFlush() {
            frame = 0;
        }

        @Override
        protected void onReset() {
            try {
                raf.close();
            } catch (Exception ignore) {
            }
        }

        @Override
        public void queueInput(ByteBuffer input) {
            int frames = input.remaining() / (2 * ch);
            ByteBuffer out = replaceOutputBuffer(frames * 2 * ch);
            int p = input.position();
            for (int f = 0; f < frames; f++, frame++) {
                long idx = itemStartFrame + frame * 48000L / sr - startFrame;
                int l = 0, r = 0;
                if (idx >= 0 && idx < totalFrames) {
                    long bi = idx / BLOCK;
                    if (bi != blkIdx) load(bi);
                    int o = (int) (idx - bi * BLOCK) * 2;
                    l = blk[o];
                    r = blk[o + 1];
                    touched++;
                }
                for (int c = 0; c < ch; c++) {
                    int v = input.getShort(p);
                    p += 2;
                    int add = ch == 1 ? (l + r) / 2 : (c == 0 ? l : (c == 1 ? r : 0));
                    v += add;
                    if (v > 32767) v = 32767;
                    else if (v < -32768) v = -32768;
                    out.putShort((short) v);
                }
            }
            input.position(p);
            out.flip();
        }
    }

    /* ---- the job lives in the phone's native side: it survives the page being reloaded, and the screen can go off ---- */
    private volatile String jobState = "idle"; // idle | running | done | failed
    private volatile double jobP = 0;
    private volatile JSObject jobResult;
    private volatile String jobError = "";
    private volatile String jobPhase = ""; // what the phone is doing right now (shown live by the web side)
    private PowerManager.WakeLock wl;

    private void jobStart() {
        jobState = "running";
        jobPhase = "starting";
        jobP = 0;
        jobResult = null;
        jobError = "";
        startKeepAlive();
    }

    private void resolveJob(PluginCall call, JSObject o) {
        jobResult = o;
        jobP = 100;
        jobState = "done";
        stopKeepAlive();
        try {
            call.resolve(o);
        } catch (Throwable ignore) {
        }
    }

    private void rejectJob(PluginCall call, String msg) {
        jobError = msg == null ? "" : msg;
        jobState = "failed";
        stopKeepAlive();
        try {
            call.reject(msg);
        } catch (Throwable ignore) {
        }
    }

    @PluginMethod
    public void status(PluginCall call) {
        JSObject o = new JSObject();
        o.put("state", jobState);
        o.put("p", jobP);
        o.put("phase", jobPhase);
        if ("done".equals(jobState) && jobResult != null) o.put("result", jobResult);
        if ("failed".equals(jobState)) o.put("error", jobError);
        call.resolve(o);
    }

    /** Free space for the working files (the video is copied into the app's own storage). */
    @PluginMethod
    public void space(PluginCall call) {
        JSObject o = new JSObject();
        try {
            o.put("free", getContext().getCacheDir().getUsableSpace());
        } catch (Throwable e) {
            o.put("free", -1);
        }
        call.resolve(o);
    }

    @PluginMethod
    public void clearStatus(PluginCall call) {
        if (!"running".equals(jobState)) {
            jobState = "idle";
            jobResult = null;
            jobError = "";
        }
        call.resolve();
    }

    private void startKeepAlive() {
        try {
            final android.app.Activity a = getActivity();
            if (a != null) a.runOnUiThread(() -> a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        } catch (Throwable ignore) {
        }
        try {
            PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
            if (wl == null) wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "onelly:turbo");
            if (!wl.isHeld()) wl.acquire(6L * 3600L * 1000L);
        } catch (Throwable ignore) {
        }
        try { // a foreground service keeps the app process alive while another app is open / the screen is off
            Intent i = new Intent(getContext(), TurboService.class);
            if (Build.VERSION.SDK_INT >= 26) getContext().startForegroundService(i);
            else getContext().startService(i);
        } catch (Throwable ignore) {
        }
    }

    private void stopKeepAlive() {
        try {
            final android.app.Activity a = getActivity();
            if (a != null) a.runOnUiThread(() -> a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        } catch (Throwable ignore) {
        }
        try {
            if (wl != null && wl.isHeld()) wl.release();
        } catch (Throwable ignore) {
        }
        try {
            getContext().stopService(new Intent(getContext(), TurboService.class));
        } catch (Throwable ignore) {
        }
    }

    /** Foreground service (only a notification + process priority): the export itself runs in the plugin. Needs the manifest lines the build adds. */
    public static class TurboService extends android.app.Service {
        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            try {
                String ch = "onelly_turbo";
                if (Build.VERSION.SDK_INT >= 26) {
                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    nm.createNotificationChannel(new NotificationChannel(ch, "Onelly Edit export", NotificationManager.IMPORTANCE_LOW));
                }
                Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, ch) : new Notification.Builder(this);
                b.setContentTitle("Onelly Edit · Turbo").setContentText("Exporting your video…").setSmallIcon(getApplicationInfo().icon).setOngoing(true);
                Intent li = getPackageManager().getLaunchIntentForPackage(getPackageName());
                if (li != null) b.setContentIntent(PendingIntent.getActivity(this, 0, li, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
                if (Build.VERSION.SDK_INT >= 29) startForeground(7117, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                else startForeground(7117, b.build());
            } catch (Throwable ignore) {
            }
            return START_NOT_STICKY;
        }
    }

    /* ---- Turbo's own pause: a transparent overlay that stops the frame pipeline while paused ---- */
    private static final Object PAUSE_LOCK = new Object();
    private static volatile boolean paused = false;

    private static void setPaused(boolean v) {
        synchronized (PAUSE_LOCK) {
            paused = v;
            PAUSE_LOCK.notifyAll();
        }
    }

    private static void waitIfPaused() {
        if (!paused) return;
        synchronized (PAUSE_LOCK) {
            while (paused) {
                try {
                    PAUSE_LOCK.wait(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Always the first overlay: draws nothing, but every frame passes through it, so pausing here pauses the whole export. */
    private static final class PauseGate extends BitmapOverlay {
        private final Bitmap blank = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);

        @Override
        public Bitmap getBitmap(long presentationTimeUs) {
            waitIfPaused();
            return blank;
        }
    }

    @PluginMethod
    public void pause(PluginCall call) {
        setPaused(true);
        call.resolve();
    }

    @PluginMethod
    public void resume(PluginCall call) {
        setPaused(false);
        call.resolve();
    }

    /* ---- Turbo's own fast upload: raw bytes over 127.0.0.1 (no base64), written at an exact position of a cache file ---- */
    private java.net.ServerSocket upSrv;
    private int upPort;
    private String upToken;

    private synchronized void ensureServer() throws Exception {
        if (upSrv != null && !upSrv.isClosed()) return;
        upSrv = new java.net.ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"));
        upPort = upSrv.getLocalPort();
        byte[] r = new byte[16];
        new java.security.SecureRandom().nextBytes(r);
        StringBuilder sb = new StringBuilder();
        for (byte b : r) sb.append(String.format("%02x", b));
        upToken = sb.toString();
        final java.net.ServerSocket srv = upSrv;
        Thread th = new Thread(() -> {
            while (!srv.isClosed()) {
                try {
                    final java.net.Socket s = srv.accept();
                    Thread h = new Thread(() -> handleUpload(s));
                    h.setDaemon(true);
                    h.start();
                } catch (Exception e) {
                    break;
                }
            }
        });
        th.setDaemon(true);
        th.start();
    }

    @PluginMethod
    public void upInfo(PluginCall call) {
        try {
            ensureServer();
            JSObject o = new JSObject();
            o.put("port", upPort);
            o.put("token", upToken);
            call.resolve(o);
        } catch (Exception e) {
            call.reject(e.toString());
        }
    }

    private static String readLine(java.io.InputStream in) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new java.io.IOException("header too long");
        }
        if (c == -1 && sb.length() == 0) return null;
        return sb.toString();
    }

    private void reply(OutputStream out, int code, String text) {
        try {
            String body = code == 200 ? "ok" : text;
            String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : code == 204 ? "No Content" : "Error") + "\r\n"
                    + "Access-Control-Allow-Origin: *\r\n"
                    + "Access-Control-Allow-Methods: POST, OPTIONS\r\n"
                    + "Access-Control-Allow-Headers: *\r\n"
                    + "Access-Control-Allow-Private-Network: true\r\n"
                    + "Content-Length: " + (code == 204 ? 0 : body.length()) + "\r\n"
                    + "Connection: close\r\n\r\n" + (code == 204 ? "" : body);
            out.write(head.getBytes("UTF-8"));
            out.flush();
        } catch (Exception ignore) {
        }
    }

    private void handleUpload(java.net.Socket s) {
        try {
            s.setSoTimeout(120000);
            java.io.InputStream in = new java.io.BufferedInputStream(s.getInputStream(), 1 << 16);
            OutputStream out = s.getOutputStream();
            String req = readLine(in);
            if (req == null) return;
            String[] rp = req.split(" ");
            long len = -1;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) len = Long.parseLong(line.substring(15).trim());
            }
            if (rp.length < 2) return;
            if ("OPTIONS".equals(rp[0])) {
                reply(out, 204, "");
                return;
            }
            String name = null, tok = null;
            long wpos = 0;
            int q = rp[1].indexOf('?');
            if (q >= 0) {
                for (String kv : rp[1].substring(q + 1).split("&")) {
                    int e = kv.indexOf('=');
                    if (e < 0) continue;
                    String k = kv.substring(0, e), v = java.net.URLDecoder.decode(kv.substring(e + 1), "UTF-8");
                    if ("name".equals(k)) name = v;
                    else if ("t".equals(k)) tok = v;
                    else if ("pos".equals(k)) wpos = Long.parseLong(v);
                }
            }
            if (tok == null || !tok.equals(upToken) || !safeName(name) || len < 0 || wpos < 0) {
                reply(out, 403, "no");
                return;
            }
            try (RandomAccessFile raf = new RandomAccessFile(new File(getContext().getCacheDir(), name), "rw")) {
                raf.seek(wpos);
                byte[] buf = new byte[1 << 18];
                long left = len;
                while (left > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) {
                        reply(out, 400, "short");
                        return;
                    }
                    raf.write(buf, 0, n);
                    left -= n;
                }
            }
            reply(out, 200, "ok");
        } catch (Throwable ignore) {
        } finally {
            try {
                s.close();
            } catch (Exception ignore) {
            }
        }
    }

    /* ---- Sound track builder (streaming, any length): decodes every extra sound with the phone's own decoder in 30 s windows,
       applies volume / fade / equalizer and writes ONE raw 16-bit stereo 48 kHz file. Memory use does not grow with the length. ---- */
    private static volatile boolean mixCancel = false;

    private static final class Biquad {
        double b0, b1, b2, a1, a2;
        double[] x1 = new double[2], x2 = new double[2], y1 = new double[2], y2 = new double[2];

        /** kind: 0 low shelf, 1 peaking, 2 high shelf (RBJ cookbook, the same shapes as the browser's filters) */
        Biquad(int kind, double f, double db, double fs) {
            double A = Math.pow(10, db / 40.0), w0 = 2 * Math.PI * f / fs, cs = Math.cos(w0), sn = Math.sin(w0);
            double B0, B1, B2, A0, A1, A2;
            if (kind == 1) {
                double al = sn / 2.0; // Q = 1
                B0 = 1 + al * A;
                B1 = -2 * cs;
                B2 = 1 - al * A;
                A0 = 1 + al / A;
                A1 = -2 * cs;
                A2 = 1 - al / A;
            } else {
                double al = sn / 2.0 * Math.sqrt(2.0), be = 2 * Math.sqrt(A) * al;
                if (kind == 0) {
                    B0 = A * ((A + 1) - (A - 1) * cs + be);
                    B1 = 2 * A * ((A - 1) - (A + 1) * cs);
                    B2 = A * ((A + 1) - (A - 1) * cs - be);
                    A0 = (A + 1) + (A - 1) * cs + be;
                    A1 = -2 * ((A - 1) + (A + 1) * cs);
                    A2 = (A + 1) + (A - 1) * cs - be;
                } else {
                    B0 = A * ((A + 1) + (A - 1) * cs + be);
                    B1 = -2 * A * ((A - 1) + (A + 1) * cs);
                    B2 = A * ((A + 1) + (A - 1) * cs - be);
                    A0 = (A + 1) - (A - 1) * cs + be;
                    A1 = 2 * ((A - 1) - (A + 1) * cs);
                    A2 = (A + 1) - (A - 1) * cs - be;
                }
            }
            b0 = B0 / A0;
            b1 = B1 / A0;
            b2 = B2 / A0;
            a1 = A1 / A0;
            a2 = A2 / A0;
        }

        void run(float[] s, int frames) {
            for (int i = 0; i < frames; i++) {
                for (int c = 0; c < 2; c++) {
                    double x = s[i * 2 + c];
                    double y = b0 * x + b1 * x1[c] + b2 * x2[c] - a1 * y1[c] - a2 * y2[c];
                    x2[c] = x1[c];
                    x1[c] = x;
                    y2[c] = y1[c];
                    y1[c] = y;
                    s[i * 2 + c] = (float) y;
                }
            }
        }
    }

    /** Decodes [startSec, startSec+durSec) of an audio (or video) file into stereo float at `rate`. Missing sound / end of file = silence. */
    private static float[] decodeRange(String path, double startSec, double durSec, int rate) throws Exception {
        int m = (int) Math.round(durSec * rate);
        float[] dst = new float[Math.max(1, m) * 2];
        android.media.MediaExtractor ex = new android.media.MediaExtractor();
        android.media.MediaCodec codec = null;
        try {
            ex.setDataSource(path);
            int ti = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String mt = ex.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME);
                if (mt != null && mt.startsWith("audio/")) {
                    ti = i;
                    break;
                }
            }
            if (ti < 0) return dst;
            ex.selectTrack(ti);
            android.media.MediaFormat f = ex.getTrackFormat(ti);
            int sr = f.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE), ch = f.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT);
            long startUs = (long) (startSec * 1e6), endUs = startUs + (long) (durSec * 1e6);
            ex.seekTo(startUs, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            codec = android.media.MediaCodec.createDecoderByType(f.getString(android.media.MediaFormat.KEY_MIME));
            codec.configure(f, null, null, 0);
            codec.start();
            // source-rate stereo buffer for the wanted span
            int cap = (int) (durSec * sr) + sr / 5 + 16;
            float[] src = new float[cap * 2];
            int maxK = 0;
            boolean inDone = false, outDone = false, isFloat = false;
            android.media.MediaCodec.BufferInfo bi = new android.media.MediaCodec.BufferInfo();
            while (!outDone) {
                if (mixCancel) throw new Exception("cancelled");
                if (!inDone) {
                    int ii = codec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(ii);
                        int sz = ex.readSampleData(ib, 0);
                        if (sz < 0 || ex.getSampleTime() > endUs + 500000L) {
                            codec.queueInputBuffer(ii, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            codec.queueInputBuffer(ii, 0, sz, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int oi = codec.dequeueOutputBuffer(bi, 10000);
                if (oi == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    android.media.MediaFormat of = codec.getOutputFormat();
                    sr = of.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE);
                    ch = of.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT);
                    isFloat = of.containsKey("pcm-encoding") && of.getInteger("pcm-encoding") == 4;
                } else if (oi >= 0) {
                    if (bi.size > 0) {
                        ByteBuffer ob = codec.getOutputBuffer(oi);
                        ob.position(bi.offset);
                        ob.limit(bi.offset + bi.size);
                        ob.order(ByteOrder.nativeOrder());
                        int frames;
                        java.nio.ShortBuffer sb = null;
                        java.nio.FloatBuffer fb = null;
                        if (isFloat) {
                            fb = ob.asFloatBuffer();
                            frames = fb.remaining() / ch;
                        } else {
                            sb = ob.asShortBuffer();
                            frames = sb.remaining() / ch;
                        }
                        long base = Math.round((bi.presentationTimeUs - startUs) * (double) sr / 1e6);
                        for (int k = 0; k < frames; k++) {
                            float l, r;
                            if (isFloat) {
                                l = fb.get(k * ch);
                                r = ch > 1 ? fb.get(k * ch + 1) : l;
                            } else {
                                l = sb.get(k * ch) / 32768f;
                                r = ch > 1 ? sb.get(k * ch + 1) / 32768f : l;
                            }
                            long di = base + k;
                            if (di >= 0 && di < cap) {
                                src[(int) di * 2] = l;
                                src[(int) di * 2 + 1] = r;
                                if (di > maxK) maxK = (int) di;
                            }
                        }
                        if (bi.presentationTimeUs > endUs + 200000L) outDone = true;
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((bi.flags & android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                }
            }
            // resample (linear) to the output rate
            double step = (double) sr / rate;
            for (int d = 0; d < m; d++) {
                double p = d * step;
                int i0 = (int) p;
                if (i0 >= cap - 1) break;
                float fr = (float) (p - i0);
                dst[d * 2] = src[i0 * 2] + (src[(i0 + 1) * 2] - src[i0 * 2]) * fr;
                dst[d * 2 + 1] = src[i0 * 2 + 1] + (src[(i0 + 1) * 2 + 1] - src[i0 * 2 + 1]) * fr;
            }
            return dst;
        } finally {
            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            } catch (Throwable ignore) {
            }
            try {
                ex.release();
            } catch (Throwable ignore) {
            }
        }
    }

    @PluginMethod
    public void buildMix(final PluginCall call) {
        Thread th = new Thread(() -> {
            try {
                mixCancel = false;
                JSONArray ss = call.getData().getJSONArray("sounds");
                double w0 = 1e18, w1 = 0;
                for (int i = 0; i < ss.length(); i++) {
                    JSONArray pcs = ss.getJSONObject(i).getJSONArray("pcs");
                    for (int j = 0; j < pcs.length(); j++) {
                        w0 = Math.min(w0, pcs.getJSONObject(j).getDouble("a"));
                        w1 = Math.max(w1, pcs.getJSONObject(j).getDouble("b"));
                    }
                }
                if (w1 <= w0) {
                    call.reject("no sound");
                    return;
                }
                final int RATE = 48000, WIN = RATE * 30;
                long len = (long) Math.ceil((w1 - w0) * RATE);
                File out = cacheFile("tmix.pcm");
                out.delete();
                Biquad[][] eq = new Biquad[ss.length()][];
                for (int i = 0; i < ss.length(); i++) {
                    JSONObject so = ss.getJSONObject(i);
                    double[] db = {so.optDouble("bass", 0), so.optDouble("mid", 0), so.optDouble("tre", 0)};
                    double[] fq = {200, 1000, 4000};
                    List<Biquad> l = new ArrayList<>();
                    for (int k = 0; k < 3; k++) if (Math.abs(db[k]) > 0.001) l.add(new Biquad(k, fq[k], db[k], RATE));
                    eq[i] = l.toArray(new Biquad[0]);
                }
                float[] acc = new float[WIN * 2];
                byte[] ob = new byte[WIN * 4];
                int any = 0;
                try (FileOutputStream fo = new FileOutputStream(out)) {
                    for (long pos = 0; pos < len; pos += WIN) {
                        if (mixCancel) throw new Exception("cancelled");
                        waitIfPaused();
                        int n = (int) Math.min(WIN, len - pos);
                        java.util.Arrays.fill(acc, 0, n * 2, 0f);
                        double ts = w0 + pos / (double) RATE, te = ts + n / (double) RATE;
                        for (int i = 0; i < ss.length(); i++) {
                            JSONObject so = ss.getJSONObject(i);
                            String sp = so.getString("path");
                            if (!safeName(sp) || !cacheFile(sp).exists()) continue;
                            double vol = so.optDouble("vol", 1), fi = so.optDouble("fi", 0), fob = so.optDouble("fo", 0);
                            double A = so.optDouble("A", 0), B = so.optDouble("B", 0);
                            JSONArray pcs = so.getJSONArray("pcs");
                            for (int j = 0; j < pcs.length(); j++) {
                                JSONObject q = pcs.getJSONObject(j);
                                double qa = q.getDouble("a"), qb = q.getDouble("b"), qs = q.getDouble("s");
                                double a2 = Math.max(qa, ts), b2 = Math.min(qb, te);
                                if (b2 - a2 < 0.002) continue;
                                float[] tmp = decodeRange(cacheFile(sp).getAbsolutePath(), qs + (a2 - qa), b2 - a2, RATE);
                                int m = tmp.length / 2;
                                for (Biquad bq : eq[i]) bq.run(tmp, m);
                                int di = (int) Math.round((a2 - ts) * RATE);
                                for (int f = 0; f < m && di + f < n; f++) {
                                    double t = a2 + f / (double) RATE, g = vol;
                                    if (fi > 0 && t < A + fi) g = Math.min(g, vol * (t - A) / fi);
                                    if (fob > 0 && t > B - fob) g = Math.min(g, vol * (B - t) / fob);
                                    if (g < 0) g = 0;
                                    acc[(di + f) * 2] += tmp[f * 2] * (float) g;
                                    acc[(di + f) * 2 + 1] += tmp[f * 2 + 1] * (float) g;
                                }
                                any++;
                            }
                        }
                        for (int f = 0; f < n * 2; f++) {
                            float v = acc[f];
                            if (v > 1f) v = 1f;
                            else if (v < -1f) v = -1f;
                            int s16 = Math.round(v * 32767f);
                            ob[f * 2] = (byte) (s16 & 0xff);
                            ob[f * 2 + 1] = (byte) ((s16 >> 8) & 0xff);
                        }
                        fo.write(ob, 0, n * 4);
                        JSObject pr = new JSObject();
                        pr.put("p", Math.min(1.0, (pos + n) / (double) len));
                        notifyListeners("mixprogress", pr);
                    }
                }
                JSObject o = new JSObject();
                o.put("path", "tmix.pcm");
                o.put("startMs", Math.round(w0 * 1000));
                o.put("pieces", any);
                call.resolve(o);
            } catch (Throwable e) {
                cacheFile("tmix.pcm").delete();
                call.reject(String.valueOf(e.getMessage()));
            }
        });
        th.setDaemon(true);
        th.start();
    }

    /* ---- Turbo's own file helpers (no use of FastRenderPlugin) ---- */
    private File cacheFile(String n) {
        return new File(getContext().getCacheDir(), n);
    }

    @PluginMethod
    public void write(PluginCall call) {
        try {
            String p = call.getString("path", "");
            if (!safeName(p)) {
                call.reject("bad file name");
                return;
            }
            byte[] b = Base64.decode(call.getString("data", ""), Base64.DEFAULT);
            try (FileOutputStream o = new FileOutputStream(cacheFile(p), call.getBoolean("append", false))) {
                o.write(b);
            }
            call.resolve();
        } catch (Throwable e) {
            call.reject(e.toString());
        }
    }

    @PluginMethod
    public void stat(PluginCall call) {
        String p = call.getString("path", "");
        JSObject o = new JSObject();
        File f = safeName(p) ? cacheFile(p) : null;
        o.put("exists", f != null && f.exists());
        o.put("size", f != null && f.exists() ? f.length() : 0);
        call.resolve(o);
    }

    @PluginMethod
    public void remove(PluginCall call) {
        String p = call.getString("path", "");
        if (safeName(p)) cacheFile(p).delete();
        call.resolve();
    }

    private static String cleanName(String n) {
        String s = (n == null ? "" : n).replaceAll("[^A-Za-z0-9._-]", "_");
        return s.isEmpty() ? "OnellyEdit_Turbo.mp4" : s;
    }

    private static void copy(File from, OutputStream to) throws Exception {
        try (FileInputStream in = new FileInputStream(from)) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) to.write(buf, 0, n);
        }
    }

    /** Keep the finished video inside the app (own folder), so the person can play it here and save it later. */
    @PluginMethod
    public void keep(PluginCall call) {
        try {
            String p = call.getString("path", "");
            if (!safeName(p) || !cacheFile(p).exists()) {
                call.reject("file missing");
                return;
            }
            File dir = getContext().getExternalFilesDir("TurboExports");
            if (dir == null) dir = new File(getContext().getFilesDir(), "TurboExports");
            dir.mkdirs();
            File dst = new File(dir, cleanName(call.getString("name", "")));
            try (FileOutputStream o = new FileOutputStream(dst)) {
                copy(cacheFile(p), o);
            }
            cacheFile(p).delete();
            JSObject o = new JSObject();
            o.put("abs", dst.getAbsolutePath());
            call.resolve(o);
        } catch (Throwable e) {
            call.reject(e.toString());
        }
    }

    /** Copy a kept video into the phone's gallery (Movies/OnellyEdit). */
    @PluginMethod
    public void toGallery(PluginCall call) {
        try {
            File src = new File(call.getString("abs", ""));
            File dir = getContext().getExternalFilesDir("TurboExports");
            if (dir == null || !src.exists() || !src.getCanonicalPath().startsWith(dir.getCanonicalPath())) {
                call.reject("file missing");
                return;
            }
            String name = cleanName(call.getString("name", src.getName()));
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/OnellyEdit");
                cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri u = getContext().getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
                if (u == null) {
                    call.reject("gallery insert failed");
                    return;
                }
                try (OutputStream o = getContext().getContentResolver().openOutputStream(u)) {
                    copy(src, o);
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContext().getContentResolver().update(u, done, null, null);
            } else {
                File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "OnellyEdit");
                d.mkdirs();
                try (FileOutputStream o = new FileOutputStream(new File(d, name))) {
                    copy(src, o);
                }
            }
            JSObject o = new JSObject();
            o.put("where", "Movies/OnellyEdit");
            call.resolve(o);
        } catch (Throwable e) {
            call.reject(e.toString());
        }
    }

    private static boolean safeName(String n) {
        return n != null && !n.isEmpty() && !n.contains("/") && !n.contains("\\") && !n.contains("..");
    }

    private static float clamp(double v, double lo, double hi) {
        return (float) Math.max(lo, Math.min(hi, v));
    }

    /* =====================================================================================================================
       PARALLEL EXPORT: the video is cut into 3 .. 30 equal pieces, several pieces are rendered at the same time (each with its own
       decoder / GPU overlay layer / encoder), the sound is made in one separate pass, and the pieces are joined WITHOUT re-encoding
       (MediaMuxer). If anything about the join looks unsafe, the whole thing falls back to the single export by itself.
       ===================================================================================================================== */
    private static final class Seg {
        int idx, clipIdx;
        long startMs, endMs, srcStartMs, srcEndMs;
        File file;
        Layer layer;
        List<Layer> blendLayers = new ArrayList<>();
        List<Object[]> blends = new ArrayList<>(); // {mode, layer}
        List<TurboOverlayVideo> vids = new ArrayList<>();
        Transformer tr;
        volatile boolean done;
    }

    private ParRun par;
    private String parNote = "";

    /* =====================================================================================================================
       GENERIC SHADER EFFECT. The web side (turbo.js) sends the GLSL fragment shader, its uniforms and (optionally) one picture used as a
       second texture (uTex2: a LUT, a curve table, a mask ...). New looks therefore need NO change here. Automatic uniforms:
       uTexSampler (the picture), uSize (width, height in pixels), uTimeMs (presentation time).
       ===================================================================================================================== */
    private static final String SHADER_VS = "attribute vec4 aFramePosition;\nvarying vec2 vTexSamplingCoord;\nvoid main() {\n  gl_Position = aFramePosition;\n  vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;\n}\n";

    private static final class ShaderFx implements GlEffect {
        final String frag;
        final java.util.Map<String, float[]> u;
        final String texPath;
        final BitmapOverlay dyn; // optional: a layer whose picture of the moment is the second texture (used for blend modes)

        ShaderFx(String frag, java.util.Map<String, float[]> u, String texPath) {
            this(frag, u, texPath, null);
        }

        ShaderFx(String frag, java.util.Map<String, float[]> u, String texPath, BitmapOverlay dyn) {
            this.frag = frag;
            this.u = u;
            this.texPath = texPath;
            this.dyn = dyn;
        }

        @Override
        public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr) throws VideoFrameProcessingException {
            return new ShaderProg(frag, u, texPath, dyn, useHdr);
        }
    }

    private static final class ShaderProg extends BaseGlShaderProgram {
        private final GlProgram glProgram;
        private final java.util.Map<String, float[]> u;
        private int tex2 = 0, w = 1, h = 1;
        private final BitmapOverlay dyn;
        private Bitmap lastBm;

        ShaderProg(String frag, java.util.Map<String, float[]> u, String texPath, BitmapOverlay dyn, boolean useHdr) throws VideoFrameProcessingException {
            super(useHdr, 1);
            this.u = u;
            this.dyn = dyn;
            try {
                glProgram = new GlProgram(SHADER_VS, frag);
                glProgram.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
            } catch (GlUtil.GlException e) {
                throw new VideoFrameProcessingException(e);
            }
            if (dyn != null) {
                int[] id = new int[1];
                GLES20.glGenTextures(1, id, 0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                tex2 = id[0];
            }
            if (texPath != null) {
                Bitmap bm = BitmapFactory.decodeFile(texPath);
                if (bm == null) throw new VideoFrameProcessingException("effect picture missing");
                int[] id = new int[1];
                GLES20.glGenTextures(1, id, 0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bm, 0);
                bm.recycle();
                tex2 = id[0];
            }
        }

        @Override
        public Size configure(int inputWidth, int inputHeight) {
            w = inputWidth;
            h = inputHeight;
            return new Size(inputWidth, inputHeight);
        }

        private void setU(String n, float[] v) {
            try { // a uniform the shader does not use is optimised away by the GPU driver: that is fine
                if (v.length == 1) glProgram.setFloatUniform(n, v[0]);
                else glProgram.setFloatsUniform(n, v);
            } catch (Throwable ignore) {
            }
        }

        @Override
        public void drawFrame(int inputTexId, long presentationTimeUs) throws VideoFrameProcessingException {
            try {
                if (dyn != null) { // the layer's picture of this moment goes into the second texture (only when it changed)
                    Bitmap bm = dyn.getBitmap(presentationTimeUs);
                    if (bm != lastBm) {
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex2);
                        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bm, 0);
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                        lastBm = bm;
                    }
                }
                glProgram.use();
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0);
                if (tex2 != 0) {
                    try {
                        glProgram.setSamplerTexIdUniform("uTex2", tex2, 1);
                    } catch (Throwable ignore) {
                    }
                }
                setU("uSize", new float[]{w, h});
                setU("uTimeMs", new float[]{presentationTimeUs / 1000f});
                for (java.util.Map.Entry<String, float[]> e : u.entrySet()) setU(e.getKey(), e.getValue());
                GlUtil.clearFocusedBuffers();
                glProgram.bindAttributesAndUniforms();
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                GlUtil.checkGlError();
            } catch (GlUtil.GlException e) {
                throw new VideoFrameProcessingException(e, presentationTimeUs);
            }
        }

        @Override
        public void release() throws VideoFrameProcessingException {
            super.release();
            try {
                glProgram.delete();
                if (tex2 != 0) GLES20.glDeleteTextures(1, new int[]{tex2}, 0);
            } catch (GlUtil.GlException e) {
                throw new VideoFrameProcessingException(e);
            }
        }
    }

    /** Shader effects of the recipe: [{frag, f:{uName: number | [numbers]}, tex: "file in the cache"}] */
    private List<Effect> shaderList(JSONArray arr) throws Exception {
        List<Effect> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            java.util.Map<String, float[]> u = new java.util.HashMap<>();
            JSONObject f = o.optJSONObject("f");
            if (f != null) {
                java.util.Iterator<String> it = f.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    Object v = f.get(k);
                    if (v instanceof JSONArray) {
                        JSONArray va = (JSONArray) v;
                        float[] fv = new float[va.length()];
                        for (int q = 0; q < fv.length; q++) fv[q] = (float) va.getDouble(q);
                        u.put(k, fv);
                    } else {
                        u.put(k, new float[]{(float) f.getDouble(k)});
                    }
                }
            }
            String tx = o.isNull("tex") ? null : o.optString("tex", null);
            if (tx != null) {
                if (!safeName(tx) || !cacheFile(tx).exists()) throw new Exception("effect picture missing");
                tx = cacheFile(tx).getAbsolutePath();
            }
            out.add(new ShaderFx(o.getString("frag"), u, tx));
        }
        return out;
    }

    private static final class LookSpec {
        int off, n;
        double t0Ms, stepMs;
        boolean geomId, colId;
    }

    private static final class ClipSpec {
        String src;
        long inMs, outMs, startMs, lenMs;
        double speed = 1, volume = 1;
        int rot, flip;
        LookSpec look;
        JSONArray fxPre, fxPost; // shader effects before / after the colour matrix
    }

    private double[] looksT; // key times (ms) of every look track of the recipe
    private float[] looksR; // 18 numbers per key: geometry (a b c d e f) + colour (3x4), read from the looks file

    private static byte[] readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f); java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream()) {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toByteArray();
        }
    }

    /** The clips of the recipe (an old single-clip recipe becomes one clip) + the look tables. */
    private List<ClipSpec> parseClips(JSONObject d) throws Exception {
        List<ClipSpec> out = new ArrayList<>();
        JSONArray ca = d.optJSONArray("clips");
        looksT = null;
        looksR = null;
        if (ca == null) {
            ClipSpec c = new ClipSpec();
            c.src = d.getString("src");
            c.inMs = d.getLong("inMs");
            c.outMs = d.getLong("outMs");
            c.startMs = 0;
            c.lenMs = c.outMs - c.inMs;
            c.volume = d.optDouble("volume", 1.0);
            c.rot = d.optInt("rot", 0);
            c.flip = d.optInt("flip", 0);
            out.add(c);
            return out;
        }
        for (int i = 0; i < ca.length(); i++) {
            JSONObject o = ca.getJSONObject(i);
            ClipSpec c = new ClipSpec();
            c.src = o.getString("src");
            c.inMs = o.getLong("inMs");
            c.outMs = o.getLong("outMs");
            c.startMs = o.getLong("startMs");
            c.lenMs = o.getLong("lenMs");
            c.speed = o.optDouble("speed", 1.0);
            c.volume = o.optDouble("volume", 1.0);
            c.rot = o.optInt("rot", 0);
            c.flip = o.optInt("flip", 0);
            c.fxPre = o.optJSONArray("fxPre");
            c.fxPost = o.optJSONArray("fxPost");
            JSONObject lo = o.optJSONObject("look");
            if (lo != null) {
                LookSpec l = new LookSpec();
                l.off = lo.getInt("off");
                l.n = lo.getInt("n");
                l.t0Ms = lo.optDouble("t0Ms", 0);
                l.stepMs = lo.optDouble("stepMs", 0);
                l.geomId = lo.optBoolean("geomId", true);
                l.colId = lo.optBoolean("colId", true);
                c.look = l;
            }
            if (!safeName(c.src) || c.outMs <= c.inMs || c.speed <= 0.05 || c.lenMs <= 0) throw new Exception("bad clip " + (i + 1));
            out.add(c);
        }
        String lf = d.isNull("looks") ? "" : d.optString("looks", "");
        if (!lf.isEmpty()) {
            if (!safeName(lf) || !cacheFile(lf).exists()) throw new Exception("look table missing");
            JSONObject lo2 = new JSONObject(new String(readAll(cacheFile(lf)), "UTF-8"));
            JSONArray ta = lo2.getJSONArray("t"), ra = lo2.getJSONArray("r");
            looksT = new double[ta.length()];
            for (int i = 0; i < looksT.length; i++) looksT[i] = ta.getDouble(i);
            looksR = new float[ra.length()];
            for (int i = 0; i < looksR.length; i++) looksR[i] = (float) ra.getDouble(i);
        }
        for (ClipSpec c : out) {
            if (c.look != null && (looksT == null || c.look.off + c.look.n > looksT.length || looksR.length < (long) (c.look.off + c.look.n) * 18)) throw new Exception("look table too short");
        }
        return out;
    }

    /** One clip's look at a moment: geometry (a b c d e f, CSS pixels, y down, from the picture's centre) + colour (3x4) + opacity folded into the colour. */
    private static final class LookFx {
        final KeyTrack kt;
        final Clock clock;
        final int W, H;

        LookFx(KeyTrack kt, Clock clock, int W, int H) {
            this.kt = kt;
            this.clock = clock;
            this.W = W;
            this.H = H;
        }

        private float[] row(long us) {
            float[] r = new float[18];
            kt.at(clock.relUs(us) / 1000.0, r);
            return r;
        }

        float[] geom(long us) {
            float[] g = row(us), m = new float[16];
            m[0] = g[0];
            m[1] = -g[1] * W / H;
            m[4] = -g[2] * H / W;
            m[5] = g[3];
            m[10] = 1;
            m[12] = 2 * g[4] / W;
            m[13] = -2 * g[5] / H;
            m[15] = 1;
            return m;
        }

        float[] color(long us) {
            float[] g = row(us), c = new float[16];
            c[0] = g[6];
            c[1] = g[10];
            c[2] = g[14];
            c[4] = g[7];
            c[5] = g[11];
            c[6] = g[15];
            c[8] = g[8];
            c[9] = g[12];
            c[10] = g[16];
            c[12] = g[9];
            c[13] = g[13];
            c[14] = g[17];
            c[15] = 1;
            return c;
        }
    }

    private Layer makeLayer(JSONArray ovs, File cache, int w, int h, Clock clock, List<TurboOverlayVideo> vidOut, int bm) throws Exception {
        List<LayerItem> items = new ArrayList<>();
        if (ovs != null) {
            for (int i = 0; i < ovs.length(); i++) {
                JSONObject o = ovs.getJSONObject(i);
                if (o.optInt("bm", 0) != bm) continue; // 0 = normal; 1.. = a blend mode (own layer + own shader pass)
                String ty = o.optString("type");
                String p = o.optString("path", "");
                if (!"anim".equals(ty) && !"wipe".equals(ty) && !safeName(p)) throw new Exception("bad overlay name");
                if ("wipe".equals(ty)) {
                    JSONArray wa = o.getJSONArray("w");
                    float[] wv = new float[wa.length()];
                    for (int q = 0; q < wv.length; q++) wv[q] = (float) wa.getDouble(q);
                    if (wv.length < 2) throw new Exception("bad wipe table");
                    items.add(new WipeItem(w, h, o.getLong("fromMs"), o.getLong("toMs"), o.getDouble("t0Ms"), o.getDouble("stepMs"), wv));
                } else if ("anim".equals(ty)) {
                    JSONArray tl = o.getJSONArray("tiles");
                    KeyTrack ktk = KeyTrack.fromJson(o.getJSONArray("tab"), 5);
                    if (tl.length() < 1) throw new Exception("bad animation table");
                    int nt = tl.length();
                    String[] ps = new String[nt];
                    float[] tox = new float[nt], toy = new float[nt], tww = new float[nt], thh = new float[nt];
                    for (int q = 0; q < nt; q++) {
                        JSONObject tj = tl.getJSONObject(q);
                        if (!safeName(tj.getString("path"))) throw new Exception("bad overlay name");
                        ps[q] = new File(cache, tj.getString("path")).getAbsolutePath();
                        tox[q] = (float) tj.getDouble("ox");
                        toy[q] = (float) tj.getDouble("oy");
                        tww[q] = (float) tj.getDouble("w");
                        thh[q] = (float) tj.getDouble("h");
                    }
                    items.add(new AnimItem(ps, tox, toy, tww, thh, w, h, o.getLong("fromMs"), o.getLong("toMs"), ktk));
                } else if ("vid".equals(ty)) { // one overlay video; `pcs` = [[fromMs, toMs, inMs], ...] when it loops (the movement keys are shared)
                    KeyTrack vkt = o.isNull("tab") ? null : KeyTrack.fromJson(o.getJSONArray("tab"), 5);
                    JSONArray pcs = o.optJSONArray("pcs");
                    int np = pcs == null ? 1 : pcs.length();
                    for (int pi = 0; pi < np; pi++) {
                        long pf = pcs == null ? o.getLong("fromMs") : pcs.getJSONArray(pi).getLong(0);
                        long pt = pcs == null ? o.getLong("toMs") : pcs.getJSONArray(pi).getLong(1);
                        long pin = pcs == null ? o.optLong("inMs", 0L) : pcs.getJSONArray(pi).getLong(2);
                        TurboOverlayVideo vo = new TurboOverlayVideo(new File(cache, p).getAbsolutePath(), w, h,
                                pf * 1000L, pt * 1000L, o.optLong("durMs", 0L) * 1000L,
                                (float) o.getDouble("x"), (float) o.getDouble("y"),
                                (float) o.getDouble("sc"), (float) o.getDouble("rot"), o.optDouble("ar", 0.5625));
                        vo.setInUs(pin * 1000L);
                        JSONObject ck = o.optJSONObject("ck");
                        if (ck != null) {
                            String cc = ck.optString("col", "#00ff00");
                            vo.setChroma(Integer.parseInt(cc.substring(1, 3), 16), Integer.parseInt(cc.substring(3, 5), 16), Integer.parseInt(cc.substring(5, 7), 16), ck.optDouble("sim", .35), ck.optDouble("sm", .1));
                        }
                        vidOut.add(vo);
                        items.add(new VidItem(vo, pf, pt, vkt));
                    }
                } else {
                    items.add(new StillItem(new File(cache, p).getAbsolutePath(), o.optInt("x", 0), o.optInt("y", 0),
                            o.getLong("fromMs"), o.getLong("toMs")));
                }
            }
        }
        return items.isEmpty() ? null : new Layer(items, w, h, clock);
    }

    /** Legacy single export: rotate (0 / 90 / 180 / 270, clockwise like the preview) and mirror, on the GPU. */
    private static void addOrient(List<Effect> vfx, JSONObject data) {
        int rot = data.optInt("rot", 0), flip = data.optInt("flip", 0);
        if (rot == 0 && flip == 0) return;
        if (flip != 0) vfx.add(new ScaleAndRotateTransformation.Builder().setScale(-1f, 1f).build()); // mirror first, then turn
        if (rot != 0) vfx.add(new ScaleAndRotateTransformation.Builder().setRotationDegrees(-rot).build());
    }

    private static void addRot(List<Effect> vfx, int rot) {
        if (rot == 0) return;
        vfx.add(new ScaleAndRotateTransformation.Builder().setRotationDegrees(-rot).build());
    }

    /** Video effects of one export piece, in the order: speed, frame rate, turn, fit to size, look (geometry, colour), overlays. */
    private List<Effect> buildVfx(JSONObject data, ClipSpec cs, boolean legacy, Clock clock, Layer lay, int w, int h, List<Object[]> blends) throws Exception {
        List<Effect> vfx = new ArrayList<>();
        if (Math.abs(cs.speed - 1.0) > 0.0005) {
            try {
                vfx.add((Effect) Class.forName("androidx.media3.effect.SpeedChangeEffect").getConstructor(float.class).newInstance((float) cs.speed));
            } catch (Throwable e) {
                throw new Exception("this phone's video library cannot change speed");
            }
        }
        int fps = data.optInt("fps", 0);
        if (fps > 0) {
            try {
                Object fd = Class.forName("androidx.media3.effect.FrameDropEffect")
                        .getMethod("createDefaultFrameDropEffect", float.class).invoke(null, (float) fps);
                vfx.add((Effect) fd);
            } catch (Throwable ignore) {
            }
        }
        if (cs.flip != 0) vfx.add(new ScaleAndRotateTransformation.Builder().setScale(-1f, 1f).build()); // mirror first, like the preview
        addRot(vfx, cs.rot);
        vfx.add(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT));
        vfx.addAll(shaderList(cs.fxPre));
        final boolean hasLook = cs.look != null && looksT != null;
        final LookFx lf = hasLook ? new LookFx(new KeyTrack(looksT, looksR, cs.look.off, cs.look.n, 18), clock, w, h) : null;
        if (hasLook && !cs.look.colId) vfx.add((RgbMatrix) (us, hdr) -> lf.color(us));
        vfx.addAll(shaderList(cs.fxPost));
        if (hasLook && !cs.look.geomId) vfx.add((GlMatrixTransformation) us -> lf.geom(us)); // a 4x4 GL matrix per frame
        if (!hasLook && legacy) { // old single-clip recipe: simple brightness / contrast / saturation / hue
            JSONObject f = data.optJSONObject("filter");
            if (f != null) {
                double b = f.optDouble("b", 1), c = f.optDouble("c", 1), s = f.optDouble("s", 1), hue = f.optDouble("hue", 0);
                if (Math.abs(b - 1) > 0.001) vfx.add(new Brightness(clamp(b - 1, -1, 1)));
                if (Math.abs(c - 1) > 0.001) vfx.add(new Contrast(clamp(c - 1, -1, 1)));
                if (Math.abs(s - 1) > 0.001 || Math.abs(hue) > 0.001) {
                    vfx.add(new HslAdjustment.Builder()
                            .adjustSaturation(clamp((s - 1) * 100, -100, 100))
                            .adjustHue(clamp(hue, -180, 180))
                            .build());
                }
            }
        }
        JSONObject bj = data.optJSONObject("blend");
        if (bj != null && blends != null) {
            for (Object[] b : blends) {
                java.util.Map<String, float[]> bu = new java.util.HashMap<>();
                bu.put("uMode", new float[]{(Integer) b[0]});
                vfx.add(new ShaderFx(bj.getString("frag"), bu, null, (Layer) b[1]));
            }
        }
        List<androidx.media3.effect.TextureOverlay> tex = new ArrayList<>();
        tex.add(new PauseGate());
        if (lay != null) tex.add(lay);
        vfx.add(new OverlayEffect(ImmutableList.copyOf(tex)));
        return vfx;
    }

    private final List<OverlayMix> mixers = new ArrayList<>();

    /** Sound effects of one clip: its volume, its speed, the extra sound track (overlay videos + added audio), the Fade tool. */
    private ImmutableList<AudioProcessor> buildAudioFx(JSONObject data, ClipSpec cs) throws Exception {
        ImmutableList.Builder<AudioProcessor> afx = ImmutableList.builder();
        if (Math.abs(cs.volume - 1.0) > 0.001) {
            ChannelMixingAudioProcessor mix = new ChannelMixingAudioProcessor();
            for (int ch = 1; ch <= 2; ch++) mix.putChannelMixingMatrix(ChannelMixingMatrix.create(ch, ch).scaleBy((float) cs.volume));
            afx.add(mix);
        }
        if (Math.abs(cs.speed - 1.0) > 0.0005) {
            SonicAudioProcessor sp = new SonicAudioProcessor();
            sp.setSpeed((float) cs.speed);
            sp.setPitch(1f);
            afx.add(sp);
        }
        JSONObject mx = data.optJSONObject("mix");
        if (mx != null) {
            String mp = mx.optString("path", "");
            if (!safeName(mp) || !cacheFile(mp).exists() || cacheFile(mp).length() < 8) throw new Exception("bad sound track");
            OverlayMix m = new OverlayMix(cacheFile(mp), Math.round(mx.optDouble("startMs", 0) * 48.0), Math.round(cs.startMs * 48.0));
            mixers.add(m);
            afx.add(m);
        }
        JSONObject fd = data.optJSONObject("fade");
        if (fd != null) afx.add(new FadeGain(fd.optDouble("i", 0), fd.optDouble("o", 0), fd.optDouble("total", 0), cs.startMs / 1000.0));
        return afx.build();
    }

    private Transformer newTransformer(int br, Transformer.Listener l) {
        VideoEncoderSettings.Builder vb = new VideoEncoderSettings.Builder().setBitrate(br);
        try {
            VideoEncoderSettings.Builder.class.getMethod("setEncoderPerformanceParameters", int.class, int.class).invoke(vb, Integer.MAX_VALUE, 1);
        } catch (Throwable ignore) {
        }
        Transformer.Builder tb = new Transformer.Builder(getContext());
        try {
            Transformer.Builder.class.getMethod("setMaxDelayBetweenMuxerSamplesMs", long.class).invoke(tb, C.TIME_UNSET);
        } catch (Throwable ignore) {
        }
        return tb.setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(new DefaultEncoderFactory.Builder(getContext())
                        .setRequestedVideoEncoderSettings(vb.build())
                        .setEnableFallback(false)
                        .build())
                .addListener(l)
                .build();
    }

    private static boolean hasAudioTrack(File f) {
        MediaExtractor e = new MediaExtractor();
        try {
            e.setDataSource(f.getAbsolutePath());
            for (int i = 0; i < e.getTrackCount(); i++) {
                String m = e.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) return true;
            }
        } catch (Throwable ignore) {
        } finally {
            try {
                e.release();
            } catch (Throwable ignore) {
            }
        }
        return false;
    }

    private static int findTrack(MediaExtractor e, String prefix) {
        for (int i = 0; i < e.getTrackCount(); i++) {
            String m = e.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static boolean sameCsd(MediaFormat a, MediaFormat b) {
        for (String k : new String[]{"csd-0", "csd-1"}) {
            ByteBuffer x = a.containsKey(k) ? a.getByteBuffer(k) : null, y = b.containsKey(k) ? b.getByteBuffer(k) : null;
            if (x == null && y == null) continue;
            if (x == null || y == null || !x.duplicate().equals(y.duplicate())) return false;
        }
        return a.getInteger(MediaFormat.KEY_WIDTH) == b.getInteger(MediaFormat.KEY_WIDTH)
                && a.getInteger(MediaFormat.KEY_HEIGHT) == b.getInteger(MediaFormat.KEY_HEIGHT);
    }

    /** Joins the video pieces (and the sound) without re-encoding. Throws when the pieces do not have identical codec settings. */
    private static void stitch(List<Seg> segs, File audio, File out) throws Exception {
        MediaMuxer mux = null;
        MediaExtractor ae = null;
        try {
            MediaExtractor e0 = new MediaExtractor();
            e0.setDataSource(segs.get(0).file.getAbsolutePath());
            int vt0 = findTrack(e0, "video/");
            if (vt0 < 0) throw new Exception("piece without video");
            MediaFormat vf = e0.getTrackFormat(vt0);
            e0.release();
            for (int i = 1; i < segs.size(); i++) {
                MediaExtractor e = new MediaExtractor();
                try {
                    e.setDataSource(segs.get(i).file.getAbsolutePath());
                    int vt = findTrack(e, "video/");
                    if (vt < 0 || !sameCsd(vf, e.getTrackFormat(vt))) throw new Exception("pieces differ (codec settings)");
                } finally {
                    e.release();
                }
            }
            if (out.exists()) out.delete();
            mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vIdx = mux.addTrack(vf), aIdx = -1;
            if (audio != null) {
                ae = new MediaExtractor();
                ae.setDataSource(audio.getAbsolutePath());
                int at = findTrack(ae, "audio/");
                if (at >= 0) {
                    aIdx = mux.addTrack(ae.getTrackFormat(at));
                    ae.selectTrack(at);
                } else {
                    ae.release();
                    ae = null;
                }
            }
            mux.start();
            ByteBuffer buf = ByteBuffer.allocate(12 << 20);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long aT = ae != null ? ae.getSampleTime() : -1;
            long offUs = 0;
            for (Seg sg : segs) {
                MediaExtractor e = new MediaExtractor();
                try {
                    e.setDataSource(sg.file.getAbsolutePath());
                    int vt = findTrack(e, "video/");
                    e.selectTrack(vt);
                    for (; ; ) {
                        buf.clear();
                        int sz = e.readSampleData(buf, 0);
                        if (sz < 0) break;
                        long pts = e.getSampleTime() + offUs;
                        while (ae != null && aT >= 0 && aT <= pts) { // keep sound and picture interleaved by time
                            buf.clear();
                            int az = ae.readSampleData(buf, 0);
                            if (az < 0) {
                                aT = -1;
                                break;
                            }
                            info.set(0, az, aT, (ae.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                            mux.writeSampleData(aIdx, buf, info);
                            ae.advance();
                            aT = ae.getSampleTime();
                        }
                        buf.clear();
                        sz = e.readSampleData(buf, 0); // (the audio loop reused the buffer)
                        if (sz < 0) break;
                        info.set(0, sz, pts, (e.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                        mux.writeSampleData(vIdx, buf, info);
                        e.advance();
                    }
                } finally {
                    e.release();
                }
                offUs += (sg.endMs - sg.startMs) * 1000L;
            }
            while (ae != null && aT >= 0) { // rest of the sound
                buf.clear();
                int az = ae.readSampleData(buf, 0);
                if (az < 0) break;
                info.set(0, az, aT, (ae.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                mux.writeSampleData(aIdx, buf, info);
                ae.advance();
                aT = ae.getSampleTime();
            }
            mux.stop();
        } finally {
            try {
                if (ae != null) ae.release();
            } catch (Throwable ignore) {
            }
            try {
                if (mux != null) mux.release();
            } catch (Throwable ignore) {
            }
        }
    }

    private final class ParRun {
        final PluginCall call;
        final File cache, outFile;
        final int w, h, br;
        final JSONObject data;
        final JSONArray ovs;
        final List<ClipSpec> specs;
        final boolean legacy; // old single-clip recipe (the single export is its safety net)
        final List<Seg> segs = new ArrayList<>();
        int next = 0, running = 0, doneCount = 0, maxParallel = 2;
        boolean failed = false, finishing = false, audioNeeded = false, audioDone = false;
        File audioFile;
        Transformer audioTr;
        long mixTouched = -1;
        final long startedAt = SystemClock.elapsedRealtime();

        ParRun(PluginCall call, File cache, File outFile, List<ClipSpec> specs, boolean legacy, int w, int h, int br) {
            this.call = call;
            this.cache = cache;
            this.outFile = outFile;
            this.specs = specs;
            this.legacy = legacy;
            this.w = w;
            this.h = h;
            this.br = br;
            this.data = call.getData();
            this.ovs = data.optJSONArray("overlays");
        }

        /** Cuts the timeline into pieces (about 40 s each, never across two clips). Returns the number of pieces. */
        int plan() {
            long total = 0;
            for (ClipSpec c : specs) total += c.lenMs;
            if (legacy && total < 20000L) return 1;
            int cores = Runtime.getRuntime().availableProcessors();
            int k = Math.max(2, Math.min(4, cores / 2));
            long layerBytes = (long) w * h * 4L * 3L;
            k = (int) Math.max(1, Math.min(k, (320L << 20) / Math.max(1, layerBytes)));
            int nv = 0; // overlay videos: every piece decodes all of them at the same time, so the CPU must not be oversubscribed
            if (ovs != null) for (int i = 0; i < ovs.length(); i++) if ("vid".equals(ovs.optJSONObject(i).optString("type"))) nv++;
            k = Math.max(1, Math.min(k, cores / (1 + nv)));
            int idx = 0;
            for (int ci = 0; ci < specs.size(); ci++) {
                ClipSpec cs = specs.get(ci);
                int n = legacy ? (int) Math.max(3, Math.min(30, Math.round(cs.lenMs / 40000.0))) : (int) Math.max(1, Math.round(cs.lenMs / 40000.0));
                for (int i = 0; i < n; i++) {
                    Seg s = new Seg();
                    s.idx = idx++;
                    s.clipIdx = ci;
                    s.startMs = cs.startMs + cs.lenMs * i / n;
                    s.endMs = cs.startMs + cs.lenMs * (i + 1) / n;
                    s.srcStartMs = cs.inMs + Math.round((s.startMs - cs.startMs) * cs.speed);
                    s.srcEndMs = (i == n - 1) ? cs.outMs : cs.inMs + Math.round((s.endMs - cs.startMs) * cs.speed);
                    s.file = new File(cache, "tseg" + s.idx + ".mp4");
                    s.file.delete();
                    segs.add(s);
                }
            }
            maxParallel = Math.min(k, segs.size());
            if (legacy && maxParallel < 2) return 1; // nothing to gain: use the single export
            return segs.size();
        }

        void begin() throws Exception {
            active = call;
            setPaused(false);
            jobPhase = "checking the video";
            mixers.clear();
            int withAudio = 0;
            for (ClipSpec c : specs) if (hasAudioTrack(new File(cache, c.src))) withAudio++;
            audioNeeded = withAudio > 0;
            if (audioNeeded && withAudio != specs.size()) throw new Exception("some clips have no sound track (level 5)");
            jobPhase = audioNeeded ? "sound pass + pieces" : "pieces";
            if (audioNeeded) startAudio();
            launch();
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (par != ParRun.this || failed || finishing) return;
                    double tot = 0, got = 0;
                    ProgressHolder ph = new ProgressHolder();
                    for (Seg s : segs) {
                        double len = s.endMs - s.startMs;
                        tot += len;
                        if (s.done) got += len;
                        else if (s.tr != null && s.tr.getProgress(ph) == Transformer.PROGRESS_STATE_AVAILABLE) got += len * ph.progress / 100.0;
                    }
                    JSObject o = new JSObject();
                    o.put("p", tot > 0 ? Math.min(99.0, 100.0 * got / tot) : 0);
                    jobP = tot > 0 ? Math.min(99.0, 100.0 * got / tot) : 0;
                    jobPhase = "pieces " + doneCount + "/" + segs.size() + " done, " + running + " at once" + (audioNeeded ? (audioDone ? ", sound done" : ", sound …") : "") + thermalNote;
                    notifyListeners("progress", o);
                    main.postDelayed(this, 500);
                }
            }, 500);
        }

        void startAudio() throws Exception {
            audioFile = new File(cache, "tseg_audio.mp4");
            audioFile.delete();
            List<EditedMediaItem> items = new ArrayList<>();
            for (ClipSpec cs : specs) {
                MediaItem mi = new MediaItem.Builder().setUri(Uri.fromFile(new File(cache, cs.src)))
                        .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(cs.inMs).setEndPositionMs(cs.outMs).build()).build();
                items.add(new EditedMediaItem.Builder(mi).setRemoveVideo(true)
                        .setEffects(new Effects(buildAudioFx(data, cs), ImmutableList.<Effect>of())).build());
            }
            Composition comp = new Composition.Builder(ImmutableList.of(new EditedMediaItemSequence(ImmutableList.copyOf(items)))).build();
            audioTr = newTransformer(br, new Transformer.Listener() {
                @Override
                public void onCompleted(Composition c, ExportResult r) {
                    audioDone = true;
                    long tt = -1;
                    for (OverlayMix m : mixers) tt = Math.max(tt, 0) + m.touched;
                    mixTouched = tt;
                    checkFinish();
                }

                @Override
                public void onError(Composition c, ExportResult r, ExportException e) {
                    fail("sound pass: " + e.getMessage() + " (" + e.getErrorCodeName() + ")");
                }
            });
            audioTr.start(comp, audioFile.getAbsolutePath());
        }

        String thermalNote = "";

        /** How many pieces may run at once right now: fewer when the phone is hot (so it cools down instead of throttling or shutting the app). */
        int allowedNow() {
            int allowed = maxParallel;
            thermalNote = "";
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
                    int ts = pm.getCurrentThermalStatus();
                    if (ts >= PowerManager.THERMAL_STATUS_SEVERE) {
                        allowed = 1;
                        thermalNote = ", phone very hot: 1 at a time";
                    } else if (ts >= PowerManager.THERMAL_STATUS_MODERATE) {
                        allowed = Math.max(1, maxParallel / 2);
                        thermalNote = ", phone warm: " + allowed + " at a time";
                    }
                }
            } catch (Throwable ignore) {
            }
            return allowed;
        }

        void launch() {
            final int allowed = allowedNow();
            while (!failed && running < allowed && next < segs.size()) {
                Seg s = segs.get(next++);
                try {
                    startSeg(s);
                } catch (Throwable e) {
                    fail("piece " + s.idx + ": " + e);
                    return;
                }
            }
        }

        void startSeg(final Seg s) throws Exception {
            ClipSpec cs = specs.get(s.clipIdx);
            Clock clock = new Clock(s.srcStartMs, Math.round(s.srcStartMs / cs.speed), s.startMs);
            s.layer = makeLayer(ovs, cache, w, h, clock, s.vids, 0);
            java.util.TreeSet<Integer> modes = new java.util.TreeSet<>();
            if (ovs != null) for (int i = 0; i < ovs.length(); i++) {
                int bmv = ovs.getJSONObject(i).optInt("bm", 0);
                if (bmv != 0) modes.add(bmv);
            }
            for (int bmv : modes) {
                Layer bl = makeLayer(ovs, cache, w, h, clock, s.vids, bmv);
                if (bl != null) {
                    s.blendLayers.add(bl);
                    s.blends.add(new Object[]{bmv, bl});
                }
            }
            List<Effect> vfx = buildVfx(data, cs, legacy, clock, s.layer, w, h, s.blends);
            MediaItem mi = new MediaItem.Builder().setUri(Uri.fromFile(new File(cache, cs.src)))
                    .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(s.srcStartMs).setEndPositionMs(s.srcEndMs).build()).build();
            EditedMediaItem item = new EditedMediaItem.Builder(mi).setRemoveAudio(true)
                    .setEffects(new Effects(ImmutableList.<AudioProcessor>of(), ImmutableList.copyOf(vfx))).build();
            Composition comp = new Composition.Builder(ImmutableList.of(new EditedMediaItemSequence(ImmutableList.of(item)))).build();
            s.tr = newTransformer(br, new Transformer.Listener() {
                @Override
                public void onCompleted(Composition c, ExportResult r) {
                    if (failed) return;
                    s.done = true;
                    running--;
                    doneCount++;
                    if (s.layer != null) s.layer.close();
                    for (Layer bl : s.blendLayers) bl.close();
                    for (TurboOverlayVideo v : s.vids) v.closeDecoder();
                    if (s.file.length() < 1000) {
                        fail("piece " + s.idx + " is empty");
                        return;
                    }
                    launch();
                    checkFinish();
                }

                @Override
                public void onError(Composition c, ExportResult r, ExportException e) {
                    fail("piece " + s.idx + ": " + e.getMessage() + " (" + e.getErrorCodeName() + ")");
                }
            });
            running++;
            s.tr.start(comp, s.file.getAbsolutePath());
        }

        void checkFinish() {
            if (failed || finishing) return;
            if (doneCount < segs.size() || (audioNeeded && !audioDone)) return;
            finishing = true;
            jobPhase = "joining the pieces";
            Thread th = new Thread(() -> {
                try {
                    stitch(segs, audioNeeded ? audioFile : null, outFile);
                    final JSObject o = result();
                    main.post(() -> {
                        if (par != ParRun.this) return;
                        par = null;
                        active = null;
                        setPaused(false);
                        cleanup();
                        resolveJob(call, o);
                    });
                } catch (Throwable e) {
                    main.post(() -> fail("join: " + e.getMessage()));
                }
            }, "turbo-stitch");
            th.setDaemon(true);
            th.start();
        }

        JSObject result() {
            long[] sum = new long[11];
            long vs = 0, sh = 0, dn = 0, cn = 0;
            int sw = 0, vc = 0;
            for (Seg s : segs) {
                if (s.layer != null) {
                    long[] c = s.layer.counts();
                    for (int i = 0; i < sum.length; i++) sum[i] += c[i];
                }
                for (TurboOverlayVideo v : s.vids) {
                    vs += v.spentMs();
                    sh += v.shown;
                    dn += v.decNs;
                    cn += v.convNs;
                    if (v.swDec) sw++;
                    vc++;
                }
            }
            JSObject o = new JSObject();
            o.put("ovDiag", sum[0] + "/" + sum[1] + " S" + sum[2] + " A" + sum[3]);
            o.put("prof", "draw " + sum[4] / 1000000 + " still " + sum[5] / 1000000 + " anim " + sum[6] / 1000000 + " vid " + sum[7] / 1000000
                    + " wait " + sum[8] / 1000000 + " pipe " + sum[9] + "/" + sum[10]);
            o.put("vidShown", sh);
            o.put("vdec", dn / 1000000);
            o.put("vconv", cn / 1000000);
            o.put("vsw", sw);
            o.put("vidMs", vs);
            o.put("vidCount", vc);
            o.put("mixFrames", mixTouched);
            o.put("par", segs.size() + " pieces x" + maxParallel + " at once, " + specs.size() + " clip(s), joined without re-encoding, " + (SystemClock.elapsedRealtime() - startedAt) / 1000 + "s");
            o.put("path", outFile.getName());
            o.put("sizeMB", Math.round(outFile.length() / 1048.576) / 1000.0);
            return o;
        }

        void cleanup() {
            for (Seg s : segs) s.file.delete();
            if (audioFile != null) audioFile.delete();
        }

        void cancelAll() {
            for (Seg s : segs) {
                try {
                    if (s.tr != null) s.tr.cancel();
                } catch (Throwable ignore) {
                }
                if (s.layer != null) s.layer.close();
                for (Layer bl : s.blendLayers) bl.close();
                for (TurboOverlayVideo v : s.vids) v.closeDecoder();
            }
            try {
                if (audioTr != null) audioTr.cancel();
            } catch (Throwable ignore) {
            }
        }

        /** Something went wrong in the piecewise export: stop it. An old single-clip recipe falls back to the single export; others report the reason. */
        void fail(String why) {
            if (failed) return;
            failed = true;
            cancelAll();
            cleanup();
            par = null;
            active = null;
            outFile.delete();
            if (legacy) {
                parNote = "parallel export failed (" + why + "), single export used";
                main.post(() -> doRenderSingle(call));
            } else {
                rejectJob(call, why);
            }
        }
    }

    private void doRender(final PluginCall call) {
        try {
            if (active != null) {
                call.reject("busy");
                return;
            }
            parNote = "";
            jobStart();
            String out = call.getString("out", "turbo_out.mp4");
            JSONObject d = call.getData();
            boolean legacy = d.optJSONArray("clips") == null;
            if (!safeName(out) || !hasHardwareH264()) {
                doRenderSingle(call); // it reports the problem
                return;
            }
            File cache = getContext().getCacheDir(), outFile = new File(cache, out);
            List<ClipSpec> specs;
            try {
                specs = parseClips(d);
            } catch (Exception e) {
                if (legacy) {
                    doRenderSingle(call);
                    return;
                }
                rejectJob(call, e.getMessage());
                return;
            }
            for (ClipSpec c : specs) {
                if (!new File(cache, c.src).exists()) {
                    if (legacy) {
                        doRenderSingle(call);
                    } else {
                        rejectJob(call, "source missing");
                    }
                    return;
                }
            }
            if (d.optJSONObject("mix") != null) {
                boolean any = false;
                for (ClipSpec c : specs) if (hasAudioTrack(new File(cache, c.src))) any = true;
                if (!any) {
                    rejectJob(call, "this video has no sound track, so the extra sound cannot be added (level 5)");
                    return;
                }
            }
            if (outFile.exists()) outFile.delete();
            if (legacy && !call.getBoolean("parallel", true)) {
                doRenderSingle(call);
                return;
            }
            ParRun pr = new ParRun(call, cache, outFile, specs, legacy, call.getInt("width", 1280), call.getInt("height", 720), call.getInt("bitrate", 4000000));
            if (legacy && pr.plan() < 2) {
                doRenderSingle(call);
                return;
            }
            if (!legacy) pr.plan();
            par = pr;
            try {
                pr.begin();
            } catch (Throwable e) {
                pr.fail("start: " + e.getMessage());
            }
        } catch (Throwable e) {
            active = null;
            rejectJob(call, e.toString());
        }
    }

    private void doRenderSingle(final PluginCall call) {
        try {
            if (active != null) {
                rejectJob(call, "busy");
                return;
            }
            if (!hasHardwareH264()) {
                rejectJob(call, "no hardware H.264 encoder");
                return;
            }
            final File cache = getContext().getCacheDir();
            String src = call.getString("src", "");
            String out = call.getString("out", "turbo_out.mp4");
            if (!safeName(src) || !safeName(out)) {
                rejectJob(call, "bad file name");
                return;
            }
            File srcFile = new File(cache, src);
            if (!srcFile.exists()) {
                rejectJob(call, "source missing");
                return;
            }
            final File outFile = new File(cache, out);
            if (outFile.exists()) outFile.delete();

            final int w = call.getInt("width", 1280), h = call.getInt("height", 720);
            final int br = call.getInt("bitrate", 4000000);
            final long inMs = call.getData().optLong("inMs", 0L), outMs = call.getData().optLong("outMs", 0L);
            if (outMs <= inMs) {
                rejectJob(call, "bad range");
                return;
            }

            List<Effect> vfx = new ArrayList<>();
            final int fps = call.getInt("fps", 0);
            if (fps > 0) { // 24 / 30 / 60: frames above the chosen rate are dropped (a lower-rate source is never stretched)
                try {
                    Object fd = Class.forName("androidx.media3.effect.FrameDropEffect")
                            .getMethod("createDefaultFrameDropEffect", float.class).invoke(null, (float) fps);
                    vfx.add((Effect) fd);
                } catch (Throwable ignore) {
                }
            }
            addOrient(vfx, call.getData());
            vfx.add(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT));

            // color filter (brightness / contrast / saturation / hue); sepia is NOT part of level 1 (the web side refuses it)
            JSONObject f = call.getData().optJSONObject("filter");
            if (f != null) {
                double b = f.optDouble("b", 1), c = f.optDouble("c", 1), s = f.optDouble("s", 1), hue = f.optDouble("hue", 0);
                if (Math.abs(b - 1) > 0.001) vfx.add(new Brightness(clamp(b - 1, -1, 1)));
                if (Math.abs(c - 1) > 0.001) vfx.add(new Contrast(clamp(c - 1, -1, 1)));
                if (Math.abs(s - 1) > 0.001 || Math.abs(hue) > 0.001) {
                    vfx.add(new HslAdjustment.Builder()
                            .adjustSaturation(clamp((s - 1) * 100, -100, 100))
                            .adjustHue(clamp(hue, -180, 180))
                            .build());
                }
            }

            setPaused(false);
            vids.clear();
            layer = null;
            JSONArray ovs = call.getData().optJSONArray("overlays");
            vids.clear();
            layer = makeLayer(call.getData().optJSONArray("overlays"), cache, w, h, new Clock(inMs, inMs, 0), vids, 0);
            List<androidx.media3.effect.TextureOverlay> tex = new ArrayList<>();
            if (layer != null) tex.add(layer);
            tex.add(0, new PauseGate());
            vfx.add(new OverlayEffect(ImmutableList.copyOf(tex)));

            // clip volume (the app's 0..3 gain) on the clip's own sound
            ImmutableList.Builder<AudioProcessor> afx = ImmutableList.builder();
            double vol = call.getData().optDouble("volume", 1.0);
            if (Math.abs(vol - 1.0) > 0.001) {
                ChannelMixingAudioProcessor mix = new ChannelMixingAudioProcessor();
                for (int ch = 1; ch <= 2; ch++) mix.putChannelMixingMatrix(ChannelMixingMatrix.create(ch, ch).scaleBy((float) vol));
                afx.add(mix);
            }

            // overlay-video sound: a ready-made track from the web side is added onto the clip's own sound
            JSONObject mx = call.getData().optJSONObject("mix");
            mixer = null;
            if (mx != null) {
                String mp = mx.optString("path", "");
                if (!safeName(mp) || !cacheFile(mp).exists() || cacheFile(mp).length() < 8) {
                    rejectJob(call, "bad sound track");
                    return;
                }
                mixer = new OverlayMix(cacheFile(mp), Math.round(mx.optDouble("startMs", 0) * 48.0));
                afx.add(mixer);
            }

            MediaItem mi = new MediaItem.Builder()
                    .setUri(Uri.fromFile(srcFile))
                    .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(inMs).setEndPositionMs(outMs).build())
                    .build();
            // effects sit on the EditedMediaItem (this is what made overlays reach the GPU path in Media3 1.4.x)
            EditedMediaItem item = new EditedMediaItem.Builder(mi)
                    .setEffects(new Effects(afx.build(), ImmutableList.copyOf(vfx)))
                    .build();
            Composition comp = new Composition.Builder(
                    ImmutableList.of(new EditedMediaItemSequence(ImmutableList.of(item)))).build();

            VideoEncoderSettings.Builder vb = new VideoEncoderSettings.Builder().setBitrate(br);
            try { // ask the hardware encoder for maximum throughput when this Media3 build supports it
                VideoEncoderSettings.Builder.class.getMethod("setEncoderPerformanceParameters", int.class, int.class)
                        .invoke(vb, Integer.MAX_VALUE, 1);
            } catch (Throwable ignore) {
            }

            active = call;
            final boolean[] finished = {false};
            Transformer.Builder tb = new Transformer.Builder(getContext());
            try { // long exports must not be killed by the muxer timeout
                Transformer.Builder.class.getMethod("setMaxDelayBetweenMuxerSamplesMs", long.class).invoke(tb, C.TIME_UNSET);
            } catch (Throwable ignore) {
            }
            transformer = tb
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(new DefaultEncoderFactory.Builder(getContext())
                            .setRequestedVideoEncoderSettings(vb.build())
                            .setEnableFallback(false) // never silently use a software encoder (heat, slow)
                            .build())
                    .addListener(new Transformer.Listener() {
                        @Override
                        public void onCompleted(Composition composition, ExportResult r) {
                            if (active != call) return;
                            active = null;
                            setPaused(false);
                            finished[0] = true;
                            JSObject o = new JSObject();
                            StringBuilder dg = new StringBuilder();
                            if (layer != null) {
                                dg.append(layer.diag());
                                layer.close();
                                o.put("prof", layer.prof());
                            }
                            long vs = 0;
                            long sh = 0;
                            for (TurboOverlayVideo v : vids) { vs += v.spentMs(); sh += v.shown; v.closeDecoder(); }
                            o.put("vidShown", sh);
                            long dn = 0, cn2 = 0;
                            int sw = 0;
                            for (TurboOverlayVideo v : vids) {
                                dn += v.decNs;
                                cn2 += v.convNs;
                                if (v.swDec) sw++;
                            }
                            o.put("vdec", dn / 1000000);
                            o.put("vconv", cn2 / 1000000);
                            o.put("vsw", sw);
                            o.put("vidMs", vs);
                            o.put("vidCount", vids.size());
                            o.put("mixFrames", mixer == null ? -1 : mixer.touched);
                            o.put("ovDiag", dg.toString());
                            o.put("par", parNote);
                            try {
                                Object en = ExportResult.class.getField("videoEncoderName").get(r);
                                if (en != null) o.put("enc", String.valueOf(en));
                            } catch (Throwable ignore) {
                            }
                            o.put("path", outFile.getName());
                            o.put("sizeMB", Math.round(outFile.length() / 1048.576) / 1000.0);
                            resolveJob(call, o);
                        }

                        @Override
                        public void onError(Composition composition, ExportResult r, ExportException e) {
                            if (active != call) return;
                            active = null;
                            setPaused(false);
                            finished[0] = true;
                            outFile.delete();
                            if (layer != null) layer.close();
                            for (TurboOverlayVideo v : vids) v.closeDecoder();
                            rejectJob(call, String.valueOf(e.getMessage()) + " (" + e.getErrorCodeName() + ")");
                        }
                    })
                    .build();

            transformer.start(comp, outFile.getAbsolutePath());

            final ProgressHolder ph = new ProgressHolder();
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (finished[0] || transformer == null) return;
                    if (transformer.getProgress(ph) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        JSObject o = new JSObject();
                        o.put("p", ph.progress);
                        jobP = ph.progress;
                        notifyListeners("progress", o);
                    }
                    main.postDelayed(this, 500);
                }
            }, 500);
        } catch (Throwable e) {
            active = null;
            rejectJob(call, e.toString());
        }
    }

    /**
     * TURBO copy of OverlayVideo (independent: no link to FastRenderPlugin / OverlayVideo). Overlay VIDEO for the Turbo export. The overlay file is decoded once, strictly in order, by the
     * hardware MediaCodec (no seeking per frame - that is what made long overlay videos impossible).
     * Every frame is converted to a small bitmap of the size it is shown at, drawn at its place
     * (x, y, size, turn) on a transparent full-frame bitmap, and given to the GPU overlay effect.
     */
    @UnstableApi
    static final class TurboOverlayVideo extends BitmapOverlay {
        private final String path;
        private final int W, H, dw, dh;
        private final long fromUs, toUs, durUs;
        private final float px, py, rotDeg;
        private final Bitmap blank = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        private final Bitmap[] ring = new Bitmap[3];
        private int ri = 0;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

        private MediaExtractor ex;
        private MediaCodec dec;
        private final MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        private int srcRot = 0, frameNo = 0, lastFrameNo = -1;
        private boolean inEos, outEos, converted, hd;
        private Bitmap vb, last;

        /* The overlay video is decoded on its own thread, ahead of the GPU, so the render never waits for it. */
        private static final class Fr {
            final int[] px;
            final long pts;
            Fr(int[] px, long pts) {
                this.px = px;
                this.pts = pts;
            }
        }

        private static final int QN = 4;
        private final ArrayBlockingQueue<Fr> queue = new ArrayBlockingQueue<>(QN);
        private final ArrayBlockingQueue<int[]> free = new ArrayBlockingQueue<>(QN + 3);
        private Thread worker;
        private volatile boolean stop;
        private volatile Exception err;
        private volatile long target;
        private long spentNs;
        private Fr showFr, pendFr;
        private boolean ended;
        private long offUs = 0;
        private long clipInUs = 0;
        private boolean firstCall = true;
        public long shown = 0; // frames actually handed to the GPU
    public volatile long decNs = 0, convNs = 0; // producer thread: decoder time / YUV->ARGB time
    public volatile boolean swDec = false;
    private boolean ckOn = false;
    private int ckR, ckG, ckB;
    private double ckSim, ckSm;

    /** Chroma key (green screen): colour + similarity + smoothness, as in the preview (distances in 0..441). */
    public void setChroma(int r, int g, int b, double sim, double sm) {
        ckOn = true;
        ckR = r;
        ckG = g;
        ckB = b;
        ckSim = sim * 441.0;
        ckSm = Math.max(1.0, sm * 441.0);
    }

        /** Start of the exported cut in the source file: Media3 may hand over absolute source time instead of time from the cut start. */
        public void setClipInUs(long us) {
            this.clipInUs = us;
        }
        private long inUs = 0; // v16: where in the video file this clip starts (split / looped overlay videos)

        public void setInUs(long us) {
            this.inUs = us;
        }

        /** Where this export piece starts on the whole timeline (parallel export). */
        public void setOffsetUs(long us) {
            this.offUs = us;
        }

        public TurboOverlayVideo(String path, int W, int H, long fromUs, long toUs, long durUs,
                            float xPct, float yPct, float sc, float rotDeg, double aspect) {
            this.path = path;
            this.W = W;
            this.H = H;
            this.fromUs = fromUs;
            this.toUs = toUs;
            this.durUs = durUs;
            this.px = xPct;
            this.py = yPct;
            this.rotDeg = rotDeg;
            this.dw = Math.max(2, Math.min(4096, Math.round(W * sc)));
            this.dh = Math.max(2, Math.min(4096, (int) Math.round(this.dw * aspect)));
        }

        private void open() throws Exception {
            if (dec != null) return;
            ex = new MediaExtractor();
            ex.setDataSource(path);
            int vi = -1;
            MediaFormat f = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat tf = ex.getTrackFormat(i);
                String m = tf.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("video/")) {
                    vi = i;
                    f = tf;
                    break;
                }
            }
            if (vi < 0 || f == null) throw new Exception("overlay video: no video track");
            ex.selectTrack(vi);
            if (target > 1500000L) ex.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC); // an export piece that starts in the middle of this overlay video
            srcRot = f.containsKey("rotation-degrees") ? f.getInteger("rotation-degrees") : 0;
            int fw = f.containsKey(MediaFormat.KEY_WIDTH) ? f.getInteger(MediaFormat.KEY_WIDTH) : 0;
            int fh = f.containsKey(MediaFormat.KEY_HEIGHT) ? f.getInteger(MediaFormat.KEY_HEIGHT) : 0;
            hd = Math.max(fw, fh) >= 1280;
            try {
                f.setInteger("operating-rate", Integer.MAX_VALUE);
                f.setInteger("priority", 1);
            } catch (Exception ignore) {
            }
            String vmime = f.getString(MediaFormat.KEY_MIME);
            dec = null;
            if (Math.max(fw, fh) <= 1280) { // small / medium overlay video: the CPU decoder runs in parallel with the hardware codec, which the main video and the encoder already keep busy
                try {
                    MediaCodecInfo[] list = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
                    for (MediaCodecInfo ci : list) {
                        if (ci.isEncoder()) continue;
                        String cn = ci.getName();
                        if (!(cn.startsWith("c2.android.") || cn.startsWith("OMX.google."))) continue;
                        boolean ok = false;
                        for (String ty : ci.getSupportedTypes()) if (ty.equalsIgnoreCase(vmime)) ok = true;
                        if (!ok) continue;
                        dec = MediaCodec.createByCodecName(cn);
                        swDec = true;
                        break;
                    }
                } catch (Throwable e) {
                    dec = null;
                    swDec = false;
                }
            }
            if (dec == null) dec = MediaCodec.createDecoderByType(vmime);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            dec.configure(f, null, null, 0);
            dec.start();
            vb = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
            vb.setHasAlpha(true);
        }

        /** Decode the next frame in order. Returns its time (us) or -1 at the end. Sets 'converted'. */
        private long decodeOne(long mtUs, int[] outArr) throws Exception {
            long waited = 0;
            for (; ; ) {
                if (stop) throw new Exception("stopped");
                if (!inEos) {
                    int ii = dec.dequeueInputBuffer(0);
                    if (ii >= 0) {
                        ByteBuffer b = dec.getInputBuffer(ii);
                        int n = (b == null) ? -1 : ex.readSampleData(b, 0);
                        if (n < 0) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inEos = true;
                        } else {
                            dec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                long dq0 = System.nanoTime();
                int oi = dec.dequeueOutputBuffer(bi, 10000);
                decNs += System.nanoTime() - dq0;
                if (oi >= 0) {
                    boolean eos = (bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    long pts = bi.presentationTimeUs;
                    converted = false;
                    if (bi.size == 0 && eos) {
                        dec.releaseOutputBuffer(oi, false);
                        outEos = true;
                        return -1;
                    }
                    if (pts + 60000 >= mtUs) {
                        Image img = dec.getOutputImage(oi);
                        if (img != null) {
                            long cv0 = System.nanoTime();
                            convert(img, outArr);
                            convNs += System.nanoTime() - cv0;
                            img.close();
                            converted = true;
                        }
                    }
                    dec.releaseOutputBuffer(oi, false);
                    if (eos) outEos = true;
                    return pts;
                }
                waited += 10;
                if (waited > 20000) throw new Exception("overlay video: decoder stalled");
            }
        }

        /** YUV -> ARGB, straight into the size the overlay is shown at (nearest sample), with rotation. */
        private byte[] yArr = new byte[0], uArr = new byte[0], vArr = new byte[0];

        private static int fill(ByteBuffer b, byte[] dst) {
            ByteBuffer d = b.duplicate();
            d.position(0);
            int n = Math.min(d.remaining(), dst.length);
            d.get(dst, 0, n);
            return n;
        }

        private void convert(Image img, int[] out) {
            Image.Plane[] pl = img.getPlanes();
            ByteBuffer yb0 = pl[0].getBuffer(), ub0 = pl[1].getBuffer(), vb0 = pl[2].getBuffer();
            if (yArr.length < yb0.capacity()) yArr = new byte[yb0.capacity()];
            if (uArr.length < ub0.capacity()) uArr = new byte[ub0.capacity()];
            if (vArr.length < vb0.capacity()) vArr = new byte[vb0.capacity()];
            fill(yb0, yArr);
            fill(ub0, uArr);
            fill(vb0, vArr);
            final byte[] yb = yArr, ub = uArr, vbuf = vArr; // plain arrays: much faster than ByteBuffer.get() per pixel
            int ys = pl[0].getRowStride();
            int us = pl[1].getRowStride(), up = pl[1].getPixelStride();
            int vs = pl[2].getRowStride(), vp = pl[2].getPixelStride();
            Rect cr = img.getCropRect();
            int cw = cr.width(), ch = cr.height(), x0 = cr.left, y0 = cr.top;
            boolean swap = (srcRot == 90 || srcRot == 270);
            int dW = swap ? ch : cw, dH = swap ? cw : ch;
            for (int y = 0; y < dh; y++) {
                int v = (int) (((long) y * 2 + 1) * dH / (2L * dh));
                if (v >= dH) v = dH - 1;
                for (int x = 0; x < dw; x++) {
                    int u = (int) (((long) x * 2 + 1) * dW / (2L * dw));
                    if (u >= dW) u = dW - 1;
                    int sx, sy;
                    switch (srcRot) {
                        case 90:
                            sx = v;
                            sy = ch - 1 - u;
                            break;
                        case 180:
                            sx = cw - 1 - u;
                            sy = ch - 1 - v;
                            break;
                        case 270:
                            sx = cw - 1 - v;
                            sy = u;
                            break;
                        default:
                            sx = u;
                            sy = v;
                            break;
                    }
                    int gx = x0 + sx, gy = y0 + sy;
                    int Y = yb[gy * ys + gx] & 0xFF;
                    int U = ub[(gy >> 1) * us + (gx >> 1) * up] & 0xFF;
                    int V = vbuf[(gy >> 1) * vs + (gx >> 1) * vp] & 0xFF;
                    int yy = Y - 16;
                    if (yy < 0) yy = 0;
                    int uu = U - 128, vv = V - 128;
                    int r, g, b;
                    if (hd) {
                        r = (1192 * yy + 1836 * vv) >> 10;
                        g = (1192 * yy - 218 * uu - 546 * vv) >> 10;
                        b = (1192 * yy + 2163 * uu) >> 10;
                    } else {
                        r = (1192 * yy + 1634 * vv) >> 10;
                        g = (1192 * yy - 401 * uu - 832 * vv) >> 10;
                        b = (1192 * yy + 2066 * uu) >> 10;
                    }
                    if (r < 0) r = 0;
                    else if (r > 255) r = 255;
                    if (g < 0) g = 0;
                    else if (g > 255) g = 255;
                    if (b < 0) b = 0;
                    else if (b > 255) b = 255;
                    if (ckOn) { // same formula as the preview's chroma key: transparent below `sim`, a soft edge up to `sim + sm`, green spill removed on the edge
                        double dr = r - ckR, dg = g - ckG, db = b - ckB, dist = Math.sqrt(dr * dr + dg * dg + db * db);
                        int al = 255;
                        if (dist < ckSim) al = 0;
                        else if (dist < ckSim + ckSm) {
                            al = (int) (255 * (dist - ckSim) / ckSm);
                            if (ckG > ckR && ckG > ckB && g > Math.max(r, b)) g = Math.max(r, b);
                        }
                        out[y * dw + x] = (al << 24) | (r << 16) | (g << 8) | b;
                    } else {
                        out[y * dw + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                }
            }
        }

        /** Producer thread: decode in order, convert, hand the frames over through a small queue. */
        private void produce() {
            int[] arr = null;
            try {
                open();
                for (int i = 0; i < QN + 3; i++) free.add(new int[dw * dh]);
                while (!stop && !outEos) {
                    if (arr == null) {
                        while (!stop && arr == null) arr = free.poll(50, TimeUnit.MILLISECONDS);
                        if (stop) break;
                    }
                    long pts = decodeOne(target, arr);
                    if (pts < 0 || !converted) continue;
                    Fr f = new Fr(arr, pts);
                    arr = null;
                    while (!stop && !queue.offer(f, 50, TimeUnit.MILLISECONDS)) {
                    }
                }
                if (!stop) {
                    Fr end = new Fr(null, Long.MAX_VALUE);
                    while (!stop && !queue.offer(end, 50, TimeUnit.MILLISECONDS)) {
                    }
                }
            } catch (Exception e) {
                if (!stop) err = e;
            }
        }

        private void startWorker() {
            stop = false;
            err = null;
            ended = false;
            showFr = null;
            pendFr = null;
            queue.clear();
            free.clear();
            inEos = false;
            outEos = false;
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    produce();
                }
            }, "turbo-overlay-video");
            worker.start();
        }

        private void stopWorker() {
            stop = true;
            if (worker != null) {
                try {
                    worker.join(3000);
                } catch (InterruptedException ignore) {
                }
                worker = null;
            }
            try {
                if (dec != null) {
                    dec.stop();
                    dec.release();
                }
            } catch (Exception ignore) {
            }
            try {
                if (ex != null) ex.release();
            } catch (Exception ignore) {
            }
            dec = null;
            ex = null;
        }

        private void advance(long mt) throws Exception {
            target = mt;
            if (worker == null) startWorker();
            if (showFr != null && mt + 100000 < showFr.pts) {
                stopWorker();
                startWorker();
            }
            for (; ; ) {
                if (pendFr != null) {
                    if (pendFr.pts <= mt) {
                        if (showFr != null) free.offer(showFr.px);
                        showFr = pendFr;
                        pendFr = null;
                        frameNo++;
                        continue;
                    }
                    break;
                }
                if (ended) break;
                Fr f = null;
                long waited = 0;
                while (f == null) {
                    if (err != null) throw err;
                    f = queue.poll(20, TimeUnit.MILLISECONDS);
                    waited += 20;
                    if (f == null && waited > 20000) throw new Exception("overlay video: decoder stalled");
                }
                if (f.px == null) {
                    ended = true;
                    break;
                }
                pendFr = f;
            }
        }

        @Override
        public Bitmap getBitmap(long presentationTimeUs) throws VideoFrameProcessingException {
            if (firstCall) {
                firstCall = false;
                if (clipInUs > 2000000L && Math.abs(presentationTimeUs - clipInUs) < 1500000L) offUs = -clipInUs;
            }
            presentationTimeUs += offUs;
            long t0 = System.nanoTime();
            try {
                if (presentationTimeUs >= toUs && dec != null) closeDecoder(); // v16: free the decoder as soon as this part is over (many looped parts)
                if (presentationTimeUs < fromUs || presentationTimeUs >= toUs) return blank;
                long mt = presentationTimeUs - fromUs + inUs;
                if (durUs > 0 && mt > durUs) mt = durUs;
                advance(mt);
                if (showFr == null) return blank;
                if (frameNo == lastFrameNo && last != null) {
                    shown++;
                    return last;
                }
                vb.setPixels(showFr.px, 0, dw, 0, 0, dw, dh);
                Bitmap out = ring[ri];
                if (out == null) {
                    out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
                    ring[ri] = out;
                }
                ri = (ri + 1) % ring.length;
                out.eraseColor(Color.TRANSPARENT);
                Canvas c = new Canvas(out);
                c.translate(W * px / 100f, H * py / 100f);
                c.rotate(rotDeg);
                c.drawBitmap(vb, -dw / 2f, -dh / 2f, paint);
                last = out;
                lastFrameNo = frameNo;
                shown++;
                return out;
            } catch (Exception e) {
                throw new VideoFrameProcessingException("overlay video: " + e, e);
            } finally {
                spentNs += System.nanoTime() - t0;
            }
        }

        /** Draws this overlay's current frame onto the given canvas (time from the clip start). Returns true when a frame was drawn. */
        public boolean drawOnto(Canvas c, long presentationTimeUs) throws Exception {
            long t0 = System.nanoTime();
            try {
                if (presentationTimeUs >= toUs && dec != null) closeDecoder();
                if (presentationTimeUs < fromUs || presentationTimeUs >= toUs) return false;
                long mt = presentationTimeUs - fromUs + inUs;
                if (durUs > 0 && mt > durUs) mt = durUs;
                advance(mt);
                if (showFr == null) return false;
                if (frameNo != lastFrameNo) {
                    vb.setPixels(showFr.px, 0, dw, 0, 0, dw, dh);
                    lastFrameNo = frameNo;
                }
                c.save();
                c.translate(W * px / 100f, H * py / 100f);
                c.rotate(rotDeg);
                c.drawBitmap(vb, -dw / 2f, -dh / 2f, paint);
                c.restore();
                shown++;
                return true;
            } finally {
                spentNs += System.nanoTime() - t0;
            }
        }

        /** Same as drawOnto, but with a transform given per frame: centre (x, y) in pixels, scale (1 = the size decoded), turn in degrees, opacity 0..1. */
        public boolean drawOntoXf(Canvas c, long presentationTimeUs, float x, float y, float s, float rot, float alpha) throws Exception {
            long t0 = System.nanoTime();
            try {
                if (presentationTimeUs >= toUs && dec != null) closeDecoder();
                if (presentationTimeUs < fromUs || presentationTimeUs >= toUs) return false;
                long mt = presentationTimeUs - fromUs + inUs;
                if (durUs > 0 && mt > durUs) mt = durUs;
                advance(mt);
                if (showFr == null) return false;
                if (frameNo != lastFrameNo) {
                    vb.setPixels(showFr.px, 0, dw, 0, 0, dw, dh);
                    lastFrameNo = frameNo;
                }
                int old = paint.getAlpha();
                paint.setAlpha(Math.max(0, Math.min(255, Math.round(alpha * 255f))));
                c.save();
                c.translate(x, y);
                c.rotate(rot);
                c.scale(s, s);
                c.drawBitmap(vb, -dw / 2f, -dh / 2f, paint);
                c.restore();
                paint.setAlpha(old);
                shown++;
                return true;
            } finally {
                spentNs += System.nanoTime() - t0;
            }
        }

        /** Time (ms) the render spent inside this overlay (includes waiting for the decoder). */
        public long spentMs() {
            return spentNs / 1000000L;
        }

        public void closeDecoder() {
            stopWorker();
        }
    }
}
