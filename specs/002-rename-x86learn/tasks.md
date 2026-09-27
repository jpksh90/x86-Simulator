---

description: "Task list for renaming the project to x86Learn"
---

# Tasks: Rename the Project to x86Learn

**Input**: Design documents from `specs/002-rename-x86learn/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/user-facing-text.md](./contracts/user-facing-text.md),
[quickstart.md](./quickstart.md)

**Tests**: **Included, and written first.** Constitution Principle III requires tests. Every
testable requirement gets a headless test in the new `src/test/kotlin/x86sim/RenameTest.kt`.

**Organization**: grouped by user story:
- US1 = the name everywhere (P1)
- US2 = the About dialog (P1)
- US3 = settings carry over (P2)

## Format: `[ID] [P?] [Story] Description`

- **[P]**: parallelizable (different files, no dependency on unfinished tasks)
- Paths are relative to the repo root `/Users/jyp/x86-Simulator`.

## Conventions

- **Branch**: work on a branch named `002-rename-x86learn`, created from the current
  `001-string-instructions` HEAD, because the README already contains feature 001's changes
  (see spec Assumptions).
- **Tests**: run `./gradlew test` after each production change. The 57 existing tests must keep
  passing.
- **Spelling**: the name is always `x86Learn`, and the command is always `x86learn`.

---

## Phase 1: Setup

- [X] T001 Create and switch to the git branch `002-rename-x86learn` from the current HEAD of
  `001-string-instructions`. Run `./gradlew test` and confirm all 57 tests pass (the baseline).
- [X] T002 [P] Create `src/test/kotlin/x86sim/RenameTest.kt` (package `x86sim`, `kotlin.test`) with
  a helper `private fun repoFile(path: String) = java.io.File(path)`. Gradle runs tests with the
  project directory as the working directory, so relative paths like `README.md` and `src/main`
  resolve.

---

## Phase 2: Foundational (blocks all stories)

**Purpose**: a single source for the name, command and version (research R1, R2).

- [X] T003 [P] In `src/test/kotlin/x86sim/RenameTest.kt`, add a test `app info names and version`
  (write it first; it won't compile yet):
  - `AppInfo.NAME == "x86Learn"` and `AppInfo.COMMAND == "x86learn"`
  - `AppInfo.VERSION` isn't `"dev"`, doesn't contain `"\${"` (the escaped Kotlin literal, meaning an unexpanded placeholder), and matches `Regex("""\d+\.\d+\.\d+.*""")`
  - The test also reads `build.gradle.kts`, extracts `version = "(.+)"` with a regex, and asserts
    that it equals `AppInfo.VERSION`.
  - `build.gradle.kts` contains `applicationName = "${AppInfo.COMMAND}"`. This guards the
    data-model rule "`COMMAND` MUST equal `applicationName`".
- [X] T004 [P] Create `src/main/resources/x86learn.properties` with the single line
  `version=${version}`.
- [X] T005 In `build.gradle.kts`, add:
  ```kotlin
  tasks.processResources {
      inputs.property("version", project.version)
      filesMatching("x86learn.properties") { expand("version" to project.version) }
  }
  ```
  Check that `processResources` on the example `.asm` files isn't affected: only
  `x86learn.properties` is expanded, and the examples contain `$`, which `expand` would choke on.
- [X] T006 Create `src/main/kotlin/x86sim/AppInfo.kt` (package `x86sim`, **no Swing/AWT imports**):
  ```kotlin
  /** The product's name, command and version, used everywhere the app names itself. */
  object AppInfo {
      const val NAME = "x86Learn"
      const val COMMAND = "x86learn"
      const val TAGLINE = "Learn x86-64 assembly by stepping through it."
      val VERSION: String by lazy { ... }
  }
  ```
  - Load `VERSION` with `AppInfo::class.java.getResourceAsStream("/x86learn.properties")` into
    `java.util.Properties`, reading `getProperty("version")`. Fall back to `"dev"` on a missing
    resource, a missing key or an exception.
  - Leave `aboutHtml()` for T016.
- [X] T007 Run `./gradlew test`. T003 must pass.

**Checkpoint**: `AppInfo` gives the name, command and real version, with no display needed.

---

## Phase 3: User Story 1 - The app presents itself as x86Learn (Priority: P1) 🎯 MVP

**Goal**: every user-facing surface says x86Learn, and the launcher is `x86learn`.

**Independent Test**:
- `./gradlew installDist && build/install/x86learn/bin/x86learn --help` prints the T3 usage.
- The window title reads `x86Learn — …`.
- `grep -rniE "x86-64 simulator|x86-simulator" README.md src/main/` finds nothing.

### Tests (write first)

- [X] T008 [P] [US1] In `src/test/kotlin/x86sim/RenameTest.kt`, add the test
  `old product name is gone from user-facing files`:
  - Walk `README.md`, `build.gradle.kts`, `settings.gradle.kts` and every file under `src/main`
    (`File("src/main").walkTopDown().filter { it.isFile }`).
  - For each file, assert that the lowercased text contains neither `"x86-64 simulator"` nor
    `"x86-simulator"`, with a message naming the file.
  - Also assert that `File("README.md").readLines().first() == "# x86Learn"` and that
    `settings.gradle.kts` contains `rootProject.name = "x86Learn"`.
- [X] T009 [P] [US1] In `src/test/kotlin/x86sim/RenameTest.kt`, add the test `cli usage names x86Learn`.
  It asserts that `USAGE` (made `internal` in T011) starts with
  `"x86Learn: learn x86-64 assembly by stepping through it"` and contains `"  x86learn run <file.asm> [--trace]"`,
  `"  x86learn run --example <name>"` and `"open the visual simulator"`, exactly per contract T3.

### Implementation

- [X] T010 [P] [US1] In `settings.gradle.kts`, set `rootProject.name = "x86Learn"`. In
  `build.gradle.kts`, set `applicationName = "x86learn"`, leaving `group` and `mainClass`
  unchanged.
- [X] T011 [US1] In `src/main/kotlin/x86sim/Main.kt`, replace `private const val USAGE = """…"""`
  with `internal val USAGE = """…"""`, built from `AppInfo`. It must print exactly contract T3:
  ```
  ${AppInfo.NAME}: ${AppInfo.TAGLINE.replaceFirstChar { it.lowercase() }.removeSuffix(".")}

  Usage:
    ${AppInfo.COMMAND}                          open the visual simulator
    ${AppInfo.COMMAND} run <file.asm> [--trace] assemble and run in the terminal
    ${AppInfo.COMMAND} run --example <name>     run a built-in example (e.g. 01_hello)
  ```
  Keep the exit-code behavior (0 for `-h`/`--help`, 2 otherwise).
- [X] T012 [US1] In `src/main/kotlin/x86sim/ui/MainWindow.kt`:
  - In `init`, set `title = AppInfo.NAME` (currently `"x86-64 Simulator"`).
  - In `updateTitle()`, use `title = "${AppInfo.NAME} — $docName" + if (unsaved) " •" else ""`.
  - In `launch()`, use `System.setProperty("apple.awt.application.name", AppInfo.NAME)`.
  - Add `import x86sim.AppInfo`.
- [X] T013 [US1] Update `README.md` per contract T5:
  - The first line is `# x86Learn`.
  - The intro reads "x86Learn is an educational, visual simulator for **x86-64 assembly** (NASM
    syntax)…", keeping the rest of the sentence.
  - Every `build/install/x86sim/bin/x86sim` becomes `build/install/x86learn/bin/x86learn`.
  - After that code block, add: "The launcher used to be called `x86sim`; update any scripts that
    call it."
  - Leave the `src/main/kotlin/x86sim/` project-layout path unchanged.
