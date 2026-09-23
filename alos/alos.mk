PRODUCT_RELEASE_CONFIG_MAPS += \
    device/lenovo/malbec/alos/release/release_config_map.textproto

PRODUCT_PACKAGES += \
    MalbecDesktopLauncher \
    MalbecAlosLauncherOverlay \
    MalbecAlosSystemUIOverlay

PRODUCT_COPY_FILES += \
    device/lenovo/malbec/alos/permissions/android.hardware.type.pc.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/android.hardware.type.pc.xml

PRODUCT_SYSTEM_PROPERTIES += \
    persist.wm.debug.force_desktop_first_on_default_display_for_testing=true
