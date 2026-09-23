#!/usr/bin/env bash
set -euo pipefail

: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK r28.2}"
toolchain="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64"
export ANDROID_NDK_ROOT="${ANDROID_NDK_HOME}"
export PATH="${toolchain}/bin:${PATH}"
export LIBLZMA_NO_PKG_CONFIG=1
export PKG_CONFIG_ALLOW_CROSS=1
export OPENSSL_NO_PKG_CONFIG=1
# This library runs inside BONELAB. Keep Rust panics unwindable so the C ABI
# can catch them instead of aborting the entire game process.
export CARGO_PROFILE_RELEASE_PANIC=unwind
export CC_aarch64_linux_android="aarch64-linux-android29-clang"
export CXX_aarch64_linux_android="aarch64-linux-android29-clang++"
export AR_aarch64_linux_android="llvm-ar"
export RANLIB_aarch64_linux_android="llvm-ranlib"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="aarch64-linux-android29-clang++"

eval "$(python3 scripts/fetch_rusty_v8_android.py | grep '^export ' | sed 's/^export //')"
export RUSTY_V8_ARCHIVE RUSTY_V8_SRC_BINDING_PATH
python3 scripts/check_v8_sandbox.py "${RUSTY_V8_ARCHIVE}"

builtins="$(find "${toolchain}" -name 'libclang_rt.builtins-aarch64-android.a' -print -quit)"
libcxx_static="${toolchain}/sysroot/usr/lib/aarch64-linux-android/libc++_static.a"
libcxxabi_static="${toolchain}/sysroot/usr/lib/aarch64-linux-android/libc++abi.a"

test -n "${builtins}" || { echo "compiler-rt builtins archive not found" >&2; exit 1; }
test -f "${libcxx_static}" || { echo "ARM64 libc++_static.a not found at ${libcxx_static}" >&2; exit 1; }

rustflags="-Clink-arg=${libcxx_static} -Clink-arg=${builtins}"
if [ -n "${libcxxabi_static}" ]; then
  rustflags="${rustflags} -Clink-arg=${libcxxabi_static}"
fi
rustflags="${rustflags} -Clink-arg=-Wl,-z,max-page-size=16384 -Clink-arg=-Wl,-z,common-page-size=16384"
# RUSTFLAGS overrides codex-rs/.cargo/config.toml for this build so the
# normal Termux -lc++_shared flag is not inherited by the single-file Quest cdylib.
export RUSTFLAGS="${rustflags}"

(
  cd codex-rs
  rustup run 1.95.0 cargo build     --target aarch64-linux-android     --release     -p codex-app-server-android
)

output="codex-rs/target/aarch64-linux-android/release/libcodex_app_server.so"
llvm-readelf -d "${output}"
if llvm-readelf -d "${output}" | grep -q 'libc++_shared.so'; then
  echo "unexpected dependency: libc++_shared.so" >&2
  exit 1
fi

echo "Built: ${output}"
