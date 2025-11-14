package net.maizegenetics.utils

import org.apache.logging.log4j.Logger
import java.io.File

object ProcessRunner {
    fun runCommand(vararg command: String, workingDir: File? = null, outputFile: File? = null, logger: Logger): Int {
        return try {
            logger.debug("Executing: ${command.joinToString(" ")}")
            val processBuilder = ProcessBuilder(*command)
                .redirectError(ProcessBuilder.Redirect.INHERIT)

            // Handle output redirection
            if (outputFile != null) {
                processBuilder.redirectOutput(ProcessBuilder.Redirect.to(outputFile))
                logger.debug("Redirecting output to: $outputFile")
            } else {
                processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT)
            }

            if (workingDir != null) {
                processBuilder.directory(workingDir)
                logger.debug("Working directory: $workingDir")
            }

            val process = processBuilder.start()

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                logger.error("Command failed with exit code $exitCode")
            }
            exitCode
        } catch (e: Exception) {
            logger.error("Failed to execute command: ${e.message}", e)
            -1
        }
    }
}
