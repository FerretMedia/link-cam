// JNI surface for cool.linker.cam.uvc.UvcDevice. Thin on purpose: every
// function unpacks its arguments and calls into UvcDevice.

#include <jni.h>

#include <memory>
#include <string>
#include <vector>

#include "uvc_device.h"

using linkcam::Codec;
using linkcam::UvcDevice;

namespace {

JavaVM* gVm = nullptr;

UvcDevice* device(jlong handle) { return reinterpret_cast<UvcDevice*>(handle); }

void throwUvc(JNIEnv* env, const std::string& message) {
    jclass cls = env->FindClass("cool/linker/cam/uvc/UvcException");
    if (cls != nullptr) env->ThrowNew(cls, message.c_str());
}

// Calls Runnable.run() on the frame listener from libuvc's thread, attaching
// that thread to the VM the first time it calls.
class FrameListener {
public:
    FrameListener(JNIEnv* env, jobject runnable) : runnable_(env->NewGlobalRef(runnable)) {
        jclass cls = env->GetObjectClass(runnable);
        run_ = env->GetMethodID(cls, "run", "()V");
    }

    void operator()() const {
        JNIEnv* env = nullptr;
        if (gVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            JavaVMAttachArgs args{JNI_VERSION_1_6, "LinkCamUvcFrames", nullptr};
            if (gVm->AttachCurrentThread(&env, &args) != JNI_OK) return;
        }
        env->CallVoidMethod(runnable_, run_);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    jobject runnable_;
    jmethodID run_;
};

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    gVm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_cool_linker_cam_uvc_UvcDevice_nativeOpen(JNIEnv* env, jclass, jint fd) {
    std::string error;
    UvcDevice* d = UvcDevice::open(fd, error);
    if (d == nullptr) {
        throwUvc(env, error);
        return 0;
    }
    return reinterpret_cast<jlong>(d);
}

extern "C" JNIEXPORT void JNICALL
Java_cool_linker_cam_uvc_UvcDevice_nativeClose(JNIEnv*, jclass, jlong handle) {
    delete device(handle);
}

// Flattened as [codec, width, height, interval100ns] per entry.
extern "C" JNIEXPORT jintArray JNICALL
Java_cool_linker_cam_uvc_UvcDevice_nativeFormats(JNIEnv* env, jclass, jlong handle) {
    auto formats = device(handle)->formats();
    jintArray out = env->NewIntArray(static_cast<jsize>(formats.size() * 4));
    std::vector<jint> flat;
    flat.reserve(formats.size() * 4);
    for (const auto& f : formats) {
        flat.push_back(static_cast<jint>(f.codec));
        flat.push_back(f.width);
        flat.push_back(f.height);
        flat.push_back(f.interval100ns);
    }
    env->SetIntArrayRegion(out, 0, static_cast<jsize>(flat.size()), flat.data());
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_cool_linker_cam_uvc_UvcDevice_nativeStart(JNIEnv* env, jclass, jlong handle, jint codec,
                                                jint width, jint height, jint fps,
                                                jobject onFrame) {
    // The listener's global ref lives as long as the stream; a shared_ptr in the
    // std::function frees it when the next start() or the destructor drops it.
    auto listener = std::shared_ptr<FrameListener>(new FrameListener(env, onFrame), [](FrameListener* l) {
        JNIEnv* e = nullptr;
        if (gVm->GetEnv(reinterpret_cast<void**>(&e), JNI_VERSION_1_6) == JNI_OK) {
            e->DeleteGlobalRef(l->runnable_);
        }
        delete l;
    });
    std::string error;
    int rc = device(handle)->start(static_cast<Codec>(codec), width, height, fps,
                                   [listener] { (*listener)(); }, error);
    if (rc != 0) throwUvc(env, error);
}

extern "C" JNIEXPORT void JNICALL
Java_cool_linker_cam_uvc_UvcDevice_nativeStop(JNIEnv*, jclass, jlong handle) {
    device(handle)->stop();
}

// dims: [yW, yH, cbW, cbH, crW, crH, 0, 0]; times: [captureNanos, decodeMicros].
extern "C" JNIEXPORT jboolean JNICALL
Java_cool_linker_cam_uvc_UvcDevice_nativeAcquire(JNIEnv* env, jclass, jlong handle,
                                                  jintArray dims, jlongArray times) {
    int d[8];
    int64_t capture = 0;
    int32_t decode = 0;
    if (!device(handle)->acquire(d, capture, decode)) return JNI_FALSE;
    env->SetIntArrayRegion(dims, 0, 8, d);
    jlong t[2] = {capture, decode};
    env->SetLongArrayRegion(times, 0, 2, t);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_cool_linker_cam_uvc_UvcDevice_nativeUpload(JNIEnv*, jclass, jlong handle, jint y, jint cb,
                                                 jint cr) {
    const unsigned int textures[3] = {static_cast<unsigned int>(y), static_cast<unsigned int>(cb),
                                      static_cast<unsigned int>(cr)};
    device(handle)->upload(textures);
}

// [received, dropped, decodeErrors]
extern "C" JNIEXPORT void JNICALL
Java_cool_linker_cam_uvc_UvcDevice_nativeStats(JNIEnv* env, jclass, jlong handle, jlongArray out) {
    auto s = device(handle)->stats();
    jlong v[3] = {s.received, s.dropped, s.decodeErrors};
    env->SetLongArrayRegion(out, 0, 3, v);
}
