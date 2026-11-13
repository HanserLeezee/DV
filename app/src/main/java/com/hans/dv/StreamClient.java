package com.hans.dv;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * 网络接收 + 硬件解码。
 * 协议见 PC 端 dv_server.py: uint32 len | uint8 type | int64 pts_us | payload
 */
final class StreamClient {

    interface Listener {
        void onStatus(String text);          // null = 隐藏状态文字

        void onVideoSize(int width, int height);

        void onStats(String text);
    }

    private static final String TAG = "DV";
    private static final String MIME_AVC = "video/avc";
    private static final String MIME_HEVC = "video/hevc";
    private static final int MSG_INFO = 0, MSG_CONFIG = 1, MSG_KEY = 2, MSG_FRAME = 3;
    private static final byte[] START = {0, 0, 0, 1};
    private static final byte[] HELLO_STREAM = {'D', 'V', '1', 'S'};
    private static final byte[] HELLO_PROBE = {'D', 'V', '1', 'P'};

    /** 探测 host:port 上有没有 DV 服务 (不会启动推流)。USB 转发在电脑端没开服务时也能连上, 所以必须握手确认。 */
    static boolean probe(String host, int port, int connectTimeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            s.setSoTimeout(1500);
            s.getOutputStream().write(HELLO_PROBE);
            s.getOutputStream().flush();
            byte[] b = new byte[3];
            new DataInputStream(s.getInputStream()).readFully(b);
            return b[0] == 'D' && b[1] == 'V' && b[2] == '!';
        } catch (Exception e) {
            return false;
        }
    }

    private final String host;
    private final int port;
    private final Surface surface;
    private final Listener listener;

    private volatile boolean running;
    private Thread netThread;
    private volatile Socket socket;
    private Decoder decoder;

    private int infoW = 1920, infoH = 1080;
    private String infoCodec = "", infoEncoder = "";
    private double infoBitrate;
    private byte[] curConfig;
    private byte[] buf = new byte[1 << 20];

    // 统计
    private long statBytes, statT0;
    private volatile int statRendered, statDropped;
    private volatile long latencySum, latencyCount;

    StreamClient(String host, int port, Surface surface, Listener listener) {
        this.host = host;
        this.port = port;
        this.surface = surface;
        this.listener = listener;
    }

    void start() {
        running = true;
        netThread = new Thread(this::netLoop, "dv-net");
        netThread.setPriority(Thread.MAX_PRIORITY);
        netThread.start();
    }

    void stop() {
        running = false;
        Socket s = socket;
        if (s != null) try {
            s.close();
        } catch (IOException ignored) {
        }
        if (netThread != null) {
            try {
                netThread.join(1500);
            } catch (InterruptedException ignored) {
            }
        }
        releaseDecoder();
    }

    // ------------------------------------------------------------------ 网络

    private void netLoop() {
        while (running) {
            try {
                listener.onStatus("正在连接 " + host + ":" + port + " ...");
                session();
            } catch (Exception e) {
                if (!running) break;
                Log.w(TAG, "session ended", e);
                String why = e instanceof java.io.EOFException ? "电脑端关闭了连接 (DV 没在运行?)"
                        : e instanceof java.net.ConnectException ? "连不上电脑 (DV 没在运行?)"
                        : e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                listener.onStatus("连接断开: " + why + "\n正在重连...");
            } finally {
                Socket s = socket;
                socket = null;
                if (s != null) try {
                    s.close();
                } catch (IOException ignored) {
                }
                releaseDecoder();
            }
            sleep(1000);
        }
    }

    private void session() throws IOException {
        Socket s = new Socket();
        socket = s;
        s.setTcpNoDelay(true);
        s.setReceiveBufferSize(1 << 20);
        s.connect(new InetSocketAddress(host, port), 3000);
        s.setSoTimeout(5000);
        s.getOutputStream().write(HELLO_STREAM);
        s.getOutputStream().flush();
        listener.onStatus("已连接, 等待画面...");
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 1 << 16));
        boolean waitKey = true;
        statT0 = System.nanoTime();
        statBytes = 0;

        while (running) {
            int len = in.readInt();
            int type = in.readUnsignedByte();
            long pts = in.readLong();
            if (len < 0 || len > (64 << 20)) throw new IOException("bad length " + len);
            if (buf.length < len) buf = new byte[Math.max(len, buf.length * 2)];
            in.readFully(buf, 0, len);
            statBytes += len;

            switch (type) {
                case MSG_INFO: {
                    JSONObject o;
                    try {
                        o = new JSONObject(new String(buf, 0, len, StandardCharsets.UTF_8));
                    } catch (Exception e) {
                        throw new IOException("bad info");
                    }
                    int w = o.optInt("width", 0), h = o.optInt("height", 0);
                    infoCodec = o.optString("codec", "h264");
                    infoEncoder = o.optString("encoder", "");
                    infoBitrate = o.optDouble("bitrate", 0);
                    if (w > 0 && h > 0) {
                        infoW = w;
                        infoH = h;
                        listener.onVideoSize(w, h);
                    }
                    break;
                }
                case MSG_CONFIG: {
                    // codec(1) | n(1) | n * (len16 + NAL)
                    byte[] cfg = Arrays.copyOf(buf, len);
                    if (decoder == null || !Arrays.equals(cfg, curConfig)) {
                        boolean hevc = cfg[0] == 1;
                        int n = cfg[1] & 0xFF, i = 2;
                        byte[][] nals = new byte[n][];
                        for (int k = 0; k < n; k++) {
                            int l = ((cfg[i] & 0xFF) << 8) | (cfg[i + 1] & 0xFF);
                            nals[k] = Arrays.copyOfRange(cfg, i + 2, i + 2 + l);
                            i += 2 + l;
                        }
                        releaseDecoder();
                        curConfig = cfg;
                        decoder = new Decoder(hevc, nals, infoW, infoH);
                        waitKey = true;
                    }
                    break;
                }
                case MSG_KEY:
                case MSG_FRAME: {
                    if (decoder == null) break;
                    if (waitKey && type != MSG_KEY) break;
                    if (waitKey) listener.onStatus(null);
                    waitKey = !decoder.feed(buf, len, pts, type == MSG_KEY);
                    break;
                }
                default:
                    break;
            }
            reportStats();
        }
    }

    private void reportStats() {
        long now = System.nanoTime();
        long dt = now - statT0;
        if (dt < 1_000_000_000L) return;
        double sec = dt / 1e9;
        long lc = latencyCount;
        String t = String.format(Locale.US, "%s %dx%d  %.0f fps  %.1f Mbps (上限 %.0f)  解码+显示 %s  丢弃 %d",
                infoCodec.equals("hevc") ? "H.265" : "H.264",
                infoW, infoH, statRendered / sec, statBytes * 8 / sec / 1e6, infoBitrate,
                lc > 0 ? (latencySum / lc / 1000) + " ms" : "-", statDropped);
        listener.onStats(t);
        statT0 = now;
        statBytes = 0;
        statRendered = 0;
        latencySum = 0;
        latencyCount = 0;
    }

    private synchronized void releaseDecoder() {
        if (decoder != null) {
            decoder.release();
            decoder = null;
        }
        curConfig = null;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    // ------------------------------------------------------------------ 解码

    private final class Decoder {
        private final MediaCodec codec;
        private final Thread outThread;
        private volatile boolean alive = true;
        private volatile String error;

        // pts -> 送入解码器的时间, 用来算 "解码+显示" 延迟
        private final long[] ringPts = new long[128];
        private final long[] ringT = new long[128];
        private int ringPos;

        Decoder(boolean hevc, byte[][] nals, int w, int h) throws IOException {
            String mime = hevc ? MIME_HEVC : MIME_AVC;
            MediaFormat base = MediaFormat.createVideoFormat(mime, w, h);
            String name = pickDecoder(mime, base);
            Log.i(TAG, "decoder: " + name + " " + w + "x" + h);

            MediaCodec c = null;
            Exception last = null;
            for (int attempt = 0; attempt < 2 && c == null; attempt++) {
                MediaFormat f = buildFormat(mime, hevc, nals, w, h, name, attempt == 0);
                MediaCodec tmp = null;
                try {
                    tmp = name != null ? MediaCodec.createByCodecName(name)
                            : MediaCodec.createDecoderByType(mime);
                    tmp.configure(f, surface, null, 0);
                    tmp.start();
                    c = tmp;
                } catch (Exception e) {
                    last = e;
                    if (tmp != null) tmp.release();
                }
            }
            if (c == null) throw new IOException("解码器启动失败: " + last);
            codec = c;
            outThread = new Thread(this::outputLoop, "dv-render");
            outThread.setPriority(Thread.MAX_PRIORITY);
            outThread.start();
        }

        private MediaFormat buildFormat(String mime, boolean hevc, byte[][] nals, int w, int h,
                                        String name, boolean vendor) {
            MediaFormat f = MediaFormat.createVideoFormat(mime, w, h);
            if (hevc) {
                // H.265: VPS+SPS+PPS 全部放进 csd-0
                f.setByteBuffer("csd-0", withStartCodes(nals, 0, nals.length));
            } else {
                // H.264: csd-0 = SPS, csd-1 = PPS
                int nSps = Math.max(1, nals.length - 1);
                f.setByteBuffer("csd-0", withStartCodes(nals, 0, nSps));
                if (nals.length > nSps) f.setByteBuffer("csd-1", withStartCodes(nals, nSps, nals.length));
            }
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.max(w * h, 2 << 20));
            if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            if (Build.VERSION.SDK_INT >= 23) f.setInteger(MediaFormat.KEY_PRIORITY, 0);
            if (vendor && name != null) {
                String n = name.toLowerCase(Locale.US);
                if (n.contains("qcom") || n.contains("qti")) {          // 高通
                    f.setInteger("vendor.qti-ext-dec-picture-order.enable", 1);
                    f.setInteger("vendor.qti-ext-dec-low-latency.enable", 1);
                } else if (n.contains("exynos")) {                      // 三星
                    f.setInteger("vendor.rtc-ext-dec-low-latency.enable", 1);
                } else if (n.contains("hisi")) {                        // 华为海思
                    f.setInteger("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req", 1);
                    f.setInteger("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy", -1);
                } else if (n.contains("mtk")) {                         // 联发科
                    f.setInteger("vendor.mtk.vdec.low-latency.enable", 1);
                }
            }
            return f;
        }

        /** 返回 false 表示这一帧没送进去, 需要等下一个关键帧。 */
        boolean feed(byte[] data, int len, long pts, boolean key) throws IOException {
            if (error != null) throw new IOException(error);
            int idx;
            try {
                long deadline = System.nanoTime() + 500_000_000L;
                while ((idx = codec.dequeueInputBuffer(10_000)) < 0) {
                    if (!running || error != null) throw new IOException("stopped");
                    if (System.nanoTime() > deadline) throw new IOException("解码器卡住");
                }
                ByteBuffer ib = codec.getInputBuffer(idx);
                if (ib == null || ib.capacity() < len) {
                    codec.queueInputBuffer(idx, 0, 0, pts, 0);
                    statDropped++;
                    return false;
                }
                ib.clear();
                ib.put(data, 0, len);
                synchronized (ringPts) {
                    ringPts[ringPos] = pts;
                    ringT[ringPos] = System.nanoTime();
                    ringPos = (ringPos + 1) % ringPts.length;
                }
                codec.queueInputBuffer(idx, 0, len, pts, key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                return true;
            } catch (IllegalStateException e) {
                throw new IOException("解码器错误: " + e.getMessage());
            }
        }

        private void outputLoop() {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo next = new MediaCodec.BufferInfo();
            try {
                while (alive) {
                    int idx = codec.dequeueOutputBuffer(info, 20_000);
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        onFormat(codec.getOutputFormat());
                        continue;
                    }
                    if (idx < 0) continue;
                    // 如果已经有更新的帧解出来了, 跳过旧的, 只显示最新的
                    while (true) {
                        int n = codec.dequeueOutputBuffer(next, 0);
                        if (n >= 0) {
                            codec.releaseOutputBuffer(idx, false);
                            statDropped++;
                            idx = n;
                            MediaCodec.BufferInfo t = info;
                            info = next;
                            next = t;
                        } else if (n == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            onFormat(codec.getOutputFormat());
                        } else {
                            break;
                        }
                    }
                    codec.releaseOutputBuffer(idx, true);
                    statRendered++;
                    long now = System.nanoTime();
                    synchronized (ringPts) {
                        for (int i = 0; i < ringPts.length; i++) {
                            if (ringPts[i] == info.presentationTimeUs && ringT[i] != 0) {
                                latencySum += (now - ringT[i]) / 1000;
                                latencyCount++;
                                ringT[i] = 0;
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                if (alive) {
                    Log.e(TAG, "render loop", e);
                    error = "解码出错: " + e.getMessage();
                }
            }
        }

        private void onFormat(MediaFormat f) {
            if (!f.containsKey(MediaFormat.KEY_WIDTH) || !f.containsKey(MediaFormat.KEY_HEIGHT)) return;
            int w = f.getInteger(MediaFormat.KEY_WIDTH);
            int h = f.getInteger(MediaFormat.KEY_HEIGHT);
            if (f.containsKey("crop-right") && f.containsKey("crop-left")) {
                w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1;
            }
            if (f.containsKey("crop-bottom") && f.containsKey("crop-top")) {
                h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1;
            }
            if (w > 0 && h > 0) listener.onVideoSize(w, h);
        }

        void release() {
            alive = false;
            try {
                outThread.join(500);
            } catch (InterruptedException ignored) {
            }
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            try {
                codec.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static ByteBuffer withStartCodes(byte[][] nals, int from, int to) {
        int size = 0;
        for (int i = from; i < to; i++) size += START.length + nals[i].length;
        ByteBuffer b = ByteBuffer.allocate(size);
        for (int i = from; i < to; i++) b.put(START).put(nals[i]);
        b.flip();
        return b;
    }

    /** 选硬件解码器, 优先支持低延迟特性的。 */
    private static String pickDecoder(String mime, MediaFormat fmt) {
        String best = null;
        int bestScore = -1;
        try {
            MediaCodecInfo[] infos = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
            for (MediaCodecInfo ci : infos) {
                if (ci.isEncoder()) continue;
                boolean avc = false;
                for (String t : ci.getSupportedTypes()) if (t.equalsIgnoreCase(mime)) avc = true;
                if (!avc) continue;
                String n = ci.getName().toLowerCase(Locale.US);
                boolean sw = n.startsWith("omx.google.") || n.startsWith("c2.android.")
                        || n.contains(".sw.") || n.contains("ffmpeg");
                if (Build.VERSION.SDK_INT >= 29) sw = ci.isSoftwareOnly();
                int score = sw ? 0 : 10;
                try {
                    MediaCodecInfo.CodecCapabilities caps = ci.getCapabilitiesForType(mime);
                    if (Build.VERSION.SDK_INT >= 30
                            && caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) {
                        score += 5;
                    }
                    if (!caps.isFormatSupported(fmt)) score -= 8;
                } catch (Exception ignored) {
                }
                if (n.contains("secure")) continue;
                if (score > bestScore) {
                    bestScore = score;
                    best = ci.getName();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "pickDecoder", e);
        }
        return best;
    }
}
