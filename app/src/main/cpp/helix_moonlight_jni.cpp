// JNI bridge between MoonlightNativeBridge.kt and moonlight-common-c.
//
// The Kotlin side performs pairing and /launch over HTTP(S); this layer only runs the real streaming
// session (RTSP handshake, control/video/audio/input streams) using the rtspSessionUrl and the
// remote-input AES key/iv from that launch. Video goes AMediaCodec -> ANativeWindow, audio goes
// Opus -> AAudio. Connection lifecycle events are reported to Kotlin via onNativeEvent().
#include <jni.h>

#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <atomic>
#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>

#include "Limelight.h"
#include "helix_common.h"

namespace {

// Event types delivered to MoonlightNativeBridge.onNativeEvent(type, a, b, text).
constexpr jint EV_STAGE_STARTING = 1;   // a = stage, text = name
constexpr jint EV_STAGE_COMPLETE = 2;   // a = stage, text = name
constexpr jint EV_STAGE_FAILED = 3;     // a = stage, b = error code, text = name
constexpr jint EV_STARTED = 4;          // connectionStarted callback fired
constexpr jint EV_TERMINATED = 5;       // a = error code (0 = host ended gracefully)
constexpr jint EV_START_RESULT = 6;     // a = LiStartConnection return value
constexpr jint EV_STATUS = 7;           // a = CONN_STATUS_*

JavaVM* g_vm = nullptr;
jmethodID g_onEvent = nullptr;

struct Session {
    std::mutex lifecycleMutex;
    std::mutex bridgeMutex;
    jobject bridge = nullptr;  // global ref used for callbacks; null disables event delivery

    std::thread startThread;
    std::atomic<bool> libStarted{false};  // LiStartConnection returned 0 and LiStopConnection is owed
    std::atomic<bool> active{false};      // connectionStarted fired and not terminated: input allowed

    std::string host, appVersion, gfeVersion, rtspUrl;
    int serverCodecModeSupport = 0;

    // Measured stats (previous sample for rate computation)
    std::mutex statsMutex;
    int64_t lastStatsMs = 0;
    int64_t lastFrames = 0;
    int64_t lastBytes = 0;
    double lastFps = 0;
    int lastBitrateKbps = 0;
};

Session S;

struct JniThreadGuard {
    bool attached = false;
    ~JniThreadGuard() {
        if (attached && g_vm != nullptr) g_vm->DetachCurrentThread();
    }
};

JNIEnv* getEnv() {
    if (g_vm == nullptr) return nullptr;
    JNIEnv* env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) return env;
    static thread_local JniThreadGuard guard;
    if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
        guard.attached = true;
        return env;
    }
    return nullptr;
}

void emitEvent(jint type, jint a, jint b, const char* text) {
    std::lock_guard<std::mutex> lock(S.bridgeMutex);
    if (S.bridge == nullptr || g_onEvent == nullptr) return;
    JNIEnv* env = getEnv();
    if (env == nullptr) return;
    jstring jtext = text != nullptr ? env->NewStringUTF(text) : nullptr;
    env->CallVoidMethod(S.bridge, g_onEvent, type, a, b, jtext);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    if (jtext != nullptr) env->DeleteLocalRef(jtext);
}

// --- decoder / audio callbacks (thin wrappers around helix_video.cpp / helix_audio.cpp) ---

int cbVideoSetup(int videoFormat, int width, int height, int redrawRate, void*, int) {
    return helix::videoSetup(videoFormat, width, height, redrawRate);
}
void cbVideoStart() { helix::videoStart(); }
void cbVideoStop() { helix::videoStop(); }
void cbVideoCleanup() { helix::videoCleanup(); }
int cbVideoSubmit(PDECODE_UNIT du) { return helix::videoSubmit(du); }

int cbAudioInit(int audioConfiguration, const POPUS_MULTISTREAM_CONFIGURATION opus, void*, int) {
    return helix::audioInit(audioConfiguration, opus);
}
void cbAudioStart() { helix::audioStart(); }
void cbAudioStop() { helix::audioStop(); }
void cbAudioCleanup() { helix::audioCleanup(); }
void cbAudioSample(char* data, int length) { helix::audioSample(data, length); }

// --- connection listener callbacks ---

void cbStageStarting(int stage) {
    const char* name = LiGetStageName(stage);
    LOGI("stage starting: %s (%d)", name ? name : "?", stage);
    emitEvent(EV_STAGE_STARTING, stage, 0, name);
}

void cbStageComplete(int stage) {
    const char* name = LiGetStageName(stage);
    LOGI("stage complete: %s (%d)", name ? name : "?", stage);
    emitEvent(EV_STAGE_COMPLETE, stage, 0, name);
}

