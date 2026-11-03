// Thin JNI bridge from classes/soundscape/SpatialRenderer.java to OpenAL Soft in loopback mode.
//
// One renderer at a time (a static device/context), and every call must come from the same thread
// — SoundscapePlayer's render thread — so there is no locking here. Coordinates are OpenAL's:
// x = east, y = up, z = SOUTH (OpenAL's default forward is -z, so "north" is -z).

#include <jni.h>
#include <android/log.h>
#include <AL/al.h>
#include <AL/alc.h>
#include <AL/alext.h>
#include <cmath>
#include <cstdlib>
#include <vector>

// stb_vorbis (public domain, third_party/stb_vorbis.c, v1.22) decodes the bundled .ogg clips. It
// replaced MediaCodec, whose software Vorbis decoder spends most of its time on per-frame IPC: ~1.3 s
// per 20 s clip on a Galaxy S21, i.e. a 5 s wait before a typical scene could start.
#define STB_VORBIS_NO_STDIO
#define STB_VORBIS_NO_PUSHDATA_API
#include "third_party/stb_vorbis.c"

#define TAG "SoundscapeNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

static ALCdevice *gDevice = nullptr;
static ALCcontext *gContext = nullptr;
static LPALCLOOPBACKOPENDEVICESOFT pLoopbackOpen = nullptr;
static LPALCRENDERSAMPLESSOFT pRender = nullptr;

#define JNI_FN(name) Java_com_wikiponder_touchmap_classes_soundscape_SpatialRenderer_##name

extern "C" {

JNIEXPORT jboolean JNICALL JNI_FN(nativeInit)(JNIEnv *, jclass, jint sampleRate) {
    if (gDevice) return JNI_TRUE;
    pLoopbackOpen = reinterpret_cast<LPALCLOOPBACKOPENDEVICESOFT>(alcGetProcAddress(nullptr, "alcLoopbackOpenDeviceSOFT"));
    pRender = reinterpret_cast<LPALCRENDERSAMPLESSOFT>(alcGetProcAddress(nullptr, "alcRenderSamplesSOFT"));
    if (!pLoopbackOpen || !pRender) {
        LOGW("ALC_SOFT_loopback unavailable");
        return JNI_FALSE;
    }
    gDevice = pLoopbackOpen(nullptr);
    if (!gDevice) return JNI_FALSE;
    const ALCint attrs[] = {
            ALC_FORMAT_CHANNELS_SOFT, ALC_STEREO_SOFT,
            ALC_FORMAT_TYPE_SOFT, ALC_FLOAT_SOFT,
            ALC_FREQUENCY, sampleRate,
            // Headphone (HRTF) rendering, not plain stereo panning: each source is convolved with
            // per-ear impulse responses, which carry both the level difference and the interaural
            // time/phase difference between the ears.
            ALC_HRTF_SOFT, ALC_TRUE,
            ALC_OUTPUT_MODE_SOFT, ALC_STEREO_HRTF_SOFT,
            0};
    gContext = alcCreateContext(gDevice, attrs);
    if (!gContext || !alcMakeContextCurrent(gContext)) {
        LOGW("alcCreateContext failed: 0x%x", alcGetError(gDevice));
        if (gContext) alcDestroyContext(gContext);
        alcCloseDevice(gDevice);
        gContext = nullptr;
        gDevice = nullptr;
        return JNI_FALSE;
    }
    alDistanceModel(AL_INVERSE_DISTANCE_CLAMPED);
    alSpeedOfSound(343.3f);
    alDopplerFactor(1.0f);
    ALCint hrtf = 0;
    alcGetIntegerv(gDevice, ALC_HRTF_STATUS_SOFT, 1, &hrtf);
    LOGI("loopback device ready, %d Hz, hrtf status %d (%s)", sampleRate, hrtf,
         alcGetString(gDevice, ALC_HRTF_SPECIFIER_SOFT));
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeHrtfEnabled)(JNIEnv *, jclass) {
    if (!gDevice) return JNI_FALSE;
    ALCint enabled = 0;
    alcGetIntegerv(gDevice, ALC_HRTF_SOFT, 1, &enabled);
    return enabled ? JNI_TRUE : JNI_FALSE;
}

/** Decodes an in-memory Ogg Vorbis file to mono 16-bit PCM (channels averaged). Thread-safe: no
 * OpenAL calls, so clips can be decoded in parallel. outRate[0] receives the sample rate. Returns
 * null if the data isn't decodable. */
JNIEXPORT jshortArray JNICALL JNI_FN(nativeDecodeOgg)(JNIEnv *env, jclass, jbyteArray ogg, jintArray outRate) {
    jsize len = env->GetArrayLength(ogg);
    jbyte *data = env->GetByteArrayElements(ogg, nullptr);
    int channels = 0, rate = 0;
    short *pcm = nullptr;
    int frames = stb_vorbis_decode_memory(reinterpret_cast<const unsigned char *>(data), len, &channels, &rate, &pcm);
    env->ReleaseByteArrayElements(ogg, data, JNI_ABORT);
    if (frames <= 0 || channels <= 0 || !pcm) {
        free(pcm);
        return nullptr;
    }
    jshortArray out = env->NewShortArray(frames);
    if (channels == 1) {
        env->SetShortArrayRegion(out, 0, frames, pcm);
    } else {
        std::vector<jshort> mono(frames);
        for (int i = 0; i < frames; i++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) sum += pcm[i * channels + c];
            mono[i] = static_cast<jshort>(sum / channels);
        }
        env->SetShortArrayRegion(out, 0, frames, mono.data());
    }
    free(pcm);
    env->SetIntArrayRegion(outRate, 0, 1, &rate);
    return out;
}

