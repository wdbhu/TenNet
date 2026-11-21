#!/bin/sh
# 用 Termux 的 clang 编译 Android .so（见 REVERSE-ENGINEERING.md 第一节）
# 用法: sh build.sh <源文件.c> <输出.so>
set -e
USR=${TERMUX_USR:-/data/local/tmp/eta/toolchain/termux/data/data/com.termux/files/usr}
export LD_LIBRARY_PATH="$USR/lib"
"$USR/bin/clang" --target=aarch64-linux-android26 \
  -I"$USR/include" -L"$USR/lib" -B"$USR/bin" \
  -shared -fPIC -O2 -o "$2" "$1" -llog -ldl
echo "OK: $2"
