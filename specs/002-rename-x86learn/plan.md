# Implementation Plan: Rename the Project to x86Learn

**Branch**: `002-rename-x86learn` | **Date**: 2026-09-27 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/002-rename-x86learn/spec.md`

## Summary

Replace the product name "x86-64 Simulator" / "x86-simulator" with **x86Learn** on every
user-facing surface:
- window title
- macOS app name
- CLI usage
- README
- Gradle project name
- launcher command, `x86sim` → `x86learn`

Replace the two-line About box with an "About x86Learn" dialog showing the real build version,
what the tool supports and where to get help. The name, command and version come from one new
core object, `AppInfo`, and the version is injected from Gradle through a processed resource.
Saved theme and zoom settings are copied once from the old `x86sim` preferences node to a new
`x86learn` node. A test scans `README.md` and `src/main/` so the old name can't return.

## Technical Context

**Language/Version**: Kotlin 2.2.10, JVM (Gradle JDK 21 toolchain; runs on JDK 17+)

**Primary Dependencies**: Swing + FlatLaf 3.7.2. No new dependencies.

**Storage**: `java.util.prefs.Preferences` for theme and zoom. The node is renamed from `x86sim`
to `x86learn`, with a one-time copy.

**Testing**: `kotlin.test` / JUnit Platform (`./gradlew test`) and the `UiSnapshot` dev helper for
visual checks.

**Target Platform**: Desktop JVM (macOS/Windows/Linux) + headless CLI

**Project Type**: Desktop app + CLI, single Gradle project

**Performance Goals**: N/A (text and config change; startup unaffected)

**Constraints**: no change to simulator behavior, example output or saved-file formats. Works
headless for tests.

**Scale/Scope**: about 7 files touched (`MainWindow.kt`, `Main.kt`, `Theme.kt`,
`build.gradle.kts`, `settings.gradle.kts`, `README.md`, + new `AppInfo.kt` and
`x86learn.properties`), plus 1 new test file.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle / gate | Pre-research | Post-design |
|---|---|---|
| **I. Architectural Fidelity** | ✅ No execution semantics change. | ✅ Only names and config change. SC-005 plus the existing test suite guard behavior. |
| **II. Learner-First Clarity** | ✅ About becomes more useful. Both themes and all zoom levels are required (FR-007). | ✅ The About text points to the Reference tab and hover help (T4). HTML without hard-coded colors themes correctly (R7). |
| **III. Test-Backed Semantics** | ✅ Tests are planned for every testable requirement. | ✅ Tests cover `AppInfo` + version, About contents, CLI usage, preference migration and the forbidden-strings scan (quickstart §1). |
| **IV. Headless Core, Observing UI** | ✅ | ✅ `AppInfo` is in the core package with no Swing. The UI only reads it. The CLI and GUI use the same name source. |
| **V. Deterministic, Reversible Execution** | ✅ Not affected. | ✅ Not affected. |
| **Tech & Scope**: no new deps; `./gradlew run`/`installDist` keep working with no extra setup | ✅ | ✅ The version comes from the standard `processResources` expand, with no plugins. `installDist` output moves to `build/install/x86learn/` and the README is updated to match. No `-Xdock` flag, which would break non-macOS JVMs (R6). |
| **Memory layout contract** | ✅ Unchanged. | ✅ Unchanged. |
| **Workflow gates**: tests, GUI + CLI, README/docs, both themes | ✅ | ✅ Covered by quickstart §1–§5. |

**Result**: PASS, no violations. The launcher rename (`x86sim` → `x86learn`) is a user-visible
breaking change for scripts. It's allowed, because the constitution fixes the memory layout and
learner semantics, not the command name. It's documented in the README (T5).

## Project Structure

### Documentation (this feature)

```text
specs/002-rename-x86learn/
├── plan.md
├── research.md                 # R1–R8 + inventory of every old-name occurrence
├── data-model.md               # AppInfo, x86learn.properties, prefs node + migration, build identity
├── quickstart.md
├── contracts/
│   └── user-facing-text.md     # T1–T6: title, macOS name, CLI usage, About, README, forbidden strings
├── checklists/requirements.md
└── tasks.md                    # /speckit-tasks
```

### Source Code (repository root)

```text
settings.gradle.kts               # rootProject.name = "x86Learn"
build.gradle.kts                  # applicationName = "x86learn"; processResources expands version
README.md                         # x86Learn title, x86learn commands, rename note
src/main/resources/
└── x86learn.properties           # NEW: version=${version}
src/main/kotlin/x86sim/
├── AppInfo.kt                    # NEW: NAME, COMMAND, VERSION, TAGLINE, aboutHtml()
├── Main.kt                       # USAGE built from AppInfo (internal for tests)
└── ui/
    ├── MainWindow.kt             # title ×2, About dialog, apple.awt.application.name
    └── Theme.kt                  # prefs node "x86learn" + migratePrefs() from "x86sim"
src/test/kotlin/x86sim/
└── RenameTest.kt                 # NEW: AppInfo/version, About items, USAGE, prefs migration, old-name scan
```

**Structure Decision**: the existing single project. The only new production files are
`AppInfo.kt` (core, headless) and one resource file.

## Implementation Order (for /speckit-tasks)

1. **Foundation**: `AppInfo.kt` + `x86learn.properties` + `processResources` expand, with tests
   (the version equals the Gradle version).
2. **US1 (P1)**: title, macOS name, CLI `USAGE`, `applicationName`, `rootProject.name`, README,
   and the forbidden-strings test.
3. **US2 (P1)**: `aboutHtml()` + About dialog, with the About-contents test and a visual check in
   both themes at 250% zoom (UiSnapshot or manual).
4. **US3 (P2)**: the prefs node rename + `migratePrefs()`, with a migration test on throwaway nodes.
5. **Polish**: quickstart §2–§5, including deleting any stale `build/install/x86sim`.

## Complexity Tracking

No constitution violations to justify.