- [X] T014 [US1] Run `./gradlew test` (T008 and T009 must pass). Then run `./gradlew installDist`,
  check that `build/install/x86learn/bin/x86learn` exists, and run
  `build/install/x86learn/bin/x86learn --help` and `… run --example 01_hello` per quickstart §2.
  Delete any stale `build/install/x86sim`. **Never run the launcher without arguments in an
  automated shell**: that opens the GUI and blocks.

**Checkpoint**: the rename is complete on every surface except the About box and preferences.

---

## Phase 4: User Story 2 - Help → About describes x86Learn (Priority: P1)

**Goal**: an "About x86Learn" dialog with the six required items.

**Independent Test**: `AppInfo.aboutHtml()` contains all six key phrases, and the dialog renders
legibly in both themes at 250% zoom.

### Tests (write first)

- [X] T015 [P] [US2] In `src/test/kotlin/x86sim/RenameTest.kt`, add the test
  `about text has all six items in order`:
  - Strip tags from `AppInfo.aboutHtml()` with `.replace(Regex("<[^>]+>"), " ")`.
  - Assert that it contains, **in this order** (compare increasing `indexOf`):
    1. `"x86Learn"`
    2. `"Version ${AppInfo.VERSION}"`
    3. `"x86-64 assembly"`
    4. `"NASM"` and then `"Linux"`
    5. `"Step"`, `"breakpoints"`, `"registers"`, `"flags"`, `"stack"`, `"memory"`
    6. `"Instruction Reference"` and then `"Hover"`
  - Also assert that the raw HTML contains no `color` attribute or `color:` style (research R7:
    no hard-coded colors).

