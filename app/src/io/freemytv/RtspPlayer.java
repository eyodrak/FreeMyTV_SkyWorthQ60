package io.freemytv;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.util.Base64;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A camera's live video: RTSP with RTP inside the TCP connection (interleaved, so Wi-Fi drops nothing), H.264 or H.265
 * straight to the TV's hardware decoder. Android's own RTSP player is made for video calls: it takes UDP and throws
 * away every frame that comes a little late, and a camera's H.265 never shows. Video only (the cameras stay quiet).
 */
final class RtspPlayer {
    interface Listener {
        void onPlaying();
        void onFailed(String why);
    }

    private static final int TIMEOUT_MS = 10_000, KEEPALIVE_MS = 25_000;

    private final String url;
    private final Surface surface;
    private final Listener listener;
    private volatile boolean stopped;
    private volatile Socket socket;
    private Thread thread;

    // RTSP
    private OutputStream out;
    private DataInputStream in;
    private int cseq;
    private String user = "", password = "", realm, nonce, session;
    private boolean basic;

    // Video
    private boolean hevc;
    private int payloadType = -1;
    private byte[] vps, sps, pps;
    private MediaCodec codec;
    private boolean keyFrameSeen, playing;
    private final ByteArrayOutputStream unit = new ByteArrayOutputStream(256 * 1024);   // the access unit being built
    private boolean unitKey;
    private final ByteArrayOutputStream fu = new ByteArrayOutputStream(256 * 1024);     // a NAL in fragments
    private boolean fuOn;

    private static final byte[] START = {0, 0, 0, 1};

    RtspPlayer(String url, Surface surface, Listener listener) {
        this.url = url;
        this.surface = surface;
        this.listener = listener;
    }

    void start() {
        thread = new Thread(this::run, "rtsp");
        thread.start();
    }

    void stop() {
        stopped = true;
        Socket s = socket;
        if (s != null) try { s.close(); } catch (IOException ignored) {}
        if (thread != null) thread.interrupt();
    }

    private void run() {
        try {
            play();
        } catch (Exception e) {
            if (!stopped) {
                AppLog.w("rtsp: " + e.getClass().getSimpleName() + " " + e.getMessage());
                listener.onFailed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        } finally {
            Socket s = socket;
            if (s != null) try { s.close(); } catch (IOException ignored) {}
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                codec.release();
                codec = null;
            }
        }
    }

    // MARK: - RTSP

    private void play() throws Exception {
        Uri u = Uri.parse(url);
        String info = u.getEncodedUserInfo();
        if (info != null) {
            int colon = info.indexOf(':');
            user = Uri.decode(colon < 0 ? info : info.substring(0, colon));
            password = colon < 0 ? "" : Uri.decode(info.substring(colon + 1));
        }
        int port = u.getPort() > 0 ? u.getPort() : 554;
        String base = "rtsp://" + u.getHost() + ":" + port + (u.getEncodedPath() == null ? "/" : u.getEncodedPath())
                + (u.getEncodedQuery() == null ? "" : "?" + u.getEncodedQuery());
        Socket s = new Socket();
        socket = s;
        s.connect(new InetSocketAddress(u.getHost(), port), TIMEOUT_MS);
        s.setSoTimeout(TIMEOUT_MS);
        s.setTcpNoDelay(true);
        s.setReceiveBufferSize(1 << 20);
        out = s.getOutputStream();
        in = new DataInputStream(new java.io.BufferedInputStream(s.getInputStream(), 1 << 16));

        Response r = request("DESCRIBE", base, "Accept: application/sdp\r\n");
        String contentBase = r.headers.get("content-base");
        String sdpBase = contentBase != null ? contentBase : base;
        String control = parseSdp(r.body);
        String track = control == null ? sdpBase
                : control.startsWith("rtsp://") ? control
                : (sdpBase.endsWith("/") ? sdpBase : sdpBase + "/") + control;

        r = request("SETUP", track, "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n");
        String sess = r.headers.get("session");
        if (sess == null) throw new IOException("no session");
        int semi = sess.indexOf(';');
        session = (semi < 0 ? sess : sess.substring(0, semi)).trim();
        request("PLAY", sdpBase, "Range: npt=0.000-\r\n");
        receive(sdpBase);
    }

    private static final class Response {
        int status;
        final Map<String, String> headers = new HashMap<>();
        String body = "";
    }

