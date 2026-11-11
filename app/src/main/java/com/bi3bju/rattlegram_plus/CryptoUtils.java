package com.bi3bju.rattlegram_plus;

import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM encryption/decryption utility.
 * Nonce length: 12 bytes. Tag length: 16 bytes.
 * Ciphertext format: Nonce(12) + ciphertext (including Tag).
 * Externally transmitted as Base64.
 */
public class CryptoUtils {

    private static final int GCM_NONCE_LENGTH = 12;      // 12-byte Nonce
    private static final int GCM_TAG_LENGTH = 16;        // 16-byte Tag
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String ALGORITHM = "AES";

    /**
     * Encrypt a plaintext string.
     * @param plainText plaintext
     * @param password  password (used to derive the key)
     * @return Base64-encoded ciphertext (Nonce + ciphertext), or null on failure
     */
    public static String encrypt(String plainText, String password) {
        if (plainText == null || plainText.isEmpty() || password == null || password.isEmpty()) {
            return null;
        }
        try {
            // Derive key from password (SHA-256)
            byte[] keyBytes = password.getBytes(StandardCharsets.UTF_8);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] key = sha.digest(keyBytes);

            // Generate random Nonce
            byte[] nonce = new byte[GCM_NONCE_LENGTH];
            new SecureRandom().nextBytes(nonce);

            // Initialize Cipher
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, nonce);
            SecretKeySpec secretKey = new SecretKeySpec(key, ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);

            // Encrypt
            byte[] plainBytes = plainText.getBytes(StandardCharsets.UTF_8);
            byte[] cipherText = cipher.doFinal(plainBytes); // includes Tag

            // Combine Nonce + ciphertext
            ByteBuffer buffer = ByteBuffer.allocate(nonce.length + cipherText.length);
            buffer.put(nonce);
            buffer.put(cipherText);
            byte[] combined = buffer.array();

            // Base64 encode (no wrap)
            return Base64.encodeToString(combined, Base64.NO_WRAP);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Decrypt a Base64-encoded ciphertext.
     * @param cipherBase64 Base64-encoded ciphertext
     * @param password     password
     * @return decrypted plaintext, or null on failure
     */
    public static String decrypt(String cipherBase64, String password) {
        if (cipherBase64 == null || cipherBase64.isEmpty() || password == null || password.isEmpty()) {
            return null;
        }
        try {
            // Base64 decode
            byte[] combined = Base64.decode(cipherBase64, Base64.DEFAULT);
            if (combined.length < GCM_NONCE_LENGTH + GCM_TAG_LENGTH) {
                return null; // too short to contain Nonce and Tag
            }

            // Split Nonce and ciphertext
            ByteBuffer buffer = ByteBuffer.wrap(combined);
            byte[] nonce = new byte[GCM_NONCE_LENGTH];
            buffer.get(nonce);
            byte[] cipherText = new byte[buffer.remaining()];
            buffer.get(cipherText);

            // Derive key from password
            byte[] keyBytes = password.getBytes(StandardCharsets.UTF_8);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] key = sha.digest(keyBytes);

            // Decrypt
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, nonce);
            SecretKeySpec secretKey = new SecretKeySpec(key, ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

            byte[] plain = cipher.doFinal(cipherText);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // ===== Binary encryption for modes 17/18/19 =====

    /**
     * Encrypt plaintext into a fixed-length binary blob.
     * The plaintext is padded with ASCII spaces (0x20) to padTo bytes.
     * Returns nonce(12) || ciphertext || tag(16), total length = padTo + 28.
     * Returns null if plaintext UTF-8 length exceeds padTo.
     */
    public static byte[] encryptBytes(String plain, String password, int padTo) {
        if (plain == null || password == null || password.isEmpty() || padTo <= 0) {
            return null;
        }
        try {
            byte[] msg = plain.getBytes(StandardCharsets.UTF_8);
            if (msg.length > padTo) {
                return null;
            }

            byte[] buf = new byte[padTo];
            System.arraycopy(msg, 0, buf, 0, msg.length);
            for (int i = msg.length; i < padTo; ++i) {
                buf[i] = (byte) 0x20;
            }

            byte[] keyBytes = password.getBytes(StandardCharsets.UTF_8);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] key = sha.digest(keyBytes);

            byte[] nonce = new byte[GCM_NONCE_LENGTH];
            new SecureRandom().nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, nonce);
            SecretKeySpec secretKey = new SecretKeySpec(key, ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);
            byte[] ct = cipher.doFinal(buf);

            byte[] out = new byte[GCM_NONCE_LENGTH + ct.length];
            System.arraycopy(nonce, 0, out, 0, GCM_NONCE_LENGTH);
            System.arraycopy(ct, 0, out, GCM_NONCE_LENGTH, ct.length);
            return out;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Decrypt a non-Base64 binary blob produced by encryptBytes().
     * Trailing whitespace (spaces, newlines, tabs) is stripped from the plaintext.
     * Returns null on any failure.
     */
    public static String decryptBytes(byte[] blob, String password) {
        if (blob == null || password == null || password.isEmpty()) {
            return null;
        }
        if (blob.length < GCM_NONCE_LENGTH + GCM_TAG_LENGTH) {
            return null;
        }
        try {
            byte[] nonce = new byte[GCM_NONCE_LENGTH];
            System.arraycopy(blob, 0, nonce, 0, GCM_NONCE_LENGTH);

            int ctLen = blob.length - GCM_NONCE_LENGTH;
            byte[] ct = new byte[ctLen];
            System.arraycopy(blob, GCM_NONCE_LENGTH, ct, 0, ctLen);

            byte[] keyBytes = password.getBytes(StandardCharsets.UTF_8);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] key = sha.digest(keyBytes);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, nonce);
            SecretKeySpec secretKey = new SecretKeySpec(key, ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
            byte[] plain = cipher.doFinal(ct);

            String s = new String(plain, StandardCharsets.UTF_8);
            int end = s.length();
            while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
                --end;
            }
            return s.substring(0, end);
        } catch (Exception e) {
            return null;
        }
    }
}