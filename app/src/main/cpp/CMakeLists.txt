cmake_minimum_required(VERSION 3.22.1)
project(soundscape C CXX)

# OpenAL Soft (git submodule, pinned to a release tag) renders the soundscape's 3D mix with its
# built-in HRTF — per-ear HRIR convolution including the interaural time delay — plus distance
# attenuation and Doppler for the moving sources. It is used ONLY in loopback mode
# (ALC_SOFT_loopback): we pull rendered stereo frames and write them to our own AudioTrack, so the
# output follows the Accessibility stream like every other sound in the app (see AudioClient) — no
# OpenAL output backend is ever opened, hence all of them are off.
#
# Kept a separate shared library (libopenal.so) on purpose: OpenAL Soft is LGPL-2.1, and dynamic
# linking is what keeps that license from extending to the app's own code.
set(ALSOFT_UTILS OFF CACHE BOOL "" FORCE)
set(ALSOFT_EXAMPLES OFF CACHE BOOL "" FORCE)
set(ALSOFT_TESTS OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL_CONFIG OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL_HRTF_DATA OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL_AMBDEC_PRESETS OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL_EXAMPLES OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL_UTILS OFF CACHE BOOL "" FORCE)
set(ALSOFT_UPDATE_BUILD_VERSION OFF CACHE BOOL "" FORCE)
set(ALSOFT_EAX OFF CACHE BOOL "" FORCE)
set(ALSOFT_EMBED_HRTF_DATA ON CACHE BOOL "" FORCE)
foreach(backend PIPEWIRE PULSEAUDIO JACK COREAUDIO OBOE OPENSL PORTAUDIO SDL3 SDL2 WAVE)
    set(ALSOFT_BACKEND_${backend} OFF CACHE BOOL "" FORCE)
endforeach()
set(LIBTYPE SHARED)
add_subdirectory(openal-soft EXCLUDE_FROM_ALL)

add_library(soundscape SHARED soundscape_jni.cpp)
target_link_libraries(soundscape PRIVATE OpenAL::OpenAL log)
# 16 KB page alignment (Play requires it for Android 15+ devices). NDK r28+ already defaults to
# this; stated explicitly so an older NDK can't silently produce 4 KB-aligned libraries.
target_link_options(soundscape PRIVATE "-Wl,-z,max-page-size=16384")
target_link_options(OpenAL PRIVATE "-Wl,-z,max-page-size=16384")
