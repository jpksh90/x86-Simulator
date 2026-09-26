; Recursive factorial: watch the Stack panel grow with each call,
; showing saved rbp values, saved rbx values and return addresses.

section .text
global _start
_start:
    mov rdi, 10
    call factorial      ; rax = 10!
    mov rdi, rax
    call print_uint     ; prints 3628800

    mov rax, 60
    xor edi, edi
    syscall

; factorial(n): n in rdi, result in rax
factorial:
    push rbp            ; standard stack frame
    mov rbp, rsp
    push rbx            ; rbx is callee-saved, so save it before using it

    mov rbx, rdi        ; remember n
    cmp rdi, 1
    jbe .base           ; if n <= 1, return 1

    lea rdi, [rdi-1]    ; factorial(n - 1)
    call factorial
    imul rax, rbx       ; rax = n * factorial(n - 1)
    jmp .done
.base:
    mov eax, 1
.done:
    pop rbx
    pop rbp
    ret

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
