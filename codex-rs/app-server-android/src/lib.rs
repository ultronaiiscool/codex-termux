#![cfg_attr(not(target_os = "android"), allow(dead_code))]

#[cfg(not(target_os = "android"))]
compile_error!("codex-app-server-android must be built for an Android target");

use std::ffi::CStr;
use std::ffi::c_char;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::ptr;
use std::sync::Mutex;
use std::sync::OnceLock;
use std::thread::JoinHandle;

use codex_app_server::AppServerEmbeddedOptions;
use codex_app_server::AppServerRuntimeOptions;
use codex_app_server::AppServerTransport;
use codex_app_server::AppServerWebsocketAuthSettings;
use codex_app_server::CodeModeHostTransport;
use codex_app_server::PluginStartupTasks;
use codex_app_server::RemoteControlStartupMode;
use codex_async_utils::THREAD_STACK_SIZE_BYTES;
use codex_arg0::Arg0DispatchPaths;
use codex_config::LoaderOverrides;
use codex_protocol::protocol::SessionSource;
use codex_utils_absolute_path::AbsolutePathBuf;
use codex_utils_cli::CliConfigOverrides;
use tokio_util::sync::CancellationToken;

const OK: i32 = 0;
const ALREADY_RUNNING: i32 = 1;
const INVALID_ARGUMENT: i32 = -1;
const START_FAILED: i32 = -2;
const INTERNAL_ERROR: i32 = -3;

const DEFAULT_BIND_ADDRESS: &str = "127.0.0.1:4500";
const VERSION_CSTR: &str = concat!(env!("CARGO_PKG_VERSION"), "\0");

struct ServerInstance {
    shutdown: CancellationToken,
    thread: JoinHandle<()>,
}

#[derive(Default)]
struct NativeState {
    instance: Option<ServerInstance>,
    last_error: String,
}

static STATE: OnceLock<Mutex<NativeState>> = OnceLock::new();

fn state() -> &'static Mutex<NativeState> {
    STATE.get_or_init(|| Mutex::new(NativeState::default()))
}

fn set_last_error(message: impl Into<String>) {
    let mut state = state().lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    state.last_error = message.into();
}

fn clear_last_error() {
    let mut state = state().lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    state.last_error.clear();
}

unsafe fn optional_c_string(pointer: *const c_char) -> Result<Option<String>, String> {
    if pointer.is_null() {
        return Ok(None);
    }
    let value = unsafe { CStr::from_ptr(pointer) }
        .to_str()
        .map_err(|_| "argument is not valid UTF-8".to_string())?;
    Ok(Some(value.to_string()))
}

fn parse_bind_address(raw: Option<String>) -> Result<SocketAddr, String> {
    let raw = raw
        .as_deref()
        .unwrap_or(DEFAULT_BIND_ADDRESS)
        .strip_prefix("ws://")
        .unwrap_or(raw.as_deref().unwrap_or(DEFAULT_BIND_ADDRESS));

    let address = raw
        .parse::<SocketAddr>()
        .map_err(|error| format!("invalid bind address {raw:?}: {error}"))?;
    if !address.ip().is_loopback() {
        return Err("Quest embedding only permits loopback App Server listeners".to_string());
    }
    Ok(address)
}

fn prepare_codex_home(raw: String) -> Result<AbsolutePathBuf, String> {
    if raw.trim().is_empty() {
        return Err("codex_home must not be empty".to_string());
    }
    let path = PathBuf::from(raw);
    std::fs::create_dir_all(&path)
        .map_err(|error| format!("failed to create CODEX_HOME {}: {error}", path.display()))?;
    let canonical = path
        .canonicalize()
        .map_err(|error| format!("failed to canonicalize CODEX_HOME {}: {error}", path.display()))?;
    AbsolutePathBuf::from_absolute_path(canonical)
        .map_err(|error| format!("invalid CODEX_HOME: {error}"))
}

fn reap_finished_locked(state: &mut NativeState) {
    let finished = state
        .instance
        .as_ref()
        .is_some_and(|instance| instance.thread.is_finished());
    if finished
        && let Some(instance) = state.instance.take()
    {
        let _ = instance.thread.join();
    }
}

