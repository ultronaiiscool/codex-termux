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

For the embedded Android build, local shell and filesystem tools run directly
inside the host application's Android sandbox. They do not re-enter a standalone
`codex` executable. When a normal Codex permission profile requests a desktop
sandbox backend, the embedded Android path uses the host application's sandbox
as the isolation boundary. This fallback is Android-only; it does not weaken
desktop or standalone Termux behavior.

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

The WebSocket transport implements this endpoint. A successful response means
the listener is accepting requests; `codex_app_server_is_running()` only means
the native server thread has not exited. If readiness does not arrive, call
`CodexNative.LastError()` and treat a non-empty result as the startup failure.

After readiness succeeds, connect the existing App Server protocol client to:

```text
ws://127.0.0.1:4500
```

Authentication can be performed over the normal App Server protocol with
`account/login/start` and `type: "chatgptDeviceCode"`; the returned
verification URL and one-time code can be shown by BoneAI without relying on a
desktop browser launcher. API-key and token-based login methods exposed by the
same protocol remain available; credentials belong in the caller-provided
`codex_home` or protocol request and must never be compiled into the library.

On shutdown, disconnect the WebSocket client and call:

```csharp
CodexNative.codex_app_server_stop();
```

The return codes are `0` for success, `1` when `start` finds an existing server,
and negative values for invalid arguments or native startup/shutdown failures.
Use `CodexNative.LastError()` for diagnostic text and
`codex_app_server_clear_error()` after handling it.

## Android loading

`UserLibs` is for managed assemblies and is not, by itself, a native Android
library search path. The `.so` must be loaded by Android's native linker (for
example by the host/mod loader's supported native-library mechanism, or an
explicit native load performed by the host) before the C# P/Invoke calls are
made. For explicit loading, the Java/Android side can call `System.load()` with
the absolute path to an app-accessible copy of `libcodex_app_server.so`; after
that, `[DllImport("codex_app_server")]` resolves the stable C ABI. The exact
copy/load hook is loader-specific and must use a mechanism supported by the
installed BONELAB/LemonLoader environment.

## CI artifact

The `quest-codex-app-server-arm64` Actions artifact contains
`quest-codex-app-server-arm64.zip` and an unpacked `quest-codex-native/`
directory with:

```text
libcodex_app_server.so
libcodex_app_server.so.sha256
codex_app_server.h
CodexNative.cs
README.md
licenses/
```

## Verification levels

- **COMPILES**: GitHub Actions cross-build completes for `aarch64-linux-android`.
- **ANDROID ELF CHECKED**: CI verifies ARM64 ELF metadata, C ABI exports,
  16 KiB-compatible link alignment flags, and no `libc++_shared.so` dependency.
- **RUNS ON ANDROID**: requires an Android ARM64 runtime test.
- **TESTED ON QUEST**: requires a physical Quest 3/3S test and is not claimed by CI.
