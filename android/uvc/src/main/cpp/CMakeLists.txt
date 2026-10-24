cmake_minimum_required(VERSION 3.22)
project(linkcam_uvc C CXX)

# Third-party sources are fetched at configure time, pinned by version and
# SHA-256, so the repository holds only our own code and a build can't silently
# pick up something else.
include(FetchContent)
include(ExternalProject)

# --- libusb (LGPL-2.1) -------------------------------------------------------
# Built as its own shared library, libusb-1.0.so, and linked dynamically. That
# keeps the LGPL's terms simple: anyone can swap in their own build of it.
FetchContent_Declare(libusb
    URL https://github.com/libusb/libusb/releases/download/v1.0.30/libusb-1.0.30.tar.bz2
    URL_HASH SHA256=fea36f34f9156400209595e300840767ab1a385ede1dc7ee893015aea9c6dbaf
    SOURCE_SUBDIR none)  # libusb has no CMake build; we list its sources below.
FetchContent_MakeAvailable(libusb)

add_library(usb-1.0 SHARED
    ${libusb_SOURCE_DIR}/libusb/core.c
    ${libusb_SOURCE_DIR}/libusb/descriptor.c
    ${libusb_SOURCE_DIR}/libusb/hotplug.c
    ${libusb_SOURCE_DIR}/libusb/io.c
    ${libusb_SOURCE_DIR}/libusb/sync.c
    ${libusb_SOURCE_DIR}/libusb/strerror.c
    ${libusb_SOURCE_DIR}/libusb/os/linux_usbfs.c
    ${libusb_SOURCE_DIR}/libusb/os/linux_netlink.c
    ${libusb_SOURCE_DIR}/libusb/os/events_posix.c
    ${libusb_SOURCE_DIR}/libusb/os/threads_posix.c)
target_include_directories(usb-1.0
    PUBLIC ${libusb_SOURCE_DIR}/libusb
    PRIVATE ${libusb_SOURCE_DIR}/android ${libusb_SOURCE_DIR}/libusb/os)
target_compile_options(usb-1.0 PRIVATE -pthread -Wno-everything)
target_link_libraries(usb-1.0 PRIVATE log)

# --- libuvc (BSD-3) ----------------------------------------------------------
# Compiled from its sources rather than its CMake build, which looks for a
# system libusb and libjpeg. We decode MJPEG ourselves, so its JPEG support
# stays off.
FetchContent_Declare(libuvc
    URL https://github.com/libuvc/libuvc/archive/d07de4fa23905ee8cac06f5d24b2a03d42a3b363.tar.gz
    URL_HASH SHA256=65b075a26de6c2e5a12818b68acc437aa2506261e4d9ca9672dfb749cc7aa1e1
    SOURCE_SUBDIR none)
FetchContent_MakeAvailable(libuvc)

set(libuvc_VERSION_MAJOR 0)
set(libuvc_VERSION_MINOR 0)
set(libuvc_VERSION_PATCH 8)
set(libuvc_VERSION 0.0.8)
configure_file(${libuvc_SOURCE_DIR}/include/libuvc/libuvc_config.h.in
    ${CMAKE_CURRENT_BINARY_DIR}/libuvc-config/libuvc/libuvc_config.h)

add_library(uvc STATIC
    ${libuvc_SOURCE_DIR}/src/ctrl.c
    ${libuvc_SOURCE_DIR}/src/ctrl-gen.c
    ${libuvc_SOURCE_DIR}/src/device.c
    ${libuvc_SOURCE_DIR}/src/diag.c
    ${libuvc_SOURCE_DIR}/src/frame.c
    ${libuvc_SOURCE_DIR}/src/init.c
    ${libuvc_SOURCE_DIR}/src/misc.c
    ${libuvc_SOURCE_DIR}/src/stream.c)
target_include_directories(uvc PUBLIC
    ${libuvc_SOURCE_DIR}/include
    ${CMAKE_CURRENT_BINARY_DIR}/libuvc-config)
target_compile_options(uvc PRIVATE -Wno-everything)
target_link_libraries(uvc PUBLIC usb-1.0)
set_target_properties(uvc PROPERTIES POSITION_INDEPENDENT_CODE ON)

# --- libjpeg-turbo (IJG + BSD-3) ---------------------------------------------
# libjpeg-turbo refuses add_subdirectory() on purpose and asks for
# ExternalProject instead, so it gets its own configure with the same NDK
# toolchain. On arm64 it uses NEON. x86_64 (emulator only) would need NASM for
# its SIMD, so it builds without.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(TJ_SIMD ON)
else()
    set(TJ_SIMD OFF)
endif()
set(TJ_PREFIX ${CMAKE_CURRENT_BINARY_DIR}/libjpeg-turbo)
ExternalProject_Add(libjpeg_turbo_ext
    URL https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/3.2.0/libjpeg-turbo-3.2.0.tar.gz
    URL_HASH SHA256=6f30092cef9fb839779646608f4ee14ae3cbac989c47fa05e841b0841f09878e
    INSTALL_DIR ${TJ_PREFIX}
    CMAKE_ARGS
        -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
        -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
        -DANDROID_ABI=${ANDROID_ABI}
        -DANDROID_PLATFORM=${ANDROID_PLATFORM}
        -DCMAKE_BUILD_TYPE=Release
        -DCMAKE_INSTALL_PREFIX=<INSTALL_DIR>
        -DCMAKE_INSTALL_LIBDIR=lib
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON
        -DENABLE_SHARED=OFF
        -DENABLE_STATIC=ON
        -DWITH_TURBOJPEG=ON
        -DWITH_TOOLS=OFF
        -DWITH_TESTS=OFF
        -DWITH_SIMD=${TJ_SIMD}
    BUILD_BYPRODUCTS ${TJ_PREFIX}/lib/libturbojpeg.a)
file(MAKE_DIRECTORY ${TJ_PREFIX}/include)
add_library(turbojpeg STATIC IMPORTED)
set_target_properties(turbojpeg PROPERTIES
    IMPORTED_LOCATION ${TJ_PREFIX}/lib/libturbojpeg.a
    INTERFACE_INCLUDE_DIRECTORIES ${TJ_PREFIX}/include)
add_dependencies(turbojpeg libjpeg_turbo_ext)

# --- Link Cam's driver -------------------------------------------------------
add_library(linkcam_uvc SHARED
    uvc_device.cpp
    jni.cpp)
target_compile_features(linkcam_uvc PRIVATE cxx_std_20)
target_compile_options(linkcam_uvc PRIVATE -Wall -Wextra)
target_link_libraries(linkcam_uvc PRIVATE uvc usb-1.0 turbojpeg GLESv3 log)
