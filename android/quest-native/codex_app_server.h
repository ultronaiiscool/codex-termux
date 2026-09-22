#pragma once

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

int codex_app_server_start(const char* bind_address, const char* codex_home);
int codex_app_server_stop(void);
int codex_app_server_is_running(void);
size_t codex_app_server_last_error(char* buffer, size_t buffer_len);
const char* codex_app_server_version(void);
void codex_app_server_clear_error(void);

#ifdef __cplusplus
}
#endif
