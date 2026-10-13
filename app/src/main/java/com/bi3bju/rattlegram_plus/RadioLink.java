package com.bi3bju.rattlegram_plus;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Bluetooth audio link to a BTech UV-Pro / Vero VR-N76 radio.
 * <p>
 * The radio exposes its audio as SBC (32 kHz mono) over an RFCOMM channel with a vendor UUID.
 * Packets are 0x7e-delimited with a command byte; 0x7d / 0x7e inside a packet are escaped.
 * Streaming audio packets to the radio keys its transmitter, and the end-of-audio packet unkeys it.
 * A second RFCOMM channel (SPP, GAIA framing) carries control commands; it is only used to
 * mute the radio's speaker while connected and to restore its volume afterwards.
 * Protocol details come from HTCommander (https://github.com/Ylianst/HTCommander).
 * <p>
 * All Listener callbacks run on the main thread.
 */
final class RadioLink {
    interface Listener {
        /** Received radio audio, 32 kHz mono, at most CHUNK_SAMPLES samples. */
        void onRadioAudio(short[] pcm, int count);

        void onRadioConnected(String name);

        /** Connection failed (wasConnected == false) or was lost / closed. */
        void onRadioDisconnected(String name, boolean wasConnected);

        void onRadioTransmitDone();

        /** The speaker was muted; original is the volume to restore later (persist it). */
        void onRadioSpeakerMuted(int original);

        /** The saved volume was restored. */
        void onRadioSpeakerRestored();
    }

    static final int SAMPLE_RATE = 32000;
    /** 20 ms at 32 kHz, matching the 20 ms chunks the microphone path feeds the decoder. */
    static final int CHUNK_SAMPLES = SAMPLE_RATE / 50;

    private static final UUID AUDIO_UUID = UUID.fromString("39144315-32FA-40DB-85ED-FBFEBA2D86E6");
    private static final UUID CONTROL_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int GAIA_GET_VOLUME = 22;
    private static final int GAIA_SET_VOLUME = 23;
    private static final long GAIA_REPLY_TIMEOUT_MS = 2000;
    private static final int CMD_AUDIO = 0x00;
    private static final int CMD_AUDIO_ALT = 0x03;
    private static final byte[] END_AUDIO = {0x7e, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x7e};
    private static final int SAMPLES_PER_SBC_FRAME = 128;
    private static final int SBC_FRAMES_PER_PACKET = 3;
    private static final int SBC_FRAME_BYTES = 88;
    /** Safety cap for a single transmission. */
    private static final long MAX_TRANSMIT_MS = 30000;
    /** How far ahead of real time the transmitter may run (the radio buffers the rest). */
    private static final long TRANSMIT_LEAD_MS = 1000;

    static {
        System.loadLibrary("rattlegram");
    }

    private static native void sbcReset();

    private static native int sbcDecode(byte[] data, int offset, int length, short[] pcm);

    private static native int sbcEncode(short[] pcm, int offset, int count, byte[] out);

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final Object writeLock = new Object();
    private volatile BluetoothSocket socket;
    private volatile boolean connected;
    private volatile boolean transmitting;
    private volatile boolean closing;
    private String deviceName = "";
    private BluetoothDevice device;
    // Speaker mute via the control channel
    private final Object controlLock = new Object();
    private volatile BluetoothSocket control;
    private volatile boolean muteSpeaker = true;
    /** Volume left over from an earlier session that ended before it could be restored, or -1. */
    private volatile int pendingRestore = -1;
    /** Volume to restore when unmuting, or -1 if this link has not muted the speaker. */
    private volatile int savedVolume = -1;

    RadioLink(Listener listener) {
        this.listener = listener;
    }

    boolean isConnected() {
        return connected;
    }

    boolean isTransmitting() {
        return transmitting;
    }

    String getDeviceName() {
        return deviceName;
    }

    /** Call before connect(): whether to mute, and a volume still waiting to be restored (-1 if none). */
    void configureSpeakerMute(boolean mute, int pendingRestore) {
        this.muteSpeaker = mute;
        this.pendingRestore = pendingRestore;
    }

    /** Turns speaker muting on or off, applying it right away when connected. */
    void setSpeakerMuted(boolean mute) {
        muteSpeaker = mute;
        if (!connected)
            return;
        new Thread(() -> {
            if (mute)
                muteSpeakerNow();
            else
                restoreSpeakerNow();
        }, "RadioLink-control").start();
    }

    @SuppressLint("MissingPermission")
    void connect(BluetoothDevice device) {
        if (connected || socket != null)
            return;
        closing = false;
        this.device = device;
        String name = device.getName();
        deviceName = name != null ? name : device.getAddress();
        new Thread(() -> {
            BluetoothSocket s = openSocket(device);
            if (s == null || closing) {
                closeQuietly(s);
                main.post(() -> listener.onRadioDisconnected(deviceName, false));
                return;
            }
            socket = s;
            sbcReset();
            connected = true;
            main.post(() -> listener.onRadioConnected(deviceName));
            if (muteSpeaker || pendingRestore >= 0)
                new Thread(() -> {
                    if (muteSpeaker)
                        muteSpeakerNow();
                    else
                        restoreSpeakerNow();
                }, "RadioLink-control").start();
            readLoop(s);
        }, "RadioLink-read").start();
    }

    void disconnect() {
        closing = true;
        BluetoothSocket s = socket;
        if (s == null)
            return;
        if (transmitting)
            sendEndAudio();
        // Put the volume back without waiting for the reply, so this stays quick on the main thread.
        synchronized (controlLock) {
            if (savedVolume >= 0 && control != null && writeControl(gaia(GAIA_SET_VOLUME, savedVolume))) {
                savedVolume = -1;
                main.post(listener::onRadioSpeakerRestored);
            }
            closeQuietly(control);
            control = null;
        }
        closeQuietly(s);
    }

    /** Sends one complete transmission (32 kHz mono PCM). Returns false if busy or not connected. */
    boolean transmit(short[] pcm, int count) {
        if (!connected || transmitting)
            return false;
        transmitting = true;
        new Thread(() -> {
            try {
                sendPaced(pcm, count);
            } finally {
                sendEndAudio();
                transmitting = false;
                main.post(listener::onRadioTransmitDone);
            }
        }, "RadioLink-transmit").start();
        return true;
    }

    @SuppressLint("MissingPermission")
    private static BluetoothSocket openSocket(BluetoothDevice device) {
        // The radio only accepts unauthenticated (insecure) RFCOMM; try the secure variant as a fallback.
        try {
            BluetoothSocket s = device.createInsecureRfcommSocketToServiceRecord(AUDIO_UUID);
            s.connect();
            return s;
        } catch (IOException | SecurityException ignored) {
        }
        try {
            BluetoothSocket s = device.createRfcommSocketToServiceRecord(AUDIO_UUID);
            s.connect();
            return s;
        } catch (IOException | SecurityException ignored) {
        }
        return null;
    }

    private void readLoop(BluetoothSocket s) {
        ByteArrayOutputStream packet = new ByteArrayOutputStream(512);
        byte[] buf = new byte[4096];
        short[] decoded = new short[SAMPLES_PER_SBC_FRAME * 16];
        short[] chunk = new short[CHUNK_SAMPLES];
        int chunkFill = 0;
        boolean inPacket = false, escape = false;
        try {
            InputStream in = s.getInputStream();
            while (!closing) {
                int n = in.read(buf);
                if (n < 0)
                    break;
                for (int i = 0; i < n; ++i) {
                    int b = buf[i] & 0xff;
                    if (b == 0x7e) {
                        if (inPacket && packet.size() > 1) {
                            byte[] p = packet.toByteArray();
                            int cmd = p[0] & 0xff;
                            // Half duplex: ignore received audio (and the radio's echo) while transmitting.
                            if ((cmd == CMD_AUDIO || cmd == CMD_AUDIO_ALT) && !transmitting) {
                                int samples = sbcDecode(p, 1, p.length - 1, decoded);
                                for (int k = 0; k < samples; ++k) {
                                    chunk[chunkFill++] = decoded[k];
                                    if (chunkFill == CHUNK_SAMPLES) {
                                        final short[] out = chunk.clone();
                                        main.post(() -> listener.onRadioAudio(out, out.length));
                                        chunkFill = 0;
                                    }
                                }
                            }
                        }
                        packet.reset();
                        inPacket = true;
                        escape = false;
                    } else if (inPacket) {
                        if (b == 0x7d) {
                            escape = true;
                        } else {
                            packet.write(escape ? b ^ 0x20 : b);
                            escape = false;
                        }
                    }
                }
            }
        } catch (IOException ignored) {
        } finally {
            if (!closing)
                restoreSpeakerNow();
            synchronized (controlLock) {
                closeQuietly(control);
                control = null;
            }
            boolean was = connected;
            connected = false;
            closeQuietly(s);
            socket = null;
            main.post(() -> listener.onRadioDisconnected(deviceName, was));
        }
    }

    // ========== Control channel (speaker mute) ==========

    private void muteSpeakerNow() {
        synchronized (controlLock) {
            if (savedVolume >= 0 || !openControl())
                return;
            int volume = getVolume();
            if (volume < 0)
                return;
            // Volume already 0 from a session that never restored it: remember the real level instead.
            int original = volume == 0 && pendingRestore > 0 ? pendingRestore : volume;
            if (volume != 0 && !setVolume(0))
                return;
            savedVolume = original;
            pendingRestore = -1;
            main.post(() -> listener.onRadioSpeakerMuted(original));
        }
    }

    private void restoreSpeakerNow() {
        synchronized (controlLock) {
            int volume = savedVolume >= 0 ? savedVolume : pendingRestore;
            if (volume < 0 || !openControl() || !setVolume(volume))
                return;
            savedVolume = -1;
            pendingRestore = -1;
            main.post(listener::onRadioSpeakerRestored);
        }
    }

    @SuppressLint("MissingPermission")
    private boolean openControl() {
        if (control != null)
            return true;
        BluetoothDevice d = device;
        if (d == null)
            return false;
        try {
            BluetoothSocket s = d.createInsecureRfcommSocketToServiceRecord(CONTROL_UUID);
            s.connect();
            control = s;
            return true;
        } catch (IOException | SecurityException ignored) {
        }
        // The radio's SDP record can advertise the wrong control channel; probe a few (as HTCommander does).
        try {
            java.lang.reflect.Method byChannel = d.getClass().getMethod("createInsecureRfcommSocket", int.class);
            for (int channel = 1; channel <= 12 && !closing; ++channel) {
                BluetoothSocket s = null;
                try {
                    s = (BluetoothSocket) byChannel.invoke(d, channel);
                    s.connect();
                    control = s;
                    if (getVolume() >= 0)
                        return true;
                    control = null;
                    closeQuietly(s);
                } catch (Exception e) {
                    closeQuietly(s);
                    sleep(250);
                }
            }
        } catch (NoSuchMethodException ignored) {
        }
        return false;
    }

    /** GAIA frame: FF 01 00 len, then the basic command group (0x0002) and command, then the payload. */
    private static byte[] gaia(int cmd, int... payload) {
        byte[] f = new byte[8 + payload.length];
        f[0] = (byte) 0xFF;
        f[1] = 0x01;
        f[2] = 0x00;
        f[3] = (byte) payload.length;
        f[4] = 0x00;
        f[5] = 0x02;
        f[6] = 0x00;
        f[7] = (byte) cmd;
        for (int i = 0; i < payload.length; ++i)
            f[8 + i] = (byte) payload[i];
        return f;
    }

    private int getVolume() {
        byte[] reply = requestControl(gaia(GAIA_GET_VOLUME), GAIA_GET_VOLUME);
        // Reply payload: status, volume, maximum
        return reply != null && reply.length > 1 && reply[0] == 0 ? reply[1] & 0xff : -1;
    }

    private boolean setVolume(int level) {
        byte[] reply = requestControl(gaia(GAIA_SET_VOLUME, level), GAIA_SET_VOLUME);
        return reply != null && reply.length > 0 && reply[0] == 0;
    }

    private boolean writeControl(byte[] frame) {
        BluetoothSocket s = control;
        if (s == null)
            return false;
        try {
            OutputStream out = s.getOutputStream();
            out.write(frame);
            out.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Sends a command and waits for the reply to it; returns the reply payload or null. */
    private byte[] requestControl(byte[] frame, int cmd) {
        BluetoothSocket s = control;
        if (s == null || !writeControl(frame))
            return null;
        byte[] buf = new byte[512];
        int len = 0;
        long deadline = System.currentTimeMillis() + GAIA_REPLY_TIMEOUT_MS;
        try {
            InputStream in = s.getInputStream();
            while (System.currentTimeMillis() < deadline) {
                if (in.available() == 0) {
                    sleep(20);
                    continue;
                }
                int n = in.read(buf, len, buf.length - len);
                if (n < 0)
                    return null;
                len += n;
                int i = 0;
                while (len - i >= 8) {
                    if ((buf[i] & 0xff) != 0xFF || buf[i + 1] != 0x01) {
                        ++i;
                        continue;
                    }
                    int payloadLength = buf[i + 3] & 0xff;
                    int total = payloadLength + 8 + (buf[i + 2] & 1);
                    if (len - i < total)
                        break;
                    if ((buf[i + 7] & 0xff) == cmd) {
                        byte[] payload = new byte[payloadLength];
                        System.arraycopy(buf, i + 8, payload, 0, payloadLength);
                        return payload;
                    }
                    i += total;
                }
                System.arraycopy(buf, i, buf, 0, len - i);
                len -= i;
                if (len == buf.length)
                    len = 0;
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private void sendPaced(short[] pcm, int count) {
        int samplesPerPacket = SAMPLES_PER_SBC_FRAME * SBC_FRAMES_PER_PACKET;
        byte[] sbc = new byte[SBC_FRAME_BYTES * SBC_FRAMES_PER_PACKET];
        long start = System.currentTimeMillis();
        long sentSamples = 0;
        for (int offset = 0; offset + SAMPLES_PER_SBC_FRAME <= count && connected && !closing; offset += samplesPerPacket) {
            int samples = Math.min(samplesPerPacket, count - offset);
            samples -= samples % SAMPLES_PER_SBC_FRAME;
            int bytes = sbcEncode(pcm, offset, samples, sbc);
            if (bytes <= 0 || !write(escapeAudio(sbc, bytes)))
                return;
            sentSamples += samples;
            long audioMs = sentSamples * 1000 / SAMPLE_RATE;
            if (audioMs > MAX_TRANSMIT_MS)
                return;
            long ahead = audioMs - (System.currentTimeMillis() - start);
            if (ahead > TRANSMIT_LEAD_MS)
                sleep(ahead - TRANSMIT_LEAD_MS);
        }
        // Let the radio play out its buffer before the end packet unkeys it.
        long remaining = sentSamples * 1000 / SAMPLE_RATE - (System.currentTimeMillis() - start);
        if (remaining > 0)
            sleep(remaining + 200);
    }

    private void sendEndAudio() {
        write(END_AUDIO);
    }

    private boolean write(byte[] bytes) {
        BluetoothSocket s = socket;
        if (s == null)
            return false;
        synchronized (writeLock) {
            try {
                OutputStream out = s.getOutputStream();
                out.write(bytes);
                out.flush();
                return true;
            } catch (IOException e) {
                return false;
            }
        }
    }

    private static byte[] escapeAudio(byte[] payload, int length) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(length + 16);
        o.write(0x7e);
        o.write(CMD_AUDIO);
        for (int i = 0; i < length; ++i) {
            int v = payload[i] & 0xff;
            if (v == 0x7d || v == 0x7e) {
                o.write(0x7d);
                o.write(v ^ 0x20);
            } else {
                o.write(v);
            }
        }
        o.write(0x7e);
        return o.toByteArray();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static void closeQuietly(BluetoothSocket s) {
        if (s == null)
            return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
