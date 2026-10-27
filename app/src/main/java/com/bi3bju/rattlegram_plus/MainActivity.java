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
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final int locationPermissionID = 100;
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
    private static final long BEACON_INTERVAL = 60 * 1000; // 1 minute

    // Call sign menu item reference
    private MenuItem callSignMenuItem;

    // Bottom buttons
    private ImageView beaconButton;
    private ImageView composeButton;

    // ========== Password in-memory variable ==========
    private String password = "";

    // ========== Location-related ==========
    private LocationManager locationManager;
    // Location message format: 📍[geo:latitude,longitude]
    private static final String LOCATION_PATTERN = "📍\\[geo:([-+]?\\d+\\.\\d+),([-+]?\\d+\\.\\d+)\\]";
    private static final String LOCATION_FORMAT = "📍[geo:%.6f,%.6f] ";
    // Maximum acceptable location age (5 minutes)
    private static final long MAX_LOCATION_AGE_MS = 5 * 60 * 1000;
    // Timeout for actively requesting a location (15 seconds)
    private static final long LOCATION_REQUEST_TIMEOUT_MS = 15 * 1000;

    // Location callback interface
    private interface LocationCallback {
        void onLocation(Location location);
    }

    // ========== Contact-related ==========
    private static final int MAX_CONTACTS = 100;
    private static final String CONTACTS_KEY = "contacts";

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
    // Cache MediaStore URIs to prevent duplicate inserts when
    // the MediaStore database has not yet synced the new entry.
    private final Map<String, Uri> mediaStoreUriCache = new HashMap<>();

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
            audioRecord.read(recordBuffer, 0, recordBuffer.length);
            if (!feedDecoder(recordBuffer, recordCount, recordChannel))
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
            int count = 100;
            if (messages.getCount() >= count) {
                messages.remove(messages.getItem(0));
            }
            messages.add(str);
            messageListView.setSelection(messages.getCount() - 1);
            storeSettings();
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
                audioRecord.read(recordBuffer, 0, recordBuffer.length);
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

        if (requestCode == permissionID) {
            // Merged request: audio + (fine|coarse) location
            boolean audioGranted = false;
            boolean locationGranted = false;
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED)
                    continue;
                if (permissions[i].equals(Manifest.permission.RECORD_AUDIO)) {
                    audioGranted = true;
                } else if (permissions[i].equals(Manifest.permission.ACCESS_FINE_LOCATION)
                        || permissions[i].equals(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    locationGranted = true;
                }
            }
            if (audioGranted) {
                initAudioRecord(false);
            }
            if (!locationGranted) {
                Toast.makeText(this, R.string.location_permission_denied, Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (requestCode == locationPermissionID) {
            boolean granted = false;
            for (int i = 0; i < permissions.length; i++) {
                if ((permissions[i].equals(Manifest.permission.ACCESS_FINE_LOCATION)
                        || permissions[i].equals(Manifest.permission.ACCESS_COARSE_LOCATION))
                        && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    granted = true;
                }
            }
            if (!granted) {
                Toast.makeText(this, R.string.location_permission_denied, Toast.LENGTH_SHORT).show();
            }
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
            JSONArray msgs = new JSONArray();
            for (int i = 0; i < messages.getCount(); ++i) {
                msgs.put(messages.getItem(i));
            }
            obj.put("messages", msgs);
            writeDocument(SETTINGS_FILE, obj.toString(2));
        } catch (Exception e) {
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

    private Uri findDocument(String name) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
    if (mediaStoreUriCache.containsKey(name)) {
        return mediaStoreUriCache.get(name);
    }
    ContentResolver cr = getContentResolver();
    Uri collection = MediaStore.Files.getContentUri("external");

    // Tolerate both "Documents/Rattlegram_Plus/" and "Documents/Rattlegram_Plus",
    // and tolerate system-renamed files like "settings (3).txt" by using LIKE.
    String baseName = name;
    String ext = "";
    int dot = name.lastIndexOf('.');
    if (dot > 0) {
        baseName = name.substring(0, dot);
        ext = name.substring(dot);
    }

    String selection =
            MediaStore.Files.FileColumns.RELATIVE_PATH + " LIKE ? AND " +
            MediaStore.Files.FileColumns.DISPLAY_NAME + " LIKE ?";
    String[] selectionArgs = new String[]{
            Environment.DIRECTORY_DOCUMENTS + "/" + PERSIST_DIR + "%",
            baseName + "%" + ext
    };

    String sortOrder = MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC";
    try (Cursor c = cr.query(collection,
            new String[]{MediaStore.Files.FileColumns._ID},
            selection, selectionArgs, sortOrder)) {
        if (c != null && c.moveToFirst()) {
            Uri uri = ContentUris.withAppendedId(collection, c.getLong(0));
            mediaStoreUriCache.put(name, uri);
            return uri;
        }
    } catch (Exception e) {
        e.printStackTrace();
    }
    return null;
}

    private Uri createDocument(String name) {
        ContentResolver cr = getContentResolver();
        Uri collection = MediaStore.Files.getContentUri("external");
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Files.FileColumns.DISPLAY_NAME, name);
        // Plain text MIME matches the .txt suffix. Content is still JSON text.
        cv.put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain");
        cv.put(MediaStore.Files.FileColumns.RELATIVE_PATH, documentsRelativePath());
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            cv.put(MediaStore.Files.FileColumns.IS_PENDING, 1);
        }
        try {
            Uri uri = cr.insert(collection, cv);
            if (uri != null) {
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                    ContentValues clear = new ContentValues();
                    clear.put(MediaStore.Files.FileColumns.IS_PENDING, 0);
                    cr.update(uri, clear, null, null);
                }
                // Cache the newly created URI.
                mediaStoreUriCache.put(name, uri);
            }
            return uri;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private File persistDir() {
    File base = getExternalFilesDir(null);
    File dir = new File(base != null ? base : getFilesDir(), PERSIST_DIR);
    if (!dir.exists()) dir.mkdirs();
    return dir;
}

private void writeDocument(String name, String content) {
    // Primary: app-private external storage (authoritative, always works)
    File primary = new File(persistDir(), name);
    try (FileWriter w = new FileWriter(primary)) {
        w.write(content);
    } catch (Exception e) {
        e.printStackTrace();
        return;  // nothing else we can do
    }

    // Optional mirror: Documents/Rattlegram_Plus/ via MediaStore (best effort)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        try {
            Uri uri = findDocument(name);
            if (uri == null) uri = createDocument(name);
            if (uri != null) {
                try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out != null) {
                        out.write(content.getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (Exception e) {
            // Mirror failure must never affect primary persistence
            e.printStackTrace();
        }
    }
}

    private String readDocument(String name) {
    // Primary: app-private external storage
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

    // Fallback: MediaStore (legacy data), then migrate to app-private
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        Uri uri = findDocument(name);
        if (uri != null) {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in != null) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                    String text = bos.toString("UTF-8");
                    if (!text.isEmpty()) {
                        // Migrate legacy data into authoritative app-private
                        try (FileWriter w = new FileWriter(primary)) {
                            w.write(text);
                        } catch (Exception ignored) {}
                        return text;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // Last fallback: legacy File API path (will fail with EACCES on Android 10+)
    File legacyDir = new File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            PERSIST_DIR);
    File legacyFile = new File(legacyDir, name);
    if (legacyFile.exists()) {
        try (FileReader r = new FileReader(legacyFile)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            return sb.toString();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    return null;
}

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
                JSONArray msgs = obj.optJSONArray("messages");
                if (msgs != null) {
                    for (int i = 0; i < msgs.length(); i++) {
                        String m = msgs.optString(i, null);
                        if (m != null) messages.add(m);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return;
        }

        // One-time migration from legacy SharedPreferences
        SharedPreferences pref = getPreferences(Context.MODE_PRIVATE);
        if (pref.getAll().isEmpty()) return;
        outputRate = pref.getInt("outputRate", outputRate);
        outputChannel = pref.getInt("outputChannel", outputChannel);
        recordRate = pref.getInt("recordRate", recordRate);
        recordChannel = pref.getInt("recordChannel", recordChannel);
        audioSource = pref.getInt("audioSource", audioSource);
        carrierFrequency = pref.getInt("carrierFrequency", carrierFrequency);
        noiseSymbols = pref.getInt("noiseSymbols", noiseSymbols);
        callSign = pref.getString("callSign", callSign);
        draftText = pref.getString("draftText", draftText);
        fancyHeader = pref.getBoolean("fancyHeader", fancyHeader);
        for (int i = 0; i < 100; ++i) {
            String mesg = pref.getString("m" + i, null);
            if (mesg != null) messages.add(mesg);
        }
        storeSettings();
        pref.edit().clear().apply();
    }

    private void loadContacts() {
        contacts.clear();
        String text = readDocument(CONTACTS_FILE);
        if (text == null) {
            // One-time migration from legacy SharedPreferences
            SharedPreferences pref = getPreferences(Context.MODE_PRIVATE);
            text = pref.getString(CONTACTS_KEY, null);
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
                saveContacts();
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return;
        }
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

    /**
     * Extract "call sign" and "note" from the first line of a message.
     * First line format: time - callSign[note] - status
     * Without note: time - callSign - status
     * Returns String[2]: {callSign, note}
     */
    private String[] parseCallAndNote(String firstLine) {
        String call = "";
        String note = "";
        if (firstLine == null) return new String[]{call, note};
        String[] parts = firstLine.split(" - ");
        if (parts.length >= 2) {
            String seg = parts[1].trim();
            int bracket = seg.indexOf('[');
            if (bracket > 0 && seg.endsWith("]")) {
                call = seg.substring(0, bracket).trim();
                note = seg.substring(bracket + 1, seg.length() - 1).trim();
            } else {
                call = seg;
            }
        }
        return new String[]{call, note};
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

        // ========== Message click listener: support opening maps for location messages ==========
        binding.messages.setOnItemClickListener((adapterView, view, i, l) -> {
            String item = messages.getItem(i);
            if (item != null) {
                String[] mesg = item.split("\n", 2);
                if (mesg.length == 2) {
                    String content = mesg[1];
                    // Match 📍[geo:latitude,longitude]
                    Pattern pattern = Pattern.compile(LOCATION_PATTERN);
                    Matcher matcher = pattern.matcher(content);
                    if (matcher.find()) {
                        try {
                            double lat = Double.parseDouble(matcher.group(1));
                            double lng = Double.parseDouble(matcher.group(2));
                            // Extract call sign and note from the first line as pin title
                            String[] cn = parseCallAndNote(mesg[0]);
                            openMap(lat, lng, cn[0], cn[1]);
                        } catch (NumberFormatException e) {
                            Toast.makeText(this, R.string.location_parse_failed, Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        composeMessage(content);
                    }
                }
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

        // ========== Initialize location manager ==========
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        // ========== First run: request all permissions at once ==========
        // Merging RECORD_AUDIO + ACCESS_FINE_LOCATION + ACCESS_COARSE_LOCATION into
        // a single requestPermissions() call prevents the second dialog from being
        // auto-denied by the system (which is what happened before).
        List<String> permissions = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
            setStatus(getString(R.string.audio_permission_denied));
        } else {
            initAudioRecord(false);
        }
        if (!hasLocationPermission()) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (!permissions.isEmpty()) {
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
            beaconButton.setOnClickListener(v -> toggleBeacon());
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

    /**
     * Whether location permission has been granted (either fine or coarse is enough).
     */
    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Whether location services are enabled.
     */
    private boolean isLocationServiceEnabled() {
        if (locationManager == null) return false;
        try {
            return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Whether the location is fresh enough.
     */
    private boolean isLocationFresh(Location loc) {
        return loc != null && (System.currentTimeMillis() - loc.getTime()) <= MAX_LOCATION_AGE_MS;
    }

    /**
     * Get the last known location (GMS-free, iterate all available providers).
     */
    private Location getLastKnownLocation() {
        if (!hasLocationPermission() || locationManager == null)
            return null;
        Location best = null;
        try {
            for (String provider : locationManager.getProviders(true)) {
                Location loc = locationManager.getLastKnownLocation(provider);
                if (loc == null) continue;
                if (best == null || loc.getTime() > best.getTime())
                    best = loc;
            }
        } catch (SecurityException e) {
            return null;
        }
        return best;
    }

    /**
     * Actively request a current location (with timeout).
     */
    private void requestCurrentLocation(final LocationCallback callback) {
        if (!hasLocationPermission() || locationManager == null) {
            callback.onLocation(null);
            return;
        }

        String provider;
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            provider = LocationManager.GPS_PROVIDER;
        } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            provider = LocationManager.NETWORK_PROVIDER;
        } else {
            callback.onLocation(null);
            return;
        }

        final boolean[] delivered = {false};
        final Runnable timeout = () -> {
            if (!delivered[0]) {
                delivered[0] = true;
                callback.onLocation(null);
            }
        };
        handler.postDelayed(timeout, LOCATION_REQUEST_TIMEOUT_MS);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                locationManager.getCurrentLocation(provider, null, getMainExecutor(), location -> {
                    if (!delivered[0]) {
                        delivered[0] = true;
                        handler.removeCallbacks(timeout);
                        callback.onLocation(location);
                    }
                });
            } else {
                // For Android 7.0 - 10 (API 24 - 29), LocationListener requires all methods.
                // Since API 30, default implementations are provided, but we implement them
                // explicitly to maintain compatibility with older versions.
                LocationListener listener = new LocationListener() {
                    @Override
                    public void onLocationChanged(@NonNull Location location) {
                        if (!delivered[0]) {
                            delivered[0] = true;
                            handler.removeCallbacks(timeout);
                            locationManager.removeUpdates(this);
                            callback.onLocation(location);
                        }
                    }

                    @Override
                    public void onStatusChanged(String provider, int status, Bundle extras) {
                        // Not used
                    }

                    @Override
                    public void onProviderEnabled(@NonNull String provider) {
                        // Not used
                    }

                    @Override
                    public void onProviderDisabled(@NonNull String provider) {
                        // Not used
                    }
                };
                locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper());
            }
        } catch (SecurityException e) {
            if (!delivered[0]) {
                delivered[0] = true;
                handler.removeCallbacks(timeout);
                callback.onLocation(null);
            }
        }
    }

    /**
     * Insert a location string at the current cursor position in the EditText.
     */
    private void insertLocationToEdit(EditText edit, Location loc) {
        if (loc == null) return;
        String locationStr = String.format(Locale.US, LOCATION_FORMAT,
                loc.getLatitude(), loc.getLongitude());
        int start = Math.max(edit.getSelectionStart(), 0);
        int end = Math.max(edit.getSelectionEnd(), 0);
        if (start > end) {
            int tmp = start;
            start = end;
            end = tmp;
        }
        edit.getText().replace(start, end, locationStr);
    }

    /**
     * Open map:
     * 1. First try the system default map app (geo: URI) with a pin title.
     * 2. If no map app is available, open Tencent Maps web in a browser.
     * Pin title is "callSign[note]"; if the note is empty only the call sign is shown;
     * if the call sign is empty it falls back to the default location label.
     * Title and address are truncated to 10 characters for Tencent Maps component compatibility.
     */
    private void openMap(double lat, double lng, String pinCall, String pinNote) {
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            Toast.makeText(this, R.string.location_format_error, Toast.LENGTH_SHORT).show();
            return;
        }

        // Pin title: callSign[note]
        String label;
        if (pinCall != null && !pinCall.isEmpty()) {
            if (pinNote != null && !pinNote.isEmpty()) {
                label = pinCall + "[" + pinNote + "]";
            } else {
                label = pinCall;
            }
        } else {
            label = getString(R.string.location_default_label);
        }
        String encodedLabel = Uri.encode(label);

        // 1. Prefer opening the system default map app via geo: URI
        Uri geoUri = Uri.parse(String.format(Locale.US,
                "geo:%.6f,%.6f?q=%.6f,%.6f(%s)",
                lat, lng, lat, lng, encodedLabel));
        Intent geoIntent = new Intent(Intent.ACTION_VIEW, geoUri);

        try {
            startActivity(geoIntent);
            return;
        } catch (ActivityNotFoundException e) {
            // No map app; fall through to browser
        }

        // 2. No map app; open Bing Maps web in a browser.
        String url = String.format(Locale.US,
                "https://www.bing.com/maps?cp=%.6f~%.6f&lvl=15&q=%.6f,%.6f",
                lat, lng, lat, lng);

        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));

        try {
            startActivity(browserIntent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_map_or_browser, Toast.LENGTH_SHORT).show();
        }
    }

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
        transmitMessage("", false);
        beaconRunnable = new Runnable() {
            @Override
            public void run() {
                if (beaconRunning) {
                    transmitMessage("", false);
                    beaconHandler.postDelayed(this, BEACON_INTERVAL);
                }
            }
        };
        beaconHandler.postDelayed(beaconRunnable, BEACON_INTERVAL);
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
                    storeSettings();
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

        // ========== Location button inside the compose dialog ==========
        View locationBtn = view.findViewById(R.id.location_button);
        if (locationBtn != null) {
            locationBtn.setOnClickListener(v -> {
                // Clear input box first, regardless of outcome
                edit.setText("");
                if (!hasLocationPermission()) {
                    // Re-request location permission (user may have denied it earlier)
                    ActivityCompat.requestPermissions(this,
                            new String[]{
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                            }, locationPermissionID);
                    return;
                }
                if (!isLocationServiceEnabled()) {
                    Toast.makeText(this, R.string.location_service_disabled, Toast.LENGTH_SHORT).show();
                    return;
                }
                setStatus(getString(R.string.location_fetching), true);
                final Location last = getLastKnownLocation();
                if (isLocationFresh(last)) {
                    insertLocationToEdit(edit, last);
                    return;
                }
                requestCurrentLocation(loc -> {
                    if (loc != null) {
                        insertLocationToEdit(edit, loc);
                    } else if (last != null) {
                        insertLocationToEdit(edit, last);
                    } else {
                        Toast.makeText(this, R.string.location_failed, Toast.LENGTH_SHORT).show();
                    }
                });
            });
        }

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
                        beaconHandler.postDelayed(this, BEACON_INTERVAL);
                    }
                }
            };
            beaconHandler.postDelayed(beaconRunnable, BEACON_INTERVAL);
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

    private void editCallSign() {
        View view = getLayoutInflater().inflate(R.layout.call_sign, null);
        EditText edit = view.findViewById(R.id.call);
        edit.setText(callSign);
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
        builder.setTitle(R.string.call_sign);
        builder.setView(view);
        builder.setNegativeButton(R.string.cancel, null);
        builder.setPositiveButton(R.string.okay, (dialogInterface, i) -> {
            callSign = edit.getText().toString();
            updateCallSignMenuItem();
        });
        builder.show();
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

    @Override
    protected void onResume() {
        startListening();
        super.onResume();
    }

    @Override
    protected void onPause() {
        stopListening();
        storeSettings();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopBeacon();
        if (audioTrack != null) audioTrack.stop();
        destroyEncoder();
        destroyDecoder();
        super.onDestroy();
    }
}