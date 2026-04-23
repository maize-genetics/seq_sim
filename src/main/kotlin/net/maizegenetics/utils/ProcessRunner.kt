package net.maizegenetics.utils

import org.apache.logging.log4j.Logger
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Thin facade that every pipeline command calls through. Holds a swappable
 * [ProcessExecutor] so tests can inject a recording/mock implementation
 * without having to thread an executor reference through every command.
 *
 * The default delegate is [RealProcessExecutor] which preserves the prior
 * behavior exactly.
 */
object ProcessRunner {

    @Volatile
    private var delegate: ProcessExecutor = RealProcessExecutor

    /**
     * Controls whether a leading `pixi run` prefix is stripped from every
     * command before execution. Defaults from `SEQ_SIM_SKIP_PIXI_PREFIX`
     * but can be toggled directly by tests.
     */
    @Volatile
    var skipPixiPrefix: Boolean = run {
        val v = System.getenv("SEQ_SIM_SKIP_PIXI_PREFIX")
        !v.isNullOrBlank() && !v.equals("false", ignoreCase = true) && v != "0"
    }

    /**
     * Execute an external command. All pipeline commands call this.
     *
     * When [skipPixiPrefix] is true, any leading `pixi run` prefix is
     * stripped before the underlying executor is invoked. This lets the
     * containerized dev environment reuse the PHGv2 conda env tools
     * directly without a nested `pixi run` shell.
     */
    fun runCommand(
        vararg command: String,
        workingDir: File? = null,
        outputFile: File? = null,
        logger: Logger
    ): Int {
        val normalized = maybeStripPixiPrefix(command)
        return delegate.runCommand(
            command = normalized,
            workingDir = workingDir,
            outputFile = outputFile,
            logger = logger
        )
    }

    /** Install a custom executor; returns the previous one so tests can restore it. */
    fun setExecutor(executor: ProcessExecutor): ProcessExecutor {
        val prev = delegate
        delegate = executor
        return prev
    }

    /** Convenience for tests: run [block] with [executor] installed and restore afterwards. */
    fun <T> withExecutor(executor: ProcessExecutor, block: () -> T): T {
        val prev = setExecutor(executor)
        return try {
            block()
        } finally {
            delegate = prev
        }
    }

    /** Reset to the real executor. Primarily useful from `@AfterEach` hooks. */
    fun resetExecutor() {
        delegate = RealProcessExecutor
    }

    private fun maybeStripPixiPrefix(command: Array<out String>): Array<out String> {
        if (!skipPixiPrefix) return command
        if (command.size < 2) return command
        if (command[0] != "pixi" || command[1] != "run") return command
        return command.copyOfRange(2, command.size)
    }
}

/**
 * Production implementation: spawn a real child process, optionally redirect
 * stdout to a file, and mirror stderr/stdout into the provided logger.
 */
object RealProcessExecutor : ProcessExecutor {
    private const val INDENT = "  | "

    override fun runCommand(
        command: Array<out String>,
        workingDir: File?,
        outputFile: File?,
        logger: Logger
    ): Int {
        return try {
            logger.debug("Executing: ${command.joinToString(" ")}")
            val processBuilder = ProcessBuilder(*command)

            if (workingDir != null) {
                processBuilder.directory(workingDir)
                logger.debug("Working directory: $workingDir")
            }

            if (outputFile != null) {
                processBuilder.redirectOutput(ProcessBuilder.Redirect.to(outputFile))
                processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT)
                logger.debug("Redirecting output to: $outputFile")

                val process = processBuilder.start()
                val exitCode = process.waitFor()

                if (exitCode != 0) {
                    logger.error("Command failed with exit code $exitCode")
                }
                return exitCode
            }

            val process = processBuilder.start()

            val stdoutThread = Thread {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    reader.lineSequence().forEach { line ->
                        logger.info("$INDENT$line")
                    }
                }
            }

            val stderrThread = Thread {
                BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                    reader.lineSequence().forEach { line ->
                        logger.warn("$INDENT$line")
                    }
                }
            }

            stdoutThread.start()
            stderrThread.start()

            val exitCode = process.waitFor()

            stdoutThread.join()
            stderrThread.join()

            if (exitCode != 0) {
                logger.error("Command failed with exit code $exitCode: ${command.joinToString(" ")}")
            }
            exitCode
        } catch (e: Exception) {
            logger.error("Failed to execute command: ${e.message}", e)
            -1
        }
    }
}
