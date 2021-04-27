# Kotlin bindings for the Rust SDK crypto layer.

## Prerequisites

### Rust

To build the bindings [Rust] will be needed it can be either installed using an
OS specific package manager or directly with the provided [installer](https://rustup.rs/).


### Android NDK

The Android NDK will be required as well, it can be installed either through
Android Studio or directly using an [installer](https://developer.android.com/ndk/downloads).

### Uniffi

The bindings are using [uniffi] to generate the C translation layer between Rust
and Kotlin. Uniffi is a Rust project and can be installed with our freshly
installed Rust setup using:

```
$ cargo install uniffi_bindgen
```

### Configuring Rust for cross compilation

First we'll need to install the Rust target for our desired Android architecture,
for example:

```
# rustup target add aarch64-linux-android
```

This will add support to cross-compile for the aarch64-linux-android target,
Rust supports many different [targets], you'll have to make sure to pick the
right one for your device or emulator.

After this is done, we'll have to configure [Cargo] to use the correct linker
for our target. Cargo is configured using a TOML file that will be found in
`%USERPROFILE%\.cargo\config.toml` on Windows or `$HOME/.cargo/config` on Unix
platforms. More details and configuration options for Cargo can be found in the
official docs over [here](https://doc.rust-lang.org/cargo/reference/config.html).

```
[target.aarch64-linux-android]
ar = "NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/ar"
linker = "NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android30-clang"
```

## Building

A `Makefile` is provided that builds and installs the dynamic library into the
appropriate target specific `jniLibs` directory. But before we can get started
we'll need to tweak our Rust setup to allow cross compilation.

To enable cross compilation fro `olm-sys` which builds our libolm C library
we'll need to set the `ANDROID_NDK` environment variable to the location of our
Android NDK installation.

```
$ export ANDROID_NDK=$HOME/Android/Sdk/ndk/22.0.7026061/
```

### Makefile build

After the prerequisites have been installed and the environment variable has
been set a build for the `aarch64` target can be build using:

```
make aarch64
```

### Manual build

If the `Makefile` doesn't work on your system, the bindings can built for the `aarch64`
target with:

```
$ cargo build --target aarch64-linux-android
```

After that, a dynamic library can be found in the `target/aarch64-linux-android/debug` directory.
The library will be called `libmatrix_crypto.so` and needs to be renamed and
copied into the `jniLibs` directory:

```
$ cp target/aarch64-linux-android/debug/libmatrix_crypto.so \
     ../matrix-sdk-android/src/main/jniLibs/aarch64/libuniffi_olm.so
```

[Rust]: https://www.rust-lang.org/
[installer]: https://rustup.rs/
[targets]: https://doc.rust-lang.org/nightly/rustc/platform-support.html
[Cargo]: https://doc.rust-lang.org/cargo/
[uniffi]: https://github.com/mozilla/uniffi-rs/
