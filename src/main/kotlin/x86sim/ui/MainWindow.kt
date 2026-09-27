package x86sim.ui

import com.formdev.flatlaf.util.UIScale
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FileDialog
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.util.Hashtable
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JEditorPane
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSlider
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JToolBar
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import x86sim.AppInfo
import x86sim.Examples
import x86sim.Machine
import x86sim.MachineState
import x86sim.asm.AsmError
import x86sim.asm.Assembler
import x86sim.asm.AssemblyException
import x86sim.cpu.Registers
import x86sim.cpu.RepPrefix
import x86sim.cpu.StringOp

class MainWindow : JFrame() {
    internal val machine = Machine()
    internal val breakpoints = mutableSetOf<Int>()

    internal val editor = AsmEditor()
    private val gutter = Gutter(editor, breakpoints)
    private val registers = RegistersPanel(machine)
    private val stackMemory = StackMemoryPanel(machine)
    private val memory = MemoryPanel(machine)
    internal val console = ConsolePanel()
    private val problems = DefaultListModel<AsmError>()
    private val problemList = JList(problems)
    internal val bottomTabs = JTabbedPane()

    private val stateLabel = Pill()
    private val messageLabel = JLabel(" ")
    private val stepsLabel = JLabel()
    private val nextLabel = JLabel(" ")
    private val speedValue = JLabel()

    private var file: File? = null
    private var docName = "untitled"
    private var unsaved = false
    /** Source changed since it was last assembled. */
    private var stale = true

    // ---- run loop state ----
    private var timer: Timer? = null
    private val running get() = timer != null
    private var stopWhen: (() -> Boolean)? = null
    private var stopWhenLabel = ""
    private var resumeAfterInput = false
    /** Run without the speed limit (Step Over a rep instruction); [resumeFullSpeed] survives a pause for input. */
    private var runFullSpeed = false
    private var resumeFullSpeed = false
    private var stepAfterInput = false
    private var lastMnemonic = ""

    private val speeds = listOf(1, 2, 5, 10, 30, 100, 1_000, 20_000, Int.MAX_VALUE)
    private val speedLabels = listOf("1/s", "2/s", "5/s", "10/s", "30/s", "100/s", "1k/s", "20k/s", "Max")
    internal val speedSlider = JSlider(0, speeds.size - 1, 3)

    private val menuKey = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx

    // ---- actions ----
    private fun action(name: String, tip: String, key: KeyStroke?, icon: ToolIcon.Kind? = null, body: () -> Unit) = object : AbstractAction(name) {
        init {
            putValue(Action.SHORT_DESCRIPTION, tip)
            if (icon != null) putValue(Action.SMALL_ICON, ToolIcon(icon))
            if (key != null) putValue(Action.ACCELERATOR_KEY, key)
        }
        override fun actionPerformed(e: ActionEvent?) = body()
    }

