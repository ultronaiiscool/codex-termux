# Third-party license notice

`libcodex_app_server.so` statically links Rust crates and native libraries from
the repository's locked dependency graph, including V8 and Android NDK runtime
components. Those components retain their respective upstream copyrights and
licenses. The authoritative dependency versions are recorded in
`codex-rs/Cargo.lock`, and their license metadata is available from the
corresponding crate/source distributions.

This milestone bundle includes the repository's `LICENSE` and `NOTICE`. It does
not yet contain a generated, dependency-by-dependency license inventory. Before
redistributing the binary outside development/testing, generate and review a
complete third-party notice set for the locked dependency graph.
