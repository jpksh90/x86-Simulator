# Contract: Learner-Facing Help (hover, Reference, status bar, highlighting)

**Consumers**: `ui/Docs.kt`, `ui/AsmEditor.kt`, `ui/MainWindow.kt` (`nextLabel`), and the README.

## H1. Hover and Reference entries

`Docs.lookup(word)` MUST return an entry for each of the following words, and the Reference tab
MUST list them under a "String instructions" heading:

- **Families** (5): `movs`, `stos`, `lods`, `scas`, `cmps`
- **Sized mnemonics** (20): `movsb`, `movsw`, `movsd`, `movsq`, `stosb`, … `cmpsq`. Each resolves
  to its family entry with the size filled in (e.g. `movsw`: "Copy a word from [rsi] to [rdi] …").
- **Prefixes** (5): `rep`, `repe`, `repz`, `repne`, `repnz`

Each instruction entry MUST mention:
- the implicit registers used (RSI and/or RDI, and AL/AX/EAX/RAX where relevant)
- the element size
- "DF = 0 → pointers increase, DF = 1 → decrease (use cld/std)"
- its flags: `none` for movs/stos/lods; `CF OF SF ZF AF PF (like cmp)` for scas/cmps

Each prefix entry MUST give its stop condition, and note that `rep` on `scas`/`cmps` behaves as
`repe`.

## H2. Status bar ("NEXT" line)

When the next instruction is a string instruction, the `NEXT` line MUST append a description of the
next iteration built from live register values:

| Situation | Example text |
|---|---|
| `rep movsb`, RCX = 5, DF = 0 | `copy byte [rsi] → [rdi], then rsi/rdi += 1 · 5 left` |
| `rep stosq`, RCX = 4 | `store rax → qword [rdi], then rdi += 8 · 4 left` |
| `repne scasb`, RCX = −1 | `compare al with byte [rdi] · stops when equal (ZF=1) or rcx = 0` |
| `repe cmpsb`, RCX = 3 | `compare byte [rsi] with byte [rdi] · stops when different (ZF=0) or rcx = 0 · 3 left` |
| any prefix, RCX = 0 | `rcx = 0: the repeat is skipped` |
| unprefixed `lodsb`, DF = 1 | `load byte [rsi] → al, then rsi -= 1` |

- "N left" shows RCX as an unsigned number. When RCX ≥ 2³² the count is omitted (e.g. RCX = −1),
  and the stop condition carries the meaning instead.

## H3. Change highlighting

This needs no new mechanism. After each iteration, the registers panel highlights the changed
RCX/RSI/RDI (and RAX for `lods`) and flags, and the memory panels highlight the bytes written.
This is the existing `CpuSnapshot` / `Memory.writeLog` behavior.

## H4. Syntax highlighting

In the editor, a prefix word is styled as a mnemonic, and so is the string mnemonic that follows
it on the same line.

## H5. Documentation files

- `README.md` → "Supported instructions" gains `movs stos lods scas cmps (b/w/d/q)` and
  `rep repe/repz repne/repnz`. "Design notes and limitations" drops "no string instructions" and
  adds the Out of Scope items from the spec (no `ins`/`outs`, segment overrides or `a32`).
  "Features" mentions iteration-level stepping.
- The example count goes from 8 to 9 in the README.
