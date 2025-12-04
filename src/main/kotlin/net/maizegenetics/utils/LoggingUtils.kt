package net.maizegenetics.utils

import net.maizegenetics.Constants
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.logging.log4j.core.Appender
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.FileAppender
import org.apache.logging.log4j.core.config.LoggerConfig
import org.apache.logging.log4j.core.layout.PatternLayout
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

object LoggingUtils {
    private const val LOG_PATTERN = "%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5level %logger{36} - %msg%n"

    /**
     * Sets up file logging for a command in the working directory
     *
     * @param workDir The working directory where logs will be stored
     * @param logFileName The name of the log file (e.g., "setup_environment.log")
     * @param logger The logger instance to configure
     */
    fun setupFileLogging(workDir: Path, logFileName: String, logger: Logger) {
        val logsDir = workDir.resolve(Constants.LOGS_DIR)
        if (!logsDir.exists()) {
            logsDir.createDirectories()
        }

        val logFile = logsDir.resolve(logFileName).toFile()
        val context = LogManager.getContext(false) as LoggerContext
        val config = context.configuration

        // Use a unique appender name based on the log file name
        val appenderName = "FileAppender_${logFileName.replace(".", "_")}"

        // Check if appender already exists and remove it
        val existingAppender: Appender? = config.getAppender(appenderName)
        if (existingAppender != null) {
            config.rootLogger.removeAppender(appenderName)
            val maizeLoggerConfig = config.getLoggerConfig("net.maizegenetics")
            if (maizeLoggerConfig != null) {
                maizeLoggerConfig.removeAppender(appenderName)
            }
        }

        val layout = PatternLayout.newBuilder()
            .withConfiguration(config)
            .withPattern(LOG_PATTERN)
            .build()

        val appender = FileAppender.newBuilder()
            .withFileName(logFile.absolutePath)
            .withAppend(false)
            .withLocking(false)
            .setName(appenderName)
            .setLayout(layout)
            .setConfiguration(config)
            .build()

        appender.start()
        config.addAppender(appender)

        // Add to root logger
        config.rootLogger.addAppender(appender, null, null)

        // Also add to the net.maizegenetics logger (which has additivity=false)
        val maizeLogger = config.getLoggerConfig("net.maizegenetics")
        if (maizeLogger != null) {
            maizeLogger.addAppender(appender, null, null)
        }

        context.updateLoggers()

        logger.info("Logging to file: $logFile")
    }
}