JNIEXPORT jint JNICALL JNI_FN(nativeCreateBuffer)(JNIEnv *env, jclass, jshortArray mono, jint sampleRate) {
    jsize n = env->GetArrayLength(mono);
    jshort *data = env->GetShortArrayElements(mono, nullptr);
    ALuint buf = 0;
    alGenBuffers(1, &buf);
    alBufferData(buf, AL_FORMAT_MONO16, data, static_cast<ALsizei>(n * sizeof(ALshort)), sampleRate);
    env->ReleaseShortArrayElements(mono, data, JNI_ABORT);
    return alGetError() == AL_NO_ERROR ? static_cast<jint>(buf) : 0;
}

JNIEXPORT void JNICALL JNI_FN(nativeDeleteBuffer)(JNIEnv *, jclass, jint buffer) {
    ALuint b = static_cast<ALuint>(buffer);
    alDeleteBuffers(1, &b);
}

JNIEXPORT jint JNICALL JNI_FN(nativeCreateSource)(JNIEnv *, jclass, jint buffer, jboolean loop,
                                                  jfloat refDistance, jfloat maxDistance, jfloat rolloff,
                                                  jboolean headRelative) {
    ALuint src = 0;
    alGenSources(1, &src);
    if (alGetError() != AL_NO_ERROR) return 0;
    alSourcei(src, AL_BUFFER, buffer);
    alSourcei(src, AL_LOOPING, loop ? AL_TRUE : AL_FALSE);
    alSourcef(src, AL_REFERENCE_DISTANCE, refDistance);
    alSourcef(src, AL_MAX_DISTANCE, maxDistance);
    alSourcef(src, AL_ROLLOFF_FACTOR, rolloff);
    alSourcei(src, AL_SOURCE_RELATIVE, headRelative ? AL_TRUE : AL_FALSE);
    // Mono buffers are spatialized by default; stated anyway so a stereo bed can never sneak in
    // un-positioned.
    alSourcei(src, AL_SOURCE_SPATIALIZE_SOFT, AL_TRUE);
    return static_cast<jint>(src);
}

JNIEXPORT void JNICALL JNI_FN(nativeUpdateSource)(JNIEnv *, jclass, jint source,
                                                  jfloat x, jfloat y, jfloat z,
                                                  jfloat vx, jfloat vy, jfloat vz,
                                                  jfloat gain, jfloat pitch) {
    ALuint s = static_cast<ALuint>(source);
    alSource3f(s, AL_POSITION, x, y, z);
    alSource3f(s, AL_VELOCITY, vx, vy, vz);
    alSourcef(s, AL_GAIN, gain);
    alSourcef(s, AL_PITCH, pitch);
}

JNIEXPORT void JNICALL JNI_FN(nativeSeekSource)(JNIEnv *, jclass, jint source, jfloat seconds) {
    alSourcef(static_cast<ALuint>(source), AL_SEC_OFFSET, seconds);
}

JNIEXPORT void JNICALL JNI_FN(nativePlay)(JNIEnv *, jclass, jint source) {
    alSourcePlay(static_cast<ALuint>(source));
}

JNIEXPORT void JNICALL JNI_FN(nativeStop)(JNIEnv *, jclass, jint source) {
    alSourceStop(static_cast<ALuint>(source));
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeIsPlaying)(JNIEnv *, jclass, jint source) {
    ALint state = AL_STOPPED;
    alGetSourcei(static_cast<ALuint>(source), AL_SOURCE_STATE, &state);
    return state == AL_PLAYING ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(nativeDeleteSource)(JNIEnv *, jclass, jint source) {
    ALuint s = static_cast<ALuint>(source);
    alSourceStop(s);
    alDeleteSources(1, &s);
}

/** headingDeg: compass direction the listener faces (0 = north, 90 = east). */
JNIEXPORT void JNICALL JNI_FN(nativeSetListenerHeading)(JNIEnv *, jclass, jfloat headingDeg) {
    float h = headingDeg * static_cast<float>(M_PI) / 180.f;
    const ALfloat orientation[6] = {std::sin(h), 0.f, -std::cos(h), 0.f, 1.f, 0.f};
    alListener3f(AL_POSITION, 0.f, 0.f, 0.f);
    alListener3f(AL_VELOCITY, 0.f, 0.f, 0.f);
    alListenerfv(AL_ORIENTATION, orientation);
}

JNIEXPORT void JNICALL JNI_FN(nativeSetMasterGain)(JNIEnv *, jclass, jfloat gain) {
    alListenerf(AL_GAIN, gain);
}

/** Renders `frames` stereo frames into `out` (interleaved L,R floats; length >= 2 * frames). */
JNIEXPORT void JNICALL JNI_FN(nativeRender)(JNIEnv *env, jclass, jfloatArray out, jint frames) {
    if (!gDevice) return;
    jfloat *buf = env->GetFloatArrayElements(out, nullptr);
    pRender(gDevice, buf, frames);
    env->ReleaseFloatArrayElements(out, buf, 0);
}

JNIEXPORT void JNICALL JNI_FN(nativeRelease)(JNIEnv *, jclass) {
    if (!gDevice) return;
    alcMakeContextCurrent(nullptr);
    if (gContext) alcDestroyContext(gContext);
    alcCloseDevice(gDevice);
    gContext = nullptr;
    gDevice = nullptr;
}

} // extern "C"