void cbStageFailed(int stage, int errorCode) {
    const char* name = LiGetStageName(stage);
    LOGE("stage failed: %s (%d) errorCode=%d portFlags=0x%x", name ? name : "?", stage, errorCode,
         LiGetPortFlagsFromStage(stage));
    emitEvent(EV_STAGE_FAILED, stage, errorCode, name);
}

void cbConnectionStarted() {
    S.active.store(true, std::memory_order_release);
    LOGI("connection started");
    emitEvent(EV_STARTED, 0, 0, nullptr);
}

void cbConnectionTerminated(int errorCode) {
    S.active.store(false, std::memory_order_release);
    LOGW("connection terminated: errorCode=%d portFlags=0x%x", errorCode,
         LiGetPortFlagsFromTerminationErrorCode(errorCode));
    emitEvent(EV_TERMINATED, errorCode, 0, nullptr);
}

void cbLogMessage(const char* format, ...) {
    if (format == nullptr) return;
    char buffer[512];
    va_list args;
    va_start(args, format);
    vsnprintf(buffer, sizeof(buffer), format, args);
    va_end(args);
    LOGI("[moonlight-common-c] %s", buffer);
}

void cbConnectionStatusUpdate(int status) {
    LOGI("connection status: %d", status);
    emitEvent(EV_STATUS, status, 0, nullptr);
}

void cbSetHdrMode(bool enabled) { LOGI("host HDR mode: %s", enabled ? "on" : "off"); }

int64_t nowMs() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

// Caller holds lifecycleMutex. Idempotent.
void stopSessionLocked() {
    S.active.store(false, std::memory_order_release);
    LiInterruptConnection();
    if (S.startThread.joinable()) S.startThread.join();
    if (S.libStarted.exchange(false, std::memory_order_acq_rel)) {
        LiStopConnection();
    }
    helix::videoSetWindow(nullptr);
}

void setBridge(JNIEnv* env, jobject bridge) {
    std::lock_guard<std::mutex> lock(S.bridgeMutex);
    if (S.bridge != nullptr) {
        env->DeleteGlobalRef(S.bridge);
        S.bridge = nullptr;
    }
    if (bridge != nullptr) S.bridge = env->NewGlobalRef(bridge);
}

