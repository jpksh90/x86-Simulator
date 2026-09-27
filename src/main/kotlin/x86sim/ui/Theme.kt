package x86sim.ui

import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.fonts.inter.FlatInterFont
import com.formdev.flatlaf.fonts.jetbrains_mono.FlatJetBrainsMonoFont
import com.formdev.flatlaf.themes.FlatMacDarkLaf
import com.formdev.flatlaf.themes.FlatMacLightLaf
import com.formdev.flatlaf.util.UIScale
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Window
import java.awt.geom.Arc2D
import java.awt.geom.Path2D
import java.util.prefs.Preferences
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingUtilities
import javax.swing.UIManager
import x86sim.AppInfo

/** All colours the UI uses, so the whole app can switch between a dark and a light look. */
class Palette(
    val dark: Boolean,
    val bg: Int, val surface: Int, val surfaceAlt: Int, val text: Int, val dim: Int, val grid: Int,
    val accent: Int, val accentSoft: Int,
    val editorBg: Int, val gutterFg: Int, val gutterFgActive: Int,
    val currentLine: Int, val currentLineArrow: Int, val errorLine: Int,
    val breakpoint: Int, val breakpointInactive: Int,
    val changed: Int, val changedStrong: Int,
    val ok: Int, val bad: Int, val warn: Int,
    val synComment: Int, val synMnemonic: Int, val synRegister: Int, val synNumber: Int,
    val synString: Int, val synLabel: Int, val synDirective: Int,
    val flagOn: Int, val flagOff: Int, val flagOffText: Int,
    val consoleBg: Int, val consoleText: Int, val consoleInput: Int, val consoleSys: Int, val consoleErr: Int,
    val cellBg: Int, val cellBorder: Int, val freeBg: Int, val hatchBg: Int, val hatchLine: Int,
    val frames: List<Int>, val shadowAlpha: Int,
    val edgeTaken: Int, val edgeNotTaken: Int, val edgeAlways: Int,
)

object Theme {
    val DARK = Palette(
        dark = true,
        bg = 0x2B2D30, surface = 0x1E1F22, surfaceAlt = 0x232427, text = 0xDFE1E5, dim = 0x80848C, grid = 0x393B40,
        accent = 0x548AF7, accentSoft = 0x25324D,
        editorBg = 0x1E1F22, gutterFg = 0x4E5157, gutterFgActive = 0xA1A3AB,
        currentLine = 0x26344F, currentLineArrow = 0xF2C55C, errorLine = 0x482A2D,
        breakpoint = 0xF0596B, breakpointInactive = 0x6B3A3E,
        changed = 0x4A3C12, changedStrong = 0xE5B53B,
        ok = 0x5FB865, bad = 0xF75464, warn = 0xE5A93B,
        synComment = 0x7A7E85, synMnemonic = 0xCF8E6D, synRegister = 0xC77DBB, synNumber = 0x2AACB8,
        synString = 0x6AAB73, synLabel = 0x56A8F5, synDirective = 0xB3AE60,
        flagOn = 0x3574F0, flagOff = 0x393B40, flagOffText = 0x9DA0A8,
        consoleBg = 0x141517, consoleText = 0xDFE1E5, consoleInput = 0x6CB6FF, consoleSys = 0x7A7E85, consoleErr = 0xF75464,
        cellBg = 0x2B2D30, cellBorder = 0x43454A, freeBg = 0x232427, hatchBg = 0x3A3322, hatchLine = 0x5A4B2A,
        frames = listOf(0x25324D, 0x243A2E, 0x3D3024, 0x352A45, 0x422A33), shadowAlpha = 70,
        edgeTaken = 0x5FB865, edgeNotTaken = 0xF75464, edgeAlways = 0x548AF7,
    )

    val LIGHT = Palette(
        dark = false,
        bg = 0xF7F8FA, surface = 0xFFFFFF, surfaceAlt = 0xF7F8FA, text = 0x1E1F22, dim = 0x818594, grid = 0xE4E6EB,
        accent = 0x3574F0, accentSoft = 0xEDF3FF,
        editorBg = 0xFFFFFF, gutterFg = 0xAEB3C2, gutterFgActive = 0x3B3F45,
        currentLine = 0xE3ECFF, currentLineArrow = 0x3574F0, errorLine = 0xFDECEE,
        breakpoint = 0xE55765, breakpointInactive = 0xF4B9BF,
        changed = 0xFFEFB8, changedStrong = 0xF2B632,
        ok = 0x208A3C, bad = 0xDB3B4B, warn = 0xC27D0E,
        synComment = 0x8C8C8C, synMnemonic = 0x0033B3, synRegister = 0x871094, synNumber = 0x1750EB,
        synString = 0x067D17, synLabel = 0x00627A, synDirective = 0x9E880D,
        flagOn = 0x3574F0, flagOff = 0xEBECF0, flagOffText = 0x6C707E,
        consoleBg = 0x1E1F22, consoleText = 0xDFE1E5, consoleInput = 0x6CB6FF, consoleSys = 0x7A7E85, consoleErr = 0xF75464,
        cellBg = 0xFFFFFF, cellBorder = 0xD3D5DB, freeBg = 0xF3F4F6, hatchBg = 0xFFF8EC, hatchLine = 0xF0D9B0,
        frames = listOf(0xDCE8FF, 0xDDF3E4, 0xFBE7D2, 0xEBDDF7, 0xF9DCE3), shadowAlpha = 22,
        edgeTaken = 0x208A3C, edgeNotTaken = 0xDB3B4B, edgeAlways = 0x3574F0,
    )

