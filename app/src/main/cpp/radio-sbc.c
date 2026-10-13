// JNI bridge between RadioLink (Java) and google/libsbc (cpp/sbc, Apache-2.0).
// Plain C because the libsbc header is C-only.
// Fixed format used by the BTech UV-Pro / Vero VR-N76 Bluetooth audio channel:
// 32 kHz, mono, 16 blocks, 8 subbands.

#include <jni.h>
#include <stdint.h>
#include "sbc.h"

static sbc_t decoder_state;
static sbc_t encoder_state;

#define SAMPLES_PER_FRAME (16 * 8)

JNIEXPORT void JNICALL
Java_com_bi3bju_rattlegram_1plus_RadioLink_sbcReset(JNIEnv *env, jclass cls) {
	(void) env;
	(void) cls;
	sbc_reset(&decoder_state);
	sbc_reset(&encoder_state);
}

// Decodes every SBC frame in data[offset, offset + length) into pcm.
// Returns the number of samples written, or -1 if pcm is too small.
JNIEXPORT jint JNICALL
Java_com_bi3bju_rattlegram_1plus_RadioLink_sbcDecode(
	JNIEnv *env, jclass cls, jbyteArray JNI_data, jint offset, jint length, jshortArray JNI_pcm) {

	(void) cls;
	jint capacity = (*env)->GetArrayLength(env, JNI_pcm);
	jbyte *data = (*env)->GetByteArrayElements(env, JNI_data, NULL);
	if (!data)
		return 0;
	jshort *pcm = (*env)->GetShortArrayElements(env, JNI_pcm, NULL);
	if (!pcm) {
		(*env)->ReleaseByteArrayElements(env, JNI_data, data, JNI_ABORT);
		return 0;
	}
	const uint8_t *in = (const uint8_t *) data + offset;
	int pos = 0, count = 0;
	struct sbc_frame frame;
	while (pos + SBC_PROBE_SIZE <= length) {
		if (sbc_probe(in + pos, &frame) < 0) {
			++pos;
			continue;
		}
		int size = (int) sbc_get_frame_size(&frame);
		int samples = frame.nblocks * frame.nsubbands;
		if (size <= 0 || pos + size > length)
			break;
		if (count + samples > capacity) {
			count = -1;
			break;
		}
		if (sbc_decode(&decoder_state, in + pos, (unsigned) size, &frame,
					   (int16_t *) pcm + count, 1, NULL, 1) == 0)
			count += samples;
		pos += size;
	}
	(*env)->ReleaseShortArrayElements(env, JNI_pcm, pcm, count > 0 ? 0 : JNI_ABORT);
	(*env)->ReleaseByteArrayElements(env, JNI_data, data, JNI_ABORT);
	return count;
}

// Encodes count / 128 whole frames from pcm[offset...] into out.
// Returns the number of bytes written, or -1 on error.
JNIEXPORT jint JNICALL
Java_com_bi3bju_rattlegram_1plus_RadioLink_sbcEncode(
	JNIEnv *env, jclass cls, jshortArray JNI_pcm, jint offset, jint count, jbyteArray JNI_out) {

	(void) cls;
	struct sbc_frame frame = {
		.msbc = false,
		.freq = SBC_FREQ_32K,
		.mode = SBC_MODE_MONO,
		.bam = SBC_BAM_SNR,
		.nblocks = 16,
		.nsubbands = 8,
		.bitpool = 40,
	};
	int size = (int) sbc_get_frame_size(&frame);
	int frames = count / SAMPLES_PER_FRAME;
	if (frames * size > (*env)->GetArrayLength(env, JNI_out))
		return -1;
	jshort *pcm = (*env)->GetShortArrayElements(env, JNI_pcm, NULL);
	if (!pcm)
		return -1;
	jbyte *out = (*env)->GetByteArrayElements(env, JNI_out, NULL);
	if (!out) {
		(*env)->ReleaseShortArrayElements(env, JNI_pcm, pcm, JNI_ABORT);
		return -1;
	}
	int written = 0;
	for (int i = 0; i < frames; ++i) {
		if (sbc_encode(&encoder_state,
					   (const int16_t *) pcm + offset + i * SAMPLES_PER_FRAME, 1, NULL, 1,
					   &frame, (uint8_t *) out + written, (unsigned) size) < 0) {
			written = -1;
			break;
		}
		written += size;
	}
	(*env)->ReleaseByteArrayElements(env, JNI_out, out, written > 0 ? 0 : JNI_ABORT);
	(*env)->ReleaseShortArrayElements(env, JNI_pcm, pcm, JNI_ABORT);
	return written;
}
