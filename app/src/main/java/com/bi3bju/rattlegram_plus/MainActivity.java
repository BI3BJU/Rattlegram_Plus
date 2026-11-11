/*
Rattlegram Plus

Copyright 2026 BI3BJU <guerilla1949@gmail.com>
*/

package com.bi3bju.rattlegram_plus;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.net.Uri;
import android.provider.MediaStore;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.os.Environment;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Button;
import android.widget.Toast;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import com.bi3bju.rattlegram_plus.databinding.ActivityMainBinding;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.graphics.drawable.DrawableCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {


    // ========== Unified color constants (8 kinds) ==========
    // ========== ANSI normal palette only ==========
    public static int COLOR_BLACK;
    public static int COLOR_WHITE;
    public static int COLOR_RED;
    public static int COLOR_GREEN;
    public static int COLOR_YELLOW;
    public static int COLOR_BLUE;
    public static int COLOR_MAGENTA;
    public static int COLOR_CYAN;

    // Semantic colors, assigned from the normal palette.
    public static int COLOR_TEXT_PRIMARY;
    public static int COLOR_BACKGROUND;
    public static int COLOR_DIVIDER;
    // Used to load the 'rattlegram' library on application startup.
    static {
        System.loadLibrary("rattlegram");
    }

    private final int permissionID = 1;
    private final int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
    private final int sampleSize = 2;
    private final int spectrumWidth = 360, spectrumHeight = 128;
    private final int spectrogramWidth = 360, spectrogramHeight = 128;
    private Bitmap spectrumBitmap, spectrogramBitmap;
    private int[] spectrumPixels, spectrogramPixels;
    private ImageView spectrumView, spectrogramView;
    private TextView status;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private boolean fancyHeader;
    private boolean showSpectrum;
    private int spectrumTint;
    private int noiseSymbols;
    private int recordRate;
    private int outputRate;
    private int recordChannel;
    private int outputChannel;
    private int audioSource;
    private int carrierFrequency;
    private int recordCount;
    private short[] recordBuffer;
    private short[] outputBuffer;
    private Menu menu;
    private Handler handler;
    private Runnable statusTimer;
    private String prevStatus;
    private byte[] payload;
    private ArrayAdapter<String> messages;
    private float[] stagedCFO;
    private int[] stagedMode;
    private byte[] stagedCall;
    private String callSign;
    private String draftText;
    private ListView messageListView;

    // ---------- Beacon-related variables ----------
    private Handler beaconHandler;
    private Runnable beaconRunnable;
    private boolean beaconRunning = false;
    // Network beacon client (high-level interface with internal threads)
    private Aprs aprs;
    private LocationManager locationManager;
    private double beaconLat = 0.0;
    private double beaconLon = 0.0;
    private boolean netSendEnabled = false;
    private boolean netIsRelay = false;
    private int netFreqKhz = 435000;
    private double netToneHz = 0.0;
    private int netUpFreqKhz = 145990;
    private double netUpToneHz = 0.0;
    private int netDownFreqKhz = 437800;
    private double netDownToneHz = 0.0;
    private boolean netRecvEnabled = true;
    private int netRecvRadiusKm = 10;
    private int netRecvWindowMin = 10;
    private long beaconIntervalMs = 60 * 1000L; // configurable, default 1 minute

    // Call sign menu item reference
    private MenuItem callSignMenuItem;

    // Bottom buttons
    private ImageView beaconButton;
    private ImageView composeButton;

    // ========== Password in-memory variable ==========
    private String password = "";


    // ========== Contact-related ==========
    private static final int MAX_CONTACTS = 100;

    private static class Contact {
        public String callSign;
        public String note;

        public Contact(String callSign, String note) {
            this.callSign = callSign;
            this.note = note;
        }
    }

    private ArrayList<Contact> contacts = new ArrayList<>();
    // Currently long-pressed selected position in contact list, -1 means no selection
    private int selectedContactIndex = -1;

    // Text persistence under Documents/Rattlegram_Plus
    private static final String PERSIST_DIR = "Rattlegram_Plus";
    private static final String SETTINGS_FILE = "settings.txt";
    private static final String CONTACTS_FILE = "contacts.txt";
    // Separate file for message history (settings.txt no longer stores messages)
    private static final String HISTORY_FILE = "history.txt";
    // Cache MediaStore URIs to prevent duplicate inserts when
    // the MediaStore database has not yet synced the new entry.
    private final Map<String, Uri> mediaStoreUriCache = new HashMap<>();

    // Single-thread executor for MediaStore mirror IO (never touch it on the main thread).
    private ExecutorService mirrorExecutor;

    // Gate: only one permission request may be in flight at a time.
    private boolean permissionRequestInFlight = false;

    private native boolean createEncoder(int sampleRate);

    private native void configureEncoder(byte[] payload, int payloadLength, byte[] callSign, int carrierFrequency, int noiseSymbols, boolean fancyHeader, boolean encrypted);

    private native boolean produceEncoder(short[] audioBuffer, int channelSelect);

    private native void destroyEncoder();

    private final AudioTrack.OnPlaybackPositionUpdateListener outputListener = new AudioTrack.OnPlaybackPositionUpdateListener() {
        @Override
        public void onMarkerReached(AudioTrack ignore) {

        }

        @Override
        public void onPeriodicNotification(AudioTrack audioTrack) {
            if (produceEncoder(outputBuffer, outputChannel)) {
                audioTrack.write(outputBuffer, 0, outputBuffer.length);
            } else {
                audioTrack.stop();
                handler.postDelayed(() -> startListening(), 1000);
            }
        }
    };

    private void initAudioTrack() {
        if (audioTrack != null) {
            boolean rateChanged = audioTrack.getSampleRate() != outputRate;
            boolean channelChanged = audioTrack.getChannelCount() != (outputChannel == 0 ? 1 : 2);
            if (!rateChanged && !channelChanged)
                return;
            audioTrack.stop();
            audioTrack.release();
        }
        int channelConfig = AudioFormat.CHANNEL_OUT_MONO;
        int channelCount = 1;
        if (outputChannel != 0) {
            channelCount = 2;
            channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
        }
        int symbolLength = (1280 * outputRate) / 8000;
        int guardLength = symbolLength / 8;
        int extendedLength = symbolLength + guardLength;
        int bufferSize = 5 * extendedLength * sampleSize * channelCount;
        audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, outputRate, channelConfig, audioFormat, bufferSize, AudioTrack.MODE_STREAM);
        outputBuffer = new short[extendedLength * channelCount];
        audioTrack.setPlaybackPositionUpdateListener(outputListener);
        audioTrack.setPositionNotificationPeriod(extendedLength);
        if (!createEncoder(outputRate))
            setStatus(getString(R.string.heap_error));
    }

    private native boolean feedDecoder(short[] audioBuffer, int sampleCount, int channelSelect);

    private native int processDecoder();

    private native void spectrumDecoder(int[] spectrumPixels, int[] spectrogramPixels, int spectrumTint);

    private native void stagedDecoder(float[] carrierFrequencyOffset, int[] operationMode, byte[] callSign);

    private native int fetchDecoder(byte[] payload);

    private native boolean createDecoder(int sampleRate);

    private native void destroyDecoder();

    private final AudioRecord.OnRecordPositionUpdateListener recordListener = new AudioRecord.OnRecordPositionUpdateListener() {
        @Override
        public void onMarkerReached(AudioRecord ignore) {

        }

        @Override
        public void onPeriodicNotification(AudioRecord audioRecord) {
            // Skip reads while the record is not actively recording.
            if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                return;
            }
            // Check read() result; negative values (e.g. -38) mean the frame is invalid.
            int samplesRead = audioRecord.read(recordBuffer, 0, recordBuffer.length);
            if (samplesRead <= 0) {
                return;
            }
            if (!feedDecoder(recordBuffer, samplesRead, recordChannel))
                return;
            int status = processDecoder();
            if (showSpectrum) {
                spectrumDecoder(spectrumPixels, spectrogramPixels, spectrumTint);
                spectrumBitmap.setPixels(spectrumPixels, 0, spectrumWidth, 0, 0, spectrumWidth, spectrumHeight);
                spectrogramBitmap.setPixels(spectrogramPixels, 0, spectrogramWidth, 0, 0, spectrogramWidth, spectrogramHeight);
                spectrumView.invalidate();
                spectrogramView.invalidate();
            }
            final int STATUS_OKAY = 0;
            final int STATUS_FAIL = 1;
            final int STATUS_SYNC = 2;
            final int STATUS_DONE = 3;
            final int STATUS_HEAP = 4;
            final int STATUS_NOPE = 5;
            final int STATUS_PING = 6;
            switch (status) {
                case STATUS_OKAY:
                    break;
                case STATUS_FAIL:
                    setStatus(getString(R.string.preamble_fail), true);
                    break;
                case STATUS_NOPE:
                    stagedDecoder(stagedCFO, stagedMode, stagedCall);
                    if (stagedMode[0] >= 17 && stagedMode[0] <= 19) {
                        // Encrypted modes are routed via STATUS_DONE; stay silent if leaked here
                        setStatus(getString(R.string.listening));
                    } else {
                        fromStatus();
                        addLine(parseCallSign(stagedCall), getString(R.string.preamble_nope, stagedMode[0]));
                    }
                    break;
                case STATUS_PING:
                    stagedDecoder(stagedCFO, stagedMode, stagedCall);
                    fromStatus();
                    // Ping also auto-saves the sender's call sign
                    String pingSender = parseCallSign(stagedCall);
                    autoSaveContact(pingSender);
                    addLine(pingSender, getString(R.string.preamble_ping));
                    break;
                case STATUS_HEAP:
                    setStatus(getString(R.string.heap_error));
                    audioRecord.stop();
                    break;
                case STATUS_SYNC:
                    stagedDecoder(stagedCFO, stagedMode, stagedCall);
                    fromStatus();
                    break;
                case STATUS_DONE:
                    int result = fetchDecoder(payload);
                    if (result < 0) {
                        addLine(parseCallSign(stagedCall), getString(R.string.decoding_failed));
                    } else {
                        String sender = parseCallSign(stagedCall);
                        if (stagedMode[0] >= 17) {
                            // Encrypted modes 17/18/19: silent on failure (status bar hint only)
                            if (password == null || password.isEmpty()) {
                                setStatus(getString(R.string.encrypted_msg_no_password), true);
                            } else {
                                                                int blobLen;
                                switch (stagedMode[0]) {
                                    case 19: blobLen = 85;  break;
                                    case 18: blobLen = 128; break;
                                    case 17: blobLen = 170; break;
                                    default: blobLen = payload.length; break;
                                }
                                byte[] blob = Arrays.copyOf(payload, blobLen);
                                String plain = CryptoUtils.decryptBytes(blob, password);
                                if (plain == null) {
                                    setStatus(getString(R.string.encrypted_msg_decrypt_failed), true);
                                } else {
                                    fromStatus();
                                    autoSaveContact(sender);
                                    addMessage(sender, getString(R.string.received_decrypted), plain);
                                }
                            }
                        } else {
                            setStatus(getResources().getQuantityString(R.plurals.bits_flipped, result, result), true);
                            autoSaveContact(sender);
                            addMessage(sender, getString(R.string.received), new String(payload).trim());
                        }
                    }
                    break;
            }
        }
    };

    private void setStatus(String str, boolean tmp) {
        if (statusTimer != null)
            handler.removeCallbacks(statusTimer);
        if (tmp) {
            statusTimer = () -> status.setText(prevStatus);
            handler.postDelayed(statusTimer, 10000);
        } else {
            prevStatus = str;
        }
        status.setText(str);
    }

    private void setStatus(String str) {
        setStatus(str, false);
    }

    private void fromStatus() {
        setStatus(getString(R.string.from_status, parseCallSign(stagedCall), stagedMode[0], stagedCFO[0]), true);
    }

    /**
     * Safely parse a call sign from the native-filled byte[10]:
     * - strip \0 padding
     * - strip leading/trailing whitespace
     * - strip full-width spaces, zero-width characters, etc.
     */
    private String parseCallSign(byte[] raw) {
        if (raw == null) return "";
        String s = new String(raw, StandardCharsets.US_ASCII);
        s = s.replace("\0", "");
        s = s.replace("\u3000", "");
        s = s.replace("\u200B", "");
        s = s.trim();
        return s;
    }

    /**
     * Check whether a call sign is blank (null, empty, all whitespace, full-width spaces, zero-width chars, etc.)
     */
    private boolean isBlankCallSign(String s) {
        if (s == null) return true;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch != ' ' && ch != '\t' && ch != '\n' && ch != '\r'
                    && ch != '\0' && ch != '\u3000' && ch != '\u200B') {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the string is entirely whitespace (null, empty, spaces, full-width spaces, tabs, newlines).
     * Used to reject "all whitespace" content in the compose dialog.
     */
    private static boolean isAllWhitespace(String s) {
        if (s == null || s.isEmpty()) return true;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) return false;
        }
        return true;
    }

    /**
     * If the call sign exists in the contact list, return "callSign[note]"; otherwise return the original call sign.
     * If the note is empty, no square brackets are added.
     */
    private String appendContactNote(String call) {
        if (call == null) return null;
        for (Contact c : contacts) {
            if (c.callSign.equals(call)) {
                if (c.note != null && !c.note.isEmpty()) {
                    return call + "[" + c.note + "]";
                }
                break;
            }
        }
        return call;
    }

    private byte[] callTerm() {
        return Arrays.copyOf(callSign.getBytes(StandardCharsets.US_ASCII), callSign.length() + 1);
    }

    private String currentTime() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
    }

    private void addLine(String call, String info) {
        addString(getString(R.string.title_line, currentTime(), appendContactNote(call), info));
    }

    // Decrypt when receiving a message
    private void addMessage(String call, String info, String mesg) {
        if (info.equals(getString(R.string.received))) {
            String decrypted = CryptoUtils.decrypt(mesg, password);
            if (decrypted != null) {
                mesg = decrypted;
            }
        }
        addString(getString(R.string.title_message, currentTime(), appendContactNote(call), info, mesg));
    }

    private void addString(String str) {
        runOnUiThread(() -> {
            int count = 1000;
            if (messages.getCount() >= count) {
                messages.remove(messages.getItem(0));
            }
            messages.add(str);
            messageListView.setSelection(messages.getCount() - 1);
            // Persist history only; settings.txt is written on pause / explicit changes.
            storeHistory();
        });
    }

    /**
     * Check whether the received message body starts with "@selfCallSign " (a trailing space is required).
     * - Case-sensitive match
     * - Only the very beginning of the message body is checked
     * - The message body is on the second line of title_message.
     * Caller (MessageAdapter) has already excluded sent messages.
     */
    private boolean isTargetingSelf(String item) {
        if (item == null || callSign == null || callSign.isEmpty()) return false;
        // Relaxed rule: any occurrence of the call sign (case-sensitive) anywhere in the message.
        return item.contains(callSign);
    }

    // -------- Custom adapter: bubble-style display --------
    private class MessageAdapter extends ArrayAdapter<String> {
        public MessageAdapter(Context context, int resource) {
            super(context, resource);
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            FrameLayout layout;
            TextView textView;
            if (convertView == null) {
                layout = (FrameLayout) getLayoutInflater().inflate(R.layout.message_list_item, parent, false);
                textView = layout.findViewById(R.id.message_text);
                layout.setTag(textView);
            } else {
                layout = (FrameLayout) convertView;
                textView = (TextView) layout.getTag();
            }

            String item = getItem(position);
            textView.setText(item);

            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) textView.getLayoutParams();
                        // Do not use bubble background
            textView.setBackground(null);
            // Alignment: all sent messages right-aligned, all received messages left-aligned.
            // A failure message is sent if its first line carries our own call sign.
            boolean sent = isSentItem(item) || (isFailureItem(item) && isSentFailure(item));
            params.gravity = sent ? Gravity.END : Gravity.START;

            // Color priority: failure red > sent green > self mention blue > normal text.
            if (isFailureItem(item)) {
                textView.setTextColor(COLOR_RED);
            } else if (sent) {
                textView.setTextColor(COLOR_GREEN);
            } else if (isTargetingSelf(item)) {
                textView.setTextColor(COLOR_BLUE);
            } else {
                textView.setTextColor(COLOR_TEXT_PRIMARY);
            }
            textView.setLayoutParams(params);

            return layout;
        }

        private boolean isSentItem(String item) {
            if (item == null) return false;
            return item.contains(getString(R.string.transmitted)) ||
                   item.contains(getString(R.string.transmitted_encrypted)) ||
                   item.contains(getString(R.string.repeated)) ||
                   item.contains(getString(R.string.sent_ping));
        }

        // Only match received ping ("Got ping"), excluding self-sent ping
        private boolean isPingItem(String item) {
            if (item == null) return false;
            return item.contains(getString(R.string.preamble_ping));
        }

        // Failure messages: no second line, not a normal sent item, not a ping.
        private boolean isFailureItem(String item) {
            if (item == null) return false;
            if (item.contains("\n")) return false;
            if (isSentItem(item)) return false;
            if (isPingItem(item)) return false;
            return true;
        }

        // Sent failure: failure message whose first line carries our own call sign.
        private boolean isSentFailure(String item) {
            if (item == null) return false;
            int nl = item.indexOf('\n');
            String firstLine = (nl >= 0) ? item.substring(0, nl) : item;
            String[] parts = firstLine.split(" - ");
            if (parts.length >= 2) {
                String call = parts[1].trim();
                int bracket = call.indexOf('[');
                if (bracket > 0) call = call.substring(0, bracket);
                return call.equals(callSign);
            }
            return false;
        }
    }

    // -------- Contact adapter: call sign + note --------
    private class ContactAdapter extends ArrayAdapter<Contact> {
        public ContactAdapter(Context context) {
            // Pass 0 and inflate ourselves to avoid super.getView casting to TextView
            super(context, 0);
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(android.R.layout.simple_list_item_2, parent, false);
            }
            TextView text1 = v.findViewById(android.R.id.text1);
            TextView text2 = v.findViewById(android.R.id.text2);
            Contact c = getItem(position);
            if (c != null) {
                text1.setText(c.callSign);
                text2.setText(c.note == null || c.note.isEmpty() ? " " : c.note);
            }
            // Long-press selection highlight
            v.setBackgroundColor(position == selectedContactIndex
                    ? COLOR_BLUE
                    : COLOR_BACKGROUND);
            return v;
        }
    }

    private void startListening() {
    if (audioRecord != null) {
        audioRecord.startRecording();
        if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            // No warm-up read here; OnRecordPositionUpdateListener drives reads.
            setStatus(getString(R.string.listening));
        } else {
            setStatus(getString(R.string.audio_recording_error));
        }
    }
}

    private void stopListening() {
        if (audioRecord != null)
            audioRecord.stop();
    }

    private void initAudioRecord(boolean restart) {
        if (audioRecord != null) {
            boolean rateChanged = audioRecord.getSampleRate() != recordRate;
            boolean channelChanged = audioRecord.getChannelCount() != (recordChannel == 0 ? 1 : 2);
            boolean sourceChanged = audioRecord.getAudioSource() != audioSource;
            if (!rateChanged && !channelChanged && !sourceChanged)
                return;
            stopListening();
            audioRecord.release();
            audioRecord = null;
        }
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int channelCount = 1;
        if (recordChannel != 0) {
            channelCount = 2;
            channelConfig = AudioFormat.CHANNEL_IN_STEREO;
        }
        int frameSize = sampleSize * channelCount;
        int bufferSize = 2 * Integer.highestOneBit(3 * recordRate) * frameSize;
        try {
            AudioRecord testAudioRecord = new AudioRecord(audioSource, recordRate, channelConfig, audioFormat, bufferSize);
            if (testAudioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                if (createDecoder(recordRate)) {
                    audioRecord = testAudioRecord;
                    recordCount = recordRate / 50;
                    recordBuffer = new short[recordCount * channelCount];
                    audioRecord.setRecordPositionUpdateListener(recordListener);
                    audioRecord.setPositionNotificationPeriod(recordCount);
                    if (restart)
                        startListening();
                } else {
                    testAudioRecord.release();
                    setStatus(getString(R.string.heap_error));
                }
            } else {
                testAudioRecord.release();
                setStatus(getString(R.string.audio_init_failed));
            }
        } catch (IllegalArgumentException e) {
            setStatus(getString(R.string.audio_setup_failed));
        } catch (SecurityException e) {
            setStatus(getString(R.string.audio_permission_denied));
        }
    }

    private void setRecordRate(int newSampleRate) {
        if (recordRate == newSampleRate)
            return;
        recordRate = newSampleRate;
        updateRecordRateMenu();
        initAudioRecord(true);
    }

    private void setRecordChannel(int newChannelSelect) {
        if (recordChannel == newChannelSelect)
            return;
        recordChannel = newChannelSelect;
        updateRecordChannelMenu();
        initAudioRecord(true);
    }

    private void setAudioSource(int newAudioSource) {
        if (audioSource == newAudioSource)
            return;
        audioSource = newAudioSource;
        updateAudioSourceMenu();
        initAudioRecord(true);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Release the gate before handling the result.
        permissionRequestInFlight = false;

        if (requestCode == permissionID) {
            boolean audioGranted = false;
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED)
                    continue;
                if (permissions[i].equals(Manifest.permission.RECORD_AUDIO)) {
                    audioGranted = true;
                }
            }
            if (audioGranted) {
                initAudioRecord(false);
            }
            // Start location updates once permission is granted
            startLocationUpdates();
            return;
        }

    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle state) {
        state.putInt("outputRate", outputRate);
        state.putInt("outputChannel", outputChannel);
        state.putInt("recordRate", recordRate);
        state.putInt("recordChannel", recordChannel);
        state.putInt("audioSource", audioSource);
        state.putInt("carrierFrequency", carrierFrequency);
        state.putInt("noiseSymbols", noiseSymbols);
        state.putString("callSign", callSign);
        state.putString("draftText", draftText);
        state.putBoolean("fancyHeader", fancyHeader);
        for (int i = 0; i < messages.getCount(); ++i)
            state.putString("m" + i, messages.getItem(i));
        super.onSaveInstanceState(state);
    }

    // Persist settings only. Message history is written to HISTORY_FILE.
    private void storeSettings() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("outputRate", outputRate);
            obj.put("outputChannel", outputChannel);
            obj.put("recordRate", recordRate);
            obj.put("recordChannel", recordChannel);
            obj.put("audioSource", audioSource);
            obj.put("carrierFrequency", carrierFrequency);
            obj.put("noiseSymbols", noiseSymbols);
            obj.put("callSign", callSign);
            obj.put("draftText", draftText);
            obj.put("fancyHeader", fancyHeader);
            obj.put("beaconIntervalMin", beaconIntervalMs / 60000L);
            obj.put("netSendEnabled", netSendEnabled);
            obj.put("netIsRelay", netIsRelay);
            obj.put("netFreqKhz", netFreqKhz);
            obj.put("netToneHz", netToneHz);
            obj.put("netUpFreqKhz", netUpFreqKhz);
            obj.put("netUpToneHz", netUpToneHz);
            obj.put("netDownFreqKhz", netDownFreqKhz);
            obj.put("netDownToneHz", netDownToneHz);
            obj.put("beaconLat", beaconLat);
            obj.put("beaconLon", beaconLon);
            obj.put("netRecvEnabled", netRecvEnabled);
            obj.put("netRecvRadiusKm", netRecvRadiusKm);
            obj.put("netRecvWindowMin", netRecvWindowMin);
            writeDocument(SETTINGS_FILE, obj.toString(2));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Persist message history as a JSON array.
    private void storeHistory() {
        try {
            JSONArray msgs = new JSONArray();
            for (int i = 0; i < messages.getCount(); ++i) {
                msgs.put(messages.getItem(i));
            }
            writeDocument(HISTORY_FILE, msgs.toString(2));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Load message history from HISTORY_FILE only.
    private void loadHistory() {
        messages.clear();
        String text = readDocument(HISTORY_FILE);
        if (text == null) return;
        try {
            JSONArray arr = new JSONArray(text);
            for (int i = 0; i < arr.length(); i++) {
                String m = arr.optString(i, null);
                if (m != null) messages.add(m);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    // ========== Contact persistence ==========

    private static String documentsRelativePath() {
    // MediaStore RELATIVE_PATH must end with '/' on Android 10+.
    // A missing trailing slash makes writes silently target a different
    // directory than the one findDocument() later queries.
    return Environment.DIRECTORY_DOCUMENTS + "/" + PERSIST_DIR + "/";
}

    // Exact-match lookup by DISPLAY_NAME. Also purges any "name (n).ext" variants.
    private Uri findDocument(String name) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
        if (mediaStoreUriCache.containsKey(name)) {
            return mediaStoreUriCache.get(name);
        }
        ContentResolver cr = getContentResolver();
        Uri collection = MediaStore.Files.getContentUri("external");

        String selection =
                MediaStore.Files.FileColumns.RELATIVE_PATH + " LIKE ? AND " +
                MediaStore.Files.FileColumns.DISPLAY_NAME + " = ?";
        String[] selectionArgs = new String[]{
                Environment.DIRECTORY_DOCUMENTS + "/" + PERSIST_DIR + "%",
                name
        };
        String sortOrder = MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC";

        Uri found = null;
        try (Cursor c = cr.query(collection,
                new String[]{MediaStore.Files.FileColumns._ID},
                selection, selectionArgs, sortOrder)) {
            if (c != null && c.moveToFirst()) {
                found = ContentUris.withAppendedId(collection, c.getLong(0));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Remove any system-renamed duplicates such as "settings (1).txt".
        deleteRenamedVariants(name);

        if (found != null) {
            mediaStoreUriCache.put(name, found);
        }
        return found;
    }

    // Delete any "name (n).ext" variants while keeping the exact "name" file.
    private void deleteRenamedVariants(String name) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        String baseName = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            baseName = name.substring(0, dot);
            ext = name.substring(dot);
        }
        String selection =
                MediaStore.Files.FileColumns.RELATIVE_PATH + " LIKE ? AND " +
                MediaStore.Files.FileColumns.DISPLAY_NAME + " LIKE ? AND " +
                MediaStore.Files.FileColumns.DISPLAY_NAME + " != ?";
        String[] args = new String[]{
                Environment.DIRECTORY_DOCUMENTS + "/" + PERSIST_DIR + "%",
                baseName + " (%)" + ext,
                name
        };
        try {
            getContentResolver().delete(
                    MediaStore.Files.getContentUri("external"), selection, args);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Create the file only if no exact-name file exists.
    // If MediaStore still renames it (rare race), delete the duplicate and reuse the existing one.
    private Uri createDocument(String name) {
        Uri existing = findDocument(name);
        if (existing != null) {
            return existing;
        }

        ContentResolver cr = getContentResolver();
        Uri collection = MediaStore.Files.getContentUri("external");
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Files.FileColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain");
        cv.put(MediaStore.Files.FileColumns.RELATIVE_PATH, documentsRelativePath());
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            cv.put(MediaStore.Files.FileColumns.IS_PENDING, 1);
        }
        try {
            Uri uri = cr.insert(collection, cv);
            if (uri == null) return null;

            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                ContentValues clear = new ContentValues();
                clear.put(MediaStore.Files.FileColumns.IS_PENDING, 0);
                cr.update(uri, clear, null, null);
            }

            // Verify that MediaStore did not append "(n)" to the file name.
            String actualName = queryDisplayName(uri);
            if (actualName != null && !actualName.equals(name)) {
                cr.delete(uri, null, null);
                deleteRenamedVariants(name);
                Uri again = findDocument(name);
                if (again != null) {
                    mediaStoreUriCache.put(name, again);
                }
                return again;
            }

            mediaStoreUriCache.put(name, uri);
            return uri;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // Read DISPLAY_NAME of a MediaStore URI.
    private String queryDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri,
                new String[]{MediaStore.Files.FileColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        }
        return null;
    }

    private File persistDir() {
    File base = getExternalFilesDir(null);
    File dir = new File(base != null ? base : getFilesDir(), PERSIST_DIR);
    if (!dir.exists()) dir.mkdirs();
    return dir;
}

private void writeDocument(String name, String content) {
    // Priority 1: public Documents/Rattlegram_Plus/
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // Android 10+: use MediaStore.
        // First try to resolve an existing file by its absolute DATA path to
        // avoid the UNIQUE constraint on files._data when the row already exists.
        try {
            File publicDir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    PERSIST_DIR);
            File target = new File(publicDir, name);
            Uri uri = null;

            String[] projection = new String[]{MediaStore.Files.FileColumns._ID};
            String selection = MediaStore.Files.FileColumns.DATA + "=?";
            String[] args = new String[]{target.getAbsolutePath()};
            try (Cursor c = getContentResolver().query(
                    MediaStore.Files.getContentUri("external"),
                    projection, selection, args, null)) {
                if (c != null && c.moveToFirst()) {
                    uri = ContentUris.withAppendedId(
                            MediaStore.Files.getContentUri("external"), c.getLong(0));
                }
            }

            if (uri == null) {
                // Not present yet: try the cache-based lookup, then insert.
                uri = findDocument(name);
            }
            if (uri == null) {
                uri = createDocument(name);
            }
            if (uri != null) {
                try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out != null) {
                        out.write(content.getBytes(StandardCharsets.UTF_8));
                    }
                }
                return; // success
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    } else {
        // Android 9 and below: use File API
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                File publicDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), PERSIST_DIR);
                if (!publicDir.exists()) publicDir.mkdirs();
                File file = new File(publicDir, name);
                try (FileWriter w = new FileWriter(file)) {
                    w.write(content);
                }
                return; // success
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // Priority 2: fallback to app-private directory
    File primary = new File(persistDir(), name);
    try (FileWriter w = new FileWriter(primary)) {
        w.write(content);
    } catch (Exception e) {
        e.printStackTrace();
    }
}

private String readDocument(String name) {
    // Priority 1: public Documents/Rattlegram_Plus/
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // Android 10+: use MediaStore
        try {
            Uri uri = findDocument(name);
            if (uri != null) {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in != null) {
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                        String text = bos.toString("UTF-8");
                        if (!text.isEmpty()) return text;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    } else {
        // Android 9 and below: use File API
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                File publicDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), PERSIST_DIR);
                File file = new File(publicDir, name);
                if (file.exists()) {
                    try (FileReader r = new FileReader(file)) {
                        StringBuilder sb = new StringBuilder();
                        char[] buf = new char[4096];
                        int n;
                        while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
                        if (sb.length() > 0) return sb.toString();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // Priority 2: fallback to app-private directory
    File primary = new File(persistDir(), name);
    if (primary.exists()) {
        try (FileReader r = new FileReader(primary)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            if (sb.length() > 0) return sb.toString();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    return null;
}

    // Load settings from SETTINGS_FILE and message history from HISTORY_FILE.
    // Legacy SharedPreferences and legacy "messages" inside settings.txt are ignored.
    private void loadSettingsFromStorage() {
        outputRate = 8000;
        outputChannel = 0;
        recordRate = 8000;
        recordChannel = 0;
        audioSource = MediaRecorder.AudioSource.DEFAULT;
        carrierFrequency = 1500;
        noiseSymbols = 6;
        callSign = "ANONYMOUS";
        draftText = "";
        fancyHeader = false;

        String text = readDocument(SETTINGS_FILE);
        if (text != null) {
            try {
                JSONObject obj = new JSONObject(text);
                outputRate = obj.optInt("outputRate", outputRate);
                outputChannel = obj.optInt("outputChannel", outputChannel);
                recordRate = obj.optInt("recordRate", recordRate);
                recordChannel = obj.optInt("recordChannel", recordChannel);
                audioSource = obj.optInt("audioSource", audioSource);
                carrierFrequency = obj.optInt("carrierFrequency", carrierFrequency);
                noiseSymbols = obj.optInt("noiseSymbols", noiseSymbols);
                callSign = obj.optString("callSign", callSign);
                draftText = obj.optString("draftText", draftText);
                fancyHeader = obj.optBoolean("fancyHeader", fancyHeader);
                beaconIntervalMs = obj.optLong("beaconIntervalMin", 1L) * 60000L;
                netSendEnabled = obj.optBoolean("netSendEnabled", false);
                netIsRelay = obj.optBoolean("netIsRelay", false);
                netFreqKhz = obj.optInt("netFreqKhz", 435000);
                netToneHz = obj.optDouble("netToneHz", 0.0);
                netUpFreqKhz = obj.optInt("netUpFreqKhz", 145990);
                netUpToneHz = obj.optDouble("netUpToneHz", 0.0);
                netDownFreqKhz = obj.optInt("netDownFreqKhz", 437800);
                netDownToneHz = obj.optDouble("netDownToneHz", 0.0);
                beaconLat = obj.optDouble("beaconLat", 0.0);
                beaconLon = obj.optDouble("beaconLon", 0.0);
                netRecvEnabled = obj.optBoolean("netRecvEnabled", true);
                netRecvRadiusKm = obj.optInt("netRecvRadiusKm", 10);
                netRecvWindowMin = obj.optInt("netRecvWindowMin", 10);
                // Legacy "messages" field inside settings.txt is intentionally ignored.
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        loadHistory();
    }

    // Load contacts from CONTACTS_FILE only (no legacy migration).
    private void loadContacts() {
        contacts.clear();
        String text = readDocument(CONTACTS_FILE);
        if (text == null) return;
        try {
            JSONArray arr = new JSONArray(text);
            for (int i = 0; i < arr.length() && i < MAX_CONTACTS; i++) {
                JSONObject obj = arr.getJSONObject(i);
                String cs = obj.optString("callSign", "");
                String note = obj.optString("note", "");
                if (!isBlankCallSign(cs))
                    contacts.add(new Contact(cs, note));
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void saveContacts() {
        try {
            JSONArray arr = new JSONArray();
            for (Contact c : contacts) {
                JSONObject obj = new JSONObject();
                obj.put("callSign", c.callSign);
                obj.put("note", c.note == null ? "" : c.note);
                arr.put(obj);
            }
            writeDocument(CONTACTS_FILE, arr.toString(2));
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    /**
     * Automatically save the sender's call sign to contacts.
     * - Skip null, empty, whitespace-only call signs
     * - Skip ANONYMOUS
     * - Ignore if already present (exact match)
     * - Max 100 contacts
     * - Note is "Auto-added at " + current datetime yyyy-MM-dd HH:mm:ss
     */
    private void autoSaveContact(String senderCall) {
        if (senderCall == null) return;

        // Strip \0 padding, full-width spaces, zero-width chars, then trim
        senderCall = senderCall.replace("\0", "")
                               .replace("\u3000", "")
                               .replace("\u200B", "")
                               .trim();

        // Skip blank, empty, ANONYMOUS
        if (isBlankCallSign(senderCall)) return;
        if ("ANONYMOUS".equals(senderCall)) return;

        // Ignore if already present
        for (Contact c : contacts) {
            if (c.callSign.equals(senderCall)) {
                return;
            }
        }

        // Limit
        if (contacts.size() >= MAX_CONTACTS) {
            return;
        }

        contacts.add(new Contact(senderCall,
                getString(R.string.contact_auto_added_at) + currentTime()));
        saveContacts();
    }

    

    @Override
    protected void onCreate(Bundle state) {
        messages = new MessageAdapter(this, R.layout.message_list_item);
        final int defaultSampleRate = 8000;
        final int defaultChannelSelect = 0;
        final int defaultAudioSource = MediaRecorder.AudioSource.DEFAULT;
        final int defaultCarrierFrequency = 1500;
        final int defaultNoiseSymbols = 6;
        final String defaultCallSign = "ANONYMOUS";
        final String defaultDraftText = "";
        final boolean defaultFancyHeader = false;
        if (state == null) {
            loadSettingsFromStorage();
        } else {
            outputRate = state.getInt("outputRate", defaultSampleRate);
            outputChannel = state.getInt("outputChannel", defaultChannelSelect);
            recordRate = state.getInt("recordRate", defaultSampleRate);
            recordChannel = state.getInt("recordChannel", defaultChannelSelect);
            audioSource = state.getInt("audioSource", defaultAudioSource);
            carrierFrequency = state.getInt("carrierFrequency", defaultCarrierFrequency);
            noiseSymbols = state.getInt("noiseSymbols", defaultNoiseSymbols);
            callSign = state.getString("callSign", defaultCallSign);
            draftText = state.getString("draftText", defaultDraftText);
            fancyHeader = state.getBoolean("fancyHeader", defaultFancyHeader);
            for (int i = 0; i < 100; ++i) {
                String mesg = state.getString("m" + i, null);
                if (mesg != null)
                    messages.add(mesg);
            }
        }
        super.onCreate(state);
        initThemeColors();
        EdgeToEdge.enable(this);
        ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        status = binding.status;
        handler = new Handler(getMainLooper());
        // Initialize network beacon client and location manager
        aprs = new Aprs(this);
        aprs.setListener(new Aprs.OnBeaconListener() {
            @Override
            public void onBeaconReceived(Aprs.ReceivedBeacon rb) {
                addNetworkBeaconReceived(rb);
            }
            @Override
            public void onBeaconSent(Aprs.SentBeacon sb) {
                addNetworkBeaconSent(sb);
            }
            @Override
            public void onAprsStatus(String message) {
                setStatus(message, true);
            }
        });
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        startLocationUpdates();
        // Start receiving network beacons at app launch if enabled
        if (netRecvEnabled) {
            syncAprsConfig();
            aprs.startReceiving();
        }
        // Single background thread for MediaStore mirror writes.
        mirrorExecutor = Executors.newSingleThreadExecutor();
        setContentView(binding.getRoot());
        binding.getRoot().setBackgroundColor(COLOR_BACKGROUND);
        findViewById(R.id.divider).setBackgroundColor(COLOR_DIVIDER);
        handleInsets();
        stagedCFO = new float[1];
        stagedMode = new int[1];
        stagedCall = new byte[10];
        payload = new byte[170];
        loadContacts();
        messageListView = binding.messages;
        binding.messages.setAdapter(messages);

        // ========== Message click listener ==========
binding.messages.setOnItemClickListener((adapterView, view, i, l) -> {
    String item = messages.getItem(i);
    if (item != null) {
        String[] mesg = item.split("\n", 2);
        if (mesg.length == 2)
            composeMessage(mesg[1]);
    }
});
binding.messages.setOnItemLongClickListener((adapterView, view, i, l) -> {
            String item = messages.getItem(i);
            if (item != null) {
                String[] mesg = item.split("\n", 2);
                if (mesg.length == 2)
                    transmitMessage(mesg[1], false);
            }
            return true;
        });
        initAudioTrack();


        // ========== First run: request microphone permission ==========
        List<String> permissions = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
            setStatus(getString(R.string.audio_permission_denied));
        } else {
            initAudioRecord(false);
        }
        // Add location permissions for network beacon position
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        // Add storage permissions for Android 9 and below
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
        if (!permissions.isEmpty()) {
            permissionRequestInFlight = true;
            ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), permissionID);
        }

        String message = extractIntent(getIntent());
        if (message != null)
            composeMessage(message);

        if (messages.getCount() > 0) {
            messageListView.setSelection(messages.getCount() - 1);
        }

        // ---------- Initialize beacon handler ----------
        beaconHandler = new Handler();

        // ---------- Initialize bottom buttons ----------
        beaconButton = findViewById(R.id.beacon_button);
        if (beaconButton != null) {
            // Single click toggles beacon; long press opens the config dialog
            beaconButton.setOnClickListener(v -> toggleBeacon());
            beaconButton.setOnLongClickListener(v -> {
                showBeaconConfigDialog();
                return true;
            });
            updateBeaconButton();
        }

        composeButton = findViewById(R.id.compose_button);
        composeButton.setOnClickListener(v -> composeMessage(null));
    }

    private void initThemeColors() {
        COLOR_BLACK   = ContextCompat.getColor(this, R.color.color_black);
        COLOR_WHITE   = ContextCompat.getColor(this, R.color.color_white);
        COLOR_RED     = ContextCompat.getColor(this, R.color.color_red);
        COLOR_GREEN   = ContextCompat.getColor(this, R.color.color_green);
        COLOR_YELLOW  = ContextCompat.getColor(this, R.color.color_yellow);
        COLOR_BLUE    = ContextCompat.getColor(this, R.color.color_blue);
        COLOR_MAGENTA = ContextCompat.getColor(this, R.color.color_magenta);
        COLOR_CYAN    = ContextCompat.getColor(this, R.color.color_cyan);

        COLOR_TEXT_PRIMARY = COLOR_BLACK;
        COLOR_BACKGROUND   = COLOR_WHITE;
        COLOR_DIVIDER      = COLOR_BLACK;
    }

    private void handleInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String message = extractIntent(intent);
        if (message != null)
            composeMessage(message);
    }

    private String extractIntent(Intent intent) {
        String action = intent.getAction();
        if (action == null)
            return null;
        if (!action.equals(Intent.ACTION_SEND))
            return null;
        String type = intent.getType();
        if (type == null)
            return null;
        if (!type.equals("text/plain"))
            return null;
        return intent.getStringExtra(Intent.EXTRA_TEXT);
    }

    // ========== Location implementation ==========

    

    

    

    

    

    

    

    // ========== Existing features below ==========

    private void setNoiseSymbols(int newNoiseSymbols) {
        if (noiseSymbols == newNoiseSymbols)
            return;
        noiseSymbols = newNoiseSymbols;
        updateNoiseSymbolsMenu();
    }

    private void updateNoiseSymbolsMenu() {
        switch (noiseSymbols) {
            case 0:
                menu.findItem(R.id.action_disable_noise).setChecked(true);
                break;
            case 1:
                menu.findItem(R.id.action_set_noise_quarter_second).setChecked(true);
                break;
            case 3:
                menu.findItem(R.id.action_set_noise_half_second).setChecked(true);
                break;
            case 6:
                menu.findItem(R.id.action_set_noise_one_second).setChecked(true);
                break;
            case 11:
                menu.findItem(R.id.action_set_noise_two_seconds).setChecked(true);
                break;
            case 22:
                menu.findItem(R.id.action_set_noise_four_seconds).setChecked(true);
                break;
        }
    }


    private void setFancyHeader(boolean newFancyHeader) {
        if (fancyHeader == newFancyHeader)
            return;
        fancyHeader = newFancyHeader;
        updateFancyHeaderMenu();
    }

    private void updateFancyHeaderMenu() {
        if (fancyHeader)
            menu.findItem(R.id.action_enable_fancy_header).setChecked(true);
        else
            menu.findItem(R.id.action_disable_fancy_header).setChecked(true);
    }

    private void setOutputRate(int newSampleRate) {
        if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING)
            return;
        if (outputRate == newSampleRate)
            return;
        outputRate = newSampleRate;
        updateOutputRateMenu();
        initAudioTrack();
    }

    private void updateOutputRateMenu() {
        switch (outputRate) {
            case 8000:
                menu.findItem(R.id.action_set_output_rate_8000).setChecked(true);
                break;
            case 16000:
                menu.findItem(R.id.action_set_output_rate_16000).setChecked(true);
                break;
            case 32000:
                menu.findItem(R.id.action_set_output_rate_32000).setChecked(true);
                break;
            case 44100:
                menu.findItem(R.id.action_set_output_rate_44100).setChecked(true);
                break;
            case 48000:
                menu.findItem(R.id.action_set_output_rate_48000).setChecked(true);
                break;
        }
    }

    private void updateRecordRateMenu() {
        switch (recordRate) {
            case 8000:
                menu.findItem(R.id.action_set_record_rate_8000).setChecked(true);
                break;
            case 16000:
                menu.findItem(R.id.action_set_record_rate_16000).setChecked(true);
                break;
            case 32000:
                menu.findItem(R.id.action_set_record_rate_32000).setChecked(true);
                break;
            case 44100:
                menu.findItem(R.id.action_set_record_rate_44100).setChecked(true);
                break;
            case 48000:
                menu.findItem(R.id.action_set_record_rate_48000).setChecked(true);
                break;
        }
    }

    private void updateRecordChannelMenu() {
        switch (recordChannel) {
            case 0:
                menu.findItem(R.id.action_set_record_channel_default).setChecked(true);
                break;
            case 1:
                menu.findItem(R.id.action_set_record_channel_first).setChecked(true);
                break;
            case 2:
                menu.findItem(R.id.action_set_record_channel_second).setChecked(true);
                break;
            case 3:
                menu.findItem(R.id.action_set_record_channel_summation).setChecked(true);
                break;
            case 4:
                menu.findItem(R.id.action_set_record_channel_analytic).setChecked(true);
                break;
        }
    }

    private void updateAudioSourceMenu() {
        switch (audioSource) {
            case MediaRecorder.AudioSource.DEFAULT:
                menu.findItem(R.id.action_set_source_default).setChecked(true);
                break;
            case MediaRecorder.AudioSource.MIC:
                menu.findItem(R.id.action_set_source_microphone).setChecked(true);
                break;
            case MediaRecorder.AudioSource.CAMCORDER:
                menu.findItem(R.id.action_set_source_camcorder).setChecked(true);
                break;
            case MediaRecorder.AudioSource.VOICE_RECOGNITION:
                menu.findItem(R.id.action_set_source_voice_recognition).setChecked(true);
                break;
            case MediaRecorder.AudioSource.UNPROCESSED:
                menu.findItem(R.id.action_set_source_unprocessed).setChecked(true);
                break;
        }
    }

    private void setOutputChannel(int newChannelSelect) {
        if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING)
            return;
        if (outputChannel == newChannelSelect)
            return;
        outputChannel = newChannelSelect;
        updateOutputChannelMenu();
        initAudioTrack();
    }

    private void updateOutputChannelMenu() {
        switch (outputChannel) {
            case 0:
                menu.findItem(R.id.action_set_output_channel_default).setChecked(true);
                break;
            case 1:
                menu.findItem(R.id.action_set_output_channel_first).setChecked(true);
                break;
            case 2:
                menu.findItem(R.id.action_set_output_channel_second).setChecked(true);
                break;
            case 4:
                menu.findItem(R.id.action_set_output_channel_analytic).setChecked(true);
                break;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        menu.findItem(R.id.action_set_source_unprocessed).setEnabled(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N);
        this.menu = menu;
        callSignMenuItem = menu.findItem(R.id.action_edit_call_sign);
        updateCallSignMenuItem();
        updateOutputRateMenu();
        updateOutputChannelMenu();
        updateRecordRateMenu();
        updateRecordChannelMenu();
        updateAudioSourceMenu();
        updateNoiseSymbolsMenu();
        updateFancyHeaderMenu();
        return true;
    }

    private void updateCallSignMenuItem() {
        if (callSignMenuItem != null) {
            callSignMenuItem.setTitle(callSign);
        }
    }

    private void updateBeaconButton() {
        if (beaconButton == null) return;
        int color = beaconRunning ? COLOR_GREEN : COLOR_TEXT_PRIMARY;
        beaconButton.setColorFilter(color);
    }

    private void startBeacon() {
        if (beaconRunning) return;
        beaconRunning = true;
        // First trigger: voice + network beacon sent together
        transmitMessage("", false);
        if (netSendEnabled && aprs != null) {
            syncAprsConfig();
            aprs.sendOnce();
        }
        beaconRunnable = new Runnable() {
            @Override
            public void run() {
                if (beaconRunning) {
                    transmitMessage("", false);
                    if (netSendEnabled && aprs != null) {
                        aprs.sendOnce();
                    }
                    beaconHandler.postDelayed(this, beaconIntervalMs);
                }
            }
        };
        beaconHandler.postDelayed(beaconRunnable, beaconIntervalMs);
        setStatus(getString(R.string.beacon_started));
        updateBeaconButton();
    }

    private void stopBeacon() {
        if (!beaconRunning) return;
        beaconRunning = false;
        if (beaconRunnable != null) {
            beaconHandler.removeCallbacks(beaconRunnable);
            beaconRunnable = null;
        }
        if (aprs != null) aprs.stopSending();
        setStatus(getString(R.string.beacon_stopped));
        updateBeaconButton();
    }

    private void toggleBeacon() {
        if (beaconRunning) {
            stopBeacon();
        } else {
            startBeacon();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_contacts) {
            showContactsDialog();
            return true;
        }

        if (id == R.id.action_delete_messages) {
            if (messages.getCount() > 0)
                deleteMessages();
            return true;
        }
        if (id == R.id.action_set_output_rate_8000) {
            setOutputRate(8000);
            return true;
        }
        if (id == R.id.action_set_output_rate_16000) {
            setOutputRate(16000);
            return true;
        }
        if (id == R.id.action_set_output_rate_32000) {
            setOutputRate(32000);
            return true;
        }
        if (id == R.id.action_set_output_rate_44100) {
            setOutputRate(44100);
            return true;
        }
        if (id == R.id.action_set_output_rate_48000) {
            setOutputRate(48000);
            return true;
        }
        if (id == R.id.action_set_output_channel_default) {
            setOutputChannel(0);
            return true;
        }
        if (id == R.id.action_set_output_channel_first) {
            setOutputChannel(1);
            return true;
        }
        if (id == R.id.action_set_output_channel_second) {
            setOutputChannel(2);
            return true;
        }
        if (id == R.id.action_set_output_channel_analytic) {
            setOutputChannel(4);
            return true;
        }
        if (id == R.id.action_set_record_rate_8000) {
            setRecordRate(8000);
            return true;
        }
        if (id == R.id.action_set_record_rate_16000) {
            setRecordRate(16000);
            return true;
        }
        if (id == R.id.action_set_record_rate_32000) {
            setRecordRate(32000);
            return true;
        }
        if (id == R.id.action_set_record_rate_44100) {
            setRecordRate(44100);
            return true;
        }
        if (id == R.id.action_set_record_rate_48000) {
            setRecordRate(48000);
            return true;
        }
        if (id == R.id.action_set_record_channel_default) {
            setRecordChannel(0);
            return true;
        }
        if (id == R.id.action_set_record_channel_first) {
            setRecordChannel(1);
            return true;
        }
        if (id == R.id.action_set_record_channel_second) {
            setRecordChannel(2);
            return true;
        }
        if (id == R.id.action_set_record_channel_summation) {
            setRecordChannel(3);
            return true;
        }
        if (id == R.id.action_set_record_channel_analytic) {
            setRecordChannel(4);
            return true;
        }
        if (id == R.id.action_set_source_default) {
            setAudioSource(MediaRecorder.AudioSource.DEFAULT);
            return true;
        }
        if (id == R.id.action_set_source_microphone) {
            setAudioSource(MediaRecorder.AudioSource.MIC);
            return true;
        }
        if (id == R.id.action_set_source_camcorder) {
            setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
            return true;
        }
        if (id == R.id.action_set_source_voice_recognition) {
            setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION);
            return true;
        }
        if (id == R.id.action_set_source_unprocessed) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                setAudioSource(MediaRecorder.AudioSource.UNPROCESSED);
                return true;
            }
            return false;
        }
        if (id == R.id.action_show_spectrum) {
            spectrumAnalyzer();
            return true;
        }
        if (id == R.id.action_edit_call_sign) {
            editCallSign();
            return true;
        }
        if (id == R.id.action_set_carrier_frequency) {
            setCarrierFrequency();
            return true;
        }
        if (id == R.id.action_disable_noise) {
            setNoiseSymbols(0);
            return true;
        }
        if (id == R.id.action_set_noise_quarter_second) {
            setNoiseSymbols(1);
            return true;
        }
        if (id == R.id.action_set_noise_half_second) {
            setNoiseSymbols(3);
            return true;
        }
        if (id == R.id.action_set_noise_one_second) {
            setNoiseSymbols(6);
            return true;
        }
        if (id == R.id.action_set_noise_two_seconds) {
            setNoiseSymbols(11);
            return true;
        }
        if (id == R.id.action_set_noise_four_seconds) {
            setNoiseSymbols(22);
            return true;
        }
        if (id == R.id.action_enable_fancy_header) {
            setFancyHeader(true);
            return true;
        }
        if (id == R.id.action_disable_fancy_header) {
            setFancyHeader(false);
            return true;
        }
        if (id == R.id.action_force_quit) {
            forcedQuit();
            return true;
        }
        if (id == R.id.action_password) {
            showPasswordDialog();
            return true;
        }
        if (id == R.id.action_privacy_policy) {
            showTextPage(getString(R.string.privacy_policy), getString(R.string.privacy_policy_text));
            return true;
        }
        if (id == R.id.action_about) {
            showTextPage(getString(R.string.about), getString(R.string.about_text, BuildConfig.VERSION_NAME));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void deleteMessages() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
        builder.setTitle(R.string.delete_messages)
                .setMessage(R.string.delete_messages_prompt)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    messages.clear();
                    // Persist empty history as "[]".
                    storeHistory();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void forcedQuit() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
        builder.setTitle(R.string.force_quit)
                .setMessage(R.string.force_quit_prompt)
                .setPositiveButton(R.string.quit, (dialog, which) -> {
                    storeSettings();
                    storeHistory();
                    System.exit(0);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void spectrumAnalyzer() {
        View view = getLayoutInflater().inflate(R.layout.spectrum_analyzer, null);
        spectrogramView = view.findViewById(R.id.spectrogram);
        spectrumView = view.findViewById(R.id.spectrum);
        spectrumBitmap = Bitmap.createBitmap(spectrumWidth, spectrumHeight, Bitmap.Config.ARGB_8888);
        spectrogramBitmap = Bitmap.createBitmap(spectrogramWidth, spectrogramHeight, Bitmap.Config.ARGB_8888);
        spectrumView.setImageBitmap(spectrumBitmap);
        spectrogramView.setImageBitmap(spectrogramBitmap);
        spectrumPixels = new int[spectrumWidth * spectrumHeight];
        spectrogramPixels = new int[spectrogramWidth * spectrogramHeight];
        spectrumTint = COLOR_BLUE;
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
        builder.setTitle(R.string.spectrum_analyzer);
        builder.setView(view);
        builder.setNeutralButton(R.string.close, (dialogInterface, i) -> showSpectrum = false);
        builder.setOnCancelListener(dialogInterface -> showSpectrum = false);
        builder.show();
        showSpectrum = true;
    }

    // ========== composeMessage: encrypted thresholds 35/68/99, plain 85/128/170 ==========
    private void composeMessage(String temp) {
        View view = getLayoutInflater().inflate(R.layout.compose_message, null);
        EditText edit = view.findViewById(R.id.message);
        CheckBox encryptCheck = view.findViewById(R.id.encrypt_checkbox);
        TextView capacity = view.findViewById(R.id.capacity);

        boolean hasPassword = password != null && !password.isEmpty();
        encryptCheck.setEnabled(hasPassword);
        if (!hasPassword) {
            encryptCheck.setChecked(false);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
        builder.setTitle(R.string.compose_message);
        builder.setView(view);

        builder.setNeutralButton(R.string.draft, (dialogInterface, i) -> draftText = edit.getText().toString());
        builder.setNegativeButton(R.string.discard, (dialogInterface, i) -> {
            if (temp == null) draftText = "";
        });
        builder.setPositiveButton(R.string.transmit, (dialogInterface, i) -> {
            if (temp == null) draftText = "";
            transmitMessage(edit.getText().toString(), encryptCheck.isChecked());
        });
        builder.setOnCancelListener(dialogInterface -> {
            if (temp == null) {
                String text = edit.getText().toString();
                draftText = text;
            }
        });


        AlertDialog dialog = builder.show();
        Context context = this;

        final TextWatcher textWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String raw = s.toString();
                int inputBytes = raw.getBytes(StandardCharsets.UTF_8).length;
                boolean encrypt = encryptCheck.isChecked() && encryptCheck.isEnabled();

                int[] thresholds;
                if (encrypt) {
                    thresholds = new int[]{57, 100, 142};
                } else {
                    thresholds = new int[]{85, 128, 170};
                }
                int maxBytes = thresholds[2];

                if (inputBytes > maxBytes) {
                    int over = inputBytes - maxBytes;
                    int overResId = encrypt ? R.plurals.over_capacity_enc : R.plurals.over_capacity;
                    capacity.setText(getResources().getQuantityString(overResId, over, over));
                    capacity.setTextColor(COLOR_RED);
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    return;
                }

                int resId;
                int threshold;
                if (encrypt) {
                    if (inputBytes <= thresholds[0]) {
                        threshold = thresholds[0];
                        resId = R.plurals.enc_strong_bytes_left;
                    } else if (inputBytes <= thresholds[1]) {
                        threshold = thresholds[1];
                        resId = R.plurals.enc_medium_bytes_left;
                    } else {
                        threshold = thresholds[2];
                        resId = R.plurals.enc_fast_bytes_left;
                    }
                } else {
                    if (inputBytes <= thresholds[0]) {
                        threshold = thresholds[0];
                        resId = R.plurals.strong_bytes_left;
                    } else if (inputBytes <= thresholds[1]) {
                        threshold = thresholds[1];
                        resId = R.plurals.medium_bytes_left;
                    } else {
                        threshold = thresholds[2];
                        resId = R.plurals.normal_bytes_left;
                    }
                }
                int remaining = threshold - inputBytes;
                capacity.setText(getResources().getQuantityString(resId, remaining, remaining));
                capacity.setTextColor(COLOR_BLUE);

                // Enable send only when there is content and it is not all whitespace
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                      .setEnabled(inputBytes > 0 && !isAllWhitespace(raw));
            }

            @Override
            public void afterTextChanged(Editable s) {}
        };
        edit.addTextChangedListener(textWatcher);

        encryptCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            String text = edit.getText().toString();
            textWatcher.onTextChanged(text, 0, 0, 0);
        });

        edit.setText(temp == null ? draftText : temp);
        // Move cursor to the end for easier continued input
        edit.setSelection(edit.getText().length());
    }

    private void transmitMessage(String message, boolean encrypt) {
        if (encrypt && (password == null || password.isEmpty())) {
            addLine(callSign.trim(), getString(R.string.encryption_disabled_no_password));
            Toast.makeText(this, R.string.encryption_disabled_no_password, Toast.LENGTH_LONG).show();
            return;
        }

        byte[] payloadBytes = new byte[payload.length];
        int payloadLen;
        if (encrypt) {
            int msgBytes = message.getBytes(StandardCharsets.UTF_8).length;
            int padTo;
            if (msgBytes <= 57) {
                padTo = 57;
            } else if (msgBytes <= 100) {
                padTo = 100;
            } else if (msgBytes <= 142) {
                padTo = 142;
            } else {
                addLine(callSign.trim(), getString(R.string.encrypted_message_too_long));
                Toast.makeText(this, R.string.encrypted_message_too_long, Toast.LENGTH_SHORT).show();
                return;
            }
            byte[] blob = CryptoUtils.encryptBytes(message, password, padTo);
            if (blob == null) {
                addLine(callSign.trim(), getString(R.string.encryption_failed));
                Toast.makeText(this, R.string.encryption_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            System.arraycopy(blob, 0, payloadBytes, 0, blob.length);
            payloadLen = blob.length;
        } else {
            byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
            if (msgBytes.length > payload.length) {
                addLine(callSign.trim(), getString(R.string.encrypted_message_too_long));
                Toast.makeText(this, R.string.encrypted_message_too_long, Toast.LENGTH_SHORT).show();
                return;
            }
            System.arraycopy(msgBytes, 0, payloadBytes, 0, msgBytes.length);
            payloadLen = msgBytes.length;
        }

        boolean wasBeaconRunning = beaconRunning;
        if (wasBeaconRunning) {
            if (beaconRunnable != null) {
                beaconHandler.removeCallbacks(beaconRunnable);
            }
        }

        stopListening();

        if (message.length() == 0) {
            addLine(callSign.trim(), getString(R.string.sent_ping));
        } else if (encrypt) {
            addMessage(callSign.trim(), getString(R.string.transmitted_encrypted), message);
        } else {
            addMessage(callSign.trim(), getString(R.string.transmitted), message);
        }

        configureEncoder(payloadBytes, payloadLen, callTerm(), carrierFrequency, noiseSymbols, fancyHeader, encrypt);
        for (int i = 0; i < 5; ++i) {
            produceEncoder(outputBuffer, outputChannel);
            audioTrack.write(outputBuffer, 0, outputBuffer.length);
        }
        audioTrack.play();
        setStatus(getString(encrypt ? R.string.transmitting_encrypted : R.string.transmitting));

        if (wasBeaconRunning) {
            beaconRunnable = new Runnable() {
                @Override
                public void run() {
                    if (beaconRunning) {
                        transmitMessage("", false);
                        if (netSendEnabled && aprs != null) {
                            aprs.sendOnce();
                        }
                        beaconHandler.postDelayed(this, beaconIntervalMs);
                    }
                }
            };
            beaconHandler.postDelayed(beaconRunnable, beaconIntervalMs);
        }
    }

    private void setInputType(ViewGroup np, int it) {
        int count = np.getChildCount();
        for (int i = 0; i < count; i++) {
            final View child = np.getChildAt(i);
            if (child instanceof ViewGroup) {
                setInputType((ViewGroup) child, it);
            } else if (child instanceof EditText) {
                EditText et = (EditText) child;
                et.setInputType(it);
                break;
            }
        }
    }

    private String[] carrierValues(int minCarrierFrequency, int maxCarrierFrequency) {
        int count = (maxCarrierFrequency - minCarrierFrequency) / 50 + 1;
        String[] values = new String[count];
        for (int i = 0; i < count; ++i)
            values[i] = String.format(Locale.US, "%d", i * 50 + minCarrierFrequency);
        return values;
    }

    private void setCarrierFrequency() {
        View view = getLayoutInflater().inflate(R.layout.carrier_frequency, null);
        NumberPicker picker = view.findViewById(R.id.carrier);
        int maxCarrierFrequency = 3000;
        int minCarrierFrequency = outputChannel == 4 ? -maxCarrierFrequency : 1000;
        if (carrierFrequency < minCarrierFrequency || carrierFrequency > maxCarrierFrequency)
            carrierFrequency = 1500;
        picker.setMinValue(0);
        picker.setDisplayedValues(null);
        picker.setMaxValue((maxCarrierFrequency - minCarrierFrequency) / 50);
        picker.setValue((carrierFrequency - minCarrierFrequency) / 50);
        picker.setDisplayedValues(carrierValues(minCarrierFrequency, maxCarrierFrequency));
        setInputType(picker, InputType.TYPE_CLASS_NUMBER);
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
        builder.setTitle(R.string.carrier_frequency);
        builder.setView(view);
        builder.setNegativeButton(R.string.cancel, null);
        builder.setPositiveButton(R.string.okay, (dialogInterface, i) -> carrierFrequency = picker.getValue() * 50 + minCarrierFrequency);
        builder.show();
    }

// Validate a call sign: non-empty, no spaces (including full-width and zero-width),
    // only 0-9 and A-Z, length 1..9.
    private boolean isValidCallSign(String s) {
        if (s == null) return false;
        s = s.trim();
        if (s.isEmpty() || s.length() > 9) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\u3000' || c == '\u200B') return false;
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z'))) return false;
        }
        return true;
    }

    private void editCallSign() {
        View view = getLayoutInflater().inflate(R.layout.call_sign, null);
        EditText edit = view.findViewById(R.id.call);
        edit.setText(callSign);
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
        builder.setTitle(R.string.call_sign);
        builder.setView(view);
        builder.setNegativeButton(R.string.cancel, null);
        // Add the positive button so it is visible; its click is handled below.
        builder.setPositiveButton(R.string.okay, null);
        AlertDialog dialog = builder.create();
        // Validate on OK; keep the dialog open when the input is invalid.
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String input = edit.getText().toString().trim();
                if (!isValidCallSign(input)) {
                    Toast.makeText(this, R.string.call_sign_invalid, Toast.LENGTH_SHORT).show();
                    return;
                }
                callSign = input;
                updateCallSignMenuItem();
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private void showTextPage(String title, String message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
        builder.setNeutralButton(R.string.close, null);
        builder.setTitle(title);
        builder.setMessage(message);
        builder.show();
    }

    // ================================================================
    // ========== Password management ==================================
    // ================================================================

    private void showPasswordDialog() {
        View view = getLayoutInflater().inflate(R.layout.password_dialog, null);
        EditText editText = view.findViewById(R.id.password_edit);
        TextView errorText = view.findViewById(R.id.password_error);
        Button generateButton = view.findViewById(R.id.generate_password_button);
        Button cancelButton = view.findViewById(R.id.button_cancel);
        Button okButton = view.findViewById(R.id.button_okay);

        editText.setText(password);
        editText.setSelection(password.length());

        generateButton.setOnClickListener(v -> {
            byte[] randomBytes = new byte[32];
            new java.security.SecureRandom().nextBytes(randomBytes);
            String hex = bytesToHex(randomBytes);
            editText.setText(hex);
            editText.setSelection(hex.length());
            errorText.setVisibility(View.GONE);
        });

        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
        builder.setTitle(R.string.password_dialog_title);
        builder.setView(view);
        builder.setCancelable(true);

        AlertDialog dialog = builder.create();

        cancelButton.setOnClickListener(v -> dialog.dismiss());

        okButton.setOnClickListener(v -> {
            String newPassword = editText.getText().toString();

            if (newPassword.isEmpty()) {
                password = "";
                errorText.setVisibility(View.GONE);
                Toast.makeText(this, R.string.password_updated, Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                return;
            }

            byte[] bytes = newPassword.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < 8 || bytes.length > 256) {
                errorText.setText(R.string.password_length_error);
                errorText.setVisibility(View.VISIBLE);
                return;
            }

            password = newPassword;
            Toast.makeText(this, R.string.password_updated, Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        dialog.show();
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ================================================================
    // ========== Contact management ===================================
    // ================================================================

    private void showContactsDialog() {
        View view = getLayoutInflater().inflate(R.layout.contact, null);
        ListView listView = view.findViewById(R.id.contact_list);
        TextView hintView = view.findViewById(R.id.contact_hint);
        LinearLayout editPanel = view.findViewById(R.id.contact_edit_panel);
        EditText editCall = view.findViewById(R.id.contact_call_sign);
        EditText editNote = view.findViewById(R.id.contact_note);
        Button okBtn = view.findViewById(R.id.contact_edit_ok);
        Button cancelBtn = view.findViewById(R.id.contact_edit_cancel);
        Button addBtn = view.findViewById(R.id.contact_add_button);
        Button modifyBtn = view.findViewById(R.id.contact_modify_button);
        Button deleteBtn = view.findViewById(R.id.contact_delete_button);

        hintView.setText(R.string.contact_add_hint);

        // Reset selection state
        selectedContactIndex = -1;

        final ContactAdapter adapter = new ContactAdapter(this);
        adapter.addAll(contacts);
        listView.setAdapter(adapter);

        // -1 means create new
        final int[] editingIndex = {-1};

        final Runnable refreshList = () -> {
            adapter.clear();
            adapter.addAll(contacts);
            adapter.notifyDataSetChanged();
        };

        final Runnable hideEdit = () -> {
            editPanel.setVisibility(View.GONE);
            editCall.setText("");
            editNote.setText("");
            editingIndex[0] = -1;
        };

        // Save button
        okBtn.setOnClickListener(v -> {
            String cs = editCall.getText().toString().trim();
            String note = editNote.getText().toString().trim();
            if (isBlankCallSign(cs)) {
                Toast.makeText(this, R.string.contact_call_sign_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            if (editingIndex[0] >= 0) {
                Contact c = contacts.get(editingIndex[0]);
                c.callSign = cs;
                c.note = note;
            } else {
                if (contacts.size() >= MAX_CONTACTS) {
                    Toast.makeText(this, R.string.contact_limit_reached, Toast.LENGTH_SHORT).show();
                    return;
                }
                contacts.add(new Contact(cs, note));
            }
            saveContacts();
            refreshList.run();
            selectedContactIndex = -1;
            hideEdit.run();
        });

        cancelBtn.setOnClickListener(v -> hideEdit.run());

        // Add
        addBtn.setOnClickListener(v -> {
            if (contacts.size() >= MAX_CONTACTS) {
                Toast.makeText(this, R.string.contact_limit_reached, Toast.LENGTH_SHORT).show();
                return;
            }
            selectedContactIndex = -1;
            adapter.notifyDataSetChanged();
            editCall.setText("");
            editNote.setText("");
            editingIndex[0] = -1;
            editPanel.setVisibility(View.VISIBLE);
        });

        // Modify
        modifyBtn.setOnClickListener(v -> {
            if (selectedContactIndex < 0 || selectedContactIndex >= contacts.size()) {
                Toast.makeText(this, R.string.contact_select_first, Toast.LENGTH_SHORT).show();
                return;
            }
            Contact c = contacts.get(selectedContactIndex);
            editCall.setText(c.callSign);
            editNote.setText(c.note == null ? "" : c.note);
            editingIndex[0] = selectedContactIndex;
            editPanel.setVisibility(View.VISIBLE);
        });

        // Delete
        deleteBtn.setOnClickListener(v -> {
            if (selectedContactIndex < 0 || selectedContactIndex >= contacts.size()) {
                Toast.makeText(this, R.string.contact_select_first, Toast.LENGTH_SHORT).show();
                return;
            }
            final int pos = selectedContactIndex;
            new AlertDialog.Builder(this, R.style.Theme_AlertDialog)
                    .setTitle(R.string.delete)
                    .setMessage(R.string.contact_delete_prompt)
                    .setPositiveButton(R.string.delete, (d, w) -> {
                        contacts.remove(pos);
                        saveContacts();
                        selectedContactIndex = -1;
                        refreshList.run();
                        hideEdit.run();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });

        // Long-press list item: select and highlight
        listView.setOnItemLongClickListener((parent, v, position, id) -> {
            selectedContactIndex = position;
            adapter.notifyDataSetChanged();
            return true;
        });

        // Click list item: close dialog, open compose window, auto-fill @[call sign] with a space
        final AlertDialog contactDialog = new AlertDialog.Builder(this, R.style.Theme_AlertDialog)
                .setTitle(R.string.contacts)
                .setView(view)
                .setNegativeButton(R.string.close, null)
                .show();

        listView.setOnItemClickListener((parent, v, position, id) -> {
            Contact c = contacts.get(position);
            contactDialog.dismiss();
            composeMessage("@[" + c.callSign + "] ");
        });
    }


    // ==================== Network beacon helpers ====================

    /** Sync current network beacon settings into Aprs.Config. */
    private void syncAprsConfig() {
        if (aprs == null) return;
        Aprs.Config c = new Aprs.Config();
        c.callSign = callSign;
        c.sendEnabled = netSendEnabled;
        c.isRelay = netIsRelay;
        c.freqKhz = netFreqKhz;
        c.toneHz = netToneHz;
        c.upFreqKhz = netUpFreqKhz;
        c.upToneHz = netUpToneHz;
        c.downFreqKhz = netDownFreqKhz;
        c.downToneHz = netDownToneHz;
        c.lat = beaconLat;
        c.lon = beaconLon;
        c.recvEnabled = netRecvEnabled;
        c.recvRadiusKm = netRecvRadiusKm;
        c.recvWindowMin = netRecvWindowMin;
        aprs.updateConfig(c);
    }

    /** Start location updates (GPS + network) and cache the latest position. */
    private void startLocationUpdates() {
        if (locationManager == null) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                beaconLat = location.getLatitude();
                beaconLon = location.getLongitude();
                if (aprs != null) syncAprsConfig();
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(@NonNull String provider) {}
            @Override public void onProviderDisabled(@NonNull String provider) {}
        };
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60000, 100, listener);
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 60000, 100, listener);
        } catch (Exception ignored) {}
    }

    /** Append a received network beacon to the message list. */
    private void addNetworkBeaconReceived(Aprs.ReceivedBeacon rb) {
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .format(new Date(rb.timestampMs));
        String time = new SimpleDateFormat("HH:mm", Locale.US)
                .format(new Date(rb.timestampMs));
        StringBuilder sb = new StringBuilder();
        sb.append(date).append(" ").append(time).append(" - ")
          .append(rb.senderCall).append(" - ")
          .append(getString(R.string.received_network_beacon)).append("\n");
        sb.append(getString(R.string.beacon_type)).append(": ")
          .append(rb.isRelay ? getString(R.string.beacon_type_relay)
                             : getString(R.string.beacon_type_direct)).append("\n");
        if (rb.isRelay) {
            sb.append(getString(R.string.beacon_up_freq)).append(": ")
              .append(String.format(Locale.US, "%.3f", rb.upFreqKhz / 1000.0)).append("MHz\n");
            sb.append(getString(R.string.beacon_down_freq)).append(": ")
              .append(String.format(Locale.US, "%.3f", rb.downFreqKhz / 1000.0)).append("MHz\n");
            if (rb.toneHz > 0) {
                sb.append(getString(R.string.beacon_tone)).append(": ")
                  .append(String.format(Locale.US, "%.1f", rb.toneHz)).append("Hz\n");
            }
        } else {
            sb.append(getString(R.string.beacon_freq)).append(": ")
              .append(String.format(Locale.US, "%.3f", rb.freqKhz / 1000.0)).append("MHz\n");
            if (rb.toneHz > 0) {
                sb.append(getString(R.string.beacon_tone)).append(": ")
                  .append(String.format(Locale.US, "%.1f", rb.toneHz)).append("Hz\n");
            }
        }
        sb.append(getString(R.string.beacon_distance)).append(": ")
          .append(rb.distanceKm).append(" km");
        addString(sb.toString());
    }

    /** Append a sent network beacon to the message list (no distance). */
    private void addNetworkBeaconSent(Aprs.SentBeacon sb) {
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String time = new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
        StringBuilder out = new StringBuilder();
        out.append(date).append(" ").append(time).append(" - ")
           .append(callSign.trim()).append(" - ")
           .append(getString(R.string.sent_network_beacon)).append("\n");
        out.append(getString(R.string.beacon_type)).append(": ")
           .append(sb.isRelay ? getString(R.string.beacon_type_relay)
                              : getString(R.string.beacon_type_direct)).append("\n");
        if (sb.isRelay) {
            out.append(getString(R.string.beacon_up_freq)).append(": ")
               .append(String.format(Locale.US, "%.3f", sb.upFreqKhz / 1000.0)).append("MHz\n");
            out.append(getString(R.string.beacon_down_freq)).append(": ")
               .append(String.format(Locale.US, "%.3f", sb.downFreqKhz / 1000.0)).append("MHz\n");
            if (sb.toneHz > 0) {
                out.append(getString(R.string.beacon_tone)).append(": ")
                   .append(String.format(Locale.US, "%.1f", sb.toneHz)).append("Hz\n");
            }
        } else {
            out.append(getString(R.string.beacon_freq)).append(": ")
               .append(String.format(Locale.US, "%.3f", sb.freqKhz / 1000.0)).append("MHz\n");
            if (sb.toneHz > 0) {
                out.append(getString(R.string.beacon_tone)).append(": ")
                   .append(String.format(Locale.US, "%.1f", sb.toneHz)).append("Hz");
            }
        }
        addString(out.toString());
    }

    /** Show the beacon configuration dialog (three top-to-bottom blocks). */
    private void showBeaconConfigDialog() {
        View view = getLayoutInflater().inflate(R.layout.beacon_config, null);

        final EditText intervalEdit = view.findViewById(R.id.beacon_interval_edit);

        final CheckBox netSendCheck = view.findViewById(R.id.net_send_checkbox);
        final RadioGroup typeGroup = view.findViewById(R.id.net_send_type_group);
        final RadioButton typeDirect = view.findViewById(R.id.net_type_direct);
        final RadioButton typeRelay = view.findViewById(R.id.net_type_relay);
        final LinearLayout directParams = view.findViewById(R.id.direct_params);
        final LinearLayout relayParams = view.findViewById(R.id.relay_params);
        final EditText directFreq = view.findViewById(R.id.direct_freq);
        final EditText directTone = view.findViewById(R.id.direct_tone);
        final EditText relayUpFreq = view.findViewById(R.id.relay_up_freq);
        final EditText relayDownFreq = view.findViewById(R.id.relay_down_freq);
        final EditText relayTone = view.findViewById(R.id.relay_tone);
        final EditText latEdit = view.findViewById(R.id.beacon_lat);
        final EditText lonEdit = view.findViewById(R.id.beacon_lon);

        final CheckBox netRecvCheck = view.findViewById(R.id.net_recv_checkbox);
        final EditText radiusEdit = view.findViewById(R.id.beacon_recv_radius_edit);
        final EditText windowEdit = view.findViewById(R.id.beacon_recv_window_edit);

        // Initialize EditTexts with current values
        intervalEdit.setText(String.valueOf(clamp((int) (beaconIntervalMs / 60000L), 1, 10)));
        radiusEdit.setText(String.valueOf(clamp(netRecvRadiusKm, 1, 100)));
        windowEdit.setText(String.valueOf(clamp(netRecvWindowMin, 1, 1440)));

        netSendCheck.setChecked(netSendEnabled);
        if (netIsRelay) typeRelay.setChecked(true);
        else typeDirect.setChecked(true);
        directParams.setVisibility(netIsRelay ? View.GONE : View.VISIBLE);
        relayParams.setVisibility(netIsRelay ? View.VISIBLE : View.GONE);

        directFreq.setText(String.format(Locale.US, "%.3f", netFreqKhz / 1000.0));
        directTone.setText(netToneHz > 0 ? String.format(Locale.US, "%.1f", netToneHz) : "");
        relayUpFreq.setText(String.format(Locale.US, "%.3f", netUpFreqKhz / 1000.0));
        relayDownFreq.setText(String.format(Locale.US, "%.3f", netDownFreqKhz / 1000.0));
        relayTone.setText(netDownToneHz > 0 ? String.format(Locale.US, "%.1f", netDownToneHz) : "");
        latEdit.setText(String.format(Locale.US, "%.5f", beaconLat));
        lonEdit.setText(String.format(Locale.US, "%.5f", beaconLon));
        netRecvCheck.setChecked(netRecvEnabled);

        typeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            boolean relay = checkedId == R.id.net_type_relay;
            directParams.setVisibility(relay ? View.GONE : View.VISIBLE);
            relayParams.setVisibility(relay ? View.VISIBLE : View.GONE);
        });

        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
        builder.setTitle(R.string.beacon_config_title);
        builder.setView(view);
        builder.setNegativeButton(R.string.cancel, null);
        builder.setPositiveButton(R.string.okay, (dialog, which) -> {
            // Parse and clamp inputs
            int intervalMin = 1;
            try { intervalMin = Integer.parseInt(intervalEdit.getText().toString().trim()); } catch (Exception ignored) {}
            intervalMin = clamp(intervalMin, 1, 10);
            beaconIntervalMs = intervalMin * 60000L;

            int radiusVal = 10;
            try { radiusVal = Integer.parseInt(radiusEdit.getText().toString().trim()); } catch (Exception ignored) {}
            radiusVal = clamp(radiusVal, 1, 100);

            int windowVal = 10;
            try { windowVal = Integer.parseInt(windowEdit.getText().toString().trim()); } catch (Exception ignored) {}
            windowVal = clamp(windowVal, 1, 1440);

            netSendEnabled = netSendCheck.isChecked();
            netIsRelay = typeRelay.isChecked();

            netFreqKhz = parseFreqMHz(directFreq.getText().toString(), netFreqKhz);
            netToneHz = parseToneHz(directTone.getText().toString(), 0.0);
            netUpFreqKhz = parseFreqMHz(relayUpFreq.getText().toString(), netUpFreqKhz);
            netUpToneHz = 0.0; // Relay now uses only downlink tone
            netDownFreqKhz = parseFreqMHz(relayDownFreq.getText().toString(), netDownFreqKhz);
            netDownToneHz = parseToneHz(relayTone.getText().toString(), 0.0);

            try { beaconLat = Double.parseDouble(latEdit.getText().toString()); } catch (Exception ignored) {}
            try { beaconLon = Double.parseDouble(lonEdit.getText().toString()); } catch (Exception ignored) {}

            boolean oldRecv = netRecvEnabled;
            netRecvEnabled = netRecvCheck.isChecked();
            netRecvRadiusKm = radiusVal;
            netRecvWindowMin = windowVal;

            syncAprsConfig();

            if (netRecvEnabled && !oldRecv && aprs != null) {
                aprs.startReceiving();
            } else if (!netRecvEnabled && oldRecv && aprs != null) {
                aprs.stopReceiving();
            }

            storeSettings();
        });
        builder.show();
    }

    /** Clamp an integer into [min, max]. */
    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }



    /** Parse a MHz string into integer kHz, clamped to 3.000..999.99 MHz. */
    private int parseFreqMHz(String text, int fallbackKhz) {
        try {
            double mhz = Double.parseDouble(text.trim());
            int khz = (int) Math.round(mhz * 1000.0);
            if (khz < 3000) khz = 3000;
            if (khz > 999990) khz = 999990;
            return khz;
        } catch (Exception e) {
            return fallbackKhz;
        }
    }

    /** Parse a Hz string into a double, empty means no tone (0). */
    private double parseToneHz(String text, double fallback) {
        if (text == null || text.trim().isEmpty()) return 0.0;
        try {
            double hz = Double.parseDouble(text.trim());
            if (hz < 1.0) hz = 1.0;
            if (hz > 999.0) hz = 999.0;
            return hz;
        } catch (Exception e) {
            return fallback;
        }
    }

    @Override
    protected void onResume() {
        startListening();
        super.onResume();
    }

    @Override
    protected void onPause() {
        stopListening();
        storeSettings();
        storeHistory();
        super.onPause();
    }

    @Override
protected void onDestroy() {
    stopBeacon();
    if (aprs != null) aprs.shutdown();
    if (audioTrack != null) audioTrack.stop();
    destroyEncoder();
    destroyDecoder();
    // Drain and shut down the mirror thread.
    if (mirrorExecutor != null) {
        mirrorExecutor.shutdown();
        mirrorExecutor = null;
    }
    super.onDestroy();
}
}