// Native video path: moonlight-common-c decode units -> AMediaCodec -> ANativeWindow (zero copy).
#include "helix_common.h"

#include <android/native_window.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>

#include <atomic>
#include <chrono>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>

#include "Limelight.h"

namespace helix {
namespace {

int64_t nowMs() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

struct VideoState {
    std::mutex mutex;
    ANativeWindow* window = nullptr;

    // Negotiated stream description (valid between setup and cleanup).
    bool configured = false;
    bool running = false;
    int format = 0;
    int width = 0;
    int height = 0;
    int fps = 60;

    AMediaCodec* codec = nullptr;
    std::thread outputThread;
    std::atomic<bool> outputStop{false};
    std::atomic<bool> codecFailed{false};

    // Until an IDR frame arrives after (re)creating the decoder, everything else is dropped.
    bool waitingForIdr = true;
    int64_t lastIdrRequestMs = 0;

    std::vector<uint8_t> configBuf;
    std::vector<uint8_t> picBuf;

    std::atomic<int64_t> submitted{0};
    std::atomic<int64_t> rendered{0};
    std::atomic<int64_t> dropped{0};
    std::atomic<int64_t> bytes{0};
};

VideoState g;

const char* mimeFor(int format) {
    if (format & VIDEO_FORMAT_MASK_H264) return "video/avc";
    if (format & VIDEO_FORMAT_MASK_H265) return "video/hevc";
    if (format & VIDEO_FORMAT_MASK_AV1) return "video/av01";
    return nullptr;
}

void outputLoop(AMediaCodec* codec, std::atomic<bool>* stop, std::atomic<bool>* failed) {
    while (!stop->load(std::memory_order_acquire)) {
        AMediaCodecBufferInfo info;
        ssize_t idx = AMediaCodec_dequeueOutputBuffer(codec, &info, 10000);
        if (idx >= 0) {
            // Low latency: if newer frames are already waiting, skip the older ones.
            for (;;) {
                AMediaCodecBufferInfo next;
                ssize_t nidx = AMediaCodec_dequeueOutputBuffer(codec, &next, 0);
                if (nidx >= 0) {
                    AMediaCodec_releaseOutputBuffer(codec, static_cast<size_t>(idx), false);
                    g.dropped.fetch_add(1, std::memory_order_relaxed);
                    idx = nidx;
                    info = next;
                } else {
                    break;
                }
            }
            AMediaCodec_releaseOutputBuffer(codec, static_cast<size_t>(idx), true);
            g.rendered.fetch_add(1, std::memory_order_relaxed);
        } else if (idx == AMEDIACODEC_INFO_TRY_AGAIN_LATER ||
                   idx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED ||
                   idx == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
            continue;
        } else {
            LOGE("Video decoder output error %zd", idx);
            failed->store(true, std::memory_order_release);
            break;
        }
    }
}

// Caller holds g.mutex.
void destroyDecoderLocked() {
    if (g.codec == nullptr) return;
    g.outputStop.store(true, std::memory_order_release);
    AMediaCodec* codec = g.codec;
    // Stopping the codec unblocks a pending dequeueOutputBuffer.
    AMediaCodec_stop(codec);
    if (g.outputThread.joinable()) g.outputThread.join();
    AMediaCodec_delete(codec);
    g.codec = nullptr;
    g.codecFailed.store(false, std::memory_order_release);
    g.waitingForIdr = true;
}

// Caller holds g.mutex.
bool createDecoderLocked() {
    if (g.window == nullptr || !g.configured) return false;
    const char* mime = mimeFor(g.format);
    if (mime == nullptr) return false;

    AMediaCodec* codec = AMediaCodec_createDecoderByType(mime);
    if (codec == nullptr) {
        LOGE("No decoder for %s", mime);
        return false;
    }
    AMediaFormat* fmt = AMediaFormat_new();
    AMediaFormat_setString(fmt, AMEDIAFORMAT_KEY_MIME, mime);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, g.width);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, g.height);
    AMediaFormat_setInt32(fmt, "low-latency", 1);
    AMediaFormat_setInt32(fmt, "priority", 0);  // realtime
    AMediaFormat_setInt32(fmt, "operating-rate", g.fps > 0 ? g.fps : 60);

    media_status_t st = AMediaCodec_configure(codec, fmt, g.window, nullptr, 0);
    AMediaFormat_delete(fmt);
    if (st != AMEDIA_OK) {
        LOGE("AMediaCodec_configure failed (%d) for %s %dx%d", st, mime, g.width, g.height);
        AMediaCodec_delete(codec);
        return false;
    }
    st = AMediaCodec_start(codec);
    if (st != AMEDIA_OK) {
        LOGE("AMediaCodec_start failed (%d)", st);
        AMediaCodec_delete(codec);
        return false;
    }
    g.codec = codec;
    g.waitingForIdr = true;
    g.outputStop.store(false, std::memory_order_release);
    g.codecFailed.store(false, std::memory_order_release);
    g.outputThread = std::thread(outputLoop, codec, &g.outputStop, &g.codecFailed);
    LOGI("Video decoder started: %s %dx%d @%d", mime, g.width, g.height, g.fps);
    return true;
}

bool queueInput(AMediaCodec* codec, const uint8_t* data, size_t size, int64_t ptsUs, uint32_t flags) {
    for (int attempt = 0; attempt < 3; attempt++) {
        ssize_t idx = AMediaCodec_dequeueInputBuffer(codec, 10000);
        if (idx >= 0) {
            size_t cap = 0;
            uint8_t* buf = AMediaCodec_getInputBuffer(codec, static_cast<size_t>(idx), &cap);
            if (buf == nullptr || size > cap) {
                AMediaCodec_queueInputBuffer(codec, static_cast<size_t>(idx), 0, 0, ptsUs, 0);
                return false;
            }
            std::memcpy(buf, data, size);
            return AMediaCodec_queueInputBuffer(codec, static_cast<size_t>(idx), 0, size, ptsUs, flags) == AMEDIA_OK;
        }
        if (idx != AMEDIACODEC_INFO_TRY_AGAIN_LATER) return false;
    }
    return false;
}

