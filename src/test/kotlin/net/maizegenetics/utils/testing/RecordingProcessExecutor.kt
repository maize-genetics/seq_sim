package net.maizegenetics.utils.testing

import net.maizegenetics.utils.ProcessExecutor
import org.apache.logging.log4j.Logger
import java.io.File

/**
 * Test double for [ProcessExecutor] that:
 *
 *  - Records every invocation so tests can assert on the exact command line,
 *    working directory, and output-file redirection.
 *  - Creates empty placeholder files for any `outputFile` argument so that
 *    pipeline code which reads those files downstream doesn't blow up on
 *    "file not found".
 *  - Optionally lets tests script exit codes or produce synthetic output
 *    files (e.g. a fake `.maf` or `.g.vcf.gz`) via the [onInvoke] hook.
 *
 * Install via [net.maizegenetics.utils.ProcessRunner.withExecutor]:
 *
 * ```
 * val exec = RecordingProcessExecutor()
 * ProcessRunner.withExecutor(exec) {
 *     MyCommand().parse(arrayOf("--ref-fasta", "ref.fa", ...))
 * }
 * assertEquals(2, exec.invocations.size)
 * ```
 */
class RecordingProcessExecutor(
    private val defaultExitCode: Int = 0,
    private val onInvoke: ((Invocation) -> Int)? = null
) : ProcessExecutor {

    data class Invocation(
        val command: List<String>,
        val workingDir: File?,
        val outputFile: File?
    ) {
        fun joinedCommand(): String = command.joinToString(" ")
        fun argAfter(flag: String): String? {
            val idx = command.indexOf(flag)
            return if (idx >= 0 && idx + 1 < command.size) command[idx + 1] else null
        }
    }

    private val _invocations = mutableListOf<Invocation>()
    val invocations: List<Invocation> get() = _invocations.toList()

    override fun runCommand(
        command: Array<out String>,
        workingDir: File?,
        outputFile: File?,
        logger: Logger
    ): Int {
        val invocation = Invocation(command.toList(), workingDir, outputFile)
        _invocations.add(invocation)

        // Create empty placeholder files so downstream code that checks
        // `*.exists()` or iterates output directories doesn't fail.
        outputFile?.let { file ->
            file.parentFile?.mkdirs()
            if (!file.exists()) file.createNewFile()
        }

        val hookExit = onInvoke?.invoke(invocation)
        return hookExit ?: defaultExitCode
    }

    fun clear() {
        _invocations.clear()
    }

    /** Return all invocations whose first token matches [executable]. */
    fun invocationsOf(executable: String): List<Invocation> =
        _invocations.filter { it.command.firstOrNull() == executable }

    /** Assert that at least one invocation contains [tokens] as a contiguous subsequence. */
    fun containsSubsequence(vararg tokens: String): Boolean =
        _invocations.any { invocation ->
            val cmd = invocation.command
            (0..cmd.size - tokens.size).any { start ->
                tokens.withIndex().all { (i, t) -> cmd[start + i] == t }
            }
        }
}
