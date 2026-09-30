/*
Rattlegram Plus

Copyright 2026 BI3BJU <guerilla1949@gmail.com>
*/

package com.bi3bju.rattlegram_plus;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
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
import android.graphics.Color;
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
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    // Used to load the 'rattlegram' library on application startup.
    static {
        System.loadLibrary("rattlegram");
    }

    private static class Message {
        public long time;
        public byte[] call;
        public byte[] data;

        public Message(byte[] call, byte[] data) {
            this.time = SystemClock.elapsedRealtime() / 1000;
            this.call = Arrays.copyOf(call, 10);
            this.data = Arrays.copyOf(data, 170);
        }
    }
    private ArrayList<Message> repeatedMessages;

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
    private boolean repeaterMode;
    private boolean showSpectrum;
    private boolean ultrasonicEnabled;
    private int spectrumTint;
    private int noiseSymbols;
    private int repeaterDelay;
    private int repeaterDebounce;
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
    // Ping menu item reference (for tinting)
    private MenuItem pingMenuItem;

    // Bottom buttons
    private TextView locationButton;   // Location button (📍)
    private ImageView composeButton;

    // ========== Password in-memory variable ==========
    private String password = "";

    // ========== Location-related ==========
    private LocationManager locationManager;
    // Location message format: 📍[geo:latitude,longitude]
    private static final String LOCATION_PATTERN = "📍\\[geo:([-+]?\\d+\\.\\d+),([-+]?\\d+\\.\\d+)\\]";
    private static final String LOCATION_FORMAT = "📍[geo:%.6f,%.6f]";
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
    private static final int COLOR_MENTION_SELF = 0xFF0000FF;  // blue

    private static class Contact {
        public String callSign;
        public String note;

        public Contact(String callSign, String note) {
            this.callSign = callSign;
            this.note = note;
        }
    }

    private ArrayList<Contact> contacts = new ArrayList<>();
    // Default bubble text color (lazily initialized)
    private int bubbleDefaultTextColor = -1;

    private native boolean createEncoder(int sampleRate);

    private native void configureEncoder(byte[] payload, byte[] callSign, int carrierFrequency, int noiseSymbols, boolean fancyHeader);

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
                    fromStatus();
                    addLine(parseCallSign(stagedCall), getString(R.string.preamble_nope, stagedMode[0]));
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
                        setStatus(getResources().getQuantityString(R.plurals.bits_flipped, result, result), true);
                        String sender = parseCallSign(stagedCall);
                        if (repeaterMode) {
                            repeatMessage();
                        } else {
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
        int nl = item.indexOf('\n');
        if (nl < 0 || nl + 1 >= item.length()) return false;
        String content = item.substring(nl + 1);
        return content.startsWith("@" + callSign + " ");
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

            // Lazily record the default text color
            if (bubbleDefaultTextColor == -1) {
                bubbleDefaultTextColor = textView.getCurrentTextColor();
            }

            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) textView.getLayoutParams();
            if (isSentItem(item)) {
                // Sent by me: right-aligned bubble, always default text color
                textView.setBackgroundResource(R.drawable.bubble_sent);
                params.gravity = Gravity.END;
                textView.setTextColor(bubbleDefaultTextColor);
            } else {
                // Received: left-aligned bubble; blue only if body starts with "@selfCallSign "
                textView.setBackgroundResource(R.drawable.bubble_received);
                params.gravity = Gravity.START;
                if (isTargetingSelf(item)) {
                    textView.setTextColor(COLOR_MENTION_SELF);
                } else {
                    textView.setTextColor(bubbleDefaultTextColor);
                }
            }
            textView.setLayoutParams(params);

            return layout;
        }

        private boolean isSentItem(String item) {
            if (item == null) return false;
            return item.contains(getString(R.string.transmitted)) ||
                   item.contains(getString(R.string.repeated)) ||
                   item.contains(getString(R.string.sent_ping));
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
            for (int i = 0; i < permissions.length; ++i) {
                if (permissions[i].equals(Manifest.permission.RECORD_AUDIO)
                        && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    initAudioRecord(false);
                }
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
        state.putInt("nightMode", AppCompatDelegate.getDefaultNightMode());
        state.putInt("outputRate", outputRate);
        state.putInt("outputChannel", outputChannel);
        state.putInt("recordRate", recordRate);
        state.putInt("recordChannel", recordChannel);
        state.putInt("audioSource", audioSource);
        state.putInt("carrierFrequency", carrierFrequency);
        state.putInt("noiseSymbols", noiseSymbols);
        state.putInt("repeaterDelay", repeaterDelay);
        state.putInt("repeaterDebounce", repeaterDebounce);
        state.putString("callSign", callSign);
        state.putString("draftText", draftText);
        state.putBoolean("fancyHeader", fancyHeader);
        state.putBoolean("repeaterMode", repeaterMode);
        for (int i = 0; i < messages.getCount(); ++i)
            state.putString("m" + i, messages.getItem(i));
        super.onSaveInstanceState(state);
    }

    private void storeSettings() {
        SharedPreferences pref = getPreferences(Context.MODE_PRIVATE);
        SharedPreferences.Editor edit = pref.edit();
        edit.putInt("nightMode", AppCompatDelegate.getDefaultNightMode());
        edit.putInt("outputRate", outputRate);
        edit.putInt("outputChannel", outputChannel);
        edit.putInt("recordRate", recordRate);
        edit.putInt("recordChannel", recordChannel);
        edit.putInt("audioSource", audioSource);
        edit.putInt("carrierFrequency", carrierFrequency);
        edit.putInt("noiseSymbols", noiseSymbols);
        edit.putInt("repeaterDelay", repeaterDelay);
        edit.putInt("repeaterDebounce", repeaterDebounce);
        edit.putString("callSign", callSign);
        edit.putString("draftText", draftText);
        edit.putBoolean("fancyHeader", fancyHeader);
        edit.putBoolean("repeaterMode", repeaterMode);
        for (int i = 0; i < messages.getCount(); ++i)
            edit.putString("m" + i, messages.getItem(i));
        edit.apply();
    }

    // ========== Contact persistence ==========

    private void loadContacts() {
        contacts.clear();
        SharedPreferences pref = getPreferences(Context.MODE_PRIVATE);
        String json = pref.getString(CONTACTS_KEY, null);
        if (json == null) return;
        try {
            JSONArray arr = new JSONArray(json);
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
            SharedPreferences pref = getPreferences(Context.MODE_PRIVATE);
            pref.edit().putString(CONTACTS_KEY, arr.toString()).apply();
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
        final int defaultRepeaterDelay = 1;
        final int defaultRepeaterDebounce = 60;
        final String defaultCallSign = "ANONYMOUS";
        final String defaultDraftText = "";
        final boolean defaultFancyHeader = false;
        final boolean defaultRepeaterMode = false;
        if (state == null) {
            SharedPreferences pref = getPreferences(Context.MODE_PRIVATE);
            AppCompatDelegate.setDefaultNightMode(pref.getInt("nightMode", AppCompatDelegate.getDefaultNightMode()));
            outputRate = pref.getInt("outputRate", defaultSampleRate);
            outputChannel = pref.getInt("outputChannel", defaultChannelSelect);
            recordRate = pref.getInt("recordRate", defaultSampleRate);
            recordChannel = pref.getInt("recordChannel", defaultChannelSelect);
            audioSource = pref.getInt("audioSource", defaultAudioSource);
            carrierFrequency = pref.getInt("carrierFrequency", defaultCarrierFrequency);
            noiseSymbols = pref.getInt("noiseSymbols", defaultNoiseSymbols);
            repeaterDelay = pref.getInt("repeaterDelay", defaultRepeaterDelay);
            repeaterDebounce = pref.getInt("repeaterDebounce", defaultRepeaterDebounce);
            callSign = pref.getString("callSign", defaultCallSign);
            draftText = pref.getString("draftText", defaultDraftText);
            fancyHeader = pref.getBoolean("fancyHeader", defaultFancyHeader);
            repeaterMode = pref.getBoolean("repeaterMode", defaultRepeaterMode);
            for (int i = 0; i < 100; ++i) {
                String mesg = pref.getString("m" + i, null);
                if (mesg != null)
                    messages.add(mesg);
            }
        } else {
            AppCompatDelegate.setDefaultNightMode(state.getInt("nightMode", AppCompatDelegate.getDefaultNightMode()));
            outputRate = state.getInt("outputRate", defaultSampleRate);
            outputChannel = state.getInt("outputChannel", defaultChannelSelect);
            recordRate = state.getInt("recordRate", defaultSampleRate);
            recordChannel = state.getInt("recordChannel", defaultChannelSelect);
            audioSource = state.getInt("audioSource", defaultAudioSource);
            carrierFrequency = state.getInt("carrierFrequency", defaultCarrierFrequency);
            noiseSymbols = state.getInt("noiseSymbols", defaultNoiseSymbols);
            repeaterDelay = state.getInt("repeaterDelay", defaultRepeaterDelay);
            repeaterDebounce = state.getInt("repeaterDebounce", defaultRepeaterDebounce);
            callSign = state.getString("callSign", defaultCallSign);
            draftText = state.getString("draftText", defaultDraftText);
            fancyHeader = state.getBoolean("fancyHeader", defaultFancyHeader);
            repeaterMode = state.getBoolean("repeaterMode", defaultRepeaterMode);
            for (int i = 0; i < 100; ++i) {
                String mesg = state.getString("m" + i, null);
                if (mesg != null)
                    messages.add(mesg);
            }
        }
        ultrasonicEnabled = Math.abs(carrierFrequency) > 3000;
        super.onCreate(state);
        EdgeToEdge.enable(this);
        ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        status = binding.status;
        handler = new Handler(getMainLooper());
        setContentView(binding.getRoot());
        handleInsets();
        stagedCFO = new float[1];
        stagedMode = new int[1];
        stagedCall = new byte[10];
        payload = new byte[170];
        repeatedMessages = new ArrayList<>();
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

        List<String> permissions = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
            setStatus(getString(R.string.audio_permission_denied));
        } else {
            initAudioRecord(false);
        }
        if (!permissions.isEmpty())
            ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), permissionID);

        // ========== Initialize location manager ==========
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        // ========== Request location permissions (both fine and coarse) ==========
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    locationPermissionID);
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
        locationButton = findViewById(R.id.location_button);
        locationButton.setOnClickListener(v -> sendLocationMessage());

        composeButton = findViewById(R.id.compose_button);
        composeButton.setOnClickListener(v -> composeMessage(null));
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
     * Send location message: prefer a fresh last known location, otherwise actively request one.
     */
    private void sendLocationMessage() {
        if (!hasLocationPermission()) {
            Toast.makeText(this, R.string.location_permission_denied, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isLocationServiceEnabled()) {
            Toast.makeText(this, R.string.location_service_disabled, Toast.LENGTH_SHORT).show();
            return;
        }

        final Location last = getLastKnownLocation();
        if (isLocationFresh(last)) {
            sendLocation(last);
            return;
        }

        // Location is too old or null; actively request a current location
        setStatus(getString(R.string.location_fetching), true);
        requestCurrentLocation(loc -> {
            if (loc != null) {
                sendLocation(loc);
            } else if (last != null) {
                // Active request failed; fall back to the old location
                sendLocation(last);
            } else {
                Toast.makeText(this, R.string.location_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Fill the compose window with the location.
     */
    private void sendLocation(Location loc) {
        String locationStr = String.format(Locale.US, LOCATION_FORMAT,
                loc.getLatitude(), loc.getLongitude());
        composeMessage(locationStr);
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

        // 2. No map app; open Tencent Maps web in a browser.
        // Tencent Maps component expects coord:latitude,longitude (lat,lng).
        // title and addr are limited to 10 Chinese characters; truncate if necessary.
        String title = label;
        if (title.length() > 10) {
            title = title.substring(0, 10);
        }
        String addr = getString(R.string.location_default_label);
        if (addr.length() > 10) {
            addr = addr.substring(0, 10);
        }
        String url = String.format(Locale.US,
                "https://mapapi.qq.com/web/mapComponents/locationMarker/v/index.html" +
                        "?marker=coord:%.6f,%.6f;title:%s;addr:%s",
                lat, lng,
                Uri.encode(title),
                Uri.encode(addr));

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

    private void setRepeaterDelay(int newRepeaterDelay) {
        if (repeaterDelay == newRepeaterDelay)
            return;
        repeaterDelay = newRepeaterDelay;
        updateRepeaterDelayMenu();
    }

    private void updateRepeaterDelayMenu() {
        switch (repeaterDelay) {
            case 0:
                menu.findItem(R.id.action_set_repeater_no_delay).setChecked(true);
                break;
            case 1:
                menu.findItem(R.id.action_set_repeater_delay_one_second).setChecked(true);
                break;
            case 2:
                menu.findItem(R.id.action_set_repeater_delay_two_seconds).setChecked(true);
                break;
            case 4:
                menu.findItem(R.id.action_set_repeater_delay_four_seconds).setChecked(true);
                break;
            case 8:
                menu.findItem(R.id.action_set_repeater_delay_eight_seconds).setChecked(true);
                break;
        }
    }


    private void setRepeaterDebounce(int newRepeaterDebounce) {
        if (repeaterDebounce == newRepeaterDebounce)
            return;
        repeaterDebounce = newRepeaterDebounce;
        updateRepeaterDebounceMenu();
    }

    private void updateRepeaterDebounceMenu() {
        switch (repeaterDebounce) {
            case 0:
                menu.findItem(R.id.action_set_repeater_allow_bouncing).setChecked(true);
                break;
            case 15:
                menu.findItem(R.id.action_set_repeater_debounce_quarter_minute).setChecked(true);
                break;
            case 30:
                menu.findItem(R.id.action_set_repeater_debounce_half_minute).setChecked(true);
                break;
            case 60:
                menu.findItem(R.id.action_set_repeater_debounce_one_minute).setChecked(true);
                break;
            case 120:
                menu.findItem(R.id.action_set_repeater_debounce_two_minutes).setChecked(true);
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

    private void setRepeaterMode(boolean newRepeaterMode) {
        if (repeaterMode == newRepeaterMode)
            return;
        repeaterMode = newRepeaterMode;
        updateRepeaterModeMenu();
    }

    private void updateRepeaterModeMenu() {
        if (repeaterMode)
            menu.findItem(R.id.action_enable_repeater_mode).setChecked(true);
        else
            menu.findItem(R.id.action_disable_repeater_mode).setChecked(true);
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
        pingMenuItem = menu.findItem(R.id.action_ping);
        updatePingMenuItem();
        updateOutputRateMenu();
        updateOutputChannelMenu();
        updateRecordRateMenu();
        updateRecordChannelMenu();
        updateAudioSourceMenu();
        updateNoiseSymbolsMenu();
        updateRepeaterDelayMenu();
        updateRepeaterDebounceMenu();
        updateFancyHeaderMenu();
        updateRepeaterModeMenu();
        return true;
    }

    private void updateCallSignMenuItem() {
        if (callSignMenuItem != null) {
            callSignMenuItem.setTitle(callSign);
        }
    }

    private void updatePingMenuItem() {
        if (pingMenuItem == null) return;
        Drawable icon = pingMenuItem.getIcon();
        if (icon == null) return;
        int color = beaconRunning ? ContextCompat.getColor(this, R.color.beacon_green)
                                  : ContextCompat.getColor(this, R.color.gray);
        DrawableCompat.setTint(icon.mutate(), color);
        pingMenuItem.setIcon(icon);
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
        updatePingMenuItem();
    }

    private void stopBeacon() {
        if (!beaconRunning) return;
        beaconRunning = false;
        if (beaconRunnable != null) {
            beaconHandler.removeCallbacks(beaconRunnable);
            beaconRunnable = null;
        }
        setStatus(getString(R.string.beacon_stopped));
        updatePingMenuItem();
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

        if (id == R.id.action_ping) {
            toggleBeacon();
            return true;
        }

        if (id == R.id.action_contacts) {
            showContactsDialog();
            return true;
        }

        if (id == R.id.action_delete_messages) {
            if (messages.getCount() > 0)
                deleteMessages();
            return true;
        }
        if (id == R.id.action_enable_ultrasonic) {
            enableUltrasonic();
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
        if (id == R.id.action_set_repeater_no_delay) {
            setRepeaterDelay(0);
            return true;
        }
        if (id == R.id.action_set_repeater_delay_one_second) {
            setRepeaterDelay(1);
            return true;
        }
        if (id == R.id.action_set_repeater_delay_two_seconds) {
            setRepeaterDelay(2);
            return true;
        }
        if (id == R.id.action_set_repeater_delay_four_seconds) {
            setRepeaterDelay(4);
            return true;
        }
        if (id == R.id.action_set_repeater_delay_eight_seconds) {
            setRepeaterDelay(8);
            return true;
        }
        if (id == R.id.action_set_repeater_allow_bouncing) {
            setRepeaterDebounce(0);
            return true;
        }
        if (id == R.id.action_set_repeater_debounce_quarter_minute) {
            setRepeaterDebounce(15);
            return true;
        }
        if (id == R.id.action_set_repeater_debounce_half_minute) {
            setRepeaterDebounce(30);
            return true;
        }
        if (id == R.id.action_set_repeater_debounce_one_minute) {
            setRepeaterDebounce(60);
            return true;
        }
        if (id == R.id.action_set_repeater_debounce_two_minutes) {
            setRepeaterDebounce(120);
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
        if (id == R.id.action_enable_repeater_mode) {
            setRepeaterMode(true);
            return true;
        }
        if (id == R.id.action_disable_repeater_mode) {
            setRepeaterMode(false);
            return true;
        }
        if (id == R.id.action_enable_night_mode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
            return true;
        }
        if (id == R.id.action_disable_night_mode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
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
                    SharedPreferences pref = getPreferences(Context.MODE_PRIVATE);
                    SharedPreferences.Editor editor = pref.edit();
                    for (int i = 0; i < 100; ++i)
                        editor.remove("m" + i);
                    editor.apply();
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

    private void enableUltrasonic() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
        builder.setTitle(R.string.enable_ultrasonic)
                .setMessage(R.string.enable_ultrasonic_prompt)
                .setPositiveButton(R.string.enable, (dialogInterface, i) -> ultrasonicEnabled = true)
                .setNegativeButton(R.string.disable, (dialogInterface, i) -> ultrasonicEnabled = false)
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
        spectrumTint = ContextCompat.getColor(this, R.color.tint);
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

        // Location messages are not allowed to be encrypted (otherwise the receiver cannot recognize [geo:...])
        boolean isLocationMsg = temp != null && temp.contains("[geo:");
        boolean hasPassword = password != null && !password.isEmpty();
        encryptCheck.setEnabled(hasPassword && !isLocationMsg);
        if (!hasPassword || isLocationMsg) {
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
                    thresholds = new int[]{35, 68, 99};
                } else {
                    thresholds = new int[]{85, 128, 170};
                }
                int maxBytes = thresholds[2];

                if (inputBytes > maxBytes) {
                    int over = inputBytes - maxBytes;
                    capacity.setText(getResources().getQuantityString(R.plurals.over_capacity, over, over));
                    capacity.setTextColor(Color.RED);
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    return;
                }

                int resId;
                int threshold;
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
                int remaining = threshold - inputBytes;
                capacity.setText(getResources().getQuantityString(resId, remaining, remaining));
                capacity.setTextColor(ContextCompat.getColor(context, R.color.tint));

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
    }

    private void transmitMessage(String message, boolean encrypt) {
        if (encrypt && (password == null || password.isEmpty())) {
            Toast.makeText(this, R.string.encryption_disabled_no_password, Toast.LENGTH_LONG).show();
            return;
        }

        String finalMessage = message;
        if (encrypt) {
            String encrypted = CryptoUtils.encrypt(message, password);
            if (encrypted == null) {
                Toast.makeText(this, R.string.encryption_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            if (encrypted.getBytes(StandardCharsets.UTF_8).length > 170) {
                Toast.makeText(this, R.string.encrypted_message_too_long, Toast.LENGTH_SHORT).show();
                return;
            }
            finalMessage = encrypted;
        }

        boolean wasBeaconRunning = beaconRunning;
        if (wasBeaconRunning) {
            if (beaconRunnable != null) {
                beaconHandler.removeCallbacks(beaconRunnable);
            }
        }

        stopListening();
        byte[] mesg = Arrays.copyOf(finalMessage.getBytes(StandardCharsets.UTF_8), payload.length);

        if (message.length() == 0) {
            addLine(callSign.trim(), getString(R.string.sent_ping));
        } else {
            String displayMessage = encrypt ? message : finalMessage;
            addMessage(callSign.trim(), getString(R.string.transmitted), displayMessage);
        }

        configureEncoder(mesg, callTerm(), carrierFrequency, noiseSymbols, fancyHeader);
        for (int i = 0; i < 5; ++i) {
            produceEncoder(outputBuffer, outputChannel);
            audioTrack.write(outputBuffer, 0, outputBuffer.length);
        }
        audioTrack.play();
        setStatus(getString(R.string.transmitting));

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

    private void repeatMessage() {
        Message message = new Message(stagedCall, payload);
        Iterator<Message> iterator = repeatedMessages.iterator();
        while (iterator.hasNext()) {
            Message repeated = iterator.next();
            if (message.time - repeated.time < repeaterDebounce) {
                if (Arrays.equals(repeated.call, message.call) && Arrays.equals(repeated.data, message.data)) {
                    setStatus(getString(R.string.ignoring), true);
                    return;
                }
            } else {
                iterator.remove();
            }
        }
        if (repeaterDebounce > 0)
            repeatedMessages.add(message);
        stopListening();
        String sender = parseCallSign(stagedCall);
        autoSaveContact(sender);
        addMessage(sender, getString(R.string.repeated), new String(payload).trim());
        configureEncoder(payload, stagedCall, carrierFrequency, noiseSymbols, fancyHeader);
        for (int i = 0; i < 5; ++i) {
            produceEncoder(outputBuffer, outputChannel);
            audioTrack.write(outputBuffer, 0, outputBuffer.length);
        }
        handler.postDelayed(() -> {
            audioTrack.play();
            setStatus(getString(R.string.transmitting));
        }, 1000L * repeaterDelay);
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
        int bandWidth = 1600;
        int maxCarrierFrequency = ultrasonicEnabled ? (outputRate - bandWidth) / 2 : 3000;
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
        LinearLayout editPanel = view.findViewById(R.id.contact_edit_panel);
        EditText editCall = view.findViewById(R.id.contact_call_sign);
        EditText editNote = view.findViewById(R.id.contact_note);
        Button okBtn = view.findViewById(R.id.contact_edit_ok);
        Button cancelBtn = view.findViewById(R.id.contact_edit_cancel);

        final ContactAdapter adapter = new ContactAdapter(this);
        adapter.addAll(contacts);
        listView.setAdapter(adapter);

        // Current edit index: -1 means new contact
        final int[] editingIndex = {-1};

        final Runnable hideEdit = () -> {
            editPanel.setVisibility(View.GONE);
            editCall.setText("");
            editNote.setText("");
            editingIndex[0] = -1;
        };

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
            adapter.clear();
            adapter.addAll(contacts);
            adapter.notifyDataSetChanged();
            hideEdit.run();
        });

        cancelBtn.setOnClickListener(v -> hideEdit.run());

        // Long-press item: edit / delete
        listView.setOnItemLongClickListener((parent, v, position, id) -> {
            final int pos = position;
            new AlertDialog.Builder(this, R.style.Theme_AlertDialog)
                    .setItems(new String[]{
                            getString(R.string.contact_edit),
                            getString(R.string.delete)
                    }, (dialog, which) -> {
                        if (which == 0) {
                            Contact c = contacts.get(pos);
                            editCall.setText(c.callSign);
                            editNote.setText(c.note);
                            editingIndex[0] = pos;
                            editPanel.setVisibility(View.VISIBLE);
                        } else {
                            new AlertDialog.Builder(this, R.style.Theme_AlertDialog)
                                    .setTitle(R.string.delete)
                                    .setMessage(R.string.contact_delete_prompt)
                                    .setPositiveButton(R.string.delete, (d, w) -> {
                                        contacts.remove(pos);
                                        saveContacts();
                                        adapter.clear();
                                        adapter.addAll(contacts);
                                        adapter.notifyDataSetChanged();
                                    })
                                    .setNegativeButton(R.string.cancel, null)
                                    .show();
                        }
                    })
                    .show();
            return true;
        });

        // Long-press empty area: add new
        final GestureDetector detector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public void onLongPress(MotionEvent e) {
                        int pos = listView.pointToPosition((int) e.getX(), (int) e.getY());
                        if (pos == ListView.INVALID_POSITION) {
                            if (contacts.size() >= MAX_CONTACTS) {
                                Toast.makeText(MainActivity.this,
                                        R.string.contact_limit_reached,
                                        Toast.LENGTH_SHORT).show();
                                return;
                            }
                            editCall.setText("");
                            editNote.setText("");
                            editingIndex[0] = -1;
                            editPanel.setVisibility(View.VISIBLE);
                        }
                    }
                });

        listView.setOnTouchListener((v, event) -> {
            detector.onTouchEvent(event);
            return false;
        });

        // Click item: close dialog, open compose window prefilled with "@callSign "
        final AlertDialog contactDialog = new AlertDialog.Builder(this, R.style.Theme_AlertDialog)
                .setTitle(R.string.contacts)
                .setView(view)
                .setNegativeButton(R.string.close, null)
                .show();

        listView.setOnItemClickListener((parent, v, position, id) -> {
            Contact c = contacts.get(position);
            contactDialog.dismiss();
            composeMessage("@" + c.callSign + " ");
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