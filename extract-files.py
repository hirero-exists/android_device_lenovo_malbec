#!/usr/bin/env -S PYTHONPATH=../../../tools/extract-utils python3
#
# SPDX-FileCopyrightText: 2026 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

from extract_utils.fixups_blob import (
    BlobFixupCtx,
    File,
    blob_fixup,
    blob_fixups_user_type,
)
from extract_utils.fixups_lib import (
    lib_fixup_remove,
    lib_fixups,
    lib_fixups_user_type,
)
from extract_utils.main import (
    ExtractUtils,
    ExtractUtilsModule,
)

namespace_imports = [
    'hardware/qcom-caf/sm8750',
    'hardware/qcom-caf/wlan',
    'hardware/qcom-caf/common/libqti-perfd-client',
    'vendor/qcom/opensource/commonsys/display',
    'vendor/qcom/opensource/commonsys-intf/display',
    'vendor/qcom/opensource/dataservices',
    'vendor/qcom/opensource/display',
    'device/lenovo/malbec',
]


def lib_fixup_vendor_suffix(lib: str, partition: str, *args, **kwargs):
    return f'{lib}_{partition}' if partition == 'vendor' else None


lib_fixups: lib_fixups_user_type = {
    **lib_fixups,
    # Add malbec-specific -V*-ndk vendor-suffix fixups here as needed.
}

blob_fixups: blob_fixups_user_type = {
    'vendor/etc/perf/perfconfigstore.xml': blob_fixup()
        .regex_replace(
            r'<Prop Name="vendor\.debug\.enable\.memperfd"\s+Value="true" />',
            '<Prop Name="vendor.debug.enable.memperfd"         Value="false" />',
    ),

    # --- graphics.allocator V1 -> V2 (camera stack, adapted from onyx sibling) ---
    (
        'vendor/lib64/camera/components/com.qti.node.dewarp.so',
        'vendor/lib64/hw/com.qti.chi.override.so',
        'vendor/lib64/libcamximageformatutils.so',
        'vendor/lib64/libchifeature2.so',
        'vendor/lib64/libqvrservice.so',
        'vendor/lib64/vendor.qti.hardware.camera.offlinecamera-service-impl.so',
    ): blob_fixup()
        .replace_needed(
            'android.hardware.graphics.allocator-V1-ndk.so',
            'android.hardware.graphics.allocator-V2-ndk.so',
    ),

    # --- sensors V2 -> V3 (blobs built against old sensors AIDL) ---
    (
        'vendor/lib64/hw/camera.qcom.so',
        'vendor/lib64/libgnss.so',
    ): blob_fixup()
        .replace_needed(
            'android.hardware.sensors-V2-ndk.so',
            'android.hardware.sensors-V3-ndk.so',
    ),

    # --- libtinyxml2 -> libtinyxml2-v34 (platform ships the -v34 soname) ---
    (
        'vendor/bin/hw/vendor.qti.camera.provider-service_64',
        'vendor/bin/poweropt-service',
        'vendor/lib64/libcamxcoreutils.so',
        'vendor/lib64/libcamxods.so',
    ): blob_fixup()
        .replace_needed(
            'libtinyxml2.so',
            'libtinyxml2-v34.so',
    ),

    (
        'vendor/bin/hw/vendor.qti.hardware.display.composer-service',
        'vendor/lib64/libsdmclient.so',
    ): blob_fixup()
        .replace_needed(
            'libtinyxml2.so',
            'libtinyxml2-stock.so',
    ),

    # --- graphics.common V5 -> V7 (codec2 core) ---
    'vendor/lib64/libqcodec2_core.so': blob_fixup()
        .replace_needed(
            'android.hardware.graphics.common-V5-ndk.so',
            'android.hardware.graphics.common-V7-ndk.so',
    ),

    # --- sepolicy fixup ---
    'vendor/etc/init/qms.rc': blob_fixup()
    .regex_replace(
        r'(service vendor\.qms /vendor/bin/qms\n)',
        r'\1     user radio\n',
    ),

    'vendor/etc/init/vendor.dpmd.rc': blob_fixup()
    .regex_replace(
        r'(service vendor\.dpmd /vendor/bin/vendor\.dpmd\n)',
        r'\1    user system\n',
    ),

    'vendor/lib64/libaudioserviceexampleimpl.so': blob_fixup()
        .add_needed('libaudioutils_shim.so'),

    # Add more malbec blob fixups here as the build surfaces them.
}  # fmt: skip

module = ExtractUtilsModule(
    'malbec',
    'lenovo',
    blob_fixups=blob_fixups,
    lib_fixups=lib_fixups,
    namespace_imports=namespace_imports,
    # malbec keeps firmware STOCK -- we don't package radio/xbl/tz/etc.
    add_firmware_proprietary_file=False,
)

if __name__ == '__main__':
    utils = ExtractUtils.device(module)
    utils.run()
