; Local variables on the stack.
; Step through 'compute' and watch the Stack Memory panel: 'sub rsp, 64' reserves
; the frame (cells show "?" = reserved but not written), then each local is filled in.
;
; Frame of compute, one 64-bit word per cell (rbp-relative):
;   rbp+8           return address          pushed by 'call'
;   rbp+0           saved rbp               pushed by 'push rbp'
;   rbp-8           long  total
;   rbp-16          char  flag  (byte at rbp-16) and  int count  (dword at rbp-12)
;   rbp-40..rbp-24  long  nums[3]           an array: 3 consecutive words
;   rbp-56..rbp-48  struct point { long x; long y; }
;   rbp-64          (unused padding - stays "?")

section .text
global _start
_start:
    mov rdi, 5
    call compute              ; rax = compute(5)
    mov rdi, rax
    call print_uint           ; prints 60

    mov rax, 60
    xor edi, edi
    syscall

; long compute(long n)
compute:
    push rbp                  ; save the caller's frame pointer
    mov rbp, rsp              ; rbp = base of our frame
    sub rsp, 64               ; reserve 8 words for locals

    mov qword [rbp-8], 0      ; long total = 0
    mov dword [rbp-12], 3     ; int count = 3   (upper half of the word at rbp-16)
    mov byte [rbp-16], 1      ; char flag = 1   (lowest byte of the word at rbp-16)

    mov [rbp-40], rdi         ; nums[0] = n
    lea rax, [rdi*2]
    mov [rbp-32], rax         ; nums[1] = 2n
    lea rax, [rdi+rdi*2]
    mov [rbp-24], rax         ; nums[2] = 3n

    mov qword [rbp-56], 10    ; point.x = 10
    mov qword [rbp-48], 20    ; point.y = 20

    xor ecx, ecx              ; for (i = 0; i < count; i++) total += nums[i]
.sum:
    mov rax, [rbp-40 + rcx*8]
    add [rbp-8], rax
    inc ecx
    cmp ecx, [rbp-12]
    jl .sum

    lea rdi, [rbp-56]         ; pass &point (a pointer into our own frame)
    call point_sum
    add [rbp-8], rax          ; total += point.x + point.y

    mov rax, [rbp-8]          ; return total
    leave                     ; mov rsp, rbp + pop rbp: frees all locals at once
    ret

; long point_sum(struct point *p)  ->  p->x + p->y
point_sum:
    push rbp
    mov rbp, rsp
    mov rax, [rdi]            ; p->x   (rdi points into compute's frame)
    add rax, [rdi+8]          ; p->y
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
