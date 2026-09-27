package x86sim

import x86sim.asm.Program
import x86sim.cpu.Cpu
import x86sim.cpu.CpuFault
import x86sim.cpu.Memory
import x86sim.cpu.Region
import x86sim.cpu.Registers.RAX
import x86sim.cpu.Registers.RDI
import x86sim.cpu.Registers.RDX
import x86sim.cpu.Registers.RSI
import x86sim.cpu.Registers.RSP
import x86sim.cpu.StepResult

enum class MachineState(val label: String) {
    EMPTY("No program"),
    READY("Ready"),
    PAUSED("Paused"),
    WAITING_INPUT("Input"),
    EXITED("Exited"),
    HALTED("Halted"),
    FAULTED("Crashed"),
}

/**
 * A tiny Linux-like process: the assembled program's sections mapped into memory, a
 * stack, and the handful of system calls needed for console I/O.
 */
class Machine {
    val memory = Memory()
    val cpu = Cpu(memory)
    var program: Program? = null
        private set

    var state = MachineState.EMPTY
        private set
    var message = ""
        private set
    var steps = 0L
        private set
    var exitCode: Int? = null
        private set

    /** Receives everything the program writes to stdout/stderr. */
    var onOutput: (String) -> Unit = { print(it) }

    /** Called when a syscall (other than the supported ones) is attempted — informational only. */
    var onNotice: (String) -> Unit = {}

    private val stdin = ArrayDeque<Byte>()
    private var stdinClosed = false

    // ---------------- history (step back) ----------------

    /** Everything one step changed, so it can be undone. */
    private class StepRecord(
        val regs: LongArray, val rip: Long, val flags: Long, val repeating: Boolean,
        val state: MachineState, val message: String, val steps: Long, val exitCode: Int?,
        val outputMark: Int,
    ) {
        val memory = mutableListOf<x86sim.cpu.MemUndo>()
        val stdinTaken = mutableListOf<Byte>()
    }

    private val history = ArrayDeque<StepRecord>()
    private var recording: StepRecord? = null

    /** Whether steps are recorded so they can be undone (the CLI turns this off). */
    var keepHistory = true
    /** How many steps can be undone at most; older ones are forgotten. */
    var historyLimit = 50_000

    /** Returns a position in the program's output; restored with [onRewindOutput] when stepping back. */
    var outputMark: () -> Int = { 0 }
    var onRewindOutput: (Int) -> Unit = {}

    val canStepBack get() = history.isNotEmpty()
    val historySize get() = history.size

    /** Undoes the most recent step. Returns false when there's nothing to undo. */
    fun stepBack(): Boolean {
        val r = history.removeLastOrNull() ?: return false
        memory.undo(r.memory)
        r.regs.copyInto(cpu.regs)
        cpu.rip = r.rip
        cpu.setFlags(r.flags)
        cpu.repeating = r.repeating
        state = r.state
        message = r.message
        steps = r.steps
        exitCode = r.exitCode
        for (b in r.stdinTaken.asReversed()) stdin.addFirst(b)
        onRewindOutput(r.outputMark)
        return true
    }

    val isFinished get() = state == MachineState.EXITED || state == MachineState.HALTED || state == MachineState.FAULTED
    val canStep get() = state == MachineState.READY || state == MachineState.PAUSED

    init {
        cpu.syscallHandler = x86sim.cpu.SyscallHandler { syscall(it) }
    }

    fun load(p: Program) {
        program = p
        memory.clear()
        for (s in p.sections) {
            if (s.name == ".text" || s.length == 0L) continue
            val size = ((s.length + PAGE - 1) / PAGE * PAGE).toInt()
            val r = memory.map(Region(s.name, s.start, size, s.writable))
            if (!s.bss) s.data.toByteArray().copyInto(r.bytes)
        }
        memory.map(Region("stack", STACK_TOP - STACK_SIZE, STACK_SIZE.toInt(), writable = true))
        cpu.reset()
        cpu.code = p.byAddress
        cpu.rip = p.entry
        cpu.regs[RSP] = STACK_TOP
        memory.writerTag = -1
        cpu.push(EXIT_ADDRESS) // so that `ret` from the entry function ends the program cleanly
        memory.clearWriteLog()
        stdin.clear(); stdinClosed = false
        history.clear()
        steps = 0
        exitCode = null
        state = MachineState.READY
        message = "Loaded"
    }

    fun reset() { program?.let { load(it) } }

    fun provideInput(text: String) {
        text.toByteArray(Charsets.UTF_8).forEach { stdin.addLast(it) }
        if (state == MachineState.WAITING_INPUT) state = MachineState.PAUSED
    }

    fun closeInput() {
        stdinClosed = true
        if (state == MachineState.WAITING_INPUT) state = MachineState.PAUSED
    }