    /** Sends a request and reads its answer (on 401, once more with the camera's digest or basic login). */
    private Response request(String method, String uri, String extra) throws Exception {
        Response r = send(method, uri, extra);
        if (r.status == 401 && !user.isEmpty()) {
            String www = r.headers.get("www-authenticate");
            if (www == null) throw new IOException("login refused");
            basic = !www.toLowerCase(Locale.ROOT).contains("digest");
            realm = field(www, "realm");
            nonce = field(www, "nonce");
            r = send(method, uri, extra);
        }
        if (r.status == 401) throw new IOException("wrong user or password");
        if (r.status != 200) throw new IOException(method + " " + r.status);
        return r;
    }

    private Response send(String method, String uri, String extra) throws Exception {
        StringBuilder b = new StringBuilder();
        b.append(method).append(' ').append(uri).append(" RTSP/1.0\r\nCSeq: ").append(++cseq)
                .append("\r\nUser-Agent: FreeMyTV\r\n").append(extra);
        if (session != null) b.append("Session: ").append(session).append("\r\n");
        String auth = authorization(method, uri);
        if (auth != null) b.append("Authorization: ").append(auth).append("\r\n");
        b.append("\r\n");
        synchronized (this) {
            out.write(b.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        // Video may already be coming in (a keep-alive during PLAY): skip it until the answer.
        while (true) {
            int first = in.readUnsignedByte();
            if (first == '$') {
                in.readUnsignedByte();
                in.skipBytes(in.readUnsignedShort());
                continue;
            }
            return readResponse(first);
        }
    }

    private Response readResponse(int first) throws IOException {
        Response r = new Response();
        String line = (char) first + readLine();
        Matcher m = Pattern.compile("RTSP/1\\.0 (\\d+)").matcher(line);
        if (!m.find()) throw new IOException("bad answer");
        r.status = Integer.parseInt(m.group(1));
        int length = 0;
        while (!(line = readLine()).isEmpty()) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT), value = line.substring(colon + 1).trim();
            // Several WWW-Authenticate: keep the digest one
            if (key.equals("www-authenticate") && r.headers.containsKey(key) && !value.toLowerCase(Locale.ROOT).startsWith("digest")) continue;
            r.headers.put(key, value);
            if (key.equals("content-length")) length = Integer.parseInt(value);
        }
        if (length > 0) {
            byte[] body = new byte[length];
            in.readFully(body);
            r.body = new String(body, StandardCharsets.UTF_8);
        }
        return r;
    }

    private String readLine() throws IOException {
        StringBuilder b = new StringBuilder();
        int c;
        while ((c = in.readUnsignedByte()) != '\n') if (c != '\r') b.append((char) c);
        return b.toString();
    }

    private String authorization(String method, String uri) throws Exception {
        if (realm == null) return null;
        if (basic) return "Basic " + Base64.encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String ha1 = md5(user + ":" + realm + ":" + password), ha2 = md5(method + ":" + uri);
        return "Digest username=\"" + user + "\", realm=\"" + realm + "\", nonce=\"" + nonce + "\", uri=\"" + uri
                + "\", response=\"" + md5(ha1 + ":" + nonce + ":" + ha2) + "\"";
    }

    private static String md5(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder();
        for (byte x : d) b.append(String.format(Locale.ROOT, "%02x", x));
        return b.toString();
    }

    private static String field(String header, String name) {
        Matcher m = Pattern.compile(name + "=\"([^\"]*)\"").matcher(header);
        return m.find() ? m.group(1) : null;
    }

