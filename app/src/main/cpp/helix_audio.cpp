// Native audio path: Opus packets -> AMediaCodec ("audio/opus") -> PCM16 -> AAudio.
// Only plain stereo/mono Opus (one stream, channel-mapping family 0) is handled; for any other layout
// the audio renderer disables itself and video keeps running.
#include "helix_common.h"

#include <aaudio/AAudio.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>

#include <atomic>
#include <cstring>
#include <mutex>
#include <vector>

#include "Limelight.h"

namespace helix {
namespace {

struct AudioState {
    std::mutex mutex;
    bool enabled = false;
    AMediaCodec* codec = nullptr;
    AAudioStream* stream = nullptr;
    int channels = 2;
    int sampleRate = 48000;
    int samplesPerFrame = 240;
    int64_t ptsUs = 0;
    int64_t decoded = 0;
    int64_t underrunsDropped = 0;
};

AudioState a;

void putLe16(std::vector<uint8_t>& v, uint16_t x) {
    v.push_back(x & 0xFF);
    v.push_back((x >> 8) & 0xFF);
}

void putLe32(std::vector<uint8_t>& v, uint32_t x) {
    for (int i = 0; i < 4; i++) v.push_back((x >> (8 * i)) & 0xFF);
}

void putLe64(std::vector<uint8_t>& v, uint64_t x) {
    for (int i = 0; i < 8; i++) v.push_back((x >> (8 * i)) & 0xFF);
}

// Caller holds a.mutex.
void teardownLocked() {
    if (a.stream != nullptr) {
        AAudioStream_requestStop(a.stream);
        AAudioStream_close(a.stream);
        a.stream = nullptr;
    }
    if (a.codec != nullptr) {
        AMediaCodec_stop(a.codec);
        AMediaCodec_delete(a.codec);
        a.codec = nullptr;
    }
    a.enabled = false;
}

bool openStreamLocked() {
    AAudioStreamBuilder* builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) return false;
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setSampleRate(builder, a.sampleRate);
    AAudioStreamBuilder_setChannelCount(builder, a.channels);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_MEDIA);
    aaudio_result_t r = AAudioStreamBuilder_openStream(builder, &a.stream);
    AAudioStreamBuilder_delete(builder);
    if (r != AAUDIO_OK) {
        LOGE("AAudio open failed: %s", AAudio_convertResultToText(r));
        a.stream = nullptr;
        return false;
    }
    // Buffer about 20 ms (at least two bursts). Opus packets arrive every 5 ms with network jitter on
    // top, so two bursts alone (2-4 ms on phones) underrun constantly and crackle; 20 ms stays well
    // under the video latency.
    const int32_t burst = AAudioStream_getFramesPerBurst(a.stream);
    const int32_t capacity = AAudioStream_getBufferCapacityInFrames(a.stream);
    int32_t target = a.sampleRate / 50;  // 20 ms
    if (burst > 0) {
        if (target < burst * 2) target = burst * 2;
        target = ((target + burst - 1) / burst) * burst;  // whole bursts
    }
    if (capacity > 0 && target > capacity) target = capacity;
    const int32_t actual = AAudioStream_setBufferSizeInFrames(a.stream, target);
    LOGI("AAudio stream: burst=%d capacity=%d buffer=%d frames", burst, capacity, actual);
    return true;
}

bool openDecoderLocked() {
    a.codec = AMediaCodec_createDecoderByType("audio/opus");
    if (a.codec == nullptr) {
        LOGE("No audio/opus decoder available");
        return false;
    }
    AMediaFormat* fmt = AMediaFormat_new();
    AMediaFormat_setString(fmt, AMEDIAFORMAT_KEY_MIME, "audio/opus");
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_SAMPLE_RATE, a.sampleRate);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_CHANNEL_COUNT, a.channels);

    // csd-0: OpusHead (RFC 7845), mapping family 0.
    std::vector<uint8_t> head = {'O', 'p', 'u', 's', 'H', 'e', 'a', 'd', 1, static_cast<uint8_t>(a.channels)};
    putLe16(head, 0);                                  // pre-skip
    putLe32(head, static_cast<uint32_t>(a.sampleRate));  // input sample rate
    putLe16(head, 0);                                  // output gain
    head.push_back(0);                                 // channel mapping family 0
    AMediaFormat_setBuffer(fmt, "csd-0", head.data(), head.size());
    // csd-1: codec delay (ns); csd-2: seek pre-roll (ns).
    std::vector<uint8_t> delay;
    putLe64(delay, 0);
    AMediaFormat_setBuffer(fmt, "csd-1", delay.data(), delay.size());
    std::vector<uint8_t> preroll;
    putLe64(preroll, 80000000ULL);
    AMediaFormat_setBuffer(fmt, "csd-2", preroll.data(), preroll.size());

    media_status_t st = AMediaCodec_configure(a.codec, fmt, nullptr, nullptr, 0);
    AMediaFormat_delete(fmt);
    if (st != AMEDIA_OK) {
        LOGE("Opus decoder configure failed (%d)", st);
        return false;
    }
    st = AMediaCodec_start(a.codec);
    if (st != AMEDIA_OK) {
        LOGE("Opus decoder start failed (%d)", st);
        return false;
    }
    return true;
}

}  // namespace