    var p: Palette = DARK
        private set
    val isDark get() = p.dark

    /**
     * Saved theme and zoom, under 'x86learn' (copied once from the old 'x86sim' node).
     * Disabled with -Dx86sim.noprefs (used by the screenshot harness).
     */
    private val prefs: Preferences? = if (System.getProperty("x86sim.noprefs") != null) null
        else try {
            Preferences.userRoot().node(AppInfo.COMMAND).also { new ->
                val root = Preferences.userRoot()
                migratePrefs(if (root.nodeExists("x86sim")) root.node("x86sim") else null, new)
            }
        } catch (_: Exception) { null }

    /** Copies theme and zoom from the pre-rename settings, unless settings were already saved under the new name. */
    internal fun migratePrefs(from: Preferences?, to: Preferences) {
        if (from == null || to.keys().isNotEmpty()) return
        for (key in listOf("theme", "zoom")) from.get(key, null)?.let { to.put(key, it) }
        to.flush()
    }
    private val listeners = mutableListOf<() -> Unit>()

    /** Registers code that re-applies colours set at construction time. Runs once immediately. */
    fun onChange(apply: () -> Unit) { listeners += apply; apply() }

    private fun c(rgb: Int) = Color(rgb)

    // ---- tokens ----
    val bg get() = c(p.bg)
    val surface get() = c(p.surface)
    val surfaceAlt get() = c(p.surfaceAlt)
    val text get() = c(p.text)
    val dim get() = c(p.dim)
    val grid get() = c(p.grid)
    val headerBg get() = c(p.bg)
    val accent get() = c(p.accent)
    val accentSoft get() = c(p.accentSoft)
    val editorBg get() = c(p.editorBg)
    val gutterBg get() = c(p.editorBg)
    val gutterFg get() = c(p.gutterFg)
    val gutterFgActive get() = c(p.gutterFgActive)
    val currentLine get() = c(p.currentLine)
    val currentLineArrow get() = c(p.currentLineArrow)
    val errorLine get() = c(p.errorLine)
    val breakpoint get() = c(p.breakpoint)
    val breakpointInactive get() = c(p.breakpointInactive)
    val changed get() = c(p.changed)
    val changedStrong get() = c(p.changedStrong)
    val ok get() = c(p.ok)
    val bad get() = c(p.bad)
    val warn get() = c(p.warn)
    val synComment get() = c(p.synComment)
    val synMnemonic get() = c(p.synMnemonic)
    val synRegister get() = c(p.synRegister)
    val synNumber get() = c(p.synNumber)
    val synString get() = c(p.synString)
    val synLabel get() = c(p.synLabel)
    val synDirective get() = c(p.synDirective)
    val flagOn get() = c(p.flagOn)
    val flagOff get() = c(p.flagOff)
    val flagOffText get() = c(p.flagOffText)
    val consoleBg get() = c(p.consoleBg)
    val consoleText get() = c(p.consoleText)
    val consoleInput get() = c(p.consoleInput)
    val consoleSys get() = c(p.consoleSys)
    val consoleErr get() = c(p.consoleErr)
    val cellBg get() = c(p.cellBg)
    val cellBorder get() = c(p.cellBorder)
    val freeBg get() = c(p.freeBg)
    val hatchBg get() = c(p.hatchBg)
    val hatchLine get() = c(p.hatchLine)
    val shadow get() = Color(0, 0, 0, p.shadowAlpha)
    fun frameColor(i: Int) = c(p.frames[i % p.frames.size])
    val edgeTaken get() = c(p.edgeTaken)
    val edgeNotTaken get() = c(p.edgeNotTaken)
    val edgeAlways get() = c(p.edgeAlways)

    /** "#rrggbb" for use inside HTML labels. */
    fun hex(color: Color) = "#%06x".format(color.rgb and 0xFFFFFF)

