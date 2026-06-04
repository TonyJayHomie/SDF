#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
OUT=..

build_one() {
    local name="$1"     # e.g. uart0_pa9pa10
    local use_usart1="$2"  # 0 or 1
    arm-none-eabi-gcc \
        -mcpu=cortex-m3 -mthumb -nostdlib -nostartfiles \
        -DUSE_USART1=${use_usart1} \
        -Wl,-Ttext=0x20000000 \
        -o "${name}.elf" uart_dumper.S
    arm-none-eabi-objcopy -O binary "${name}.elf" "${OUT}/${name}.bin"
    echo "built: ${OUT}/${name}.bin ($(stat -c '%s' "${OUT}/${name}.bin") bytes)"
}

build_one uart0_pa9pa10 0
build_one uart1_pa2pa3  1
