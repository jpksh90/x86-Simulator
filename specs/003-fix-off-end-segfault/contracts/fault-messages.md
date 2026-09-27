# Contract: Fault Messages and Build Warnings

These are the learner-facing texts. Tests assert on the **key phrases** (in bold), not on the
whole sentence, so the wording can be polished without breaking them. `L` is the 1-based line,
`src` is the source without its comment, and `X` is the RIP in lowercase hex.

## Runtime faults (headline / hint)

| Kind | Example program | Headline | Hint |
|---|---|---|---|
| RAN_PAST_END | `…` / `imul rax, rdi` / `.done:` | `Segmentation fault: `**`ran past the last instruction`**` (line L: src) to RIP=0xX, where there is no code` | `End the program with an exit syscall (mov eax, 60 / syscall) or ret, or add an instruction after label '.done'.` The label clause appears only when a label sits at X |
| EMPTY_LABEL | `jmp .done` … `.done:` at end, not adjacent | `Segmentation fault: line L (src) `**`jumped to label '.done'`**`, but there is `**`no instruction after it`**` (RIP=0xX)` | `Put an instruction after '.done', such as ret or an exit syscall.` `call` uses "called" instead of "jumped to" |
| BAD_RETURN | `push 1` / `ret` | `Segmentation fault: `**`ret on line L`**` returned to RIP=0xX, `**`which is not a return address`** | `The value on top of the stack wasn't pushed by a call. Check that every push has a matching pop before ret.` |
| BAD_TARGET | `mov rax, 5` / `jmp rax` | `Segmentation fault: line L (src) jumped to RIP=0xX, `**`which is not an instruction`** | `Check the address in the register or memory that the jump goes through.` |
| MISALIGNED | `mov rax, _start` / `add rax, 2` / `jmp rax` | `Segmentation fault: line L (src) jumped to RIP=0xX, `**`inside the code but not at the start of an instruction`** | `The nearest instruction is line M at 0xY; jump to a label instead of a computed address.` |
| ENTRY | `.text` whose `_start:` is the last line | `Segmentation fault: the program starts at RIP=0xX, but `**`there is no instruction after _start`** | `Put the program's first instruction after the _start label.` |

Unchanged rules:

- Every headline starts with `Segmentation fault:` and contains `RIP=0x`.
- A `ret` from `_start` to the exit address still exits cleanly with `Exited · code N`.
- Other faults (`Divide error`, memory access, read-only write) keep their current text. They now
  also get the editor highlight.

## Where the texts appear

| Surface | Content |
|---|---|
| `Machine.message` | headline |
| GUI status bar | headline, in `Theme.bad` |
| GUI console | `× headline`, then `× L<line>: <src>`, then `× <hint>` |
| GUI editor | line `L` painted with the fault background (both themes) |
| GUI NEXT label (paused at a bad RIP) | `NEXT  no instruction at RIP=0xX. The next step will fault: <short kind phrase>` |
| CLI stderr | `[sim] <headline> (N instructions)` then `[sim] hint: <hint>` |
| CLI exit code | `139` (unchanged) |

## Build warnings (non-blocking)

| Id | Trigger | Problems/CLI text | Line |
|---|---|---|---|
| W1 | A reachable CFG block ends with exit `"end of code"` | **`execution can run past the last instruction`**`: end with ret, jmp or an exit syscall` | The block's last instruction |
| W2 | A `.text` label equals the end of `.text` | **`label '.done' has no instruction after it`** | The label's definition line |

- GUI: `⚠  L<n>  <text>` in `Theme.warn`. The tab title reads `Problems (1 warning)` or
  `Problems (2 errors, 1 warning)`. The status message reads `Built · N instructions · 1 warning`.
  No tab switch. Run, Step and the rest stay enabled.
- CLI: `warning: line <n>: <text>` on stderr, printed before the program runs. Warnings don't
  change the exit code.
- When both W1 and W2 fall on the same line, W2 wins (one entry per line).
