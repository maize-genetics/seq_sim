package net.maizegenetics.utils

import org.apache.logging.log4j.Logger
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object ProcessRunner {
    private const val INDENT = "  | "

    fun runCommand(vararg command: String, workingDir: File? = null, outputFile: File? = null, logger: Logger): Int {
        return try {
            logger.debug("Executing: ${command.joinToString(" ")}")
            val processBuilder = ProcessBuilder(*command)

            if (workingDir != null) {
                processBuilder.directory(workingDir)
                logger.debug("Working directory: $workingDir")
            }

            // Handle output redirection to file
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

            // Capture output for logging with indentation
            val process = processBuilder.start()

            // Create threads to read stdout and stderr
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

            // Wait for output threads to finish
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
