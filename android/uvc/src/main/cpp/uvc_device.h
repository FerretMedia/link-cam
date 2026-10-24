#pragma once

// One open UVC device: libuvc streaming into a triple buffer of decoded
// planes, which the GL thread takes from without ever waiting on the camera.

#include <atomic>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

// libusb.h first: libuvc.h only declares uvc_wrap() when it can see
// LIBUSB_API_VERSION.
#include <libusb.h>
#include <libuvc/libuvc.h>
#include <turbojpeg.h>

namespace linkcam {

// Codec ids, matching SourceFormat.Codec's ordinal in the shared module.
enum class Codec : int { Mjpeg = 0, Yuyv = 1, Nv12 = 2 };

struct FormatEntry {
    Codec codec;
    int width;
    int height;
    int interval100ns;
};

// A decoded frame as three 8-bit planes: Y, then Cb and Cr at whatever
// subsampling the source used. The shader samples them with normalised
// coordinates, so 4:2:0, 4:2:2 and 4:4:4 all draw the same way.
struct Planes {
    std::vector<uint8_t> data[3];
    int width[3] = {0, 0, 0};
    int height[3] = {0, 0, 0};
    int64_t captureNanos = 0;
    int32_t decodeMicros = 0;
};

struct Stats {
    int64_t received = 0;
    int64_t dropped = 0;
    int64_t decodeErrors = 0;
};

class UvcDevice {
public:
    // Takes a file descriptor from Android's UsbDeviceConnection. The caller
    // keeps the connection open for as long as this object lives.
    static UvcDevice* open(int fd, std::string& error);
    ~UvcDevice();

    std::vector<FormatEntry> formats() const;

    // onFrame runs on libuvc's transfer thread after each decoded frame is
    // published. Keep it short: it's there to wake the renderer.
    int start(Codec codec, int width, int height, int fps, std::function<void()> onFrame,
              std::string& error);
    void stop();

    // GL thread only. acquire() takes the newest frame if there is one and
    // describes it; upload() then copies that frame into the given textures,
    // which the caller has already sized from acquire()'s answer.
    bool acquire(int out[8], int64_t& captureNanos, int32_t& decodeMicros);
    void upload(const unsigned int textures[3]);

    Stats stats() const;

private:
    UvcDevice() = default;
    static void onUvcFrame(uvc_frame_t* frame, void* self);
    void handleFrame(uvc_frame_t* frame);
    bool decode(uvc_frame_t* frame, Planes& out);
    void publish();
    void runEvents();

    libusb_context* usb_ = nullptr;
    uvc_context_t* uvc_ = nullptr;
    uvc_device_handle_t* devh_ = nullptr;
    tjhandle tj_ = nullptr;

    std::thread events_;
    std::atomic<bool> runningEvents_{false};
    std::atomic<bool> streaming_{false};
    std::function<void()> onFrame_;

    // Triple buffer. The camera thread owns writing_, the GL thread owns
    // reading_, and ready_ changes hands under the mutex.
    Planes slots_[3];
    int writing_ = 0;
    int ready_ = 1;
    int reading_ = 2;
    bool readyIsFresh_ = false;
    mutable std::mutex swap_;

    std::atomic<int64_t> received_{0};
    std::atomic<int64_t> dropped_{0};
    std::atomic<int64_t> decodeErrors_{0};
};

}  // namespace linkcam
