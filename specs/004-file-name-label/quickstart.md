# Quickstart: Validate the File Name Label

## Automated

```sh
./gradlew test --tests 'x86sim.DocumentLabelTest'   # every row of contracts/toolbar-label.md
./gradlew test                                      # full suite still green
```

## Visual (both themes, zoom)

Build `./gradlew testClasses installDist`, then render with `UiSnapshot` (the classpath recipe is
in the project's UiSnapshot notes), for example:

```sh
java -cp "$CP" x86sim.UiSnapshotKt build/snap 01_hello 0 0 dark 0 0
java -cp "$CP" x86sim.UiSnapshotKt build/snap 01_hello 0 0 light 0 0
java -cp "$CP" x86sim.UiSnapshotKt build/snap 06_bubble_sort 0 0 dark 8 0   # 250% zoom
```

Expected: the toolbar starts with **Hello, world** (or **Bubble sort**) in bold, and has no
dropdown. At 250% every toolbar button up to the Speed slider is still visible.

## Manual walkthrough (`./gradlew run`)

| Step | Expected label |
|------|----------------|
| Launch | `Hello, world` |
| Type a character | `*Hello, world` (the window title also shows `•`) |
| File → New → "No" to saving | `*New File` |
| Type a character | `*New File` (no double `*`) |
| File → Save As → `mine.asm` | `mine.asm`, tooltip shows the full path |
| Examples → Bubble sort | `Bubble sort` |
| File → Open → cancel | unchanged |
| Open a file with a 60-character name | shortened with `…`, tooltip shows the full path |
| View → toggle theme; zoom ⌘+ ×8 | readable in both themes, no buttons clipped |
| `x86learn run --example 01_hello` | CLI output unchanged |
