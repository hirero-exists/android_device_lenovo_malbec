#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

# Inherit from those products. Most specific first.
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit_only.mk)

$(call inherit-product, $(SRC_TARGET_DIR)/product/aosp_base.mk)

# Inherit some common Lineage stuff.
$(call inherit-product, vendor/lineage/config/common_full_tablet_wifionly.mk)

# Inherit from malbec device
$(call inherit-product, device/lenovo/malbec/device.mk)

PRODUCT_NAME := lineage_malbec
PRODUCT_DEVICE := malbec
PRODUCT_MANUFACTURER := Lenovo
PRODUCT_BRAND := Lenovo
PRODUCT_MODEL := Idea Tab Pro 2

PRODUCT_SYSTEM_NAME := malbec
PRODUCT_SYSTEM_DEVICE := malbec

PRODUCT_BUILD_PROP_OVERRIDES += \
    BuildDesc="TB390FU-user 16 BQ2A.250610.001-BP2A.250605.031.A3 18.0.10.061_260316 release-keys" \
    BuildFingerprint=Lenovo/TB390FU/TB390FU:16/BQ2A.250610.001-BP2A.250605.031.A3/ZUI_18.0.10.061_260316_ROW:user/release-keys
    DeviceName=$(PRODUCT_SYSTEM_DEVICE) \
    DeviceProduct=$(PRODUCT_SYSTEM_NAME)

PRODUCT_GMS_CLIENTID_BASE := android-lenovo