// Caller holds g.mutex; asks for an IDR at most once per second.
int needIdrLocked() {
    int64_t t = nowMs();
    if (t - g.lastIdrRequestMs >= 1000) {
        g.lastIdrRequestMs = t;
        return DR_NEED_IDR;
    }
    return DR_OK;
}

}  // namespace

int videoSetup(int videoFormat, int width, int height, int fps) {
    std::lock_guard<std::mutex> lock(g.mutex);
    destroyDecoderLocked();
    g.format = videoFormat;
    g.width = width;
    g.height = height;
    g.fps = fps;
    g.configured = true;
    g.waitingForIdr = true;
    g.submitted = 0;
    g.rendered = 0;
    g.dropped = 0;
    g.bytes = 0;
    if (mimeFor(videoFormat) == nullptr) {
        LOGE("Unsupported negotiated video format 0x%x", videoFormat);
        return -1;
    }
    LOGI("Video setup: format=0x%x %dx%d @%d", videoFormat, width, height, fps);
    if (g.window != nullptr) createDecoderLocked();
    return 0;
}

void videoStart() {
    std::lock_guard<std::mutex> lock(g.mutex);
    g.running = true;
    g.lastIdrRequestMs = nowMs();
}

void videoStop() {
    std::lock_guard<std::mutex> lock(g.mutex);
    g.running = false;
}

void videoCleanup() {
    std::lock_guard<std::mutex> lock(g.mutex);
    g.running = false;
    g.configured = false;
    destroyDecoderLocked();
}

void videoSetWindow(ANativeWindow* window) {
    std::lock_guard<std::mutex> lock(g.mutex);
    destroyDecoderLocked();
    if (g.window != nullptr) ANativeWindow_release(g.window);
    g.window = window;
    if (window != nullptr && g.configured) {
        if (createDecoderLocked() && g.running) {
            // The new decoder needs a key frame to start; ask the host for one right away.
            g.lastIdrRequestMs = nowMs();
            LiRequestIdrFrame();
        }
    }
}

int videoSubmit(PDECODE_UNIT du) {
    if (du == nullptr || du->bufferList == nullptr || du->fullLength <= 0) {
        g.dropped.fetch_add(1, std::memory_order_relaxed);
        return DR_NEED_IDR;
    }
    g.submitted.fetch_add(1, std::memory_order_relaxed);

    std::lock_guard<std::mutex> lock(g.mutex);
    if (!g.running) return DR_OK;

    if (g.codec != nullptr && g.codecFailed.load(std::memory_order_acquire)) {
        LOGW("Video decoder failed; recreating");
        destroyDecoderLocked();
    }
    if (g.codec == nullptr && !createDecoderLocked()) {
        // No window yet (window closed) or decoder unavailable: drop silently, retry on next frame.
        g.dropped.fetch_add(1, std::memory_order_relaxed);
        return DR_OK;
    }

    const bool idr = du->frameType == FRAME_TYPE_IDR;
    if (g.waitingForIdr) {
        if (!idr) {
            g.dropped.fetch_add(1, std::memory_order_relaxed);
            return needIdrLocked();
        }
        g.waitingForIdr = false;
    }

    g.configBuf.clear();
    g.picBuf.clear();
    for (PLENTRY e = du->bufferList; e != nullptr; e = e->next) {
        if (e->data == nullptr || e->length <= 0) {
            g.dropped.fetch_add(1, std::memory_order_relaxed);
            g.waitingForIdr = true;
            return DR_NEED_IDR;
        }
        const uint8_t* p = reinterpret_cast<const uint8_t*>(e->data);
        auto& dst = (e->bufferType == BUFFER_TYPE_PICDATA) ? g.picBuf : g.configBuf;
        dst.insert(dst.end(), p, p + e->length);
    }

    const int64_t pts = static_cast<int64_t>(du->presentationTimeUs);
    if (!g.configBuf.empty() &&
        !queueInput(g.codec, g.configBuf.data(), g.configBuf.size(), pts, AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG)) {
        g.dropped.fetch_add(1, std::memory_order_relaxed);
        g.waitingForIdr = true;
        return DR_NEED_IDR;
    }
    if (!g.picBuf.empty()) {
        const uint32_t flags = idr ? AMEDIACODEC_BUFFER_FLAG_KEY_FRAME : 0;
        if (!queueInput(g.codec, g.picBuf.data(), g.picBuf.size(), pts, flags)) {
            g.dropped.fetch_add(1, std::memory_order_relaxed);
            g.waitingForIdr = true;
            return DR_NEED_IDR;
        }
    }
    g.bytes.fetch_add(du->fullLength, std::memory_order_relaxed);
    return DR_OK;
}

VideoStats videoStats() {
    VideoStats s;
    s.framesSubmitted = g.submitted.load(std::memory_order_relaxed);
    s.framesRendered = g.rendered.load(std::memory_order_relaxed);
    s.framesDropped = g.dropped.load(std::memory_order_relaxed);
    s.bytes = g.bytes.load(std::memory_order_relaxed);
    // width/height/format are plain ints written under the mutex; a stale read is harmless for telemetry.
    s.width = g.width;
    s.height = g.height;
    s.format = g.format;
    return s;
}

}  // namespace helix
