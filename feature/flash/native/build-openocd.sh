#!/usr/bin/env bash
# Run in MSYS2 or Linux with autoconf, automake, libtool, make and pkg-config.
set -euo pipefail
work=$1
ndk=$2
project=$3
host=${4:-windows-x86_64}
export PATH="$ndk/toolchains/llvm/prebuilt/$host/bin:$PATH"
for abi in arm64-v8a armeabi-v7a; do
    triple=aarch64-linux-android
    flags=""
    if [ "$abi" = armeabi-v7a ]; then triple=armv7a-linux-androideabi; flags="-mthumb"; fi
    export CC="clang --target=${triple}24"
    export CXX="clang++ --target=${triple}24"
    export AR=llvm-ar RANLIB=llvm-ranlib STRIP=llvm-strip
    export CFLAGS="-O2 -fPIC $flags"
    export LDFLAGS="-Wl,-z,max-page-size=16384"
    prefix="$work/install-$abi"
    export PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig"
    mkdir -p "$work/usb-$abi" "$work/ocd-$abi" "$prefix" "$project/feature/flash/src/main/jniLibs/$abi"
    cd "$work/libusb-1.0.27"
    if [ ! -f configure ]; then ./bootstrap.sh; fi
    cd "$work/usb-$abi"
    if [ ! -f Makefile ]; then
        "$work/libusb-1.0.27/configure" --host="$triple" --prefix="$prefix" --disable-udev --disable-shared --enable-static
    fi
    make -j8
    make install
    cd "$work/ocd-$abi"
    if [ ! -f jimtcl/Makefile ] || [ ! -f src/jtag/drivers/libjaylink/Makefile ] ||
        ! grep -q '^#define BUILD_CMSIS_DAP_USB 1$' config.h; then
        "$work/openocd-0.12.0/configure" --host="$triple" --prefix="$prefix" \
            --enable-stlink --enable-jlink --enable-hla --enable-cmsis-dap-v2 \
            --disable-doxygen-html --disable-doxygen-pdf \
            --disable-werror --disable-shared --enable-static --disable-capstone --disable-jim-regexp \
            LIBUSB1_LIBS="$prefix/lib/libusb-1.0.a" LIBUSB1_CFLAGS="-I$prefix/include/libusb-1.0"
    fi
    "$ndk/toolchains/llvm/prebuilt/$host/bin/clang" --target="${triple}24" -I"$prefix/include/libusb-1.0" \
        -O2 -fPIE -c "$project/feature/flash/native/usb_fd.c" -o usb_fd.o
    if ! grep -q 'TOOLKIT_USB_BRIDGE' Makefile; then
        echo '# TOOLKIT_USB_BRIDGE' >> Makefile
        echo "src_openocd_LDADD += $work/ocd-$abi/usb_fd.o -Wl,--wrap=libusb_init,--wrap=libusb_open,--wrap=libusb_get_device_list,--wrap=libusb_exit" >> Makefile
    fi
    make -j8 bin_PROGRAMS=src/openocd LDFLAGS="-pie -Wl,-z,max-page-size=16384"
    dest="$project/feature/flash/src/main/jniLibs/$abi"
    cp src/openocd "$dest/libopenocd.so"
    llvm-strip "$dest/libopenocd.so"
    clang --target="${triple}24" -O2 -shared -fPIC -Wl,-z,max-page-size=16384 \
        "$project/feature/flash/native/process.c" -o "$dest/libflashprocess.so"
done
mkdir -p "$project/feature/flash/src/main/assets/openocd"
cp -R "$work/openocd-0.12.0/tcl/"* "$project/feature/flash/src/main/assets/openocd/"
