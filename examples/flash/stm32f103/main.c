#include <stdint.h>

#define REG32(address) (*(volatile uint32_t *)(address))

int main(void) {
    REG32(0x40021018) |= (1u << 4); /* RCC APB2: GPIOC */
    REG32(0x40011004) = (REG32(0x40011004) & ~(15u << 20)) | (2u << 20);
    for (;;) {
        REG32(0x4001100c) ^= (1u << 13);
        for (volatile uint32_t ticks = 0; ticks < 500000; ++ticks) { __asm volatile ("nop"); }
    }
}
