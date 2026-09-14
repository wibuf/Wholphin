# Native libretro host

Retro-game playback for Wholphin+. The emulator cores themselves are
downloaded at runtime from the libretro buildbot; this directory holds the
host that drives them.

Ported from [Moonfin](https://github.com/Moonfin-Client/Moonfin-Core)
(GPL-2.0), which also wrote the Moonbase server plugin the games library
comes from:

| Here | Moonfin |
|------|---------|
| `libretro_host/` | `native/libretro_host/` (verbatim, log tag renamed) |
| `native_game_jni.c` | `android/app/src/main/cpp/native_game_jni.c` (JNI package renamed) |
| `egl_backend.{c,h}` | `android/app/src/main/cpp/egl_backend.{c,h}` (log tag renamed) |

Keep the host verbatim so upstream fixes can be diffed straight in.
