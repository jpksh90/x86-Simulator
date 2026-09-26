; Reading keyboard input with the 'read' system call.
; When the program asks, type your name into the console box and press Enter.

section .rodata
    prompt db "What is your name? "
    prompt_len equ $ - prompt
    greet db "Nice to meet you, "
    greet_len equ $ - greet

section .bss
    name resb 64            ; 64-byte input buffer

section .text
global _start
_start:
    mov rax, 1              ; write(stdout, prompt, prompt_len)
    mov rdi, 1
    mov rsi, prompt
    mov rdx, prompt_len
    syscall

    mov rax, 0              ; read(stdin, name, 64) -> rax = bytes read
    mov rdi, 0
    mov rsi, name
    mov rdx, 64
    syscall
    mov rbx, rax            ; keep the length (rbx survives syscalls)

    mov rax, 1              ; write(stdout, greet, greet_len)
    mov rdi, 1
    mov rsi, greet
    mov rdx, greet_len
    syscall

    mov rax, 1              ; write(stdout, name, length)
    mov rdi, 1
    mov rsi, name
    mov rdx, rbx
    syscall

    mov rax, 60
    xor edi, edi
    syscall
