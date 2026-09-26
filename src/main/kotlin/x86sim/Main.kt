package x86sim

import java.io.File
import kotlin.system.exitProcess
import x86sim.asm.Assembler
import x86sim.asm.AssemblyException
import x86sim.cpu.Registers
import x86sim.ui.MainWindow

object Examples {
    val names = listOf(
        "01_hello" to "Hello, world",
        "02_loop_sum" to "Loop: sum 1..100",
        "03_factorial" to "Recursion: factorial",
        "04_echo_name" to "Keyboard input",
        "05_fibonacci" to "Arrays: Fibonacci",
        "06_bubble_sort" to "Bubble sort",
        "07_flags" to "Flags tour",
        "08_locals" to "Stack: local variables",
    )

    fun load(id: String): String =
        Examples::class.java.getResource("/examples/$id.asm")?.readText()
            ?: error("missing example $id")
}

private const val USAGE = """x86-64 simulator

Usage:
  x86sim                         open the visual simulator
  x86sim run <file.asm> [--trace] assemble and run in the terminal
  x86sim run --example <name>     run a built-in example (e.g. 01_hello)
"""

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        MainWindow.launch()
        return
    }
    if (args[0] != "run" || args.size < 2) {
        print(USAGE); exitProcess(if (args[0] in listOf("-h", "--help")) 0 else 2)
    }
    val source = if (args[1] == "--example") Examples.load(args.getOrElse(2) { "01_hello" })
        else File(args[1]).readText()
    exitProcess(runCli(source, trace = "--trace" in args))
}

fun runCli(source: String, trace: Boolean): Int {
    val program = try {
        Assembler.assemble(source)
    } catch (e: AssemblyException) {
        e.errors.forEach { System.err.println("error: $it") }
        return 2
    }
    val m = Machine()
    m.keepHistory = false // the terminal runner never steps back
    m.onOutput = { print(it); System.out.flush() }
    m.onNotice = { System.err.println("[sim] $it") }
    m.load(program)
    while (true) {
        if (trace) m.cpu.currentInstruction()?.let {
            System.err.println("%08x  %-40s rax=%x rsp=%x".format(it.address, it.source, m.cpu.regs[Registers.RAX], m.cpu.regs[Registers.RSP]))
        }
        if (m.step()) continue
        if (m.state == MachineState.WAITING_INPUT) {
            val line = readlnOrNull()
            if (line == null) m.closeInput() else m.provideInput(line + "\n")
            continue
        }
        break
    }
    System.err.println("[sim] ${m.message} (${m.steps} instructions)")
    return m.exitCode ?: if (m.state == MachineState.FAULTED) 139 else 0
}
