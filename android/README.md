# Codex App Server for Meta Quest

The Quest runtime target is a single Android ARM64 native library:

```text
libcodex_app_server.so
```

It does **not** require Termux, a separate `codex` executable, a separate
`codex-code-mode-host` process, root, ADB, or a PC while BONELAB is running.

## Runtime shape

```text
BONELAB / BoneAI.dll
        |
        | P/Invoke
        v
libcodex_app_server.so
        |
        +-- Codex App Server
        +-- WebSocket listener on 127.0.0.1
        +-- Codex protocol + thread state
        +-- auth/config/state DB
        +-- in-process V8 Code Mode
        |
        v
      OpenAI
```

The library only permits loopback WebSocket binds. BoneAI supplies an absolute,
writable app-private directory as `codex_home`; the native library does not
hard-code another Android package name.

## Build

Use Android NDK r28.2 and Rust 1.95.0:

```bash
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
bash android/build-quest-native.sh
```

Output:

```text
codex-rs/target/aarch64-linux-android/release/libcodex_app_server.so
```

The build statically links the NDK C++ runtime so the deliverable does not need
`libc++_shared.so`. CI fails if `DT_NEEDED` still contains that library.

## C ABI

See `android/quest-native/codex_app_server.h`.

Typical startup from C#:

```csharp
int rc = CodexNative.codex_app_server_start(
    "127.0.0.1:4500",
    codexHomeAbsolutePath);
```

Then poll:

```text
http://127.0.0.1:4500/readyz
```

and connect the existing App Server protocol client to:

```text
ws://127.0.0.1:4500
```

Authentication can be performed over the normal App Server protocol with
`account/login/start` and `type: "chatgptDeviceCode"`; the returned
verification URL and one-time code can be shown by BoneAI without relying on a
desktop browser launcher.

## Android loading

`UserLibs` is for managed assemblies and is not, by itself, a native Android
library search path. The `.so` must be loaded by Android's native linker (for
example by the host/mod loader's supported native-library mechanism, or an
explicit native load performed by the host) before the C# P/Invoke calls are
made.

## Verification levels

- **COMPILES**: GitHub Actions cross-build completes for `aarch64-linux-android`.
- **ANDROID ELF CHECKED**: CI verifies ARM64 ELF metadata, C ABI exports,
  16 KiB-compatible link alignment flags, and no `libc++_shared.so` dependency.
- **RUNS ON ANDROID**: requires an Android ARM64 runtime test.
- **TESTED ON QUEST**: requires a physical Quest 3/3S test and is not claimed by CI.
