# Implementation Plan: Application Icon

**Branch**: `008-app-icon` | **Date**: 2026-09-29 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/008-app-icon/spec.md`

## Summary

Make the supplied retro "monitor on an x86-64 chip" icon (`/tmp/x86_64_simulator_retro.ico`, 7
PNG-encoded sizes from 16 to 256 px) x86Learn's application icon. The 7 PNGs are copied byte for
byte into `src/main/resources/icons/`. A small `ui/AppIcon` object loads them (or reports
"unavailable") and picks the right size. The GUI then:
- sets the macOS Dock icon through `java.awt.Taskbar` from `MainWindow.launch()`;
- gives `MainWindow` and `CfgWindow` all 7 images through `setIconImages`, which owned dialogs
  inherit;
- shows a zoom- and HiDPI-aware icon in the About dialog.

The headless CLI never reaches any of this code. See [research.md](research.md).

## Technical Context

**Language/Version**: Kotlin 2.2 on the JVM (JDK 21 toolchain, runs on JDK 17+)

**Primary Dependencies**: Swing + FlatLaf (existing). JDK only for the new code:
`javax.imageio.ImageIO`, `java.awt.Taskbar`, `java.awt.Window.setIconImages`. No new dependencies.

**Storage**: 7 PNG files packaged as classpath resources (`/icons/x86learn-<size>.png`)

**Testing**: `kotlin.test` on JUnit Platform (`./gradlew test`), headless; `UiSnapshot` renders
for the About dialog in both themes and at the smallest and largest zoom

**Target Platform**: Desktop GUI on macOS, Windows and Linux (X11/XWayland)

**Project Type**: Desktop application with a headless CLI (single Gradle project)

**Performance Goals**: Icon loading adds < 50 ms to GUI start-up (7 PNGs, ~58 KB total, loaded
lazily once). Zero added cost to the CLI.

**Constraints**: No change to CLI behaviour or output. A missing icon must never block start-up.
Pixels are unchanged. Must work under `java.awt.headless=true` in tests.

**Scale/Scope**: 1 new UI source file (~70 lines), 1 new test file, 7 resource files, small edits
to `MainWindow.kt`, `CfgWindow.kt` and `README.md`

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Assessment | Status |
|---|---|---|
| **I. Architectural Fidelity** | No change to instruction, flag, syscall, fault or assembler behaviour. There is nothing to verify against the Intel/AMD manuals. One new README limitation is added: on some Wayland desktops, and before launch, a generic icon may appear (research R2, R3). | ✅ Pass |
| **II. Learner-First Clarity** | The icon is not an instruction, register or syscall, so no hover or Reference entry is needed. Themes and zoom: the About icon must be checked in Dark and Light themes and at 80% and 250% zoom (quickstart §5, contract D6). There are no new errors: a missing icon falls back silently. | ✅ Pass |
| **III. Test-Backed Semantics** | New `AppIconTest` checks that all 7 resources load with the right size and alpha, the `bestFor` size table, and the missing-resource fallback. Execution semantics don't change, so no test-first semantics work applies. `./gradlew test` must stay green. | ✅ Pass |
| **IV. Headless Core, Observing UI** | `AppIcon` lives in `ui/`. `cpu/`, `asm/`, `analysis/`, `disasm/` and `Machine.kt` are untouched. `Taskbar` is used only in `MainWindow.launch()`, so the CLI (`run`, `disasm`) behaves identically and never loads it. The UI stays an observer, with no execution logic added. | ✅ Pass |
| **V. Deterministic, Reversible Execution** | No machine state is involved. There is nothing to record or undo. | ✅ Pass (N/A) |
| **Tech & Scope Constraints** | No new runtime dependency (JDK only). `./gradlew run` and `installDist` keep working with no setup, since resources go in the jar. No network or native binaries. The memory layout is untouched. | ✅ Pass |
| **Workflow & Quality Gates** | README updated (Project layout, icon note, limitation). UiSnapshot renders in both themes. The work runs on the `008-app-icon` branch in one focused commit ("Add the x86Learn application icon"). No example program is needed, since this is not a learner capability. | ✅ Pass |

**Result**: all gates pass. There are no violations, so Complexity Tracking is empty.

**Post-design re-check (after Phase 1)**: the design in [data-model.md](data-model.md) and
[contracts/icon-display.md](contracts/icon-display.md) adds no core dependency on UI. It keeps
the CLI path untouched (D8) and has a headless-testable loader and size picker. It still covers
both themes and all zoom levels (D6). **All gates still pass.**

## Project Structure

### Documentation (this feature)

```text
specs/008-app-icon/
├── plan.md                  # This file
├── research.md              # Phase 0: storage format, Dock/taskbar APIs, About icon, fallback, licence
├── data-model.md            # Phase 1: the Application icon entity, bestFor() table, consumers
├── quickstart.md            # Phase 1: validation guide
├── contracts/
│   └── icon-display.md      # Phase 1: resource paths and where the icon must appear
├── checklists/
│   └── requirements.md      # from /speckit-specify
└── tasks.md                 # Phase 2 (/speckit-tasks, not created here)
```

### Source Code (repository root)

```text
src/main/resources/icons/
├── x86learn-16.png          # NEW, byte-identical PNG entries from the supplied .ico
├── x86learn-24.png
├── x86learn-32.png
├── x86learn-48.png
├── x86learn-64.png
├── x86learn-128.png
└── x86learn-256.png

src/main/kotlin/x86sim/ui/
├── AppIcon.kt               # NEW: load 7 images (or "unavailable"), bestFor(px), installOn(window),
│                            #      installDockIcon(), aboutIcon(): Icon scaled by Theme.z and device scale
├── MainWindow.kt            # EDIT: launch() → AppIcon.installDockIcon(); init → AppIcon.installOn(this);
│                            #       showAbout() → 5-arg showMessageDialog with AppIcon.aboutIcon()
└── CfgWindow.kt             # EDIT: init → AppIcon.installOn(this)

src/test/kotlin/x86sim/
└── AppIconTest.kt           # NEW: resources load with size and alpha; bestFor table; missing → unavailable, no throw

README.md                    # EDIT: Project layout (icons/), icon note, Linux/Wayland + pre-launch limitation
THIRD_PARTY_NOTICES.md       # EDIT only if the user says the artwork is third-party (research R6)
```

**Structure Decision**: this is the existing single Gradle project. All new code is in the `ui`
package, because only the GUI shows the icon (Principle IV). The loader and size picker are plain
functions that tests can call without a display. `Main.kt`, the CLI and all core packages are left
unchanged.

## Implementation notes for /speckit-tasks

1. Extract the 7 PNG entries from `/tmp/x86_64_simulator_retro.ico` into
   `src/main/resources/icons/` without re-encoding, and verify the checksums (quickstart §1).
   Do this first, while `/tmp` still has the file.
2. Write `AppIconTest` first (it fails with no `AppIcon`), then `AppIcon`.
3. Wire up `MainWindow.launch()`, `MainWindow.init`, `showAbout()` and `CfgWindow.init`.
4. Render UiSnapshot `ABOUT` in both themes and at 80% and 250% zoom, then review the PNGs.
5. Ask the user about the artwork's origin, then update README (and THIRD_PARTY_NOTICES if
   needed).
6. `./gradlew test`, check the CLI (quickstart §3), and do the GUI check on macOS (§4).

## Complexity Tracking

No constitution violations, so nothing to justify.
