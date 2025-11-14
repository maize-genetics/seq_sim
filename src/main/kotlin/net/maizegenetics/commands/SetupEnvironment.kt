package net.maizegenetics.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import net.maizegenetics.utils.FileDownloader
import net.maizegenetics.utils.ProcessRunner
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.FileAppender
import org.apache.logging.log4j.core.layout.PatternLayout
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.system.exitProcess

class SetupEnvironment : CliktCommand(name = "setup-environment") {
    private val logger: Logger = LogManager.getLogger(SetupEnvironment::class.java)

    private val workDir by option(
        "--work-dir", "-w",
        help = "Working directory for files and scripts"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)
        .default(Path.of("seq_sim_work"))

    private fun setupFileLogging() {
        val logsDir = workDir.resolve("logs")
        if (!logsDir.exists()) {
            logsDir.createDirectories()
        }

        val logFile = logsDir.resolve("setup_environment.log").toFile()
        val context = LogManager.getContext(false) as LoggerContext
        val config = context.configuration

        val layout = PatternLayout.newBuilder()
            .withConfiguration(config)
            .withPattern("%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5level %logger{36} - %msg%n")
            .build()

        val appender = FileAppender.newBuilder()
            .withFileName(logFile.absolutePath)
            .withAppend(true)
            .withLocking(false)
            .setName("WorkDirFileLogger")
            .setLayout(layout)
            .setConfiguration(config)
            .build()

        appender.start()
        config.addAppender(appender)
        config.rootLogger.addAppender(appender, null, null)
        context.updateLoggers()

        logger.info("Logging to file: $logFile")
    }

    override fun run() {
        // Create working directory if it doesn't exist
        if (!workDir.exists()) {
            workDir.createDirectories()
        }

        // Configure file logging to working directory
        setupFileLogging()

        logger.info("Starting environment setup")
        logger.info("Working directory: $workDir")

        // Copy pixi.toml from resources to working directory
        val pixiTomlFile = workDir.resolve("pixi.toml").toFile()
        if (!FileDownloader.copyResourceToFile("/pixi.toml", pixiTomlFile, logger)) {
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

        // Download and extract MLImpute repository
        val mlImputeUrl = "https://github.com/maize-genetics/MLImpute/archive/refs/heads/main.zip"
        val srcDir = workDir.resolve("src")
        val mlImputeDir = srcDir.resolve("MLImpute").toFile()

        if (mlImputeDir.exists()) {
            logger.info("MLImpute directory already exists: $mlImputeDir")
        } else {
            // Create src directory
            if (!srcDir.exists()) {
                logger.debug("Creating src directory: $srcDir")
                srcDir.createDirectories()
            }

            // Download to src directory
            if (!FileDownloader.downloadAndExtractZip(mlImputeUrl, srcDir, logger)) {
                exitProcess(1)
            }
        }

        logger.info("Environment setup completed successfully")
        logger.info("To activate the environment, run: pixi shell")
    }
}
