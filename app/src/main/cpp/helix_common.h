#pragma once

#include <android/log.h>
#include <stdint.h>

#define HELIX_TAG "HelixMoonlightJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, HELIX_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, HELIX_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, HELIX_TAG, __VA_ARGS__)

struct _DECODE_UNIT;
struct _OPUS_MULTISTREAM_CONFIGURATION;
struct ANativeWindow;

namespace helix {

// ---- video (helix_video.cpp): AMediaCodec decoder rendering straight into an ANativeWindow ----

int videoSetup(int videoFormat, int width, int height, int fps);
void videoStart();
void videoStop();
void videoCleanup();
int videoSubmit(struct _DECODE_UNIT* decodeUnit);

// Takes ownership of one reference to `window` (may be null). Releases the previous window.
void videoSetWindow(ANativeWindow* window);

struct VideoStats {
    int64_t framesSubmitted;
    int64_t framesRendered;
    int64_t framesDropped;
    int64_t bytes;
    int width;
    int height;
    int format;
};
VideoStats videoStats();

// ---- audio (helix_audio.cpp): Opus via AMediaCodec, output via AAudio ----

int audioInit(int audioConfiguration, const struct _OPUS_MULTISTREAM_CONFIGURATION* opus);
void audioStart();
void audioStop();
void audioCleanup();
void audioSample(char* data, int length);

}  // namespace helix
