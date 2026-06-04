/*
 * uart_dumper.S - SRAM-resident flash dumper for GD32F130C8T6
 *
 * Reads the entire internal flash (64 KiB at 0x08000000) and streams it
 * out a USART at 115200 baud, 8N1.
 *
 * Built twice with different -D flags:
 *   USART0  -> PA9 (TX) / PA10 (RX)  -> uart0_pa9pa10.bin
 *   USART1  -> PA2 (TX) / PA3  (RX)  -> uart1_pa2pa3.bin
 *
 * Assumed clock state at entry: HSI 8 MHz with no PLL (cold reset state),
 * so PCLK = 8 MHz. With USART_BRR = 0x45 (decimal 69) we get
 *   baud = 8_000_000 / 69 = ~115942 ~= 115200 (within 0.6%).
 *
 * Load address: 0x20000000
 * Entry point : 0x20000000 (vector table thumb-bit handled by OpenOCD `resume`)
 *
 * Memory map used:
 *   RCC base          0x40021000
 *     RCC_AHBENR      0x14   bit 17 = GPIOA en
 *     RCC_APB2ENR     0x18   bit 14 = USART0 en
 *     RCC_APB1ENR     0x1C   bit 17 = USART1 en
 *   GPIOA base        0x48000000
 *     GPIOA_MODER     0x00
 *     GPIOA_AFRL      0x20
 *     GPIOA_AFRH      0x24
 *   USART0 base       0x40013800
 *   USART1 base       0x40004400
 *     USART_BRR       0x0C
 *     USART_CR1       0x00   (UE | TE)
 *     USART_ISR       0x1C   TXE bit 7
 *     USART_TDR       0x28
 *
 * Frame format streamed out (little-endian):
 *   magic   : 4 bytes  "HBDP"
 *   length  : 4 bytes  flash length in bytes (=0x10000)
 *   payload : <length> bytes raw flash contents
 *   crc32   : 4 bytes  IEEE 802.3 CRC32 over payload only
 *   trailer : 4 bytes  "DONE"
 */

    .syntax unified
    .cpu cortex-m3
    .thumb

    .equ FLASH_BASE,    0x08000000
    .equ FLASH_LEN,     0x00010000   /* 64 KiB */

    .equ RCC_BASE,      0x40021000
    .equ RCC_AHBENR,    0x14
    .equ RCC_APB2ENR,   0x18
    .equ RCC_APB1ENR,   0x1C

    .equ GPIOA_BASE,    0x48000000
    .equ GPIOA_MODER,   0x00
    .equ GPIOA_AFRL,    0x20
    .equ GPIOA_AFRH,    0x24

    .equ USART_BRR,     0x0C
    .equ USART_CR1,     0x00
    .equ USART_ISR,     0x1C
    .equ USART_TDR,     0x28

#ifndef USE_USART1
#define USE_USART1 0
#endif

#if USE_USART1
    .equ USART_BASE,    0x40004400   /* USART1 */
#else
    .equ USART_BASE,    0x40013800   /* USART0 */
#endif

    .section .text
    .global _start
    .thumb_func
