# Native retro-game playback: the portable libretro host plus the Android JNI
# glue that feeds it a Surface, an input mask, and an AudioTrack.
#
# Ported from Moonfin (https://github.com/Moonfin-Client/Moonfin-Core, GPL-2.0),
# native/libretro_host and android/app/src/main/cpp. The host is kept verbatim
# so upstream fixes can be diffed straight in; only the JNI export prefix and
# the log tags differ.
cmake_minimum_required(VERSION 3.22)
project(wholphin_libretro C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_library(libretro_host STATIC libretro_host/libretro_host.c)
target_include_directories(libretro_host PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/libretro_host)
# The per-frame pixel conversion and audio mixing are far too slow at -O0, so
# debug builds still compile the host optimized. The flag lands after the
# toolchain's -O0 and the last -O wins.
target_compile_options(libretro_host PRIVATE "$<$<CONFIG:Debug>:-O2>" -Wall -Wextra)
find_package(Threads REQUIRED)
target_link_libraries(libretro_host PUBLIC Threads::Threads ${CMAKE_DL_LIBS})

add_library(wholphin_libretro SHARED native_game_jni.c egl_backend.c)
target_compile_options(wholphin_libretro PRIVATE "$<$<CONFIG:Debug>:-O2>")
# EGL and GLESv2 back the hardware-render path. GLESv3 entry points are
# resolved through eglGetProcAddress instead of linked, so one binary runs on
# a GLES2-only device and a GLES3 one alike.
target_link_libraries(wholphin_libretro PRIVATE libretro_host android log EGL GLESv2)
