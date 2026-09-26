; Hello, world! - the classic first program.
; Linux 'write' system call: rax=1, rdi=file descriptor, rsi=buffer, rdx=length.

section .data
    msg db "Hello, world!", 10    ; 10 = newline character
    len equ $ - msg               ; length = current address - start of msg

section .text
global _start
_start:
    mov rax, 1          ; syscall 1 = write
    mov rdi, 1          ; fd 1 = stdout
    mov rsi, msg        ; address of the string
    mov rdx, len        ; number of bytes to write
    syscall

    mov rax, 60         ; syscall 60 = exit
    xor edi, edi        ; exit code 0
    syscall
