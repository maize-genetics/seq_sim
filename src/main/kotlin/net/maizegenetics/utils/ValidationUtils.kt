package net.maizegenetics.utils

import com.github.ajalt.clikt.core.CliktError
import net.maizegenetics.Constants
import org.apache.logging.log4j.Logger
import java.nio.file.Path
import kotlin.io.path.*

/**
 * Thrown when a pipeline command cannot continue due to an unrecoverable
 * error (missing binary, failed subprocess, ...). Clikt's top-level error
 * handling prints the message and sets the JVM exit code, while tests that
 * drive commands via `CliktCommand.parse(...)` can catch this and make
 * assertions instead of having `System.exit` kill the test runner.
 */
class SeqSimCommandException(message: String, val exitCode: Int = 1) :
    CliktError(message, statusCode = exitCode)

/**
 * Utility functions for validation operations across commands
 */
object ValidationUtils {

    /**
     * Validates that the working directory exists.
     * @throws SeqSimCommandException if validation fails.
     */
    fun validateWorkingDirectory(workDir: Path, logger: Logger) {
        if (!workDir.exists()) {
            logger.error("Working directory does not exist: $workDir")
            logger.error("Please run 'setup-environment' command first")
            throw SeqSimCommandException("Working directory does not exist: $workDir")
        }
    }

    /**
     * Validates that a binary/tool exists at the expected path.
     * @throws SeqSimCommandException if validation fails.
     */
    fun validateBinaryExists(binaryPath: Path, toolName: String, logger: Logger) {
        if (!binaryPath.exists()) {
            logger.error("$toolName binary not found: $binaryPath")
            logger.error("Please run 'setup-environment' command first")
            throw SeqSimCommandException("$toolName binary not found: $binaryPath")
        }
    }

    /**
     * Resolves the path to a binary in the working directory's src folder
     *
     * @param workDir The working directory
     * @param toolDir The tool directory name (e.g., Constants.PHGV2_DIR)
     * @param binaryName The binary file name
     * @return The resolved binary path
     */
    fun resolveBinaryPath(workDir: Path, toolDir: String, binaryName: String): Path {
        return workDir.resolve(Constants.SRC_DIR)
            .resolve(toolDir)
            .resolve("bin")
            .resolve(binaryName)
    }

    /**
     * Validates working directory and PHG binary in one call
     * Common pattern for PHG-dependent commands
     *
     * @param workDir The working directory
     * @param logger Logger for error messages
     * @return Path to the PHG binary
     */
    fun validatePhgSetup(workDir: Path, logger: Logger): Path {
        validateWorkingDirectory(workDir, logger)
        val phgBinary = resolveBinaryPath(workDir, Constants.PHGV2_DIR, "phg")
        validateBinaryExists(phgBinary, "PHG", logger)
        return phgBinary
    }

    /**
     * Validates working directory and biokotlin-tools binary in one call
     *
     * @param workDir The working directory
     * @param logger Logger for error messages
     * @return Path to the biokotlin-tools binary
     */
    fun validateBiokotlinSetup(workDir: Path, logger: Logger): Path {
        validateWorkingDirectory(workDir, logger)
        val bioktBinary = resolveBinaryPath(workDir, Constants.BIOKOTLIN_TOOLS_DIR, "biokotlin-tools")
        validateBinaryExists(bioktBinary, "biokotlin-tools", logger)
        return bioktBinary
    }
}
