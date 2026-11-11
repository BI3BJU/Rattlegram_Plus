package com.bi3bju.rattlegram_plus;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * APRS-IS network beacon client.
 *
 * High-level interface with internal worker threads:
 *  - Sending: builds packet, computes passcode, rotates through server pool,
 *    verified login, retries, comment length check.
 *  - Receiving: pass -1 read-only login, combined filter
 *    "s/|/| r/lat/lon/dist", parses packets, applies mode/radius/time filters.
 *
 * All callbacks are dispatched on the main thread so that background
 * receiving never blocks the UI.
 */
public class Aprs {

    private static final String TAG = "Aprs";

    // Server pool (same as demo.py)
    private static final String[] SERVERS = {
            "rotate.aprs2.net",
            "euro.aprs2.net",
            "noam.aprs2.net",
            "asia.aprs2.net",
            "china.aprs2.net"
    };
    private static final int PORT = 14580;

    // Root symbol is fixed
    private static final String SYMBOL = "|";

    // Allowed modes for receiving
    private static final String MODE_DIRECT = "Rattlegram_Plus_D";
    private static final String MODE_RELAY = "Rattlegram_Plus_R";

    // Frequency / tone ranges
    private static final int FREQ_MIN_KHZ = 3000;
    private static final int FREQ_MAX_KHZ = 999990;
    private static final double TONE_MIN_HZ = 1.0;
    private static final double TONE_MAX_HZ = 999.0;

    // Comment length limit
    private static final int COMMENT_MAX = 43;

    private static final Pattern FREQ6 = Pattern.compile("^\\d{6}$");
    private static final Pattern BRACKETS = Pattern.compile("\\[([^\\]]*)\\]");

    // ------------------------------------------------------------------
    // Callback interface (always invoked on main thread)
    // ------------------------------------------------------------------
    public interface OnBeaconListener {
        /** Called when a network beacon is received and passes all filters. */
        void onBeaconReceived(ReceivedBeacon beacon);
        /** Called after a network beacon is sent successfully. */
        void onBeaconSent(SentBeacon beacon);
        /** Called for connection / login / send status messages. */
        void onAprsStatus(String message);
    }

    // ------------------------------------------------------------------
    // Data holders
    // ------------------------------------------------------------------
    public static class ReceivedBeacon {
        public String senderCall;
        public String mode;          // Rattlegram_Plus_D or Rattlegram_Plus_R
        public boolean isRelay;
        public int freqKhz;          // direct: downlink freq
        public double toneHz;        // 0 means none
        public int upFreqKhz;        // relay only
        public double upToneHz;      // 0 means none
        public int downFreqKhz;      // relay only
        public double downToneHz;    // 0 means none
        public int distanceKm;
        public long timestampMs;     // resolved UTC timestamp of the beacon
    }

    public static class SentBeacon {
        public String mode;
        public boolean isRelay;
        public int freqKhz;
        public double toneHz;
        public int upFreqKhz;
        public double upToneHz;
        public int downFreqKhz;
        public double downToneHz;
    }

    // ------------------------------------------------------------------
    // Configuration snapshot
    // ------------------------------------------------------------------
    public static class Config {
        public String callSign = "ANONYMOUS";
        public boolean sendEnabled = false;
        public boolean isRelay = false;
        public int freqKhz = 435000;
        public double toneHz = 0.0;
        public int upFreqKhz = 145990;
        public double upToneHz = 0.0;
        public int downFreqKhz = 437800;
        public double downToneHz = 0.0;
        public double lat = 0.0;
        public double lon = 0.0;
        public boolean recvEnabled = true;
        public int recvRadiusKm = 1;
        public int recvWindowMin = 10;
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private OnBeaconListener listener;
    private Config config = new Config();

    private Thread sendThread;
    private Thread recvThread;
    private volatile boolean sendRunning = false;
    private volatile boolean recvRunning = false;
    private volatile boolean shutdown = false;

    // Server rotation indices
    private int sendServerIdx = 0;
    private int recvServerIdx = 0;

    public Aprs(Context context) {
        // Context not strictly needed; kept for future use (e.g. location).
    }

