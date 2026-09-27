package x86sim

import java.io.File
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import x86sim.ui.Theme

/** The product is called x86Learn everywhere it names itself (specs/002-rename-x86learn). */
class RenameTest {
    // Gradle runs tests with the project directory as the working directory.
    private fun repoFile(path: String) = File(path)

    @Test fun `app info names and version`() {
        assertEquals("x86Learn", AppInfo.NAME)
        assertEquals("x86learn", AppInfo.COMMAND)
        assertTrue(AppInfo.VERSION != "dev" && "\${" !in AppInfo.VERSION, AppInfo.VERSION)
        assertTrue(Regex("""\d+\.\d+\.\d+.*""").matches(AppInfo.VERSION), AppInfo.VERSION)
        val gradle = repoFile("build.gradle.kts").readText()
        assertEquals(Regex("""(?m)^version = "(.+)"""").find(gradle)!!.groupValues[1], AppInfo.VERSION)
        assertTrue("applicationName = \"${AppInfo.COMMAND}\"" in gradle, "launcher name matches AppInfo.COMMAND")
    }

    @Test fun `old product name is gone from user-facing files`() {
        val files = listOf("README.md", "build.gradle.kts", "settings.gradle.kts").map(::repoFile) +
            repoFile("src/main").walkTopDown().filter { it.isFile }
        for (f in files) {
            val text = f.readText().lowercase()
            for (old in listOf("x86-64 simulator", "x86-simulator")) assertFalse(old in text, "'$old' in ${f.path}")
        }
        assertEquals("# x86Learn", repoFile("README.md").readLines().first())
        assertTrue("rootProject.name = \"x86Learn\"" in repoFile("settings.gradle.kts").readText())
    }

    @Test fun `cli usage names x86Learn`() {
        assertTrue(USAGE.startsWith("x86Learn: learn x86-64 assembly by stepping through it"), USAGE)
        assertTrue("  x86learn                          open the visual simulator" in USAGE, USAGE)
        assertTrue("  x86learn run <file.asm> [--trace] assemble and run in the terminal" in USAGE, USAGE)
        assertTrue("  x86learn run --example <name>     run a built-in example" in USAGE, USAGE)
    }

    @Test fun `about text has all six items in order`() {
        val html = AppInfo.aboutHtml()
        val text = html.replace(Regex("<[^>]+>"), " ")
        var at = -1
        for (phrase in listOf("x86Learn", "Version ${AppInfo.VERSION}", "x86-64 assembly", "NASM", "Linux",
            "Step", "breakpoints", "registers", "flags", "stack", "memory", "Instruction Reference", "Hover")) {
            val i = text.indexOf(phrase, at + 1)
            assertTrue(i > at, "'$phrase' missing or out of order in: $text")
            at = i
        }
        assertFalse(Regex("(?i)color").containsMatchIn(html), "no hard-coded colours")
    }

    // ---------------- preference migration ----------------

    private fun withNodes(body: (old: Preferences, new: Preferences) -> Unit) {
        val root = Preferences.userRoot().node("x86learn-test-${System.nanoTime()}")
        try { body(root.node("old"), root.node("new")) } finally { root.removeNode() }
    }

    @Test fun `saved settings are copied from the old name once`() = withNodes { old, new ->
        old.put("theme", "light"); old.putFloat("zoom", 1.5f)
        Theme.migratePrefs(old, new)
        assertEquals("light", new.get("theme", null))
        assertEquals(1.5f, new.getFloat("zoom", 0f))
        assertEquals(setOf("theme", "zoom"), old.keys().toSet(), "old settings left in place")
    }

    @Test fun `settings already saved under the new name win`() = withNodes { old, new ->
        old.put("theme", "light"); old.putFloat("zoom", 1.5f)
        new.put("theme", "dark")
        Theme.migratePrefs(old, new)
        assertEquals("dark", new.get("theme", null))
        assertNull(new.get("zoom", null))
    }

    @Test fun `only the settings that exist are copied`() = withNodes { old, new ->
        old.putFloat("zoom", 1.25f)
        Theme.migratePrefs(old, new)
        assertEquals(1.25f, new.getFloat("zoom", 0f))
        assertNull(new.get("theme", null))
    }

    @Test fun `nothing to copy leaves the defaults`() = withNodes { _, new ->
        Theme.migratePrefs(null, new)
        assertTrue(new.keys().isEmpty())
    }
}
