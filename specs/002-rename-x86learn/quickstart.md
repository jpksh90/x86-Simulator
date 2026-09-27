# Quickstart: Validating the x86Learn Rename

**Feature**: [spec.md](./spec.md) | **Contract**: [contracts/user-facing-text.md](./contracts/user-facing-text.md)

## 1. Automated checks

```bash
./gradlew test
```

This is expected to include new tests for:
- `AppInfo`: name, command, and a version equal to the build version (not `dev`, and no `${`)
- the About text containing all six T4 key phrases
- the CLI usage (T3)
- preference migration on throwaway preference nodes (copied once, old node untouched, no
  migration when the new node has keys)
- the forbidden-strings scan (T6)

All existing tests must still pass.

## 2. Launcher and CLI

```bash
./gradlew installDist
ls build/install/x86learn/bin/            # x86learn  x86learn.bat
build/install/x86learn/bin/x86learn --help; echo "exit=$?"
build/install/x86learn/bin/x86learn run --example 01_hello
```

**Expected**:
- The usage text matches T3, and it prints `exit=0`.
- `Hello, world!` appears, then `[sim] Exited · code 0 (…)`.
- `build/install/x86sim` is not recreated. Delete any stale copy left from earlier builds.

## 3. Preference carry-over (macOS/Linux/Windows)

1. Check out the previous commit, run it, and pick **light** theme and **150%** zoom (⌘+ twice).
   Quit.
2. Check out this feature, run `./gradlew run`, and check that light theme and 150% zoom are
   active.
3. Change to dark, quit, and relaunch. Dark sticks, because settings now live under the new name.

## 4. GUI walkthrough

```bash
./gradlew run
```

1. The window title reads `x86Learn — …`. On macOS the menu bar shows **x86Learn**.
2. Edit the program. The title gains ` •`.
3. Open **Help → About**. The title is "About x86Learn", and the dialog shows the six items in
   T4, including `Version 1.0.0`. Close it with Escape.
4. Toggle the theme (◐) and zoom to 250% (⌘+ repeatedly), then reopen About. The text is legible
   and nothing is cut off.

## 5. Old-name scan

```bash
grep -rniE "x86-64 simulator|x86-simulator" README.md src/main/ build.gradle.kts settings.gradle.kts
```

**Expected**: no output.
