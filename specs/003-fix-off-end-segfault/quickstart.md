# Quickstart: Validating Fault Explanations

**Feature**: [spec.md](./spec.md) | **Contract**: [contracts/fault-messages.md](./contracts/fault-messages.md)

## Prerequisites

- JDK 17+. Run from the repo root.
- Create the sample programs in a scratch folder:

```bash
mkdir -p /tmp/x86f && cd /tmp/x86f
printf '_start:\n mov rdi, 2\n mov rsi, 10\n mov rax, 1\n.loop:\n test rsi, rsi\n jz .done\n imul rax, rdi\n.done:\n' > past_end.asm
printf '_start:\n push 1\n ret\n' > bad_ret.asm
printf '_start:\n mov rax, 5\n jmp rax\n' > bad_jmp.asm
printf '_start:\n jmp .done\n nop\n.done:\n' > empty_label.asm
```

## 1. Automated checks (must pass)

```bash
./gradlew test
```

| Test (`src/test/kotlin/x86sim/`) | Proves |
|---|---|
| `FaultExplainerTest`: one test per `FaultKind`, asserting on the key phrases, the line and the RIP | FR-002 to FR-005, SC-001 |
| `FaultExplainerTest`: the exact reported program gives `RIP=0x401018` + "ran past the last instruction" + line 8 | FR-012 regression |
| `FaultExplainerTest`: `ret` from `_start` still exits with code 0 | FR-010 |
| `ProgramLintTest`: W1 and W2 fire, an exit syscall or `ret` ending gives no warning, and all 9 examples give zero warnings | FR-009, SC-004 |
| `StepBackTest`: fault → Step Back → Step gives an equal `FaultReport`; the snapshot includes `lastRip` | FR-008 |
| `SimulatorTest` `faults are reported`: assertion updated to the new BAD_RETURN phrase | existing coverage |

## 2. Headless CLI

```bash
./gradlew installDist
for f in past_end bad_ret bad_jmp empty_label; do
  build/install/x86learn/bin/x86learn run /tmp/x86f/$f.asm; echo "exit=$?"; done
```

**Expected**: each program prints the `warning:` lines listed in the contract (the first and last
programs only), then a `[sim] Segmentation fault: …` headline naming a line and RIP, a
`[sim] hint: …` line, and `exit=139`. `past_end` reports `RIP=0x401018` and line 8. Also run
`run --example 01_hello`: it prints no warnings and gives the same output as before.

## 3. GUI

`./gradlew run`, then open `past_end.asm`:

1. **Build**: the Problems tab title shows `(2 warnings)` and stays unselected. Run is enabled.
2. **Step** until RIP leaves the code: the NEXT label shows the "no instruction at RIP=0x401018"
   preview.
3. **Step** once more: the state is Faulted, the status bar shows the headline, the console shows
   the headline, source line and hint, and line 8 (`imul`) is highlighted.
4. **Step Back**: the highlight clears and the state is Paused. Step again and you get the identical
   message.
5. Repeat step 3 in the light and dark themes and at 80% and 250% zoom (the `UiSnapshot` helper can
   render these). The highlight is visible, and the status bar doesn't overflow the window.
6. Open `02_loop_sum`, then Build: no warnings.
