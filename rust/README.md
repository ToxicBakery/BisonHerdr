# Rust workspace

This workspace has one crate, `herdr-protocol`. The crate is a reference for the Herdr
wire types: Endpoint Generation 1, the wire messages, and the surface delta, reuse, and
scroll codecs. Its tests check the Herdr JSON fixtures and the binary formats.

The app does not use this crate. Gradle does not build it, and no APK contains it. The
Kotlin module `:core:protocol` has the protocol code that the app uses.

## Test the crate

The `rust` job in `.github/workflows/validation.yml` runs these commands on each pull
request. Run them from this directory:

```sh
cargo test --locked -p herdr-protocol
cargo clippy --locked -p herdr-protocol --all-targets -- -D warnings
```

## Removed crates

The workspace had two more crates, `herdr-mobile-core` and `ghostty-vt`. The repository
also had the vendored `libghostty-vt` source in `vendor/`. These parts did not build with
the installed Zig, and no Kotlin class loaded them. Commit
[`8cf8063`](https://github.com/ToxicBakery/BisonHerdr/tree/8cf8063f6ebe0f30a343bf69ab1d95c06e312407)
on `main` still has them. Issue [#22](https://github.com/ToxicBakery/BisonHerdr/issues/22)
holds the work for a real Mosh path.