### Implementation

- [X] T016 [US2] In `src/main/kotlin/x86sim/AppInfo.kt`, add `fun aboutHtml(): String` returning:
  ```html
  <html><body style='width: 360px'>
  <h2>x86Learn</h2>
  <p>Version {VERSION}</p><br>
  <p>An educational, visual simulator for learning x86-64 assembly.</p><br>
  <p>Write NASM-syntax programs that talk to a simulated Linux through system calls.</p><br>
  <p>Step forward and back one instruction at a time, set breakpoints, and watch the registers,
  flags, stack and memory change.</p><br>
  <p>Help → Instruction Reference lists everything supported. Hover over an instruction or
  register for a description.</p>
  </body></html>
  ```
  - Use `$NAME` and `$VERSION` interpolation instead of literals.
  - Use no color attributes (contract T4).
- [X] T017 [US2] In `src/main/kotlin/x86sim/ui/MainWindow.kt`, change the Help → About action to
  `JOptionPane.showMessageDialog(this@MainWindow, JLabel(AppInfo.aboutHtml()), "About ${AppInfo.NAME}", JOptionPane.INFORMATION_MESSAGE)`,
  importing `javax.swing.JLabel` if it isn't already imported. Rename the menu item text from
  `"About"` to `"About ${AppInfo.NAME}"`.
- [X] T018 [US2] Run `./gradlew test` (T015 must pass). Then check the dialog visually. Either:
  - add an `ABOUT` scenario to `src/test/kotlin/x86sim/UiSnapshot.kt` that opens the About dialog
    after layout (on a later `invokeLater`, because the dialog is modal) and renders all visible
    windows, run it for `dark` and for `light` with 6 zoom-ins, and inspect the PNGs; or
  - open Help → About in `./gradlew run` in both themes at 250% zoom.

  The text must be legible with nothing clipped (FR-007).

**Checkpoint**: About is complete.

---

## Phase 5: User Story 3 - Existing users keep their settings (Priority: P2)

**Goal**: theme and zoom carry over from the old `x86sim` preferences node once.

**Independent Test**: the migration unit test passes, and the manual quickstart §3 check (light
theme + 150% zoom carried over) passes.

### Tests (write first)

- [X] T019 [P] [US3] In `src/test/kotlin/x86sim/RenameTest.kt`, add migration tests using
  throwaway nodes under `Preferences.userRoot().node("x86learn-test-${System.nanoTime()}")`,
  called `root`. Call `root.removeNode()` in a `finally` block. Cases:
  - **(a) Carry over**: `old` has `theme=light` and `zoom=1.5f`, and `new` is empty. After
    `Theme.migratePrefs(old, new)`, `new` has both values and `old` is unchanged.
  - **(b) New already has keys**: `new` has `theme=dark`. Migration leaves it dark and doesn't
    copy `zoom`.
  - **(c) Only some keys**: `old` has only `zoom=1.25f`. `new` gets `zoom` and no `theme` key.
  - **(d) Nothing to copy**: `migratePrefs(null, new)` leaves `new` empty.

