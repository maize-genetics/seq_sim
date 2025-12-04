package net.maizegenetics.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import net.maizegenetics.Constants
import net.maizegenetics.utils.FileDownloader
import net.maizegenetics.utils.LoggingUtils
import net.maizegenetics.utils.ProcessRunner
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.system.exitProcess

class SetupEnvironment : CliktCommand(name = "setup-environment") {
    companion object {
        private const val LOG_FILE_NAME = "00_setup_environment.log"
        private const val PIXI_TOML_RESOURCE = "/pixi.toml"
        private const val PIXI_TOML_FILE = "pixi.toml"
    }

    private val logger: Logger = LogManager.getLogger(SetupEnvironment::class.java)

    private val workDir by option(
        "--work-dir", "-w",
        help = "Working directory for files and scripts"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)
        .default(Path.of(Constants.DEFAULT_WORK_DIR))

    override fun run() {
        // Create working directory if it doesn't exist
        if (!workDir.exists()) {
            workDir.createDirectories()
        }

        // Configure file logging to working directory
        LoggingUtils.setupFileLogging(workDir, LOG_FILE_NAME, logger)

        logger.info("Starting environment setup")
        logger.info("Working directory: $workDir")

        // Copy pixi.toml from resources to working directory
        val pixiTomlFile = workDir.resolve(PIXI_TOML_FILE).toFile()
        if (!FileDownloader.copyResourceToFile(PIXI_TOML_RESOURCE, pixiTomlFile, logger)) {
            exitProcess(1)
        }

        // Install pixi environment in working directory
        val pixiExitCode = ProcessRunner.runCommand("pixi", "install", workingDir = workDir.toFile(), logger = logger)
        if (pixiExitCode != 0) {
            logger.error("pixi install failed with exit code $pixiExitCode")
            logger.error("Make sure pixi is installed. Visit: https://pixi.sh/")
            exitProcess(pixiExitCode)
        }
        logger.info("Pixi environment initialized successfully")

        // Create src directory
        val srcDir = workDir.resolve(Constants.SRC_DIR)
        if (!srcDir.exists()) {
            logger.debug("Creating src directory: $srcDir")
            srcDir.createDirectories()
        }

        // Download and extract MLImpute repository
        val mlImputeDir = srcDir.resolve(Constants.MLIMPUTE_DIR).toFile()

        if (mlImputeDir.exists()) {
            logger.info("MLImpute directory already exists: $mlImputeDir")
        } else {
            // Download to src directory
            if (!FileDownloader.downloadAndExtractZip(Constants.MLIMPUTE_URL, srcDir, logger)) {
                exitProcess(1)
            }

            // Find extracted directory and rename to standard name (removes "-main" suffix)
            val extractedDir = srcDir.toFile().listFiles { file ->
                file.isDirectory && file.name.startsWith("MLImpute") && file.name != Constants.MLIMPUTE_DIR
            }?.firstOrNull()

            if (extractedDir != null) {
                logger.info("Renaming ${extractedDir.name} to ${Constants.MLIMPUTE_DIR}")
                if (!extractedDir.renameTo(mlImputeDir)) {
                    logger.warn("Failed to rename MLImpute directory, will use extracted name: ${extractedDir.name}")
                }
            } else {
                logger.warn("Could not find extracted MLImpute directory")
            }
        }

        // Make MLImpute gradlew executable
        val mlImputeGradlew = srcDir.resolve(Constants.MLIMPUTE_DIR)
            .resolve("src")
            .resolve("kotlin")
            .resolve("gradlew")
            .toFile()

        if (mlImputeGradlew.exists()) {
            if (mlImputeGradlew.setExecutable(true)) {
                logger.info("Made MLImpute gradlew executable: ${mlImputeGradlew.path}")
            } else {
                logger.warn("Failed to set executable permissions on gradlew: ${mlImputeGradlew.path}")
            }
        } else {
            logger.warn("MLImpute gradlew not found at expected location: ${mlImputeGradlew.path}")
        }

        // Download and extract biokotlin-tools
        val biokotlinDir = srcDir.resolve(Constants.BIOKOTLIN_TOOLS_DIR).toFile()

        if (biokotlinDir.exists()) {
            logger.info("biokotlin-tools directory already exists: $biokotlinDir")
        } else {
            // Download and extract to src directory
            if (!FileDownloader.downloadAndExtractTar(Constants.BIOKOTLIN_TOOLS_URL, srcDir, logger)) {
                exitProcess(1)
            }
        }

        // Download and extract PHGv2 latest release
        val phgv2Dir = srcDir.resolve(Constants.PHGV2_DIR).toFile()

        if (phgv2Dir.exists()) {
            logger.info("PHGv2 directory already exists: $phgv2Dir")
        } else {
            if (!FileDownloader.downloadLatestGitHubReleaseTar(Constants.PHGV2_API_URL, srcDir, logger)) {
                exitProcess(1)
            }

            // Find extracted directory and rename to standard name
            val extractedDir = srcDir.toFile().listFiles { file ->
                file.isDirectory && file.name.startsWith("phg") && file.name != Constants.PHGV2_DIR
            }?.firstOrNull()

            if (extractedDir != null) {
                logger.info("Renaming ${extractedDir.name} to ${Constants.PHGV2_DIR}")
                if (!extractedDir.renameTo(phgv2Dir)) {
                    logger.warn("Failed to rename PHGv2 directory, will use extracted name: ${extractedDir.name}")
                }
            } else {
                logger.warn("Could not find extracted PHGv2 directory")
            }
        }

        // Run PHGv2 setup-environment command
        if (phgv2Dir.exists()) {
            logger.info("Running PHGv2 setup-environment command")
            val phgScript = phgv2Dir.resolve("bin").resolve("phg")

            if (phgScript.exists()) {
                val setupExitCode = ProcessRunner.runCommand(
                    phgScript.absolutePath,
                    "setup-environment",
                    workingDir = workDir.toFile(),
                    logger = logger
                )

                if (setupExitCode != 0) {
                    logger.error("PHGv2 setup-environment failed with exit code $setupExitCode")
                    exitProcess(setupExitCode)
                }
                logger.info("PHGv2 setup-environment completed successfully")
            } else {
                logger.warn("PHGv2 script not found at: ${phgScript.absolutePath}")
            }
        }

        // Final cleanup: move any remaining PHG/conda log files created during setup
        val phgLogsDir = workDir.resolve(Constants.LOGS_DIR).resolve("phg")
        if (!phgLogsDir.exists()) {
            phgLogsDir.createDirectories()
        }

        workDir.toFile().listFiles { file ->
            file.isFile && (file.name.endsWith(".log") || file.name.contains("phg") || file.name.startsWith("conda"))
        }?.forEach { logFile ->
            val destination = phgLogsDir.resolve(logFile.name).toFile()
            if (logFile.renameTo(destination)) {
                logger.debug("Moved log file: ${logFile.name} -> logs/phg/")
            } else {
                logger.warn("Failed to move log file: ${logFile.name}")
            }
        }

        logger.info("Environment setup completed successfully")
        logger.info("To activate the environment, run: pixi shell")
    }
}