    /** Executes one instruction. Returns false if the machine can't (or can no longer) run. */
    fun step(): Boolean {
        if (!canStep) return false
        memory.writerTag = cpu.currentInstruction()?.line ?: -1
        val rec = if (keepHistory) StepRecord(cpu.regs.copyOf(), cpu.rip, cpu.rflags, cpu.repeating, state, message, steps, exitCode, outputMark()) else null
        recording = rec
        memory.undoLog = rec?.memory
        val result = try {
            cpu.step()
        } catch (f: CpuFault) {
            state = MachineState.FAULTED
            message = f.message ?: "fault"
            remember(rec) // a crash can be stepped back out of, too
            return false
        } finally {
            memory.undoLog = null
            recording = null
        }
        // A blocked read changed nothing, so there's nothing to undo.
        if (result != StepResult.Blocked) remember(rec)
        when (result) {
            StepResult.Ok -> {
                steps++
                if (cpu.rip == EXIT_ADDRESS) {
                    exitCode = cpu.regs[RAX].toInt()
                    finish(MachineState.EXITED, "Exited · code ${cpu.regs[RAX].toInt()}")
                    return false
                }
                state = MachineState.PAUSED
                return true
            }
            StepResult.Blocked -> {
                state = MachineState.WAITING_INPUT
                message = "Waiting for input"
                return false
            }
            is StepResult.Exit -> { steps++; exitCode = result.code; finish(MachineState.EXITED, "Exited · code ${result.code}") }
            is StepResult.Halt -> { steps++; finish(MachineState.HALTED, "Halted") }
        }
        return false
    }

    private fun finish(s: MachineState, msg: String) { state = s; message = msg }

    private fun remember(rec: StepRecord?) {
        if (rec == null) return
        history.addLast(rec)
        if (history.size > historyLimit) history.removeFirst()
    }

    /** Runs until the program stops or [maxSteps] have run (used by the CLI and tests). */
    fun runToEnd(maxSteps: Long = 50_000_000): MachineState {
        var n = 0L
        while (step()) {
            if (++n >= maxSteps) { message = "Stopped after $maxSteps steps"; break }
        }
        return state
    }

    // ---------------- Linux system calls ----------------

    private fun syscall(cpu: Cpu): StepResult {
        val r = cpu.regs
        when (val nr = r[RAX]) {
            SYS_READ -> {
                if (r[RDI] != 0L) { r[RAX] = -EBADF; return StepResult.Ok }
                if (stdin.isEmpty() && !stdinClosed) return StepResult.Blocked
                // Terminal-style line buffering: deliver at most one line per read.
                val count = r[RDX].coerceAtMost(1 shl 20).toInt()
                var n = 0
                try {
                    while (n < count && stdin.isNotEmpty()) {
                        val b = stdin.removeFirst()
                        recording?.stdinTaken?.add(b)
                        memory.write(r[RSI] + n, 1, b.toLong())
                        n++
                        if (b == '\n'.code.toByte()) break
                    }
                } catch (_: CpuFault) { r[RAX] = -EFAULT; return StepResult.Ok }
                r[RAX] = n.toLong()
            }
            SYS_WRITE -> {
                if (r[RDI] != 1L && r[RDI] != 2L) { r[RAX] = -EBADF; return StepResult.Ok }
                val count = r[RDX]
                if (count < 0 || count > (1 shl 20)) { r[RAX] = -EINVAL; return StepResult.Ok }
                val bytes = try { memory.readBytes(r[RSI], count.toInt()) }
                    catch (_: CpuFault) { r[RAX] = -EFAULT; return StepResult.Ok }
                onOutput(String(bytes, Charsets.UTF_8))
                r[RAX] = count
            }
            SYS_EXIT, SYS_EXIT_GROUP -> return StepResult.Exit(r[RDI].toInt())
            SYS_GETPID -> r[RAX] = 4242
            SYS_TIME -> r[RAX] = System.currentTimeMillis() / 1000
            else -> {
                onNotice("syscall $nr not supported")
                r[RAX] = -ENOSYS
            }
        }
        return StepResult.Ok
    }

    companion object {
        const val PAGE = 0x1000L
        const val STACK_TOP = 0x7FFF_FFFF_F000L
        const val STACK_SIZE = 0x10000L
        const val EXIT_ADDRESS = 0x400000L

        const val SYS_READ = 0L
        const val SYS_WRITE = 1L
        const val SYS_GETPID = 39L
        const val SYS_EXIT = 60L
        const val SYS_TIME = 201L
        const val SYS_EXIT_GROUP = 231L

        const val EBADF = 9L
        const val EFAULT = 14L
        const val EINVAL = 22L
        const val ENOSYS = 38L

        val SYSCALL_NAMES = mapOf(SYS_READ to "read", SYS_WRITE to "write", SYS_GETPID to "getpid",
            SYS_EXIT to "exit", SYS_TIME to "time", SYS_EXIT_GROUP to "exit_group")
    }
}
