# Data Model: Rename the Project to x86Learn

**Feature**: [spec.md](./spec.md) | **Research**: [research.md](./research.md)

## AppInfo *(new, `src/main/kotlin/x86sim/AppInfo.kt`, no UI imports)*

| Field | Type | Value / rule |
|---|---|---|
| `NAME` | `const String` | `"x86Learn"`, the only display name (spelling rule: lowercase `x`, `86`, capital `L`) |
| `COMMAND` | `const String` | `"x86learn"`, the terminal launcher name. It MUST equal `applicationName` in `build.gradle.kts`. |
| `VERSION` | `String` | Loaded once from classpath `/x86learn.properties`, key `version`. Falls back to `"dev"` if missing or unreadable. |
| `TAGLINE` | `const String` | `"Learn x86-64 assembly by stepping through it."` |
| `aboutHtml()` | `fun (): String` | The About dialog body. Contents are fixed by [contracts/user-facing-text.md](./contracts/user-facing-text.md). |

## x86learn.properties *(new resource, `src/main/resources/`)*

```properties
version=${version}
```

`processResources` expands `${version}` from Gradle `version` (currently `1.0.0`). After
processing, it must not contain a literal `${`.

## Saved preferences *(changed location, `ui/Theme.kt`)*

| | Before | After |
|---|---|---|
| Node | `Preferences.userRoot().node("x86sim")` | `Preferences.userRoot().node("x86learn")` |
| Keys | `theme` (`"dark"`/`"light"`), `zoom` (float) | unchanged |

### Migration state transitions (on startup)

```text
new node has keys?  ── yes ──► use new node (no migration)
        │ no
old node "x86sim" exists? ── no ──► defaults (dark, 100%)
        │ yes
copy "theme" and "zoom" (only those present) old → new; flush ──► use new node
```

The old node is never modified or deleted. Migration is disabled together with all preferences
when `-Dx86sim.noprefs` is set.

## Build identity *(changed, Gradle files)*

| Setting | Before | After |
|---|---|---|
| `rootProject.name` (`settings.gradle.kts`) | `x86-simulator` | `x86Learn` |
| `application.applicationName` (`build.gradle.kts`) | `x86sim` | `x86learn` (= `AppInfo.COMMAND`) |
| `group`, `mainClass` | `x86sim`, `x86sim.MainKt` | unchanged (internal namespace is out of scope) |