_start:
    /* ---------- enable clocks ---------- */
    ldr  r0, =RCC_BASE

    /* AHBENR |= (1<<17)  -> GPIOA */
    ldr  r1, [r0, #RCC_AHBENR]
    movs r2, #1
    lsls r2, r2, #17
    orrs r1, r1, r2
    str  r1, [r0, #RCC_AHBENR]

#if USE_USART1
    /* APB1ENR |= (1<<17) -> USART1 */
    ldr  r1, [r0, #RCC_APB1ENR]
    movs r2, #1
    lsls r2, r2, #17
    orrs r1, r1, r2
    str  r1, [r0, #RCC_APB1ENR]
#else
    /* APB2ENR |= (1<<14) -> USART0 */
    ldr  r1, [r0, #RCC_APB2ENR]
    movs r2, #1
    lsls r2, r2, #14
    orrs r1, r1, r2
    str  r1, [r0, #RCC_APB2ENR]
#endif

    /* ---------- configure GPIOA pins ---------- */
    ldr  r0, =GPIOA_BASE
    ldr  r1, [r0, #GPIOA_MODER]

#if USE_USART1
    /* PA2/PA3 -> AF (10b): clear bits [5:4] and [7:6], then set 10 in each */
    ldr  r2, =0x000000F0     /* mask bits 4..7 */
    bics r1, r1, r2
    ldr  r2, =0x000000A0     /* 10 10 in those slots */
    orrs r1, r1, r2
#else
    /* PA9/PA10 -> AF (10b): bits [19:18] and [21:20] */
    ldr  r2, =0x003C0000     /* mask bits 18..21 */
    bics r1, r1, r2
    ldr  r2, =0x00280000     /* 10 in PA9, 10 in PA10 */
    orrs r1, r1, r2
#endif
    str  r1, [r0, #GPIOA_MODER]

#if USE_USART1
    /* AFRL: PA2 AF1, PA3 AF1 -> nibbles 2 and 3 = 0x1 each */
    ldr  r1, [r0, #GPIOA_AFRL]
    ldr  r2, =0x0000FF00
    bics r1, r1, r2
    ldr  r2, =0x00001100
    orrs r1, r1, r2
    str  r1, [r0, #GPIOA_AFRL]
#else
    /* AFRH: PA9 AF1 (nibble 1), PA10 AF1 (nibble 2) */
    ldr  r1, [r0, #GPIOA_AFRH]
    ldr  r2, =0x00000FF0
    bics r1, r1, r2
    ldr  r2, =0x00000110
    orrs r1, r1, r2
    str  r1, [r0, #GPIOA_AFRH]
#endif

    /* ---------- configure USART ---------- */
    ldr  r4, =USART_BASE

    /* BRR = 69 (~115200 @ 8 MHz HSI) */
    movs r1, #69
    str  r1, [r4, #USART_BRR]

    /* CR1 = UE | TE = (1<<0) | (1<<3) = 0x09 */
    movs r1, #0x09
    str  r1, [r4, #USART_CR1]

    /* small settling delay */
    ldr  r5, =0x4000
1:  subs r5, r5, #1
    bne  1b

    /* ---------- send "HBDP" ---------- */
    ldr  r0, =magic_hbdp
    movs r1, #4
    bl   send_buf

    /* ---------- send length (little-endian 0x00010000) ---------- */
    ldr  r0, =length_word
    movs r1, #4
    bl   send_buf

    /* ---------- stream flash + update CRC ---------- */
    ldr  r6, =FLASH_BASE
    ldr  r7, =FLASH_LEN
    ldr  r8, =0xFFFFFFFF        /* CRC32 accumulator */

stream_loop:
    ldrb r0, [r6]
    /* send byte */
    bl   send_byte
    /* CRC32 update */
    bl   crc32_update
    adds r6, r6, #1
    subs r7, r7, #1
    bne  stream_loop

    /* finalize CRC: invert */
    mvn  r8, r8

    /* push CRC to a scratch in SRAM and send */
    ldr  r0, =crc_word
    str  r8, [r0]
    movs r1, #4
    bl   send_buf

    /* send "DONE" */
    ldr  r0, =trailer_done
    movs r1, #4
    bl   send_buf

hang:
    b    hang


/* ------------------------------------------------------------------
 * send_byte: send byte in r0.b out USART
 * clobbers: r1, r2
 * ------------------------------------------------------------------ */
    .thumb_func
send_byte:
    ldr  r2, =USART_BASE
2:  ldr  r1, [r2, #USART_ISR]
    movs r3, #0x80              /* TXE = bit 7 */
    tst  r1, r3
    beq  2b
    str  r0, [r2, #USART_TDR]
    bx   lr

/* ------------------------------------------------------------------
 * send_buf: r0 = pointer, r1 = length
 * ------------------------------------------------------------------ */
    .thumb_func
send_buf:
    push {r4, r5, lr}
    mov  r4, r0
    mov  r5, r1
3:  cmp  r5, #0
    beq  4f
    ldrb r0, [r4]
    bl   send_byte
    adds r4, r4, #1
    subs r5, r5, #1
    b    3b
4:  pop  {r4, r5, pc}

/* ------------------------------------------------------------------
 * crc32_update: r0 = new byte (low 8 bits), r8 = current CRC
 * Standard IEEE 802.3 reflected CRC32, polynomial 0xEDB88320
 * clobbers r1, r2, r3
 * ------------------------------------------------------------------ */
    .thumb_func
crc32_update:
    eors r8, r8, r0             /* crc ^= byte */
    movs r1, #8                 /* 8 bit iterations */
5:  movs r2, #1
    ands r2, r2, r8             /* lsb = crc & 1 */
    lsrs r8, r8, #1             /* crc >>= 1 */
    cmp  r2, #0
    beq  6f
    ldr  r3, =0xEDB88320
    eors r8, r8, r3
6:  subs r1, r1, #1
    bne  5b
    bx   lr


/* ------------------------------------------------------------------
 * data
 * ------------------------------------------------------------------ */
    .align 2
magic_hbdp:
    .ascii "HBDP"

length_word:
    .word FLASH_LEN

trailer_done:
    .ascii "DONE"

    .align 2
crc_word:
    .word 0
