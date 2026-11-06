bash "$(gettop)/device/lenovo/malbec-kernel/setup-kernel-headers.sh" "$(gettop)"
ln -sfnT usr/include "$(gettop)/kernel/lenovo/malbec/kernel-headers"
