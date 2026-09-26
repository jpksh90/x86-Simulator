; Fill an array in .bss with the first 20 Fibonacci numbers, then print them.
; Open the Memory tab and jump to 'fib' to watch the array fill up.

section .bss
    fib resq 20             ; 20 qwords (8 bytes each)

section .text
global _start
_start:
    mov qword [fib], 0
    mov qword [fib+8], 1
    mov rcx, 2              ; index of the next element
.next:
    mov rax, [fib + rcx*8 - 8]     ; fib[i-1]
    add rax, [fib + rcx*8 - 16]    ; + fib[i-2]
    mov [fib + rcx*8], rax         ; fib[i] = sum
    inc rcx
    cmp rcx, 20
    jl .next

    xor ebx, ebx            ; print loop; rbx isn't changed by print_uint or syscalls
.print:
    mov rdi, [fib + rbx*8]
    call print_uint
    inc rbx
    cmp rbx, 20
    jl .print

    mov rax, 60
    xor edi, edi
    syscall

; ------------------------------------------------------------
; print_uint: prints the unsigned number in rdi, then a newline.
; Builds the digits backwards in a buffer on the stack.
; ------------------------------------------------------------
print_uint:
    push rbp
    mov rbp, rsp
    sub rsp, 32             ; room for up to 20 digits + newline
    mov rax, rdi
    lea rsi, [rbp-1]        ; last byte of the buffer
    mov byte [rsi], 10      ; newline
    mov rcx, 10
.digit:
    xor edx, edx            ; rdx:rax is the dividend, so clear rdx
    div rcx                 ; rax = rax / 10, rdx = rax % 10
    add dl, '0'             ; remainder -> ASCII digit
    dec rsi
    mov [rsi], dl
    test rax, rax
    jnz .digit
    mov rax, 1              ; sys_write
    mov rdi, 1              ; stdout
    mov rdx, rbp
    sub rdx, rsi            ; length = end of buffer - first digit
    syscall
    leave                   ; mov rsp, rbp / pop rbp
    ret
