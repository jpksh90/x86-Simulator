# Tasks: Application Icon

**Input**: Design documents from `/specs/008-app-icon/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/icon-display.md](contracts/icon-display.md), [quickstart.md](quickstart.md)

**Tests**: Included. Constitution Principle III requires unit tests for new behaviour, and the plan
asks for `AppIconTest` to be written before `AppIcon`.

**Organization**: Tasks are grouped by user story. US1 = the icon shown by the OS (P1, MVP).
US2 = the icon in the About dialog (P2).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (US1, US2)

## Path Conventions

Single Gradle project: main code `src/main/kotlin/x86sim/`, resources `src/main/resources/`, tests
`src/test/kotlin/x86sim/`.

---

## Phase 1: Setup

**Purpose**: Branch, and get the icon out of `/tmp` into the project before `/tmp` is cleared.

- [X] T001 Create and switch to branch `008-app-icon` from `main` (`git checkout -b 008-app-icon`). Carry the untracked `specs/007-windows-syscalls/` and `specs/008-app-icon/` along, but commit only 008 files on this branch.
- [X] T002 Extract the 7 PNG entries of `/tmp/x86_64_simulator_retro.ico` **byte for byte (no decoding or re-encoding)** into `src/main/resources/icons/x86learn-16.png`, `-24`, `-32`, `-48`, `-64`, `-128` and `-256.png`. ICO layout: a 6-byte header (`reserved u16, type u16, count u16`, little-endian), then `count` 16-byte entries (`width u8` with 0 meaning 256, `height u8`, … `bytesInRes u32` at entry offset 8, `imageOffset u32` at entry offset 12). Put any helper script in the session scratchpad, not in the repo. If `/tmp/x86_64_simulator_retro.ico` is gone, stop and ask the user for the file.
- [X] T003 Verify quickstart §1: the SHA-256 of each file in `src/main/resources/icons/` equals the SHA-256 of the matching PNG entry in the `.ico` (7 of 7), and `file src/main/resources/icons/*.png` reports `N x N, 8-bit/color RGBA` for each size N.

---

## Phase 2: Foundational (loader and size picker, needed by US1 and US2)

**Purpose**: The headless-testable core of `AppIcon`. It needs no windows.

**⚠️ CRITICAL**: US1 and US2 both depend on this phase.

- [X] T004 Write `src/test/kotlin/x86sim/AppIconTest.kt` (kotlin.test, runs headless). It must fail before T005:
  - (a) `AppIcon.images` holds 7 images in the order `16, 24, 32, 48, 64, 128, 256`. Image *n* is `sizes[n]` × `sizes[n]` and `colorModel.hasAlpha()` (data-model V1 and V2).
  - (b) `AppIcon.bestFor(px)` gives the side of the image it returns, per the data-model table: 1→16, 16→16, 17→24, 24→24, 51→64, 64→64, 128→128, 160→256, 320→256.
  - (c) `AppIcon.load { null }` (a lookup that finds nothing) returns an empty list without throwing, and so does a lookup whose 5th resource is missing ("the app never uses a partial set").
  - (d) `bestFor` on an empty list returns `null`.
- [X] T005 Create `src/main/kotlin/x86sim/ui/AppIcon.kt`, `object AppIcon` in package `x86sim.ui`, with a KDoc line that matches the style of `ui/` files:
  - `val sizes = listOf(16, 24, 32, 48, 64, 128, 256)`.
  - `internal fun load(open: (String) -> java.io.InputStream?): List<BufferedImage>` reads `/icons/x86learn-<size>.png` for every size with `ImageIO.read`. It returns `emptyList()` if **any** resource is missing, is unreadable or throws, and never throws itself.
  - `val images: List<BufferedImage> by lazy { load { AppIcon::class.java.getResourceAsStream(it) } }`.
  - `internal fun bestFor(px: Int, from: List<BufferedImage> = images): BufferedImage?` returns the smallest image with `width >= px`, else the largest; `null` if the list is empty.
- [X] T006 Run `./gradlew test --tests 'x86sim.AppIconTest'`. It must pass. Then run `./gradlew test`, which must also pass.

**Checkpoint**: icons load, sizes are picked correctly, and a missing icon is handled.

---

## Phase 3: User Story 1 — Recognise the app by its icon while it runs (Priority: P1) 🎯 MVP

**Goal**: the Dock, ⌘-Tab, taskbar, Alt-Tab, window switchers and title bars show the x86Learn icon
instead of the Java icon (contract D1–D5, D7–D8).

**Independent Test**: `./gradlew run` on macOS: the Dock and ⌘-Tab show the monitor-on-chip icon.
On Windows or Linux: the title bar, taskbar and switcher show it. `x86learn run --example 01_hello`
shows no window or Dock icon.

- [X] T007 [US1] In `src/main/kotlin/x86sim/ui/AppIcon.kt` add `fun installOn(w: java.awt.Window)`. It calls `w.iconImages = images` only when `images` is non-empty, so owned dialogs inherit the icon (research R3).
- [X] T008 [US1] In `src/main/kotlin/x86sim/ui/AppIcon.kt` add `fun installDockIcon()`. If `images` is non-empty, `Taskbar.isTaskbarSupported()` is true and `Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)` is true, it sets `Taskbar.getTaskbar().iconImage = images.last()` (256 px). Catch `UnsupportedOperationException` and `SecurityException` and ignore them silently: no output (FR-006, contract D7).
- [X] T009 [US1] In `src/main/kotlin/x86sim/ui/MainWindow.kt`: in `companion object … fun launch()` (around line 916), call `AppIcon.installDockIcon()` after the `System.setProperty` lines and before `SwingUtilities.invokeLater`. In `init` (around line 148, after `title = AppInfo.NAME`), call `AppIcon.installOn(this)`. Do **not** touch `Main.kt`: the CLI paths must never reach `AppIcon` (FR-007, contract D8).
- [X] T010 [P] [US1] In `src/main/kotlin/x86sim/ui/CfgWindow.kt` `init` (around line 56), call `AppIcon.installOn(this)` so the Control Flow Graph window has the icon too (contract D4).
- [X] T011 [US1] Run the manual check in quickstart §4 on macOS with `./gradlew run`. The Dock and ⌘-Tab show the icon. Open View → Control Flow Graph, File → Open…, File → Disassemble Binary… and Help → About: no generic Java cup anywhere. Note that the Windows and Linux checks (D2, D3) still need a machine with that OS, and report that to the user instead of claiming them.

**Checkpoint**: US1 is complete, and the MVP can ship on its own.

---

## Phase 4: User Story 2 — See the icon inside the app (Priority: P2)

**Goal**: the About x86Learn dialog shows the icon beside the name and version, sharp in both
themes and at 80%–250% zoom (contract D6).

**Independent Test**: Render UiSnapshot `ABOUT` in Dark and Light themes and at 80% and 250% zoom.
The icon replaces the "i", is sharp, and blends into the background.

- [X] T012 [US2] Extend `src/test/kotlin/x86sim/AppIconTest.kt` (it must fail before T013). `AppIcon.aboutPixels(logical = 64, deviceScale = s)` returns `ceil(logical * s)` device pixels, and `bestFor` on that value picks 64 for (64, 1.0), 128 for (64, 2.0), 256 for (160, 1.0) (250% zoom) and 64 for (51, 1.0) (80% zoom).
- [X] T013 [US2] In `src/main/kotlin/x86sim/ui/AppIcon.kt` add `internal fun aboutPixels(logical: Int, deviceScale: Double): Int` and `fun aboutIcon(): javax.swing.Icon?`:
  - Return `null` when `images` is empty.
  - Otherwise return an `Icon` whose `getIconWidth`/`getIconHeight` are `Theme.z(64)`, computed when the dialog opens.
  - `paintIcon` reads `(g as Graphics2D).transform.scaleX` as the device scale, picks `bestFor(aboutPixels(size, scale))`, and draws it into a `size`×`size` box with `RenderingHints.VALUE_INTERPOLATION_BICUBIC` on a `g.create()` copy, which it disposes afterwards. No background fill or border (FR-008).
- [X] T014 [US2] In `src/main/kotlin/x86sim/ui/MainWindow.kt`, change `showAbout()` (around line 394) to the 5-argument `JOptionPane.showMessageDialog(this, JLabel(AppInfo.aboutHtml(UIScale.scale(360))), "About ${AppInfo.NAME}", JOptionPane.INFORMATION_MESSAGE, AppIcon.aboutIcon())`. With a `null` icon, JOptionPane falls back to the standard "i" (contract D7).
- [X] T015 [P] [US2] In `src/test/kotlin/x86sim/UiSnapshot.kt`, let the 6th argument (zoom steps) be negative: `n > 0` → `repeat(n) { Theme.zoomIn() }`, `n < 0` → `repeat(-n) { Theme.zoomOut() }`. Only zooming in is possible today, and the 80% check needs zooming out. Then update the memory note `/Users/jyp/.claude/projects/-Users-jyp-x86-Simulator/memory/uisnapshot-run.md`, which says zooming down is impossible.
- [X] T016 [US2] Run `./gradlew testClasses installDist`, then UiSnapshot `ABOUT` (see the memory note `uisnapshot-run.md` for the classpath) in 4 combinations: dark/0, light/0, light/+N to reach 250%, dark/−N to reach 80%. Save them in the session scratchpad and Read each PNG. The icon must be left of the text, not blurry at 250%, and on a transparent background in both themes. Fix and re-render if not.

**Checkpoint**: US1 and US2 both work independently.

---

## Phase 5: Polish & Cross-Cutting Concerns

- [X] T017 Ask the user once: "Did you create the icon artwork, or did it come from someone else (and under what licence)?" If it is third-party, add an entry to `THIRD_PARTY_NOTICES.md` and to the README "Third-party software" list with the name, author and licence the user gives. Never guess an author (research R6, FR-009).
- [X] T018 Update `README.md`:
  - Add `resources/icons/  app icon (16–256 px)` to "Project layout" (or extend the `src/main/resources/examples/` line to match its style).
  - Add a one-line mention of the app icon near the start of "Features" or "Help while you work", written in the README's existing voice.
  - Add under "Design notes and limitations": "The app sets its icon when it starts, so the Dock or taskbar may show the generic Java icon for a moment, and some Wayland Linux desktops, which look icons up from an installed launcher, may keep showing a generic one" (research R2, R3).
- [X] T019 Run quickstart §6, the missing-icon fallback: temporarily move `src/main/resources/icons/` out of the tree, `./gradlew run`, open About and confirm there's no error and nothing printed. Then restore the folder and check `git status` shows no change to it.
- [X] T020 Run quickstart §3, the unchanged CLI: `./gradlew installDist`, then `build/install/x86learn/bin/x86learn run --example 01_hello` and `… disasm src/test/resources/binaries/hello-elf.o | head`. The output must equal `main`'s, with no Dock icon appearing.
- [X] T021 Run `./gradlew test`. All tests pass (constitution gate).
- [X] T022 Check that nothing in `src/` references `/tmp` (`grep -rn "/tmp" src/` gives nothing; SC-005). Then commit only the 008 files (`src/main/resources/icons/`, `AppIcon.kt`, `AppIconTest.kt`, `MainWindow.kt`, `CfgWindow.kt`, `UiSnapshot.kt`, `README.md`, `THIRD_PARTY_NOTICES.md` if changed, and `specs/008-app-icon/`) on branch `008-app-icon` with the message "Add the x86Learn application icon", ending with the attribution trailer. Do not stage `specs/007-windows-syscalls/` or the untracked `.specify/`/`.claude/` files.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (T001–T003)**: first. T002 is urgent because `/tmp` may be cleared.
- **Foundational (T004–T006)**: after Setup. Blocks US1 and US2.
- **US1 (T007–T011)** and **US2 (T012–T016)**: both after Foundational, and independent of each other. They both edit `AppIcon.kt` and `MainWindow.kt`, so do them one after the other (US1 first) unless they're in separate worktrees.
- **Polish (T017–T022)**: after both stories. T017 can be asked at any time; ask it early so the answer is ready.

### User Story Dependencies

- **US1 (P1)**: only Foundational.
- **US2 (P2)**: only Foundational. It doesn't need US1, since the About icon works even without window or Dock icons.

### Within Each Story

- Tests first (T004 before T005, T012 before T013), then `AppIcon` functions, then the call sites, then manual or visual checks.

### Parallel Opportunities

- T010 (`CfgWindow.kt`) can run alongside T007–T009 once T007's signature is agreed.
- T015 (`UiSnapshot.kt`) can run alongside T012–T014.
- T017 (question to the user) can run alongside anything.

---

## Parallel Example: User Story 1

```text
# After T007 defines AppIcon.installOn(Window):
Task: "T009 [US1] call installDockIcon()/installOn(this) in src/main/kotlin/x86sim/ui/MainWindow.kt"
Task: "T010 [P] [US1] call installOn(this) in src/main/kotlin/x86sim/ui/CfgWindow.kt"
```

## Parallel Example: User Story 2

```text
Task: "T013 [US2] aboutIcon() in src/main/kotlin/x86sim/ui/AppIcon.kt"
Task: "T015 [P] [US2] negative zoom steps in src/test/kotlin/x86sim/UiSnapshot.kt"
```

---

## Implementation Strategy

### MVP First (User Story 1 only)

1. T001–T003: branch, and get the icon into the repo (do this now while `/tmp` has it).
2. T004–T006: loader and tests.
3. T007–T011: Dock, taskbar and title bar icon. **Stop and validate**: the app is no longer shown as the Java cup.

### Incremental Delivery

1. MVP (US1), then add US2 (About dialog), then Polish (README, fallback check, CLI check, commit).
2. Each step leaves `./gradlew test` green.
