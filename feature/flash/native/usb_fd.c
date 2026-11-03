/* SPDX-License-Identifier: GPL-2.0-or-later
 * Link-time USB bridge. Only the UsbManager-authorized descriptor is visible.
 * The parent retains its UsbDeviceConnection until the child has exited.
 */
#include <libusb.h>
#include <stdlib.h>
#include <stdint.h>

int __real_libusb_init(libusb_context **ctx);
int __real_libusb_open(libusb_device *dev, libusb_device_handle **handle);
void __real_libusb_exit(libusb_context *ctx);
static libusb_context *selected_context;
static libusb_device_handle *enumeration_handle;

static int selected_fd(void) {
    const char *value = getenv("TOOLKIT_USB_FD");
    return value ? atoi(value) : -1;
}

int __wrap_libusb_init(libusb_context **ctx) {
    /* Android denies /dev/bus/usb discovery. Never fall back to another probe. */
    libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    int result = __real_libusb_init(ctx);
    if (!result) selected_context = *ctx;
    return result;
}

ssize_t __wrap_libusb_get_device_list(libusb_context *ctx, libusb_device ***list) {
    if (selected_fd() < 0) return LIBUSB_ERROR_ACCESS;
    if (!enumeration_handle) {
        int result = libusb_wrap_sys_device(ctx, (intptr_t)selected_fd(), &enumeration_handle);
        if (result < 0) return result;
    }
    *list = calloc(2, sizeof(libusb_device *));
    if (!*list) return LIBUSB_ERROR_NO_MEM;
    (*list)[0] = libusb_ref_device(libusb_get_device(enumeration_handle));
    return 1;
}

int __wrap_libusb_open(libusb_device *dev, libusb_device_handle **handle) {
    if (!enumeration_handle || libusb_get_device(enumeration_handle) != dev)
        return LIBUSB_ERROR_ACCESS;
    return libusb_wrap_sys_device(selected_context, (intptr_t)selected_fd(), handle);
}

void __wrap_libusb_exit(libusb_context *ctx) {
    if (enumeration_handle) { libusb_close(enumeration_handle); enumeration_handle = NULL; }
    selected_context = NULL;
    __real_libusb_exit(ctx);
}