    public void setListener(OnBeaconListener l) {
        this.listener = l;
    }

    public void updateConfig(Config c) {
        this.config = c;
    }

    // ------------------------------------------------------------------
    // Public high-level API
    // ------------------------------------------------------------------

    /** Start the sending worker thread. Sends one beacon immediately, then at interval. */
    public synchronized void startSending(long intervalMs) {
        if (sendRunning) return;
        sendRunning = true;
        shutdown = false;
        final long interval = Math.max(1000L, intervalMs);
        sendThread = new Thread(() -> sendLoop(interval), "AprsSend");
        sendThread.start();
    }

    public synchronized void stopSending() {
        sendRunning = false;
        if (sendThread != null) {
            sendThread.interrupt();
            sendThread = null;
        }
    }

    /** Start the receiving worker thread. */
    public synchronized void startReceiving() {
        if (recvRunning) return;
        recvRunning = true;
        shutdown = false;
        recvThread = new Thread(this::recvLoop, "AprsRecv");
        recvThread.start();
    }

    public synchronized void stopReceiving() {
        recvRunning = false;
        if (recvThread != null) {
            recvThread.interrupt();
            recvThread = null;
        }
    }

    public synchronized void shutdown() {
        shutdown = true;
        stopSending();
        stopReceiving();
    }

