#include "uvc_device.h"

#include <GLES3/gl3.h>
#include <android/log.h>

#include <chrono>
#include <cstring>
#include <utility>

#define LOG_TAG "LinkCamUvc"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace linkcam {

namespace {

int64_t nowNanos() {
    // CLOCK_MONOTONIC, the same clock as Kotlin's System.nanoTime() on Android,
    // so capture and present times can be subtracted across the JNI boundary.
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

void ensure(Planes& p, int plane, int width, int height) {
    p.width[plane] = width;
    p.height[plane] = height;
    p.data[plane].resize(static_cast<size_t>(width) * height);
}

uvc_frame_format toUvc(Codec codec) {
    switch (codec) {
        case Codec::Mjpeg: return UVC_FRAME_FORMAT_MJPEG;
        case Codec::Yuyv: return UVC_FRAME_FORMAT_YUYV;
        case Codec::Nv12: return UVC_FRAME_FORMAT_NV12;
    }
    return UVC_FRAME_FORMAT_ANY;
}

}  // namespace

UvcDevice* UvcDevice::open(int fd, std::string& error) {
    auto* d = new UvcDevice();

    // Android apps can't scan /dev/bus/usb, so libusb must not try. The device
    // comes in as a file descriptor that UsbManager already opened for us.
    libusb_init_option options[] = {{LIBUSB_OPTION_NO_DEVICE_DISCOVERY, {0}}};
    int rc = libusb_init_context(&d->usb_, options, 1);
    if (rc != LIBUSB_SUCCESS) {
        error = std::string("libusb init: ") + libusb_strerror(rc);
        delete d;
        return nullptr;
    }

    // Given our own libusb context, libuvc expects us to run its events.
    uvc_error_t uerr = uvc_init(&d->uvc_, d->usb_);
    if (uerr != UVC_SUCCESS) {
        error = std::string("uvc init: ") + uvc_strerror(uerr);
        delete d;
        return nullptr;
    }
    d->runningEvents_ = true;
    d->events_ = std::thread([d] { d->runEvents(); });

    uerr = uvc_wrap(fd, d->uvc_, &d->devh_);
    if (uerr != UVC_SUCCESS) {
        error = std::string("uvc open: ") + uvc_strerror(uerr);
        delete d;
        return nullptr;
    }

    d->tj_ = tj3Init(TJINIT_DECOMPRESS);
    if (d->tj_ == nullptr) {
        error = "libjpeg-turbo init failed";
        delete d;
        return nullptr;
    }
    LOGI("opened UVC device on fd %d", fd);
    return d;
}

UvcDevice::~UvcDevice() {
    stop();
    if (devh_ != nullptr) uvc_close(devh_);
    if (runningEvents_) {
        runningEvents_ = false;
        if (usb_ != nullptr) libusb_interrupt_event_handler(usb_);
    }
    if (events_.joinable()) events_.join();
    // With an external libusb context, uvc_exit leaves the context to us.
    if (uvc_ != nullptr) uvc_exit(uvc_);
    if (usb_ != nullptr) libusb_exit(usb_);
    if (tj_ != nullptr) tj3Destroy(tj_);
}

void UvcDevice::runEvents() {
    while (runningEvents_) {
        timeval tv{0, 100 * 1000};
        libusb_handle_events_timeout_completed(usb_, &tv, nullptr);
    }
}

std::vector<FormatEntry> UvcDevice::formats() const {
    std::vector<FormatEntry> out;
    for (const uvc_format_desc_t* f = uvc_get_format_descs(devh_); f != nullptr; f = f->next) {
        Codec codec;
        if (f->bDescriptorSubtype == UVC_VS_FORMAT_MJPEG) {
            codec = Codec::Mjpeg;
        } else if (f->bDescriptorSubtype == UVC_VS_FORMAT_UNCOMPRESSED) {
            uint8_t guid[16];
            std::memcpy(guid, f->guidFormat, sizeof(guid));
            uvc_frame_format ff = uvc_frame_format_for_guid(guid);
            if (ff == UVC_FRAME_FORMAT_YUYV) {
                codec = Codec::Yuyv;
            } else if (ff == UVC_FRAME_FORMAT_NV12) {
                codec = Codec::Nv12;
            } else {
                continue;  // A format we can't draw yet.
            }
        } else {
            continue;
        }
        for (const uvc_frame_desc_t* fr = f->frame_descs; fr != nullptr; fr = fr->next) {
            if (fr->intervals != nullptr) {
                for (const uint32_t* i = fr->intervals; *i != 0; ++i) {
                    out.push_back({codec, fr->wWidth, fr->wHeight, static_cast<int>(*i)});
                }
            } else {
                // A continuous range: offer its fastest end.
                out.push_back({codec, fr->wWidth, fr->wHeight,
                               static_cast<int>(fr->dwMinFrameInterval)});
            }
        }
    }
    return out;
}

int UvcDevice::start(Codec codec, int width, int height, int fps,
                     std::function<void()> onFrame, std::string& error) {
    stop();
    uvc_stream_ctrl_t ctrl;
    uvc_error_t rc = uvc_get_stream_ctrl_format_size(devh_, &ctrl, toUvc(codec), width, height, fps);
    if (rc != UVC_SUCCESS) {
        error = std::string("format not accepted: ") + uvc_strerror(rc);
        return rc;
    }
    onFrame_ = std::move(onFrame);
    {
        std::lock_guard<std::mutex> lock(swap_);
        readyIsFresh_ = false;
    }
    received_ = 0;
    dropped_ = 0;
    decodeErrors_ = 0;
    rc = uvc_start_streaming(devh_, &ctrl, &UvcDevice::onUvcFrame, this, 0);
    if (rc != UVC_SUCCESS) {
        error = std::string("start streaming: ") + uvc_strerror(rc);
        return rc;
    }
    streaming_ = true;
    LOGI("streaming codec %d %dx%d @ %d", static_cast<int>(codec), width, height, fps);
    return 0;
}

void UvcDevice::stop() {
    if (streaming_.exchange(false)) {
        uvc_stop_streaming(devh_);
        LOGI("stopped streaming");
    }
}

void UvcDevice::onUvcFrame(uvc_frame_t* frame, void* self) {
    static_cast<UvcDevice*>(self)->handleFrame(frame);
}

void UvcDevice::handleFrame(uvc_frame_t* frame) {
    const int64_t arrived = nowNanos();
    received_++;
    Planes& slot = slots_[writing_];
    if (!decode(frame, slot)) {
        decodeErrors_++;
        return;
    }
    slot.captureNanos = arrived;
    slot.decodeMicros = static_cast<int32_t>((nowNanos() - arrived) / 1000);
    publish();
    if (onFrame_) onFrame_();
}

bool UvcDevice::decode(uvc_frame_t* frame, Planes& out) {
    const auto* src = static_cast<const uint8_t*>(frame->data);
    const int w = static_cast<int>(frame->width);
    const int h = static_cast<int>(frame->height);

    switch (frame->frame_format) {
        case UVC_FRAME_FORMAT_MJPEG: {
            if (tj3DecompressHeader(tj_, src, frame->data_bytes) != 0) return false;
            const int jw = tj3Get(tj_, TJPARAM_JPEGWIDTH);
            const int jh = tj3Get(tj_, TJPARAM_JPEGHEIGHT);
            const int subsamp = tj3Get(tj_, TJPARAM_SUBSAMP);
            unsigned char* planes[3];
            if (subsamp == TJSAMP_GRAY) {
                ensure(out, 0, jw, jh);
                ensure(out, 1, 1, 1);
                ensure(out, 2, 1, 1);
                out.data[1][0] = 128;
                out.data[2][0] = 128;
            } else {
                for (int c = 0; c < 3; ++c) {
                    ensure(out, c, tj3YUVPlaneWidth(c, jw, subsamp), tj3YUVPlaneHeight(c, jh, subsamp));
                }
            }
            for (int c = 0; c < 3; ++c) planes[c] = out.data[c].data();
            int strides[3] = {out.width[0], out.width[1], out.width[2]};
            // Decoding to planes skips libjpeg-turbo's colour conversion; the
            // shader does it with the source's ColorSpec instead.
            if (tj3DecompressToYUVPlanes8(tj_, src, frame->data_bytes, planes, strides) != 0) {
                // A warning (e.g. a truncated scan) still leaves a usable picture.
                return tj3GetErrorCode(tj_) == TJERR_WARNING;
            }
            return true;
        }
        case UVC_FRAME_FORMAT_YUYV: {
            if (frame->data_bytes < static_cast<size_t>(w) * h * 2) return false;
            ensure(out, 0, w, h);
            ensure(out, 1, w / 2, h);
            ensure(out, 2, w / 2, h);
            uint8_t* y = out.data[0].data();
            uint8_t* u = out.data[1].data();
            uint8_t* v = out.data[2].data();
            const size_t pairs = static_cast<size_t>(w / 2) * h;
            for (size_t i = 0; i < pairs; ++i) {
                const uint8_t* p = src + i * 4;
                y[i * 2] = p[0];
                u[i] = p[1];
                y[i * 2 + 1] = p[2];
                v[i] = p[3];
            }
            return true;
        }
        case UVC_FRAME_FORMAT_NV12: {
            if (frame->data_bytes < static_cast<size_t>(w) * h * 3 / 2) return false;
            ensure(out, 0, w, h);
            ensure(out, 1, w / 2, h / 2);
            ensure(out, 2, w / 2, h / 2);
            std::memcpy(out.data[0].data(), src, static_cast<size_t>(w) * h);
            const uint8_t* uv = src + static_cast<size_t>(w) * h;
            const size_t n = static_cast<size_t>(w / 2) * (h / 2);
            for (size_t i = 0; i < n; ++i) {
                out.data[1][i] = uv[i * 2];
                out.data[2][i] = uv[i * 2 + 1];
            }
            return true;
        }
        default:
            return false;
    }
}

void UvcDevice::publish() {
    std::lock_guard<std::mutex> lock(swap_);
    std::swap(writing_, ready_);
    // A fresh frame that nobody took is being replaced: the renderer fell behind.
    if (readyIsFresh_) dropped_++;
    readyIsFresh_ = true;
}

bool UvcDevice::acquire(int out[8], int64_t& captureNanos, int32_t& decodeMicros) {
    {
        std::lock_guard<std::mutex> lock(swap_);
        if (!readyIsFresh_) return false;
        std::swap(reading_, ready_);
        readyIsFresh_ = false;
    }
    const Planes& p = slots_[reading_];
    for (int c = 0; c < 3; ++c) {
        out[c * 2] = p.width[c];
        out[c * 2 + 1] = p.height[c];
    }
    out[6] = 0;
    out[7] = 0;
    captureNanos = p.captureNanos;
    decodeMicros = p.decodeMicros;
    return true;
}

void UvcDevice::upload(const unsigned int textures[3]) {
    const Planes& p = slots_[reading_];
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    for (int c = 0; c < 3; ++c) {
        glBindTexture(GL_TEXTURE_2D, textures[c]);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, p.width[c], p.height[c], GL_RED, GL_UNSIGNED_BYTE,
                        p.data[c].data());
    }
}

Stats UvcDevice::stats() const {
    return Stats{received_.load(), dropped_.load(), decodeErrors_.load()};
}

}  // namespace linkcam
