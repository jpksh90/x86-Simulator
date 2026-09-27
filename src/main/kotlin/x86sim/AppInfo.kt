package x86sim

/** The product's name, command and version, used everywhere the app names itself. */
object AppInfo {
    const val NAME = "x86Learn"
    /** The terminal launcher; must match `applicationName` in build.gradle.kts. */
    const val COMMAND = "x86learn"
    const val TAGLINE = "Learn x86-64 assembly by stepping through it."

    /** The build's version, filled into x86learn.properties by Gradle. */
    val VERSION: String by lazy {
        try {
            AppInfo::class.java.getResourceAsStream("/x86learn.properties")?.use { s ->
                java.util.Properties().apply { load(s) }.getProperty("version")
            } ?: "dev"
        } catch (_: Exception) { "dev" }
    }

    /**
     * Body of the Help → About dialog. No colours, so it follows the current theme.
     * [widthPx] is the wrap width; the UI scales it with the zoom level.
     */
    fun aboutHtml(widthPx: Int = 360): String = """
        <html><body style='width: ${widthPx}px'>
        <h2>$NAME</h2>
        <p>Version $VERSION</p><br>
        <p>An educational, visual simulator for learning x86-64 assembly.</p><br>
        <p>Write NASM-syntax programs that talk to a simulated Linux through system calls.</p><br>
        <p>Step forward and back one instruction at a time, set breakpoints, and watch the registers,
        flags, stack and memory change.</p><br>
        <p>Help → Instruction Reference lists everything supported. Hover over an instruction or
        register for a description.</p>
        </body></html>
    """.trimIndent()
}