    // ---- zoom (⌘+ / ⌘− / ⌘0) ----

    /** Current zoom factor; 1.0 = 100%. Everything we size or paint ourselves is multiplied by it. */
    val zoom: Float get() = UIScale.getZoomFactor()
    /** A pixel size scaled by the current zoom. */
    fun z(px: Int): Int = Math.round(px * zoom)
    fun zf(pt: Float): Float = pt * zoom

    private val zoomSteps = floatArrayOf(0.8f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f)

    fun zoomIn() = applyZoom { UIScale.zoomIn() }
    fun zoomOut() = applyZoom { UIScale.zoomOut() }
    fun zoomReset() = applyZoom { UIScale.zoomReset() }

    private fun applyZoom(change: () -> Boolean): Boolean {
        if (!change()) return false // already at the smallest / largest step
        prefs?.putFloat("zoom", zoom)
        refreshAll()
        return true
    }

    /** Re-applies the look and feel, then lets every component re-apply its own fonts, sizes and colours. */
    private fun refreshAll() {
        FlatLaf.updateUI()
        listeners.forEach { it() }
        for (w in Window.getWindows()) { w.revalidate(); w.repaint() }
    }

    // ---- fonts (scaled with the zoom) ----
    private fun monoFont(style: Int, size: Float) = Font(FlatJetBrainsMonoFont.FAMILY, style, 1).deriveFont(zf(size))
    val mono: Font get() = monoFont(Font.PLAIN, 13f)
    val monoSmall: Font get() = monoFont(Font.PLAIN, 12f)
    val monoBold: Font get() = monoFont(Font.BOLD, 13f)
    fun mono(size: Float, style: Int = Font.PLAIN) = monoFont(style, size)
    /** The UI (Inter) font at a given point size, scaled with the zoom. */
    fun ui(size: Float, style: Int = Font.PLAIN): Font = UIManager.getFont("defaultFont")?.deriveFont(style, zf(size))
        ?: Font(FlatInterFont.FAMILY, style, 1).deriveFont(zf(size))

    // ---- look and feel ----

    /** Installs fonts and the saved (or default dark) theme. Call before creating any UI. */
    fun install() {
        FlatInterFont.install()
        FlatJetBrainsMonoFont.install()
        FlatLaf.setPreferredFontFamily(FlatInterFont.FAMILY)
        FlatLaf.setPreferredMonospacedFontFamily(FlatJetBrainsMonoFont.FAMILY)
        p = if (prefs?.get("theme", "dark") == "light") LIGHT else DARK
        UIScale.setSupportedZoomFactors(zoomSteps)
        prefs?.getFloat("zoom", 1f)?.takeIf { it in zoomSteps.toList() && it != 1f }?.let { UIScale.setZoomFactor(it) }
        setupLaf()
    }

    private fun setupLaf() {
        FlatLaf.setGlobalExtraDefaults(mapOf("@accentColor" to hex(accent)))
        if (p.dark) FlatMacDarkLaf.setup() else FlatMacLightLaf.setup()
        val d = UIManager.getDefaults()
        d["Component.arc"] = 8
        d["Button.arc"] = 8
        d["TextComponent.arc"] = 8
        d["ScrollBar.showButtons"] = false
        d["ScrollBar.width"] = 10
        d["ScrollBar.thumbArc"] = 999
        d["ScrollBar.thumbInsets"] = java.awt.Insets(2, 2, 2, 2)
        d["SplitPane.dividerSize"] = 5
        d["SplitPaneDivider.style"] = "plain"
        d["TabbedPane.tabHeight"] = 34
        d["TabbedPane.showTabSeparators"] = false
        d["Table.showHorizontalLines"] = false
        d["Table.showVerticalLines"] = false
        d["Table.intercellSpacing"] = Dimension(0, 0)
        d["TableHeader.separatorColor"] = grid
        d["ToolTip.background"] = if (p.dark) Color(0x393B40) else Color(0xFFFFFF)
        d["Panel.background"] = bg
    }

    fun toggle() {
        p = if (p.dark) LIGHT else DARK
        prefs?.put("theme", if (p.dark) "dark" else "light")
        setupLaf()
        for (w in Window.getWindows()) SwingUtilities.updateComponentTreeUI(w)
        listeners.forEach { it() }
        for (w in Window.getWindows()) w.repaint()
    }

    // ---- small building blocks ----

    /** A small uppercase panel heading, e.g. "REGISTERS". */
    fun sectionLabel(text: String) = JLabel(text.uppercase()).apply {
        onChange { foreground = dim; font = ui(11f, Font.BOLD) }
        border = BorderFactory.createEmptyBorder(8, 10, 6, 8)
    }