    private val assembleAction = action("Assemble", "Assemble (⌘B)", KeyStroke.getKeyStroke(KeyEvent.VK_B, menuKey), ToolIcon.Kind.BUILD) { assemble() }
    private val runAction = action("Run", "Run (F5)", KeyStroke.getKeyStroke(KeyEvent.VK_R, menuKey), ToolIcon.Kind.RUN) { run() }
    private val pauseAction = action("Pause", "Pause (F6)", KeyStroke.getKeyStroke(KeyEvent.VK_PERIOD, menuKey), ToolIcon.Kind.PAUSE) { pause("Paused") }
    private val stepBackAction = action("Step Back", "Undo one step (⇧F7 / ⌘[)", KeyStroke.getKeyStroke(KeyEvent.VK_OPEN_BRACKET, menuKey), ToolIcon.Kind.BACK) { stepBack() }
    private val stepAction = action("Step", "Step into (F7)", KeyStroke.getKeyStroke(KeyEvent.VK_J, menuKey), ToolIcon.Kind.STEP) { step() }
    private val stepOverAction = action("Step Over", "Step over calls (F8)", KeyStroke.getKeyStroke(KeyEvent.VK_K, menuKey), ToolIcon.Kind.OVER) { stepOver() }
    private val stepOutAction = action("Step Out", "Step out of function (⇧F8)", KeyStroke.getKeyStroke(KeyEvent.VK_K, menuKey or InputEvent.SHIFT_DOWN_MASK), ToolIcon.Kind.OUT) { stepOut() }
    private val resetAction = action("Reset", "Restart (⇧⌘R)", KeyStroke.getKeyStroke(KeyEvent.VK_R, menuKey or InputEvent.SHIFT_DOWN_MASK), ToolIcon.Kind.RESET) { reset() }
    private val breakpointAction = action("Toggle Breakpoint", "Toggle breakpoint (F9)", KeyStroke.getKeyStroke(KeyEvent.VK_B, menuKey or InputEvent.SHIFT_DOWN_MASK)) {
        toggleBreakpoint(editor.lineOfOffset(editor.caretPosition))
    }
    private var cfgWindow: CfgWindow? = null
    private var rightPanel: JSplitPane? = null
    private var topPanel: JSplitPane? = null
    private val cfgAction = action("Graph", "Control flow graph (⇧⌘G)", KeyStroke.getKeyStroke(KeyEvent.VK_G, menuKey or InputEvent.SHIFT_DOWN_MASK), ToolIcon.Kind.GRAPH) { showCfg() }
    private val zoomInAction = action("Zoom In", "Bigger text (⌘+)", KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, menuKey)) { zoom(Theme::zoomIn) }
    private val zoomOutAction = action("Zoom Out", "Smaller text (⌘−)", KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, menuKey)) { zoom(Theme::zoomOut) }
    private val zoomResetAction = action("Actual Size", "Reset zoom (⌘0)", KeyStroke.getKeyStroke(KeyEvent.VK_0, menuKey)) { zoom(Theme::zoomReset) }
    private val themeAction = action("Toggle Dark/Light", "Dark / light theme", null, ToolIcon.Kind.THEME) { Theme.toggle() }

    private val clearBreakpointsAction = action("Clear All Breakpoints", "Clear breakpoints", null) {
        breakpoints.clear(); gutter.repaint()
    }

    init {
        title = AppInfo.NAME
        rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) { if (confirmDiscard()) { dispose(); System.exit(0) } }
        })
        machine.memory.logWrites = true
        machine.onOutput = { console.print(it) }
        machine.onNotice = { console.system(it) }
        machine.outputMark = { console.mark() }
        machine.onRewindOutput = { console.rewind(it) }

        console.onInput = { text ->
            console.echoInput(text)
            machine.provideInput(text)
            afterInput()
        }
        console.onEof = {
            console.system("EOF")
            machine.closeInput()
            afterInput()
        }

        editor.onEdited = {
            unsaved = true
            if (!stale) {
                stale = true
                if (running) pause("Paused · source edited")
                message("Edited · rebuilds on run", Theme.warn)
            }
            editor.errorLines = emptySet()
            gutter.instructionLines = null
            updateTitle()
        }
        gutter.onToggle = { toggleBreakpoint(it) }

        jMenuBar = buildMenus()
        contentPane = buildLayout()
        bindFunctionKeys()

        loadExample(Examples.names.first().first)
        updateActions()
        refresh(null)

        val screen = graphicsConfiguration.bounds
        size = Dimension(minOf(1720, screen.width - 40), minOf(1000, screen.height - 60))
        minimumSize = Dimension(1000, 650)
        setLocationRelativeTo(null)
    }

    // ---------------- layout ----------------

    private fun buildLayout(): JComponent {
        val toolbar = JToolBar().apply {
            isFloatable = false
            border = BorderFactory.createEmptyBorder(6, 10, 6, 10)
            val examples = JComboBox(arrayOf("Examples") + Examples.names.map { it.second }).apply {
                maximumSize = Dimension(200, 30)
                toolTipText = "Load an example"
                addActionListener {
                    val i = selectedIndex
                    if (i > 0) { selectedIndex = 0; if (confirmDiscard()) loadExample(Examples.names[i - 1].first) }
                }
            }
            add(examples)
            addSeparator(Dimension(14, 0))
            fun button(a: Action, label: String) = JButton(a).apply {
                text = label
                isFocusable = false
                putClientProperty("JButton.buttonType", "toolBarButton")
                iconTextGap = 6
            }
            add(button(assembleAction, "Build"))
            addSeparator(Dimension(6, 0))
            // Wrapped in a panel so it paints as a filled (primary) button, not a flat toolbar button.
            add(JPanel(FlowLayout(FlowLayout.CENTER, 0, 0)).apply {
                isOpaque = false
                maximumSize = Dimension(90, 32)
                add(JButton(runAction).apply {
                    isFocusable = false
                    icon = ToolIcon(ToolIcon.Kind.RUN) { java.awt.Color.WHITE }
                    iconTextGap = 6
                    Theme.onChange {
                        putClientProperty("FlatLaf.style", "background: ${Theme.hex(Theme.accent)}; foreground: #ffffff; " +
                            "hoverBackground: lighten(${Theme.hex(Theme.accent)},6%); pressedBackground: darken(${Theme.hex(Theme.accent)},6%); " +
                            "disabledBackground: ${Theme.hex(Theme.grid)}; borderWidth: 0; focusWidth: 0; arc: 8; margin: 3,12,3,14; font: bold")
                    }
                })
            })
            add(button(pauseAction, "Pause"))
            addSeparator(Dimension(6, 0))
            add(button(stepBackAction, "Back"))
            add(button(stepAction, "Step"))
            add(button(stepOverAction, "Over"))
            add(button(stepOutAction, "Out"))
            add(button(resetAction, "Reset"))
            addSeparator(Dimension(14, 0))
            add(button(cfgAction, "Graph"))
            add(javax.swing.Box.createHorizontalGlue())
            add(JLabel("Speed").apply { Theme.onChange { foreground = Theme.dim } })
            speedSlider.apply {
                maximumSize = Dimension(150, 30)
                preferredSize = Dimension(150, 30)
                snapToTicks = true
                isFocusable = false
                toolTipText = "Run speed (instructions per second)"
                addChangeListener {
                    speedValue.text = speedLabels[value]
                    if (running) restartTimer()
                }
            }
            add(speedSlider)
            add(speedValue.apply {
                text = speedLabels[speedSlider.value]
                Theme.onChange {
                    font = Theme.monoSmall
                    preferredSize = Dimension(Theme.z(48), Theme.z(20)); maximumSize = preferredSize
                }
            })
            addSeparator(Dimension(10, 0))
            add(JButton(themeAction).apply { text = null; isFocusable = false })
        }

        val editorScroll = JScrollPane(editor).apply {
            setRowHeaderView(gutter)
            verticalScrollBar.unitIncrement = 16
            Theme.onChange { border = BorderFactory.createMatteBorder(1, 0, 1, 0, Theme.grid) }
        }
        nextLabel.apply {
            Theme.onChange { font = Theme.monoSmall }
            border = BorderFactory.createEmptyBorder(7, 12, 7, 12)
            isOpaque = true
            Theme.onChange { background = Theme.bg; foreground = Theme.text }
        }
        val editorPanel = JPanel(BorderLayout()).apply {
            Theme.onChange { preferredSize = Dimension(Theme.z(620), Theme.z(500)) }
            add(Theme.sectionLabel("Program"), BorderLayout.NORTH)
            add(editorScroll, BorderLayout.CENTER)
            add(nextLabel, BorderLayout.SOUTH)
        }

        Theme.onChange {
            registers.preferredSize = Dimension(Theme.z(420), Theme.z(500))
            registers.minimumSize = Dimension(Theme.z(300), Theme.z(300))
        }
        val right = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, registers, stackMemory).apply {
            resizeWeight = 0.4; border = null; isContinuousLayout = true
        }
        rightPanel = right
        val top = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, editorPanel, right).apply {
            resizeWeight = 0.42; border = null; isContinuousLayout = true
        }
        topPanel = top

        problemList.apply {
            Theme.onChange { font = Theme.mono }
            cellRenderer = javax.swing.DefaultListCellRenderer().let { base ->
                javax.swing.ListCellRenderer<AsmError> { list, value, index, sel, focus ->
                    (base.getListCellRendererComponent(list, "×  L${value.line + 1}  ${value.message}", index, sel, focus) as JLabel).apply {
                        if (!sel) foreground = Theme.bad
                        border = BorderFactory.createEmptyBorder(4, 10, 4, 10)
                    }
                }
            }
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) { selectedValue?.let { editor.goToLine(it.line) } }
            })
        }
        bottomTabs.addTab("Console", console)
        bottomTabs.addTab("Memory", memory)
        bottomTabs.addTab("Problems", JScrollPane(problemList))
        val reference = JEditorPane("text/html", "").apply {
            isEditable = false
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        }
        Theme.onChange { reference.text = Docs.referenceHtml(); reference.caretPosition = 0 }
        bottomTabs.addTab("Reference", JScrollPane(reference))
        bottomTabs.putClientProperty("JTabbedPane.tabAreaAlignment", "leading")

        val main = JSplitPane(JSplitPane.VERTICAL_SPLIT, top, bottomTabs).apply {
            resizeWeight = 0.75; border = null; isContinuousLayout = true
        }

        val status = JPanel(BorderLayout()).apply {
            Theme.onChange {
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.grid),
                    BorderFactory.createEmptyBorder(5, 10, 5, 12))
                stepsLabel.foreground = Theme.dim
            }
            val left = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply {
                isOpaque = false
                add(stateLabel); add(messageLabel)
            }
            add(left, BorderLayout.CENTER)
            add(stepsLabel.apply { Theme.onChange { font = Theme.monoSmall } }, BorderLayout.EAST)
        }

        return JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(main, BorderLayout.CENTER)
            add(status, BorderLayout.SOUTH)
        }
    }

    private fun buildMenus() = JMenuBar().apply {
        add(JMenu("File").apply {
            add(item("New", KeyEvent.VK_N) { if (confirmDiscard()) { file = null; docName = "untitled"; setSource(NEW_PROGRAM) } })
            add(item("Open…", KeyEvent.VK_O) { open() })
            add(item("Save", KeyEvent.VK_S) { save(false) })
            add(item("Save As…", KeyEvent.VK_S, InputEvent.SHIFT_DOWN_MASK) { save(true) })
            addSeparator()
            add(JMenu("Examples").apply {
                for ((id, label) in Examples.names) add(JMenuItem(label).apply {
                    addActionListener { if (confirmDiscard()) loadExample(id) }
                })
            })
        })
        add(JMenu("Edit").apply {
            add(item("Undo", KeyEvent.VK_Z) { if (editor.undo.canUndo()) editor.undo.undo() })
            add(item("Redo", KeyEvent.VK_Z, InputEvent.SHIFT_DOWN_MASK) { if (editor.undo.canRedo()) editor.undo.redo() })
        })
        add(JMenu("Run").apply {
            for (a in listOf(assembleAction, runAction, pauseAction, stepAction, stepBackAction, stepOverAction, stepOutAction, resetAction))
                add(JMenuItem(a))
            addSeparator()
            add(JMenuItem(breakpointAction)); add(JMenuItem(clearBreakpointsAction))
        })
        add(JMenu("View").apply {
            add(JMenuItem(cfgAction).apply { text = "Control Flow Graph" })
            add(JMenuItem(themeAction))
            addSeparator()
            add(JMenuItem(zoomInAction)); add(JMenuItem(zoomOutAction)); add(JMenuItem(zoomResetAction))
            addSeparator()
            add(javax.swing.JCheckBoxMenuItem("Stack Panel", true).apply {
                accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_M, menuKey or InputEvent.SHIFT_DOWN_MASK)
                addActionListener {
                    stackMemory.isVisible = isSelected
                    rightPanel?.resetToPreferredSizes()
                    rightPanel?.revalidate()
                }
            })
        })
        add(JMenu("Help").apply {
            add(JMenuItem("Instruction Reference").apply { addActionListener { bottomTabs.selectedIndex = 3 } })
            add(JMenuItem("About ${AppInfo.NAME}").apply { addActionListener { showAbout() } })
        })
    }

    internal fun showAbout() = JOptionPane.showMessageDialog(this, JLabel(AppInfo.aboutHtml(UIScale.scale(360))),
        "About ${AppInfo.NAME}", JOptionPane.INFORMATION_MESSAGE)

    private fun item(name: String, key: Int, extra: Int = 0, body: () -> Unit) = JMenuItem(name).apply {
        accelerator = KeyStroke.getKeyStroke(key, menuKey or extra)
        addActionListener { body() }
    }

    private fun bindFunctionKeys() {
        val im = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        val am = rootPane.actionMap
        fun bind(ks: String, a: Action) { im.put(KeyStroke.getKeyStroke(ks), ks); am.put(ks, a) }
        bind("F5", runAction); bind("F6", pauseAction); bind("F7", stepAction); bind("shift F7", stepBackAction)
        bind("F8", stepOverAction); bind("shift F8", stepOutAction); bind("F9", breakpointAction)
        bindZoomKeys(rootPane)
    }

    /** ⌘= / ⌘+ (with or without Shift, or the keypad +), ⌘− and ⌘0 — in any window of the app. */
    internal fun bindZoomKeys(root: javax.swing.JRootPane) {
        val im = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        val am = root.actionMap
        val shift = InputEvent.SHIFT_DOWN_MASK
        for ((key, mods) in listOf(KeyEvent.VK_EQUALS to 0, KeyEvent.VK_EQUALS to shift, KeyEvent.VK_PLUS to 0,
            KeyEvent.VK_PLUS to shift, KeyEvent.VK_ADD to 0)) {
            im.put(KeyStroke.getKeyStroke(key, menuKey or mods), "zoomIn")
        }
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, menuKey), "zoomOut")
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_SUBTRACT, menuKey), "zoomOut")
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_0, menuKey), "zoomReset")
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_NUMPAD0, menuKey), "zoomReset")
        am.put("zoomIn", zoomInAction); am.put("zoomOut", zoomOutAction); am.put("zoomReset", zoomResetAction)
    }

    private fun zoom(change: () -> Boolean) {
        if (!change()) {
            message(if (Theme.zoom >= 1f) "Maximum zoom" else "Minimum zoom", Theme.warn)
            return
        }
        // Give each pane room for its larger contents.
        topPanel?.resetToPreferredSizes()
        rightPanel?.resetToPreferredSizes()
        editor.scrollToLine(maxOf(editor.currentLine, 0))
        message("Zoom ${Math.round(Theme.zoom * 100)}%", Theme.accent)
    }

    // ---------------- files ----------------

    private fun setSource(text: String) {
        if (running) stopTimer()
        editor.setSource(text)
        breakpoints.clear()
        unsaved = false
        stale = true
        assemble()
        updateTitle()
    }

    internal fun loadExample(id: String) {
        file = null
        docName = Examples.names.first { it.first == id }.second
        setSource(Examples.load(id))
    }

    private fun open() {
        if (!confirmDiscard()) return
        val d = FileDialog(this, "Open assembly file", FileDialog.LOAD).apply { isVisible = true }
        val f = d.file?.let { File(d.directory, it) } ?: return
        file = f
        docName = f.name
        setSource(f.readText())
    }

    private fun save(askName: Boolean): Boolean {
        var f = file
        if (f == null || askName) {
            val d = FileDialog(this, "Save assembly file", FileDialog.SAVE).apply {
                file = this@MainWindow.file?.name ?: "program.asm"; isVisible = true
            }
            f = d.file?.let { File(d.directory, it) } ?: return false
        }
        f.writeText(editor.text)
        file = f
        docName = f.name
        unsaved = false
        updateTitle()
        message("Saved ${f.name}", Theme.ok)
        return true
    }

    private fun confirmDiscard(): Boolean {
        if (!unsaved) return true
        val r = JOptionPane.showConfirmDialog(this, "Save changes to your program first?", "Unsaved changes",
            JOptionPane.YES_NO_CANCEL_OPTION)
        return when (r) {
            JOptionPane.YES_OPTION -> save(false)
            JOptionPane.NO_OPTION -> true
            else -> false
        }
    }

    private fun updateTitle() {
        title = "${AppInfo.NAME} — $docName" + if (unsaved) " •" else ""
    }

    // ---------------- assembling & running ----------------

    private fun assemble(): Boolean {
        if (running) stopTimer()
        problems.clear()
        return try {
            val p = Assembler.assemble(editor.text)
            machine.load(p)
            stale = false
            editor.errorLines = emptySet()
            gutter.instructionLines = p.byLine.keys
            cfgWindow?.programChanged()
            stackMemory.onProgramLoaded()
            console.clear()
            memory.onProgramLoaded()
            resumeAfterInput = false; stepAfterInput = false
            if (bottomTabs.selectedIndex == 2) bottomTabs.selectedIndex = 0
            bottomTabs.setTitleAt(2, "Problems")
            refresh(null)
            message("Built · ${p.instructions.size} instructions", Theme.ok)
            true
        } catch (e: AssemblyException) {
            e.errors.forEach { problems.addElement(it) }
            editor.errorLines = e.errors.map { it.line }.toSet()
            gutter.instructionLines = null
            bottomTabs.selectedIndex = 2
            editor.currentLine = -1
            gutter.repaint()
            stateLabel.text = "Errors"
            stateLabel.pillColor = Theme.bad
            nextLabel.text = "Fix the errors to run"
            bottomTabs.setTitleAt(2, "Problems (${e.errors.size})")
            updateActions()
            message(if (e.errors.size == 1) "1 error" else "${e.errors.size} errors", Theme.bad)
            false
        }
    }

    /** Makes sure an up-to-date program is loaded and able to run. */
    private fun prepare(): Boolean {
        if (stale && !assemble()) return false
        if (machine.isFinished) {
            machine.reset(); console.clear()
            console.system("restarted")
        }
        if (machine.state == MachineState.WAITING_INPUT) {
            message("Waiting for input", Theme.warn)
            bottomTabs.selectedIndex = 0
            console.setWaiting(true)
            return false
        }
        return machine.canStep
    }

    internal fun step() {
        if (running || !prepare()) return
        val before = CpuSnapshot.of(machine)
        machine.memory.clearWriteLog()
        lastMnemonic = machine.cpu.currentInstruction()?.mnemonic ?: ""
        message(" ")
        machine.step()
        if (machine.state == MachineState.WAITING_INPUT) stepAfterInput = true
        afterStop()
        refresh(before)
    }

    internal fun run() = startRun(null, "")

    /** Undoes the last executed instruction: registers, flags, memory, output and consumed input. */
    internal fun stepBack() {
        if (running || stale || !machine.canStepBack) return
        val before = CpuSnapshot.of(machine)
        machine.memory.clearWriteLog()
        resumeAfterInput = false; stepAfterInput = false
        machine.stepBack()
        console.setWaiting(machine.state == MachineState.WAITING_INPUT)
        refresh(before)
        message(if (machine.canStepBack) "Stepped back" else "At the start", Theme.accent)
    }

    internal fun stepOver() {
        if (running || !prepare()) return
        val ins = machine.cpu.currentInstruction()
        if (ins != null && ins.prefix != RepPrefix.NONE && StringOp.of(ins.mnemonic) != null) {
            val a = ins.address
            return startRun({ machine.cpu.rip != a }, "Stepped over repeat", fullSpeed = true)
        }
        if (ins?.mnemonic != "call") return step()
        val ret = ins.address + ins.size
        val rsp0 = machine.cpu.regs[Registers.RSP]
        startRun({ machine.cpu.rip == ret && machine.cpu.regs[Registers.RSP] >= rsp0 }, "Stepped over call")
    }

    private fun stepOut() {
        if (running || !prepare()) return
        val rsp0 = machine.cpu.regs[Registers.RSP]
        startRun({ lastMnemonic == "ret" && machine.cpu.regs[Registers.RSP] > rsp0 }, "Returned")
    }

    private fun startRun(condition: (() -> Boolean)?, label: String, fullSpeed: Boolean = false) {
        if (running || !prepare()) return
        runFullSpeed = fullSpeed
        stopWhen = condition
        stopWhenLabel = label
        firstStepOfRun = true
        restartTimer()
        updateActions()
        message("", Theme.accent)
    }

    private var firstStepOfRun = false

    private fun restartTimer() {
        timer?.stop()
        val ips = speeds[speedSlider.value]
        val delay = if (ips <= 60 && !runFullSpeed) 1000 / ips else 15
        timer = Timer(delay) { tick() }.apply { initialDelay = 0; start() }
    }

    private fun stopTimer() {
        timer?.stop()
        timer = null
        runFullSpeed = false
        updateActions()
    }

    private fun tick() {
        val ips = speeds[speedSlider.value]
        val budget = when {
            runFullSpeed -> Int.MAX_VALUE
            ips <= 60 -> 1
            ips == Int.MAX_VALUE -> Int.MAX_VALUE
            else -> ips * 15 / 1000
        }
        val deadline = System.nanoTime() + 14_000_000
        val before = CpuSnapshot.of(machine)
        machine.memory.clearWriteLog()
        var n = 0
        while (n < budget) {
            val ins = machine.cpu.currentInstruction()
            if (!firstStepOfRun) {
                if (stopWhen?.invoke() == true) { pause(stopWhenLabel); break }
                if (ins != null && ins.line in breakpoints && !machine.cpu.repeating) { pause("Breakpoint · L${ins.line + 1}"); break }
            }
            firstStepOfRun = false
            lastMnemonic = ins?.mnemonic ?: ""
            if (!machine.step()) {
                if (machine.state == MachineState.WAITING_INPUT) { resumeAfterInput = true; resumeFullSpeed = runFullSpeed }
                stopTimer()
                afterStop()
                break
            }
            n++
            if ((n and 1023) == 0 && System.nanoTime() > deadline) break
            if (machine.memory.writeLog.size > 10_000) machine.memory.clearWriteLog()
        }
        refresh(before)
    }

    private fun pause(msg: String) {
        stopTimer()
        refresh(null)
        message(msg, Theme.accent)
    }

    private fun afterStop() {
        when (machine.state) {
            MachineState.WAITING_INPUT -> {
                bottomTabs.selectedIndex = 0
                console.setWaiting(true)
            }
            MachineState.EXITED, MachineState.HALTED -> console.system(machine.message)
            MachineState.FAULTED -> {
                console.error(machine.message)
                machine.cpu.currentInstruction()?.let { console.error("L${it.line + 1}: ${it.source.substringBefore(';').trim()}") }
            }
            else -> {}
        }
    }

    private fun afterInput() {
        console.setWaiting(false)
        when {
            resumeAfterInput -> { resumeAfterInput = false; startRun(stopWhen, stopWhenLabel, resumeFullSpeed) }
            stepAfterInput -> { stepAfterInput = false; step() } // finish the read that was waiting
            else -> refresh(null)
        }
    }

    private fun reset() {
        if (stale) { assemble(); return }
        stopTimer()
        machine.reset()
        console.clear()
        resumeAfterInput = false; stepAfterInput = false
        console.setWaiting(false)
        refresh(null)
        message("Reset", Theme.accent)
    }

    private fun toggleBreakpoint(line: Int) {
        if (!breakpoints.remove(line)) breakpoints += line
        gutter.repaint()
        cfgWindow?.repaint()
        if (line in breakpoints && gutter.instructionLines?.contains(line) == false)
            message("No instruction on L${line + 1}", Theme.warn)
    }

    internal fun showCfg() {
        if (stale && !assemble()) {
            message("Fix errors first", Theme.bad)
            return
        }
        val w = cfgWindow ?: CfgWindow(this, machine, breakpoints) { editor.goToLine(it) }.also {
            cfgWindow = it
            bindZoomKeys(it.rootPane)
            it.programChanged()
        }
        w.refresh()
        w.isVisible = true
        w.toFront()
    }

    // ---------------- view refresh ----------------

    private fun refresh(before: CpuSnapshot?) {
        registers.refresh(before)
        stackMemory.refresh()
        memory.refresh()
        val ins = if (machine.program != null && !machine.isFinished) machine.cpu.currentInstruction() else null
        editor.currentLine = ins?.line ?: -1
        if (ins != null) editor.scrollToLine(ins.line)
        gutter.repaint()
        cfgWindow?.takeIf { it.isVisible }?.refresh()

        nextLabel.text = when {
            ins != null -> {
                val doc = StringOp.of(ins.mnemonic)?.let { Docs.stringIteration(it, ins.prefix, machine.cpu.regs[Registers.RCX], machine.cpu.df) }
                    ?: Docs.lookup(ins.mnemonic)?.let { shortDoc(it.description) }
                "<html><font color='${Theme.hex(Theme.dim)}'>NEXT</font>&nbsp;&nbsp;<b>${escape(ins.source.substringBefore(';').trim())}</b>" +
                    (doc?.let { "&nbsp;&nbsp;<font color='${Theme.hex(Theme.dim)}'>$it</font>" } ?: "") +
                    syscallHint(ins.mnemonic) + "</html>"
            }
            machine.isFinished -> "Finished"
            else -> " "
        }
        stateLabel.text = if (running) "Running" else machine.state.label
        stateLabel.pillColor = when {
            running -> Theme.accent
            machine.state == MachineState.FAULTED -> Theme.bad
            machine.state == MachineState.EXITED -> Theme.ok
            machine.state == MachineState.WAITING_INPUT || machine.state == MachineState.HALTED -> Theme.warn
            else -> Theme.dim
        }
        if (!running && machine.state != MachineState.PAUSED && machine.state != MachineState.READY && machine.state != MachineState.EMPTY)
            message(machine.message, stateLabel.pillColor)
        stepsLabel.text = "${machine.steps} steps"
        updateActions()
    }

    private fun syscallHint(m: String): String {
        if (m != "syscall") return ""
        val nr = machine.cpu.regs[Registers.RAX]
        val name = Machine.SYSCALL_NAMES[nr] ?: "unknown"
        return "&nbsp;&nbsp;<font color='${Theme.hex(Theme.synDirective)}'>$name</font>"
    }

    /** First sentence of a description, capped to keep the status line short. */
    private fun shortDoc(d: String): String {
        val first = d.substringBefore(". ").removeSuffix(".")
        return escape(if (first.length > 44) first.take(42).trimEnd() + "…" else first)
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun message(text: String, color: Color = Theme.dim) {
        messageLabel.text = text
        messageLabel.foreground = color
    }

    private fun updateActions() {
        val canGo = !running && (stale || machine.program != null)
        assembleAction.isEnabled = !running
        runAction.isEnabled = canGo
        stepAction.isEnabled = canGo
        stepBackAction.isEnabled = !running && !stale && machine.canStepBack
        stepOverAction.isEnabled = canGo
        stepOutAction.isEnabled = canGo
        pauseAction.isEnabled = running
        resetAction.isEnabled = !running && machine.program != null
    }

    companion object {
        private const val NEW_PROGRAM = """section .data
    ; your data here

section .text
global _start
_start:
    ; your code here

    mov rax, 60         ; exit(0)
    xor edi, edi
    syscall
"""

        fun launch() {
            System.setProperty("apple.laf.useScreenMenuBar", "true")
            System.setProperty("apple.awt.application.name", AppInfo.NAME)
            System.setProperty("awt.useSystemAAFontSettings", "on")
            SwingUtilities.invokeLater {
                Theme.install()
                MainWindow().isVisible = true
            }
        }
    }
}
