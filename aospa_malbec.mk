#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

# Inherit from those products. Most specific first.
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit_only.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/aosp_base.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/large_screen_common.mk)

TARGET_NO_TELEPHONY := true

# Inherit from malbec device
$(call inherit-product, device/lenovo/malbec/device.mk)

# Inherit from the AOSPA configuration.
TARGET_USES_BLUR := true
TARGET_USES_PENGUIN_LATINIME := true

$(call inherit-product, vendor/aospa/target/product/aospa-target.mk)

# Settings
PRODUCT_PACKAGES += \
    qti-telephony-utils \
    qti_telephony_utils.xml

PRODUCT_NAME := aospa_malbec
PRODUCT_DEVICE := malbec
PRODUCT_MANUFACTURER := Lenovo
PRODUCT_BRAND := Lenovo
PRODUCT_MODEL := Idea Tab Pro 2

TARGET_SCREEN_WIDTH := 2190
TARGET_CALL_RECORDING_SUPPORTED := false

PRODUCT_SYSTEM_NAME := malbec
PRODUCT_SYSTEM_DEVICE := malbec

PRODUCT_BUILD_PROP_OVERRIDES += \
    BuildDesc="TB390FU-user 16 BQ2A.250610.001-BP2A.250605.031.A3 18.0.10.061_260316 release-keys" \
    BuildFingerprint=Lenovo/TB390FU/TB390FU:16/BQ2A.250610.001-BP2A.250605.031.A3/ZUI_18.0.10.061_260316_ROW:user/release-keys \
    DeviceName=$(PRODUCT_SYSTEM_DEVICE) \
    DeviceProduct=$(PRODUCT_SYSTEM_NAME)

PRODUCT_GMS_CLIENTID_BASE := android-lenovo