    fun <T : JComponent> T.padded(t: Int = 6, l: Int = 8, b: Int = 6, r: Int = 8): T {
        border = BorderFactory.createEmptyBorder(t, l, b, r); return this
    }

    fun hex64(v: Long) = "%08x %08x".format(v ushr 32, v and 0xFFFFFFFFL)
    fun hexAddr(v: Long) = "%012x".format(v)
}

/** A rounded, coloured badge — used for the machine state in the status bar. */
class Pill : JLabel() {
    var pillColor: Color = Color.GRAY
        set(v) { field = v; repaint() }

    init {
        Theme.onChange { font = Theme.ui(11.5f, Font.BOLD) }
        border = BorderFactory.createEmptyBorder(3, 10, 3, 10)
        isOpaque = false
    }

    override fun paintComponent(g0: Graphics) {
        val g = g0.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(pillColor.red, pillColor.green, pillColor.blue, if (Theme.isDark) 60 else 36)
        g.fillRoundRect(0, 0, width, height, height, height)
        g.dispose()
        foreground = pillColor
        super.paintComponent(g0)
    }
}

/** Small vector icons for the toolbar, drawn in the current theme's colours. */
class ToolIcon(private val kind: Kind, private val tint: (() -> Color)? = null) : Icon {
    enum class Kind { BUILD, RUN, PAUSE, STEP, BACK, OVER, OUT, RESET, GRAPH, THEME }

    override fun getIconWidth() = Theme.z(16)
    override fun getIconHeight() = Theme.z(16)

    override fun paintIcon(c: Component?, g0: Graphics, x: Int, y: Int) {
        val g = g0.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.translate(x, y)
        g.scale(Theme.zoom.toDouble(), Theme.zoom.toDouble())
        val enabled = c?.isEnabled ?: true
        val base = tint?.invoke() ?: Theme.text
        g.color = if (enabled) base else Theme.dim.let { Color(it.red, it.green, it.blue, 110) }
        g.stroke = BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        when (kind) {
            Kind.RUN -> g.fill(Path2D.Double().apply { moveTo(4.0, 2.5); lineTo(13.5, 8.0); lineTo(4.0, 13.5); closePath() })
            Kind.PAUSE -> { g.fillRoundRect(3, 3, 3, 10, 2, 2); g.fillRoundRect(10, 3, 3, 10, 2, 2) }
            Kind.STEP -> {
                g.drawLine(8, 2, 8, 10); g.drawLine(5, 7, 8, 10); g.drawLine(11, 7, 8, 10)
                g.fillOval(6, 12, 4, 4)
            }
            Kind.BACK -> {
                // an arrow curving back up and to the left: "undo one step"
                g.draw(Arc2D.Double(4.0, 3.0, 9.0, 9.0, 90.0, -210.0, Arc2D.OPEN))
                g.fill(Path2D.Double().apply { moveTo(3.0, 3.0); lineTo(8.5, 0.5); lineTo(8.5, 5.5); closePath() })
                g.fillOval(6, 12, 4, 4)
            }
            Kind.OVER -> {
                g.draw(Arc2D.Double(2.5, 3.0, 11.0, 10.0, 180.0, -180.0, Arc2D.OPEN))
                g.drawLine(13, 8, 11, 5); g.drawLine(13, 8, 15, 6)
                g.fillOval(6, 12, 4, 4)
            }
            Kind.OUT -> {
                g.drawLine(8, 12, 8, 3); g.drawLine(5, 6, 8, 3); g.drawLine(11, 6, 8, 3)
                g.drawLine(3, 14, 13, 14)
            }
            Kind.RESET -> {
                g.draw(Arc2D.Double(2.5, 2.5, 11.0, 11.0, 90.0, 270.0, Arc2D.OPEN))
                g.fill(Path2D.Double().apply { moveTo(7.0, 0.5); lineTo(11.0, 3.0); lineTo(7.0, 5.5); closePath() })
            }
            Kind.BUILD -> {
                g.drawRoundRect(2, 2, 12, 12, 4, 4)
                g.drawLine(5, 8, 7, 10); g.drawLine(7, 10, 11, 5)
            }
            Kind.GRAPH -> {
                g.fillOval(6, 1, 5, 5); g.fillOval(1, 11, 5, 5); g.fillOval(11, 11, 5, 5)
                g.drawLine(8, 5, 4, 11); g.drawLine(9, 5, 13, 11)
            }
            Kind.THEME -> {
                g.drawOval(2, 2, 12, 12)
                g.fill(Arc2D.Double(2.0, 2.0, 12.0, 12.0, 90.0, 180.0, Arc2D.PIE))
            }
        }
        g.dispose()
    }
}
