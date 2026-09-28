# 01_hello.asm translated to GNU assembler Intel syntax (clang's integrated assembler has no NASM mode).
.intel_syntax noprefix

.data
msg:    .ascii "Hello, world!\n"
        .set len, . - msg

.text
.globl _start
_start:
        mov rax, 1
        mov rdi, 1
        lea rsi, [rip + msg]
        mov rdx, offset len
        syscall

        mov rax, 60
        xor edi, edi
        syscall