    /** The video track of the SDP: codec, payload type, parameter sets; returns its control address. */
    private String parseSdp(String sdp) throws IOException {
        String control = null;
        boolean video = false;
        for (String raw : sdp.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("m=")) {
                if (video && payloadType >= 0) break;   // the video section is over
                video = line.startsWith("m=video");
                if (video) {
                    String[] p = line.split(" ");
                    if (p.length > 3) payloadType = Integer.parseInt(p[3]);
                }
            } else if (video && line.startsWith("a=rtpmap:")) {
                String codecName = line.substring(line.indexOf(' ') + 1).toUpperCase(Locale.ROOT);
                hevc = codecName.startsWith("H265") || codecName.startsWith("HEVC");
                if (!hevc && !codecName.startsWith("H264")) throw new IOException("video " + codecName);
            } else if (video && line.startsWith("a=control:")) {
                control = line.substring(10);
            } else if (video && line.startsWith("a=fmtp:")) {
                for (String param : line.substring(line.indexOf(' ') + 1).split(";")) {
                    String[] kv = param.trim().split("=", 2);
                    if (kv.length < 2) continue;
                    switch (kv[0]) {
                        case "sprop-vps": vps = b64(kv[1]); break;
                        case "sprop-sps": sps = b64(kv[1]); break;
                        case "sprop-pps": pps = b64(kv[1]); break;
                        case "sprop-parameter-sets": {
                            String[] sets = kv[1].split(",");
                            sps = b64(sets[0]);
                            if (sets.length > 1) pps = b64(sets[1]);
                            break;
                        }
                        default:
                    }
                }
            }
        }
        if (payloadType < 0) throw new IOException("no video");
        return control;
    }

    private static byte[] b64(String s) {
        try {
            byte[] b = Base64.decode(s.trim(), Base64.DEFAULT);
            return b.length == 0 ? null : b;   // "sprop-parameter-sets=" (EZVIZ): they come in the stream
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // MARK: - RTP

    private void receive(String keepAliveUri) throws Exception {
        long lastKeepAlive = System.currentTimeMillis();
        byte[] buf = new byte[65536];
        while (!stopped) {
            int first = in.readUnsignedByte();
            if (first != '$') {   // the answer to a keep-alive
                readResponse(first);
                continue;
            }
            int channel = in.readUnsignedByte(), length = in.readUnsignedShort();
            in.readFully(buf, 0, length);
            if (channel == 0) rtp(buf, length);
            long now = System.currentTimeMillis();
            if (now - lastKeepAlive > KEEPALIVE_MS) {
                lastKeepAlive = now;
                StringBuilder b = new StringBuilder("GET_PARAMETER ").append(keepAliveUri).append(" RTSP/1.0\r\nCSeq: ")
                        .append(++cseq).append("\r\nSession: ").append(session).append("\r\n");
                String auth = authorization("GET_PARAMETER", keepAliveUri);
                if (auth != null) b.append("Authorization: ").append(auth).append("\r\n");
                out.write(b.append("\r\n").toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }
    }

    private void rtp(byte[] p, int length) throws Exception {
        if (length < 12) return;
        int cc = p[0] & 0x0F;
        boolean extension = (p[0] & 0x10) != 0, padding = (p[0] & 0x20) != 0, marker = (p[1] & 0x80) != 0;
        if ((p[1] & 0x7F) != payloadType) return;
        long timestamp = ((p[4] & 0xFFL) << 24) | ((p[5] & 0xFF) << 16) | ((p[6] & 0xFF) << 8) | (p[7] & 0xFF);
        int off = 12 + 4 * cc;
        if (extension && off + 4 <= length) off += 4 + 4 * (((p[off + 2] & 0xFF) << 8) | (p[off + 3] & 0xFF));
        int end = padding ? length - (p[length - 1] & 0xFF) : length;
        if (off >= end) return;
        if (hevc) hevcPayload(p, off, end); else avcPayload(p, off, end);
        if (marker) frame(timestamp);
    }

    private void hevcPayload(byte[] p, int off, int end) {
        int type = (p[off] >> 1) & 0x3F;
        if (type == 48) {   // aggregation: 2-byte sizes, then each NAL
            int i = off + 2;
            while (i + 2 <= end) {
                int size = ((p[i] & 0xFF) << 8) | (p[i + 1] & 0xFF);
                i += 2;
                if (size == 0 || i + size > end) break;
                nal(p, i, size);
                i += size;
            }
        } else if (type == 49) {   // fragment: the NAL header rebuilt from the FU header's type
            if (off + 3 > end) return;
            int fuHeader = p[off + 2] & 0xFF;
            if ((fuHeader & 0x80) != 0) {
                fu.reset();
                fu.write((p[off] & 0x81) | ((fuHeader & 0x3F) << 1));
                fu.write(p[off + 1]);
                fuOn = true;
            }
            if (!fuOn) return;
            fu.write(p, off + 3, end - off - 3);
            if ((fuHeader & 0x40) != 0) {
                fuOn = false;
                byte[] n = fu.toByteArray();
                nal(n, 0, n.length);
            }
        } else {
            nal(p, off, end - off);
        }
    }

    private void avcPayload(byte[] p, int off, int end) {
        int type = p[off] & 0x1F;
        if (type == 24) {   // STAP-A
            int i = off + 1;
            while (i + 2 <= end) {
                int size = ((p[i] & 0xFF) << 8) | (p[i + 1] & 0xFF);
                i += 2;
                if (size == 0 || i + size > end) break;
                nal(p, i, size);
                i += size;
            }
        } else if (type == 28) {   // FU-A
            if (off + 2 > end) return;
            int fuHeader = p[off + 1] & 0xFF;
            if ((fuHeader & 0x80) != 0) {
                fu.reset();
                fu.write((p[off] & 0xE0) | (fuHeader & 0x1F));
                fuOn = true;
            }
            if (!fuOn) return;
            fu.write(p, off + 2, end - off - 2);
            if ((fuHeader & 0x40) != 0) {
                fuOn = false;
                byte[] n = fu.toByteArray();
                nal(n, 0, n.length);
            }
        } else {
            nal(p, off, end - off);
        }
    }

    /** One whole NAL: the parameter sets are kept for the decoder; everything goes into the frame. */
    private void nal(byte[] p, int off, int size) {
        if (size <= 0) return;
        if (hevc) {
            int type = (p[off] >> 1) & 0x3F;
            if (type == 32) vps = java.util.Arrays.copyOfRange(p, off, off + size);
            else if (type == 33) sps = java.util.Arrays.copyOfRange(p, off, off + size);
            else if (type == 34) pps = java.util.Arrays.copyOfRange(p, off, off + size);
            else if (type >= 16 && type <= 21) unitKey = true;   // IRAP: a frame the decoder can start on
        } else {
            int type = p[off] & 0x1F;
            if (type == 7) sps = java.util.Arrays.copyOfRange(p, off, off + size);
            else if (type == 8) pps = java.util.Arrays.copyOfRange(p, off, off + size);
            else if (type == 5) unitKey = true;
        }
        unit.write(START, 0, 4);
        unit.write(p, off, size);
    }

    // MARK: - Decoder

    private void frame(long timestamp) throws Exception {
        boolean key = unitKey;
        byte[] data = unit.toByteArray();
        unit.reset();
        unitKey = false;
        if (data.length == 0) return;
        if (codec == null) {
            if (!key || sps == null || pps == null || (hevc && vps == null)) return;   // waits for a key frame
            startCodec();
        }
        if (!keyFrameSeen && !key) return;
        keyFrameSeen = true;
        int i = codec.dequeueInputBuffer(50_000);
        if (i >= 0) {
            ByteBuffer b = codec.getInputBuffer(i);
            b.clear();
            if (data.length > b.capacity()) {   // can't happen with the decoder's own sizes, but a frame too big is dropped
                codec.queueInputBuffer(i, 0, 0, 0, 0);
            } else {
                b.put(data);
                codec.queueInputBuffer(i, 0, data.length, timestamp * 1000 / 90, 0);
            }
        } else if (key) {
            keyFrameSeen = false;   // the decoder is behind: start again on the next key frame
        }
        drain();
    }

    private void startCodec() throws IOException {
        String mime = hevc ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
        MediaFormat f = MediaFormat.createVideoFormat(mime, 1920, 1080);
        if (hevc) {
            f.setByteBuffer("csd-0", ByteBuffer.wrap(join(vps, sps, pps)));
        } else {
            f.setByteBuffer("csd-0", ByteBuffer.wrap(join(sps)));
            f.setByteBuffer("csd-1", ByteBuffer.wrap(join(pps)));
        }
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 << 20);
        if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        codec = MediaCodec.createDecoderByType(mime);
        codec.configure(f, surface, null, 0);
        codec.start();
    }

    private static byte[] join(byte[]... nals) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] n : nals) {
            b.write(START, 0, 4);
            b.write(n, 0, n.length);
        }
        return b.toByteArray();
    }

    /** Shows each decoded frame as soon as it's ready: a live camera has nothing to wait for. */
    private void drain() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int o;
        while ((o = codec.dequeueOutputBuffer(info, 0)) != MediaCodec.INFO_TRY_AGAIN_LATER) {
            if (o >= 0) {
                codec.releaseOutputBuffer(o, true);
                if (!playing) {
                    playing = true;
                    listener.onPlaying();
                }
            }
        }
    }
}
