# Research: Rename the Project to x86Learn

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Date**: 2026-09-27

The Technical Context has no open NEEDS CLARIFICATION items. The stack is fixed by the existing
project. These decisions settle how each requirement maps onto the current code.

## Inventory of the old name (from a code search)

| Where | Current text | Surface |
|---|---|---|
| `src/main/kotlin/x86sim/ui/MainWindow.kt:134` | `title = "x86-64 Simulator"` | window title at start |
| `src/main/kotlin/x86sim/ui/MainWindow.kt` `updateTitle()` | `"x86-64 Simulator — $docName"` + ` •` | window title with a document |
| `src/main/kotlin/x86sim/ui/MainWindow.kt` About item | `"x86-64 Simulator\nLearn assembly by stepping through it."`, title `"About"` | Help → About |
| `src/main/kotlin/x86sim/ui/MainWindow.kt` `launch()` | `apple.awt.application.name = "x86-64 Simulator"` | macOS menu bar/Dock |
| `src/main/kotlin/x86sim/Main.kt` `USAGE` | `x86-64 simulator`, `x86sim …` examples | CLI usage |
| `build.gradle.kts` | `applicationName = "x86sim"` | launcher command / install dir |
| `settings.gradle.kts` | `rootProject.name = "x86-simulator"` | build project name |
| `src/main/kotlin/x86sim/ui/Theme.kt` | `Preferences.userRoot().node("x86sim")` | saved theme/zoom |
| `README.md` | title, `build/install/x86sim/bin/x86sim …` | docs |

Deliberately left alone:
- the `x86sim` Kotlin package and `group = "x86sim"` (internal, out of scope)
- the `-Dx86sim.noprefs` dev flag, which is internal and used by `UiSnapshot`
- prose saying "simulator" as a description

## R1. One source of truth for the name and version

**Decision**: add a core object, `x86sim.AppInfo`, with no UI imports:
- `const val NAME = "x86Learn"`
- `const val COMMAND = "x86learn"`
- `val VERSION: String`, read from a classpath resource `/x86learn.properties` that the build
  generates

All surfaces reference `AppInfo` instead of string literals.

**Rationale**:
- FR-006 needs the real version, and FR-011 needs no drift between surfaces.
- A single constant makes both testable without a display.

**Alternatives**:
- *String literals in each file*: rejected. The old name spread across six places exactly this
  way.
- *`Package.getImplementationVersion()` from the jar manifest*: rejected. It returns null under
  `./gradlew run` and in tests, which run from class directories rather than a jar.

## R2. Getting the Gradle version into the app

**Decision**: `build.gradle.kts` → `tasks.processResources { inputs.property("version", version);
filesMatching("x86learn.properties") { expand("version" to version) } }`, with
`src/main/resources/x86learn.properties` containing `version=${version}`. `AppInfo.VERSION` loads
it and falls back to `"dev"` if the resource is missing.

**Rationale**: this is standard Gradle. It works the same for `run`, `test`, `installDist` and
the jar, and it needs no plugins or new dependencies (constitution: Tech & Scope).

## R3. Launcher command

**Decision**: set `applicationName = "x86learn"` in `build.gradle.kts`. `installDist` then
produces `build/install/x86learn/bin/x86learn` (and `x86learn.bat`). The CLI `USAGE` text is
built from `AppInfo.NAME`/`AppInfo.COMMAND`. The README shows the new paths and a one-line note
that the command used to be `x86sim`.

## R4. Build project name

**Decision**: `rootProject.name = "x86Learn"` in `settings.gradle.kts`.

**Rationale**: FR-010. Gradle accepts mixed-case project names. This only affects the project's
display name and default archive base name, since the jar is named from the project name. The
launcher scripts reference the jar through the generated classpath, so nothing else changes.

## R5. Preference migration

**Decision**: `Theme` uses `Preferences.userRoot().node("x86learn")`. On startup (inside the
existing `try`), if the new node has **no keys** and a node `x86sim` exists (checked with
`Preferences.userRoot().nodeExists("x86sim")`), copy its `theme` and `zoom` values into the new
node and `flush()`. The old node is left untouched. The logic lives in a small function,
`Theme.migratePrefs(from: Preferences, to: Preferences)`, so it can be unit-tested on throwaway
nodes.

**Rationale**: FR-009. The copy happens once, because afterwards the new node has keys. It is
harmless if the old version is still installed. Checking with `nodeExists` avoids creating an
empty `x86sim` node on machines that never had one.

**Alternatives**:
- *Keep using the `x86sim` node forever*: rejected. It violates "after that, keep settings under
  its own name" (FR-009).
- *Move the old node, deleting it*: rejected. It is destructive for anyone who still runs the old
  build.

## R6. macOS application name

**Decision**: keep the existing mechanism, `System.setProperty("apple.awt.application.name", …)`
in `MainWindow.launch()`, with the value `AppInfo.NAME`.

**Rationale**: FR-002. It works for the installed launcher and `./gradlew run` on current JDKs.
We don't add `-Xdock:name` to `applicationDefaultJvmArgs`: it's a macOS-only JVM flag, and on
Windows or Linux the JVM would refuse to start with "Unrecognized option".

## R7. The About dialog

**Decision**:
- A pure function `AppInfo.aboutHtml(): String` builds the dialog body as simple HTML with no
  hard-coded colors, so FlatLaf themes it in both dark and light. It contains the six FR-005 items
  (see [contracts/user-facing-text.md](./contracts/user-facing-text.md)).
- `MainWindow` shows it with `JOptionPane.showMessageDialog(this, JLabel(aboutHtml), "About ${AppInfo.NAME}", INFORMATION_MESSAGE)`.

**Rationale**:
- `JOptionPane` already closes with OK, Enter and Escape and has no side effects on the simulator
  (FR-007).
- HTML in a `JLabel` scales with FlatLaf's zoom (`UIScale`), as the rest of the UI does.
- Width is constrained with `<body style='width: 360px'>` so text wraps instead of making a very
  wide dialog. *Correction during implementation:* FlatLaf does **not** scale CSS pixel widths. At 250% zoom the text wrapped into a narrow column. The width is now passed in as `UIScale.scale(360)`.
- Keeping the text in a pure function makes FR-005 unit-testable (6/6 items), which is
  Principle III in spirit.

**Alternatives**: *A custom `JDialog` with a logo*: rejected. Branding is out of scope, and it's
more code for the same content.

## R8. Verifying "no old name left" (SC-001, FR-011)

**Decision**: add a unit test that reads `README.md` and every file under `src/main/`, and fails
if any contains `x86-64 Simulator` or `x86-simulator`. It also asserts that `AppInfo.NAME` appears
in the README title. Separate tests check the `AppInfo` values and that the CLI usage text
(exposed as `internal val USAGE` in `Main.kt`) contains `x86Learn` and `x86learn`. None of these
tests need a display.

**Rationale**: a regression guard. A future edit that reintroduces the old name fails CI
immediately.
