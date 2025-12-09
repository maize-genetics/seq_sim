package net.maizegenetics.utils

import net.maizegenetics.Constants
import org.apache.logging.log4j.Logger
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.system.exitProcess

/**
 * Utility functions for validation operations across commands
 */
object ValidationUtils {

    /**
     * Validates that the working directory exists
     * Exits with error if validation fails
     *
     * @param workDir The working directory to validate
     * @param logger Logger for error messages
     */
    fun validateWorkingDirectory(workDir: Path, logger: Logger) {
        if (!workDir.exists()) {
            logger.error("Working directory does not exist: $workDir")
            logger.error("Please run 'setup-environment' command first")
            exitProcess(1)
        }
    }

    /**
     * Validates that a binary/tool exists at the expected path
     * Exits with error if validation fails
     *
     * @param binaryPath The full path to the binary
     * @param toolName Human-readable tool name for error messages
     * @param logger Logger for error messages
     */
    fun validateBinaryExists(binaryPath: Path, toolName: String, logger: Logger) {
        if (!binaryPath.exists()) {
            logger.error("$toolName binary not found: $binaryPath")
            logger.error("Please run 'setup-environment' command first")
            exitProcess(1)
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
        val bioktBinary = resolveBinaryPath(workDir, Constants.BIOKOTLIN_TOOLS_DIR, "biokotlin")
        validateBinaryExists(bioktBinary, "biokotlin-tools", logger)
        return bioktBinary
    }
}