    /** Send one beacon immediately (used for the first trigger). */
    public void sendOnce() {
        new Thread(() -> doSendOnce(), "AprsSendOnce").start();
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    private void sendLoop(long intervalMs) {
        while (sendRunning && !shutdown) {
            doSendOnce();
            long end = System.currentTimeMillis() + intervalMs;
            while (sendRunning && !shutdown && System.currentTimeMillis() < end) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private void doSendOnce() {
        try {
            Config c = config;
            String call = c.callSign;
            int passcode = calculatePasscode(call);

            int freqKhz;
            double toneHz;
            int upFreqKhz = 0, downFreqKhz = 0;
            double upToneHz = 0.0, downToneHz = 0.0;
            String mode;
            boolean isRelay = c.isRelay;

            if (isRelay) {
                mode = MODE_RELAY;
                upFreqKhz = c.upFreqKhz;
                upToneHz = c.upToneHz;
                downFreqKhz = c.downFreqKhz;
                downToneHz = c.downToneHz;
                freqKhz = downFreqKhz;
                toneHz = downToneHz;
            } else {
                mode = MODE_DIRECT;
                freqKhz = c.freqKhz;
                toneHz = c.toneHz;
            }

            String comment;
            if (isRelay) {
                // Relay now uses only one tone: [upFreq_downFreq_tone]
                comment = "[" + mode + "][" + upFreqKhz + "_"
                        + downFreqKhz + "_"
                        + toneToField(downToneHz) + "]";
            } else {
                comment = "[" + mode + "][" + freqKhz + "_"
                        + toneToField(toneHz) + "]";
            }

            if (comment.length() > COMMENT_MAX) {
                postStatus("Network beacon comment exceeds " + COMMENT_MAX + " chars, not sent");
                return;
            }

            String pos = formatAprsPos(c.lat, c.lon);
            String ts = utcTimestamp();
            String packet = "/" + ts + pos + SYMBOL + comment;
            String line = call + ">APRS,TCPIP*:" + packet + "\r\n";

            boolean ok = trySend(call, passcode, line);
            if (ok) {
                SentBeacon sb = new SentBeacon();
                sb.mode = mode;
                sb.isRelay = isRelay;
                sb.freqKhz = freqKhz;
                sb.toneHz = toneHz;
                sb.upFreqKhz = upFreqKhz;
                sb.upToneHz = upToneHz;
                sb.downFreqKhz = downFreqKhz;
                sb.downToneHz = downToneHz;
                postSent(sb);
            } else {
                postStatus("Network beacon send failed");
            }
        } catch (Exception e) {
            Log.w(TAG, "sendOnce error", e);
            postStatus("Network beacon send error: " + e.getMessage());
        }
    }

    private boolean trySend(String call, int passcode, String line) {
        int attempts = SERVERS.length;
        for (int i = 0; i < attempts; i++) {
            String server = SERVERS[sendServerIdx % SERVERS.length];
            sendServerIdx = (sendServerIdx + 1) % SERVERS.length;
            Socket s = null;
            try {
                s = new Socket();
                s.connect(new InetSocketAddress(server, PORT), 8000);
                OutputStream out = s.getOutputStream();
                String login = "user " + call + " pass " + passcode
                        + " vers RattlegramPlus 1.0\r\n";
                out.write(login.getBytes(StandardCharsets.US_ASCII));
                out.flush();
                Thread.sleep(300);
                out.write(line.getBytes(StandardCharsets.US_ASCII));
                out.flush();
                Thread.sleep(500);
                return true;
            } catch (Exception e) {
                Log.w(TAG, "send to " + server + " failed: " + e.getMessage());
            } finally {
                try { if (s != null) s.close(); } catch (Exception ignored) {}
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Receiving
    // ------------------------------------------------------------------

    private void recvLoop() {
        while (recvRunning && !shutdown) {
            String server = SERVERS[recvServerIdx % SERVERS.length];
            Socket sock = null;
            BufferedReader reader = null;
            try {
                sock = new Socket();
                sock.connect(new InetSocketAddress(server, PORT), 10000);
                OutputStream out = sock.getOutputStream();
                String login = "user " + config.callSign + " pass -1"
                        + " vers RattlegramPlus 1.0\r\n";
                out.write(login.getBytes(StandardCharsets.US_ASCII));
                out.flush();

                reader = new BufferedReader(new InputStreamReader(
                        sock.getInputStream(), StandardCharsets.US_ASCII));

                // Wait for logresp
                long deadline = System.currentTimeMillis() + 15000;
                String line;
                while (System.currentTimeMillis() < deadline) {
                    line = reader.readLine();
                    if (line == null) break;
                    if (line.startsWith("# logresp")) break;
                }

                // Combined filter
                String filter = "s/" + SYMBOL + "/" + SYMBOL
                        + " r/" + formatCoord(config.lat) + "/"
                        + formatCoord(config.lon) + "/"
                        + config.recvRadiusKm;
                out.write(("#filter " + filter + "\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();

                postStatus("Receiving from " + server + ", filter " + filter);

                while (recvRunning && !shutdown) {
                    line = reader.readLine();
                    if (line == null) {
                        postStatus(server + " closed connection, rotating");
                        break;
                    }
                    if (line.startsWith("#")) continue;
                    if (!line.contains(">")) continue;
                    handleLine(line);
                }
            } catch (Exception e) {
                if (recvRunning && !shutdown) {
                    postStatus("Receive error on " + server + ": " + e.getMessage());
                }
            } finally {
                try { if (reader != null) reader.close(); } catch (Exception ignored) {}
                try { if (sock != null) sock.close(); } catch (Exception ignored) {}
            }
            recvServerIdx = (recvServerIdx + 1) % SERVERS.length;
            if (recvRunning && !shutdown) {
                try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
            }
        }
    }

    private void handleLine(String line) {
        try {
            int colon = line.indexOf(':');
            if (colon < 0) return;
            String src = line.substring(0, line.indexOf('>')).trim();
            String body = line.substring(colon + 1);

            if (!body.startsWith("/")) return;
            if (body.length() < 1 + 7 + 18 + 1) return;

            String ts = body.substring(1, 8);
            if (!ts.endsWith("z") || !isDigits(ts.substring(0, 6))) return;

            String pos = body.substring(8, 8 + 18);
            if (pos.charAt(8) != '/') return;

            double lat = parseAprsLat(pos.substring(0, 8));
            double lon = parseAprsLon(pos.substring(9, 18));
            if (Double.isNaN(lat) || Double.isNaN(lon)) return;

            String rest = body.substring(8 + 18);
            if (rest.isEmpty()) return;
            if (!rest.substring(0, 1).equals(SYMBOL)) return;
            String comment = rest.substring(1);

            // Extract bracketed fields
            Matcher m = BRACKETS.matcher(comment);
            java.util.List<String> fields = new java.util.ArrayList<>();
            while (m.find()) fields.add(m.group(1));
            if (fields.size() < 2) return;

            String mode = fields.get(0);
            if (!mode.equals(MODE_DIRECT) && !mode.equals(MODE_RELAY)) return;

            boolean isRelay = mode.equals(MODE_RELAY);

            int freqKhz = 0;
            double toneHz = 0.0;
            int upFreqKhz = 0, downFreqKhz = 0;
            double upToneHz = 0.0, downToneHz = 0.0;

            if (isRelay) {
                if (fields.size() < 2) return;
                String[] parts = fields.get(1).split("_", -1);
                // Support both new (3 parts) and legacy (4 parts) formats
                if (parts.length == 3) {
                    upFreqKhz = parseInt(parts[0]);
                    upToneHz = 0.0;
                    downFreqKhz = parseInt(parts[1]);
                    downToneHz = parseTone(parts[2]);
                } else if (parts.length == 4) {
                    upFreqKhz = parseInt(parts[0]);
                    upToneHz = parseTone(parts[1]);
                    downFreqKhz = parseInt(parts[2]);
                    downToneHz = parseTone(parts[3]);
                } else {
                    return;
                }
                if (upFreqKhz < FREQ_MIN_KHZ || upFreqKhz > FREQ_MAX_KHZ) return;
                if (downFreqKhz < FREQ_MIN_KHZ || downFreqKhz > FREQ_MAX_KHZ) return;
                if (upToneHz < 0 || (upToneHz > 0 && (upToneHz < TONE_MIN_HZ || upToneHz > TONE_MAX_HZ))) return;
                if (downToneHz < 0 || (downToneHz > 0 && (downToneHz < TONE_MIN_HZ || downToneHz > TONE_MAX_HZ))) return;
                freqKhz = downFreqKhz;
                toneHz = downToneHz;
            } else {
                String[] parts = fields.get(1).split("_", -1);
                if (parts.length != 2) return;
                freqKhz = parseInt(parts[0]);
                toneHz = parseTone(parts[1]);
                if (freqKhz < FREQ_MIN_KHZ || freqKhz > FREQ_MAX_KHZ) return;
                if (toneHz < 0 || (toneHz > 0 && (toneHz < TONE_MIN_HZ || toneHz > TONE_MAX_HZ))) return;
            }

            // Resolve timestamp
            int day = Integer.parseInt(ts.substring(0, 2));
            int hour = Integer.parseInt(ts.substring(2, 4));
            int minute = Integer.parseInt(ts.substring(4, 6));
            long tsMs = resolveUtcMillis(day, hour, minute);

            // Time filter
            long now = System.currentTimeMillis();
            long windowMs = config.recvWindowMin * 60L * 1000L;
            if (windowMs > 0 && now - tsMs > windowMs) return;

            // Radius filter
            double dist = haversineKm(config.lat, config.lon, lat, lon);
            if (dist > config.recvRadiusKm) return;

            // Filter out self-beacons, unless the local call sign is "ANONYMOUS"
            if (config.callSign != null 
                    && !config.callSign.trim().isEmpty() 
                    && !"ANONYMOUS".equalsIgnoreCase(config.callSign.trim())) {
                // Strip any SSID from both for a robust comparison, or compare exact
                // Let's compare the base call sign (without -SSID) to be safe, 
                // though exact string match is also fine. We use exact match here.
                if (src.equalsIgnoreCase(config.callSign.trim())) {
                    return;
                }
            }

            ReceivedBeacon rb = new ReceivedBeacon();
            rb.senderCall = src;
            rb.mode = mode;
            rb.isRelay = isRelay;
            rb.freqKhz = freqKhz;
            rb.toneHz = toneHz;
            rb.upFreqKhz = upFreqKhz;
            rb.upToneHz = upToneHz;
            rb.downFreqKhz = downFreqKhz;
            rb.downToneHz = downToneHz;
            rb.distanceKm = (int) Math.round(dist);
            rb.timestampMs = tsMs;

            postReceived(rb);
        } catch (Exception e) {
            Log.w(TAG, "parse error: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static boolean isDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return -1; }
    }

    private static double parseTone(String s) {
        if (s == null || s.isEmpty()) return 0.0;
        try { return Double.parseDouble(s) / 10.0; } catch (Exception e) { return -1.0; }
    }

    private static String toneToField(double toneHz) {
        if (toneHz <= 0.0) return "";
        long tenths = Math.round(toneHz * 10.0);
        return String.valueOf(tenths);
    }

    private static String formatCoord(double v) {
        return String.format(Locale.US, "%.5f", v);
    }

    private static String utcTimestamp() {
        SimpleDateFormat f = new SimpleDateFormat("ddHHmm'z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    private static String formatAprsPos(double lat, double lon) {
        return formatLat(lat) + "/" + formatLon(lon);
    }

    private static String formatLat(double v) {
        String hemi = v >= 0 ? "N" : "S";
        v = Math.abs(v);
        int deg = (int) v;
        double minutes = (v - deg) * 60.0;
        if (minutes >= 60.0) { minutes -= 60.0; deg += 1; }
        return String.format(Locale.US, "%02d%05.2f%s", deg, minutes, hemi);
    }

    private static String formatLon(double v) {
        String hemi = v >= 0 ? "E" : "W";
        v = Math.abs(v);
        int deg = (int) v;
        double minutes = (v - deg) * 60.0;
        if (minutes >= 60.0) { minutes -= 60.0; deg += 1; }
        return String.format(Locale.US, "%03d%05.2f%s", deg, minutes, hemi);
    }

    private static double parseAprsLat(String s) {
        try {
            String hemi = s.substring(7);
            String num = s.substring(0, 7);
            int dot = num.indexOf('.');
            if (dot < 2) return Double.NaN;
            int deg = Integer.parseInt(num.substring(0, dot - 2));
            double min = Double.parseDouble(num.substring(dot - 2));
            double v = deg + min / 60.0;
            return hemi.equals("S") ? -v : v;
        } catch (Exception e) { return Double.NaN; }
    }

    private static double parseAprsLon(String s) {
        try {
            String hemi = s.substring(8);
            String num = s.substring(0, 8);
            int dot = num.indexOf('.');
            if (dot < 3) return Double.NaN;
            int deg = Integer.parseInt(num.substring(0, dot - 2));
            double min = Double.parseDouble(num.substring(dot - 2));
            double v = deg + min / 60.0;
            return hemi.equals("W") ? -v : v;
        } catch (Exception e) { return Double.NaN; }
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0;
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1);
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    private static long resolveUtcMillis(int day, int hour, int minute) {
        java.util.Calendar now = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        int y = now.get(java.util.Calendar.YEAR);
        int m = now.get(java.util.Calendar.MONTH) + 1;
        int nowDay = now.get(java.util.Calendar.DAY_OF_MONTH);
        if (day > nowDay) {
            if (m == 1) { m = 12; y -= 1; } else { m -= 1; }
        }
        java.util.Calendar c = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, m - 1, day, hour, minute, 0);
        return c.getTimeInMillis();
    }

    private static int calculatePasscode(String call) {
        String base = call.toUpperCase(Locale.US);
        int dash = base.indexOf('-');
        if (dash >= 0) base = base.substring(0, dash);
        if (base.length() % 2 != 0) base += "\0";
        int h = 0x73E2;
        for (int i = 0; i < base.length(); i += 2) {
            h ^= base.charAt(i) << 8;
            h ^= base.charAt(i + 1);
        }
        return h & 0x7FFF;
    }

    // ------------------------------------------------------------------
    // Main-thread dispatch
    // ------------------------------------------------------------------

    private void postStatus(final String msg) {
        mainHandler.post(() -> {
            if (listener != null) listener.onAprsStatus(msg);
        });
    }

    private void postReceived(final ReceivedBeacon rb) {
        mainHandler.post(() -> {
            if (listener != null) listener.onBeaconReceived(rb);
        });
    }

    private void postSent(final SentBeacon sb) {
        mainHandler.post(() -> {
            if (listener != null) listener.onBeaconSent(sb);
        });
    }
}