/*
 * Copyright (c) 2026 BI3BJU
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.bi3bju.text_encryption_tool

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class MainActivity : AppCompatActivity() {

    private lateinit var keyEditText: EditText
    private lateinit var plainTextEditText: EditText
    private lateinit var cipherTextEditText: EditText
    private lateinit var encryptButton: Button
    private lateinit var decryptButton: Button
    private lateinit var generateKeyButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var formatRadioGroup: RadioGroup

    // 统计标签
    private lateinit var plainStatsTextView: TextView
    private lateinit var cipherStatsTextView: TextView

    companion object {
        private const val KEY_LENGTH_BYTES = 32
        private const val NONCE_LENGTH = 12
        private const val SALT_LENGTH = 16
        private const val PBKDF2_ITERATIONS = 600_000
        private const val GCM_TAG_LENGTH = 128                  // bits
        private const val GCM_TAG_BYTES = GCM_TAG_LENGTH / 8    // 16 bytes
        private const val MIN_RAW_LEN = SALT_LENGTH + NONCE_LENGTH + GCM_TAG_BYTES  // 44
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 绑定视图
        keyEditText = findViewById(R.id.keyEditText)
        plainTextEditText = findViewById(R.id.plainTextEditText)
        cipherTextEditText = findViewById(R.id.cipherTextEditText)
        encryptButton = findViewById(R.id.encryptButton)
        decryptButton = findViewById(R.id.decryptButton)
        generateKeyButton = findViewById(R.id.generateKeyButton)
        statusTextView = findViewById(R.id.statusTextView)
        formatRadioGroup = findViewById(R.id.formatRadioGroup)
        plainStatsTextView = findViewById(R.id.plainStatsTextView)
        cipherStatsTextView = findViewById(R.id.cipherStatsTextView)

        keyEditText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        keyEditText.hint = getString(R.string.key_hint)

        // 按钮监听
        encryptButton.setOnClickListener { encrypt() }
        decryptButton.setOnClickListener { decrypt() }
        generateKeyButton.setOnClickListener { onGenerateRandomKey() }

        // 长按菜单
        setupLongClickMenu(plainTextEditText, "plain")
        setupLongClickMenu(cipherTextEditText, "cipher")
        setupLongClickMenu(keyEditText, "key")

        // 文本变化监听（实时统计）
        plainTextEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updatePlainStats()
            }
        })

        cipherTextEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateCipherStats()
            }
        })

        // 输出格式切换监听
        formatRadioGroup.setOnCheckedChangeListener { _, _ ->
            updateCipherStats()
        }

        // 初始统计
        updatePlainStats()
        updateCipherStats()

        // 边距处理
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView.findViewById(android.R.id.content)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    // ---------- 状态消息 ----------
    private fun showStatus(message: String, isError: Boolean = false) {
        statusTextView.text = message
        statusTextView.setTextColor(
            if (isError)
                android.graphics.Color.parseColor("#D32F2F")
            else
                android.graphics.Color.parseColor("#388E3C")
        )
        Handler(Looper.getMainLooper()).removeCallbacksAndMessages(null)
        Handler(Looper.getMainLooper()).postDelayed({
            statusTextView.text = ""
        }, 3000)
    }

    // ---------- 统计更新函数 ----------
    private fun updatePlainStats() {
        val text = plainTextEditText.text.toString()
        val byteCount = text.toByteArray(Charsets.UTF_8).size
        plainStatsTextView.text = getString(R.string.plain_stats, byteCount)
    }

    private fun updateCipherStats() {
        val encoded = cipherTextEditText.text.toString().trim()
        if (encoded.isEmpty()) {
            cipherStatsTextView.text = getString(R.string.cipher_stats_empty)
            return
        }
        try {
            val raw = decodeCiphertext(encoded)
            val isBase64 = formatRadioGroup.checkedRadioButtonId == R.id.radioBase64
            val count = if (isBase64) encoded.length else raw.size
            val message = if (isBase64) {
                getString(R.string.cipher_stats_base64, count)
            } else {
                getString(R.string.cipher_stats_hex, count)
            }
            cipherStatsTextView.text = message
        } catch (_: Exception) {
            cipherStatsTextView.text = getString(R.string.cipher_stats_invalid)
        }
    }

    // ---------- 密钥派生（PBKDF2-HMAC-SHA256, 600k, 16B salt） ----------
    private fun deriveKey(password: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun createRandomKey(): String {
        val keyBytes = ByteArray(KEY_LENGTH_BYTES).apply { SecureRandom().nextBytes(this) }
        return Base64.getEncoder().encodeToString(keyBytes)
    }

    // ---------- 自动识别密文格式 ----------
    private fun decodeCiphertext(encoded: String): ByteArray {
        val hexPattern = Regex("^[0-9a-fA-F]+$")
        if (encoded.length % 2 == 0 && hexPattern.matches(encoded)) {
            try {
                return encoded.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            } catch (_: Exception) { /* fall through */ }
        }

        return try {
            val clean = encoded.trim()
            val missing = (4 - clean.length % 4) % 4
            val padded = clean + "=".repeat(missing)
            Base64.getDecoder().decode(padded)
        } catch (_: Exception) {
            throw IllegalArgumentException(getString(R.string.status_invalid_format))
        }
    }

    // ---------- 加密 ----------
    private fun encrypt() {
        val keyInput = keyEditText.text.toString().trim()
        val plainText = plainTextEditText.text.toString()

        if (keyInput.isEmpty()) {
            showStatus(getString(R.string.status_key_empty), true)
            return
        }
        if (plainText.isEmpty()) {
            showStatus(getString(R.string.status_plain_empty), true)
            return
        }

        try {
            // 1) 16 字节随机盐
            val salt = ByteArray(SALT_LENGTH).apply { SecureRandom().nextBytes(this) }
            // 2) PBKDF2 派生密钥
            val key = deriveKey(keyInput, salt)
            // 3) 12 字节随机 nonce
            val nonce = ByteArray(NONCE_LENGTH).apply { SecureRandom().nextBytes(this) }

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, nonce))
            val ciphertext = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

            // 4) blob = salt || nonce || ciphertext(+tag)
            val raw = salt + nonce + ciphertext
            val encoded = when (formatRadioGroup.checkedRadioButtonId) {
                R.id.radioHex -> raw.joinToString("") { "%02x".format(it) }
                else -> Base64.getEncoder().encodeToString(raw)
            }

            cipherTextEditText.setText(encoded)
            copyToClipboard(encoded)
            showStatus(getString(R.string.status_encrypt_success), false)
            updateCipherStats()
        } catch (e: Exception) {
            showStatus(getString(R.string.status_encrypt_failed, e.message ?: ""), true)
        }
    }

    // ---------- 解密 ----------
    private fun decrypt() {
        val keyInput = keyEditText.text.toString().trim()
        val encoded = cipherTextEditText.text.toString().trim()

        if (keyInput.isEmpty()) {
            showStatus(getString(R.string.status_key_empty), true)
            return
        }
        if (encoded.isEmpty()) {
            showStatus(getString(R.string.status_cipher_empty), true)
            return
        }

        try {
            val data = decodeCiphertext(encoded)
            if (data.size < MIN_RAW_LEN) {
                showStatus(getString(R.string.status_invalid_format), true)
                return
            }

            // 切分：salt(16) || nonce(12) || ct+tag
            val salt = data.copyOfRange(0, SALT_LENGTH)
            val nonce = data.copyOfRange(SALT_LENGTH, SALT_LENGTH + NONCE_LENGTH)
            val ciphertext = data.copyOfRange(SALT_LENGTH + NONCE_LENGTH, data.size)

            val key = deriveKey(keyInput, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, nonce))
            val decryptedResult = String(cipher.doFinal(ciphertext), Charsets.UTF_8)

            plainTextEditText.setText(decryptedResult)
            copyToClipboard(decryptedResult)
            showStatus(getString(R.string.status_decrypt_success), false)
            updatePlainStats()
        } catch (e: IllegalArgumentException) {
            showStatus(e.message ?: getString(R.string.status_invalid_format), true)
        } catch (e: Exception) {
            val msg = when (e::class.simpleName) {
                "AEADBadTagException" -> getString(R.string.status_decrypt_failed_wrong_key)
                else -> getString(R.string.status_decrypt_failed_general)
            }
            showStatus(msg, true)
        }
    }

    // ---------- 生成随机密钥 ----------
    private fun onGenerateRandomKey() {
        val newKey = createRandomKey()
        keyEditText.setText(newKey)
        copyToClipboard(newKey)
        showStatus(getString(R.string.status_key_generated), false)
    }

    // ---------- 剪贴板 ----------
    private fun copyToClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("AES256GCM", text))
        } catch (e: Exception) {
            showStatus(getString(R.string.status_copy_failed), true)
        }
    }

    // ---------- 长按上下文菜单 ----------
    private fun setupLongClickMenu(editText: EditText, type: String) {
        editText.setOnLongClickListener {
            val items = mutableListOf(
                getString(R.string.menu_copy),
                getString(R.string.menu_paste),
                getString(R.string.menu_cut),
                getString(R.string.menu_select_all),
                getString(R.string.menu_clear)
            )
            when (type) {
                "plain" -> items.add(getString(R.string.menu_encrypt_current))
                "cipher" -> items.add(getString(R.string.menu_decrypt_current))
                "key" -> items.add(getString(R.string.menu_generate_key))
            }

            AlertDialog.Builder(this)
                .setTitle(getString(R.string.menu_options_title))
                .setItems(items.toTypedArray()) { _, which ->
                    when (items[which]) {
                        getString(R.string.menu_copy) -> {
                            val text = editText.text.toString()
                            if (text.isNotEmpty()) {
                                copyToClipboard(text)
                                showStatus(getString(R.string.status_copied), false)
                            }
                        }
                        getString(R.string.menu_paste) -> {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val paste = clipboard.primaryClip?.getItemAt(0)?.text
                            if (paste != null) {
                                editText.text.replace(editText.selectionStart, editText.selectionEnd, paste)
                                showStatus(getString(R.string.status_pasted), false)
                            }
                        }
                        getString(R.string.menu_cut) -> {
                            val start = editText.selectionStart
                            val end = editText.selectionEnd
                            if (start != end) {
                                copyToClipboard(editText.text.substring(start, end))
                                editText.text.delete(start, end)
                                showStatus(getString(R.string.status_cut), false)
                            }
                        }
                        getString(R.string.menu_select_all) -> {
                            editText.selectAll()
                            showStatus(getString(R.string.status_selected_all), false)
                        }
                        getString(R.string.menu_clear) -> {
                            editText.text.clear()
                            showStatus(getString(R.string.status_cleared), false)
                        }
                        getString(R.string.menu_encrypt_current) -> encrypt()
                        getString(R.string.menu_decrypt_current) -> decrypt()
                        getString(R.string.menu_generate_key) -> onGenerateRandomKey()
                    }
                }.show()
            true
        }
    }
}