std::string toStd(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jstring JNICALL
Java_com_nightcinder_helix_stream_MoonlightNativeBridge_nativeLaunchQueryParameters(JNIEnv* env, jobject) {
    const char* p = LiGetLaunchUrlQueryParameters();
    return env->NewStringUTF(p != nullptr ? p : "");
}

/**
 * Starts the streaming session asynchronously. Progress and the result arrive via onNativeEvent();
 * "connected" is reported only after moonlight-common-c invokes connectionStarted.
 */
JNIEXPORT jboolean JNICALL
Java_com_nightcinder_helix_stream_MoonlightNativeBridge_nativeStart(
        JNIEnv* env, jobject thiz,
        jstring host, jstring appVersion, jstring gfeVersion, jstring rtspSessionUrl,
        jint serverCodecModeSupport,
        jint width, jint height, jint fps, jint bitrateKbps,
        jint supportedVideoFormats,
        jbyteArray riKey, jint riKeyId,
        jobject surface) {
    std::lock_guard<std::mutex> lock(S.lifecycleMutex);
    stopSessionLocked();

    if (g_onEvent == nullptr) {
        jclass cls = env->GetObjectClass(thiz);
        g_onEvent = env->GetMethodID(cls, "onNativeEvent", "(IIILjava/lang/String;)V");
        env->DeleteLocalRef(cls);
        if (g_onEvent == nullptr) {
            env->ExceptionClear();
            LOGE("onNativeEvent not found");
            return JNI_FALSE;
        }
    }
    if (riKey == nullptr || env->GetArrayLength(riKey) != 16) {
        LOGE("riKey must be 16 bytes");
        return JNI_FALSE;
    }

    setBridge(env, thiz);
    S.host = toStd(env, host);
    S.appVersion = toStd(env, appVersion);
    S.gfeVersion = toStd(env, gfeVersion);
    S.rtspUrl = toStd(env, rtspSessionUrl);
    S.serverCodecModeSupport = serverCodecModeSupport;
    {
        std::lock_guard<std::mutex> sl(S.statsMutex);
        S.lastStatsMs = 0;
        S.lastFrames = 0;
        S.lastBytes = 0;
        S.lastFps = 0;
        S.lastBitrateKbps = 0;
    }

    if (surface != nullptr) {
        helix::videoSetWindow(ANativeWindow_fromSurface(env, surface));
    }

    char key[16];
    env->GetByteArrayRegion(riKey, 0, 16, reinterpret_cast<jbyte*>(key));
    // IV = rikeyid as big-endian int32 in the first four bytes, rest zero (matches Moonlight's NvConnection).
    char iv[16] = {0};
    iv[0] = static_cast<char>((riKeyId >> 24) & 0xFF);
    iv[1] = static_cast<char>((riKeyId >> 16) & 0xFF);
    iv[2] = static_cast<char>((riKeyId >> 8) & 0xFF);
    iv[3] = static_cast<char>(riKeyId & 0xFF);

    const int w = width, h = height, f = fps, br = bitrateKbps, vf = supportedVideoFormats;

    S.startThread = std::thread([w, h, f, br, vf, key, iv]() {
        SERVER_INFORMATION serverInfo;
        LiInitializeServerInformation(&serverInfo);
        serverInfo.address = S.host.c_str();
        serverInfo.serverInfoAppVersion = S.appVersion.c_str();
        serverInfo.serverInfoGfeVersion = S.gfeVersion.empty() ? nullptr : S.gfeVersion.c_str();
        serverInfo.rtspSessionUrl = S.rtspUrl.empty() ? nullptr : S.rtspUrl.c_str();
        serverInfo.serverCodecModeSupport = S.serverCodecModeSupport;

        STREAM_CONFIGURATION cfg;
        LiInitializeStreamConfiguration(&cfg);
        cfg.width = w;
        cfg.height = h;
        cfg.fps = f;
        cfg.bitrate = br;
        cfg.packetSize = 1392;
        cfg.streamingRemotely = STREAM_CFG_AUTO;
        cfg.audioConfiguration = AUDIO_CONFIGURATION_STEREO;
        cfg.supportedVideoFormats = vf;
        cfg.clientRefreshRateX100 = f * 100;
        cfg.colorSpace = COLORSPACE_REC_709;
        cfg.colorRange = COLOR_RANGE_LIMITED;
        cfg.encryptionFlags = ENCFLG_ALL;
        std::memcpy(cfg.remoteInputAesKey, key, sizeof(cfg.remoteInputAesKey));
        std::memcpy(cfg.remoteInputAesIv, iv, sizeof(cfg.remoteInputAesIv));

        CONNECTION_LISTENER_CALLBACKS cl;
        LiInitializeConnectionCallbacks(&cl);
        cl.stageStarting = cbStageStarting;
        cl.stageComplete = cbStageComplete;
        cl.stageFailed = cbStageFailed;
        cl.connectionStarted = cbConnectionStarted;
        cl.connectionTerminated = cbConnectionTerminated;
        cl.logMessage = cbLogMessage;
        cl.connectionStatusUpdate = cbConnectionStatusUpdate;
        cl.setHdrMode = cbSetHdrMode;

        DECODER_RENDERER_CALLBACKS dr;
        LiInitializeVideoCallbacks(&dr);
        dr.setup = cbVideoSetup;
        dr.start = cbVideoStart;
        dr.stop = cbVideoStop;
        dr.cleanup = cbVideoCleanup;
        dr.submitDecodeUnit = cbVideoSubmit;
        dr.capabilities = CAPABILITY_DIRECT_SUBMIT;

        AUDIO_RENDERER_CALLBACKS ar;
        LiInitializeAudioCallbacks(&ar);
        ar.init = cbAudioInit;
        ar.start = cbAudioStart;
        ar.stop = cbAudioStop;
        ar.cleanup = cbAudioCleanup;
        ar.decodeAndPlaySample = cbAudioSample;
        ar.capabilities = CAPABILITY_DIRECT_SUBMIT;

        LOGI("LiStartConnection host=%s %dx%d@%d %dkbps formats=0x%x", S.host.c_str(), w, h, f, br, vf);
        const int result = LiStartConnection(&serverInfo, &cfg, &cl, &dr, &ar, nullptr, 0, nullptr, 0);
        if (result == 0) S.libStarted.store(true, std::memory_order_release);
        LOGI("LiStartConnection returned %d", result);
        emitEvent(EV_START_RESULT, result, 0, nullptr);
    });
    return JNI_TRUE;
}

// Swaps the render target. Safe to call at any time (including with null); never touches the session lifecycle.
JNIEXPORT void JNICALL
Java_com_nightcinder_helix_stream_MoonlightNativeBridge_nativeSetSurface(JNIEnv* env, jobject, jobject surface) {
    ANativeWindow* window = surface != nullptr ? ANativeWindow_fromSurface(env, surface) : nullptr;
    helix::videoSetWindow(window);
}

// Idempotent: a no-op when no session exists.
JNIEXPORT void JNICALL
Java_com_nightcinder_helix_stream_MoonlightNativeBridge_nativeStop(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(S.lifecycleMutex);
    stopSessionLocked();
    setBridge(env, nullptr);
    LOGI("nativeStop done");
}

JNIEXPORT void JNICALL
Java_com_nightcinder_helix_stream_MoonlightNativeBridge_nativeSendMouseMove(JNIEnv*, jobject, jshort dx, jshort dy) {
    if (S.active.load(std::memory_order_acquire)) LiSendMouseMoveEvent(dx, dy);
}

JNIEXPORT void JNICALL
Java_com_nightcinder_helix_stream_MoonlightNativeBridge_nativeSendMousePosition(
        JNIEnv*, jobject, jshort x, jshort y, jshort refW, jshort refH) {
    if (S.active.load(std::memory_order_acquire)) LiSendMousePositionEvent(x, y, refW, refH);
}

JNIEXPORT void JNICALL
Java_com_nightcinder_helix_stream_MoonlightNativeBridge_nativeSendMouseButton(JNIEnv*, jobject, jbyte button, jboolean down) {
    if (S.active.load(std::memory_order_acquire)) {
        LiSendMouseButtonEvent(down == JNI_TRUE ? BUTTON_ACTION_PRESS : BUTTON_ACTION_RELEASE, static_cast<int>(button));
    }
}

JNIEXPORT void JNICALL
Java_com_nightcinder_helix_stream_MoonlightNativeBridge_nativeSendKeyboard(
        JNIEnv*, jobject, jshort keyCode, jbyte keyAction, jbyte modifiers) {
    if (S.active.load(std::memory_order_acquire)) {
        LiSendKeyboardEvent(keyCode, static_cast<char>(keyAction), static_cast<char>(modifiers));
    }
}

JNIEXPORT void JNICALL
Java_com_nightcinder_helix_stream_MoonlightNativeBridge_nativeSendScroll(JNIEnv*, jobject, jbyte clicks) {
    if (S.active.load(std::memory_order_acquire)) LiSendScrollEvent(static_cast<signed char>(clicks));
}

JNIEXPORT void JNICALL
Java_com_nightcinder_helix_stream_MoonlightNativeBridge_nativeSendHighResScroll(JNIEnv*, jobject, jshort amount) {
    if (S.active.load(std::memory_order_acquire)) LiSendHighResScrollEvent(amount);
}

JNIEXPORT void JNICALL
Java_com_nightcinder_helix_stream_MoonlightNativeBridge_nativeSendHighResHScroll(JNIEnv*, jobject, jshort amount) {
    if (S.active.load(std::memory_order_acquire)) LiSendHighResHScrollEvent(amount);
}

/**
 * Measured values only: [fps, bitrateKbps, rttMs, droppedFrames, width, height, videoFormat].
 * Returns null while no connection is active.
 */
JNIEXPORT jdoubleArray JNICALL
Java_com_nightcinder_helix_stream_MoonlightNativeBridge_nativeGetStats(JNIEnv* env, jobject) {
    if (!S.active.load(std::memory_order_acquire)) return nullptr;

    const helix::VideoStats vs = helix::videoStats();
    uint32_t rtt = 0, rttVar = 0;
    if (!LiGetEstimatedRttInfo(&rtt, &rttVar)) rtt = 0;

    double fps, kbps;
    {
        std::lock_guard<std::mutex> sl(S.statsMutex);
        const int64_t now = nowMs();
        if (S.lastStatsMs == 0) {
            S.lastStatsMs = now;
            S.lastFrames = vs.framesRendered;
            S.lastBytes = vs.bytes;
        } else if (now - S.lastStatsMs >= 500) {
            const double dt = (now - S.lastStatsMs) / 1000.0;
            S.lastFps = (vs.framesRendered - S.lastFrames) / dt;
            S.lastBitrateKbps = static_cast<int>(((vs.bytes - S.lastBytes) * 8.0 / 1000.0) / dt);
            S.lastStatsMs = now;
            S.lastFrames = vs.framesRendered;
            S.lastBytes = vs.bytes;
        }
        fps = S.lastFps;
        kbps = S.lastBitrateKbps;
    }

    jdouble values[7] = {fps, kbps, static_cast<double>(rtt), static_cast<double>(vs.framesDropped),
                         static_cast<double>(vs.width), static_cast<double>(vs.height),
                         static_cast<double>(vs.format)};
    jdoubleArray out = env->NewDoubleArray(7);
    if (out != nullptr) env->SetDoubleArrayRegion(out, 0, 7, values);
    return out;
}

}  // extern "C"