int audioInit(int audioConfiguration, const OPUS_MULTISTREAM_CONFIGURATION* opus) {
    std::lock_guard<std::mutex> lock(a.mutex);
    teardownLocked();
    if (opus == nullptr) return 0;
    LOGI("Audio init: config=0x%x rate=%d channels=%d streams=%d coupled=%d spf=%d",
         audioConfiguration, opus->sampleRate, opus->channelCount, opus->streams,
         opus->coupledStreams, opus->samplesPerFrame);

    const bool plain = opus->streams == 1 && opus->channelCount <= 2 && opus->channelCount >= 1;
    if (!plain) {
        LOGW("Unsupported Opus layout (%d channels, %d streams): audio disabled", opus->channelCount, opus->streams);
        return 0;
    }
    a.channels = opus->channelCount;
    a.sampleRate = opus->sampleRate > 0 ? opus->sampleRate : 48000;
    a.samplesPerFrame = opus->samplesPerFrame > 0 ? opus->samplesPerFrame : 240;
    a.ptsUs = 0;
    a.decoded = 0;

    if (!openDecoderLocked() || !openStreamLocked()) {
        LOGW("Audio output unavailable; continuing without audio");
        teardownLocked();
        return 0;
    }
    a.enabled = true;
    return 0;
}

void audioStart() {
    std::lock_guard<std::mutex> lock(a.mutex);
    if (a.enabled && a.stream != nullptr) AAudioStream_requestStart(a.stream);
}

void audioStop() {
    std::lock_guard<std::mutex> lock(a.mutex);
    if (a.enabled && a.stream != nullptr) AAudioStream_requestStop(a.stream);
}

void audioCleanup() {
    std::lock_guard<std::mutex> lock(a.mutex);
    teardownLocked();
}

void audioSample(char* data, int length) {
    if (data == nullptr || length <= 0) return;
    std::lock_guard<std::mutex> lock(a.mutex);
    if (!a.enabled || a.codec == nullptr || a.stream == nullptr) return;

    ssize_t in = AMediaCodec_dequeueInputBuffer(a.codec, 2000);
    if (in >= 0) {
        size_t cap = 0;
        uint8_t* buf = AMediaCodec_getInputBuffer(a.codec, static_cast<size_t>(in), &cap);
        if (buf != nullptr && static_cast<size_t>(length) <= cap) {
            std::memcpy(buf, data, static_cast<size_t>(length));
            AMediaCodec_queueInputBuffer(a.codec, static_cast<size_t>(in), 0, static_cast<size_t>(length), a.ptsUs, 0);
            a.ptsUs += static_cast<int64_t>(a.samplesPerFrame) * 1000000LL / a.sampleRate;
        } else {
            AMediaCodec_queueInputBuffer(a.codec, static_cast<size_t>(in), 0, 0, a.ptsUs, 0);
        }
    }

    // Drain whatever PCM is ready without blocking.
    for (;;) {
        AMediaCodecBufferInfo info;
        ssize_t out = AMediaCodec_dequeueOutputBuffer(a.codec, &info, 0);
        if (out >= 0) {
            size_t cap = 0;
            uint8_t* pcm = AMediaCodec_getOutputBuffer(a.codec, static_cast<size_t>(out), &cap);
            if (pcm != nullptr && info.size > 0) {
                const int32_t frames = info.size / (2 * a.channels);
                aaudio_result_t w = AAudioStream_write(a.stream, pcm + info.offset, frames, 0);
                if (w >= 0 && w < frames) a.underrunsDropped++;  // output buffer full: drop the tail (stay low latency)
                a.decoded += frames;
            }
            AMediaCodec_releaseOutputBuffer(a.codec, static_cast<size_t>(out), false);
        } else if (out == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED || out == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
            continue;
        } else {
            break;
        }
    }
}

}  // namespace helix