### Implementation

- [X] T020 [US3] In `src/main/kotlin/x86sim/ui/Theme.kt`:
  - Add `internal fun migratePrefs(from: Preferences?, to: Preferences)`. If
    `to.keys().isNotEmpty()` or `from == null`, return. Otherwise, for each key in
    `listOf("theme", "zoom")`, `from.get(key, null)?.let { to.put(key, it) }`, then `to.flush()`.
  - Replace the `prefs` initializer so that, still guarded by `x86sim.noprefs` and still inside
    the `try`:
    ```kotlin
    Preferences.userRoot().node(AppInfo.COMMAND).also { new ->
        val root = Preferences.userRoot()
        migratePrefs(if (root.nodeExists("x86sim")) root.node("x86sim") else null, new)
    }
    ```
  - Update the KDoc: "Saved theme and zoom, under 'x86learn' (copied once from the old 'x86sim'
    node). Disabled with -Dx86sim.noprefs."
  - Import `x86sim.AppInfo`.
- [ ] T021 [US3] Run `./gradlew test` (T019 must pass). Then do quickstart §3 by hand: set light
  theme and 150% zoom on the old build, launch the new build, and confirm both carried over.
  - **Status (2026-09-27)**: the migration tests pass. A real carry-over also happened on the dev machine (an unintended test side effect, since fixed): theme=dark and zoom=1.0 were copied from `x86sim` to `x86learn`, and `x86sim` was untouched. The by-hand light theme + 150% check hasn't been done yet.

**Checkpoint**: all three stories are complete.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T022 Run the old-name scan from quickstart §5:
  `grep -rniE "x86-64 simulator|x86-simulator" README.md src/main/ build.gradle.kts settings.gradle.kts`.
  It must print nothing.
- [ ] T023 [P] Do the quickstart §4 GUI walkthrough (title with and without ` •`, the macOS menu
  bar name, About in both themes at 250%).
  - **Status (2026-09-27)**: About was checked with UiSnapshot renders (dark at 100%, light at 250%). The window title and the macOS menu-bar name haven't been checked in the running app yet.
- [X] T024 Final `./gradlew test`: all tests pass, and there are 57 existing tests plus the new
  `RenameTest` tests. Update `specs/002-rename-x86learn/spec.md` **Status** to `Implemented`.

---

## Dependencies & Execution Order

- **Setup (T001–T002)** comes first, then **Foundational (T003–T007)**, which blocks every story.
- **US1 (T008–T014)** depends only on Foundational. This is the MVP.
- **US2 (T015–T018)** depends on Foundational. It shares `MainWindow.kt` with US1 (T012 then T017),
  so do US1 first or coordinate those edits.
- **US3 (T019–T021)** depends on Foundational (`AppInfo.COMMAND`) and is independent of US1/US2,
  since it only touches `Theme.kt`.
- **Polish (T022–T024)** comes after all stories.

```text
Setup → Foundational ─┬─► US1 ─► US2 ─┐
                      └─► US3 ────────┴─► Polish
```

## Parallel Opportunities

- T002 can run in parallel with T001 (after the branch exists). T003 and T004 can run in
  parallel.
- **US1**: T008, T009 and T010 in parallel (the two tests are in one file, so write them together).
- **US3** can run in parallel with US1/US2 (different production file: `Theme.kt`).
- **Polish**: T023 can run in parallel with T022.

## Implementation Strategy

1. **MVP = Setup + Foundational + US1**: the product is fully renamed and the launcher is
   `x86learn`. Commit: "Rename project to x86Learn".
2. **+ US2**: the new About dialog. Commit: "Expand the About dialog".
3. **+ US3**: preference carry-over. Commit: "Carry saved settings over to x86Learn".
4. **Polish**: final scans and GUI checks.