fn start_impl(bind_address: *const c_char, codex_home: *const c_char) -> Result<i32, String> {
    let bind_address = unsafe { optional_c_string(bind_address) }?;
    let codex_home = unsafe { optional_c_string(codex_home) }?
        .ok_or_else(|| "codex_home is required".to_string())?;

    let bind_address = parse_bind_address(bind_address)?;
    let codex_home = prepare_codex_home(codex_home)?;

    let mut native_state = state().lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    reap_finished_locked(&mut native_state);
    if native_state.instance.is_some() {
        return Ok(ALREADY_RUNNING);
    }

    let shutdown = CancellationToken::new();
    let server_shutdown = shutdown.clone();

    let thread = std::thread::Builder::new()
        .name("codex-app-server".to_string())
        .stack_size(THREAD_STACK_SIZE_BYTES)
        .spawn(move || {
            let server_result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                let runtime = tokio::runtime::Builder::new_multi_thread()
                    .enable_all()
                    .thread_stack_size(THREAD_STACK_SIZE_BYTES)
                    .build()
                    .map_err(|error| format!("failed to create Tokio runtime: {error}"))?;

                runtime
                    .block_on(codex_app_server::run_main_embedded(
                        Arg0DispatchPaths::default(),
                        CliConfigOverrides::default(),
                        LoaderOverrides::default(),
                        false,
                        false,
                        AppServerTransport::WebSocket { bind_address },
                        SessionSource::VSCode,
                        AppServerWebsocketAuthSettings::default(),
                        AppServerRuntimeOptions {
                            code_mode_host_transport: CodeModeHostTransport::InProcess,
                            plugin_startup_tasks: PluginStartupTasks::Start,
                            remote_control_startup_mode:
                                RemoteControlStartupMode::DisabledEphemeral,
                            install_shutdown_signal_handler: false,
                            managed_daemon: false,
                        },
                        AppServerEmbeddedOptions {
                            codex_home: Some(codex_home),
                            shutdown_token: Some(server_shutdown),
                        },
                    ))
                    .map(|_| ())
                    .map_err(|error| format!("Codex App Server exited with error: {error}"))
            }));

            match server_result {
                Ok(Ok(())) => {}
                Ok(Err(error)) => set_last_error(error),
                Err(_) => set_last_error("panic in embedded Codex App Server thread"),
            }
        })
        .map_err(|error| format!("failed to create App Server thread: {error}"))?;

    native_state.instance = Some(ServerInstance { shutdown, thread });
    native_state.last_error.clear();
    Ok(OK)
}

fn stop_impl() -> Result<i32, String> {
    let instance = {
        let mut native_state = state().lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        reap_finished_locked(&mut native_state);
        native_state.instance.take()
    };

    let Some(instance) = instance else {
        return Ok(OK);
    };

    instance.shutdown.cancel();
    instance
        .thread
        .join()
        .map_err(|_| "App Server thread panicked during shutdown".to_string())?;
    Ok(OK)
}

/// Start the in-process Codex App Server.
///
/// `bind_address` may be null for the default 127.0.0.1:4500, or contain
/// either "127.0.0.1:PORT" or "ws://127.0.0.1:PORT". Non-loopback addresses
/// are rejected. `codex_home` is required and may point at any caller-owned,
/// writable app-private directory.
#[unsafe(no_mangle)]
pub extern "C" fn codex_app_server_start(
    bind_address: *const c_char,
    codex_home: *const c_char,
) -> i32 {
    match std::panic::catch_unwind(|| start_impl(bind_address, codex_home)) {
        Ok(Ok(code)) => code,
        Ok(Err(error)) => {
            set_last_error(error);
            INVALID_ARGUMENT
        }
        Err(_) => {
            set_last_error("panic while starting Codex App Server");
            INTERNAL_ERROR
        }
    }
}

/// Request a graceful shutdown and wait for the embedded server thread.
#[unsafe(no_mangle)]
pub extern "C" fn codex_app_server_stop() -> i32 {
    match std::panic::catch_unwind(stop_impl) {
        Ok(Ok(code)) => code,
        Ok(Err(error)) => {
            set_last_error(error);
            START_FAILED
        }
        Err(_) => {
            set_last_error("panic while stopping Codex App Server");
            INTERNAL_ERROR
        }
    }
}

/// Returns 1 while the App Server thread is alive, otherwise 0.
#[unsafe(no_mangle)]
pub extern "C" fn codex_app_server_is_running() -> i32 {
    let mut native_state = state().lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    reap_finished_locked(&mut native_state);
    i32::from(native_state.instance.is_some())
}

/// Copies the latest native error into `buffer` as UTF-8 plus NUL.
///
/// Returns the required buffer size including the NUL terminator. Passing a
/// null buffer or zero length is a size query.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn codex_app_server_last_error(
    buffer: *mut c_char,
    buffer_len: usize,
) -> usize {
    let message = {
        let native_state = state().lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        native_state.last_error.clone()
    };
    let bytes = message.as_bytes();
    let required = bytes.len().saturating_add(1);

    if buffer.is_null() || buffer_len == 0 {
        return required;
    }

    let payload_len = bytes.len().min(buffer_len.saturating_sub(1));
    unsafe {
        ptr::copy_nonoverlapping(bytes.as_ptr(), buffer.cast::<u8>(), payload_len);
        *buffer.add(payload_len) = 0;
    }
    required
}

/// Returns a process-lifetime NUL-terminated version string.
#[unsafe(no_mangle)]
pub extern "C" fn codex_app_server_version() -> *const c_char {
    VERSION_CSTR.as_ptr().cast()
}

/// Clears the stored diagnostic error string.
#[unsafe(no_mangle)]
pub extern "C" fn codex_app_server_clear_error() {
    clear_last_error();
}
