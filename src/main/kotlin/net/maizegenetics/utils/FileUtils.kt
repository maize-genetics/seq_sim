package net.maizegenetics.utils

import net.maizegenetics.Constants
import org.apache.logging.log4j.Logger
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.system.exitProcess

/**
 * Utility functions for common file operations across commands
 */
object FileUtils {

    /**
     * Collects files from various input sources (single file, directory, or text list)
     *
     * @param input The input path (can be null for auto-detection scenarios)
     * @param validExtensions Set of valid file extensions (without dots)
     * @param fileTypeName Human-readable file type name for error messages
     * @param logger Logger instance for error reporting
     * @param allowTextList Whether to allow text file lists as input (default: true)
     * @return List of collected file paths
     */
    fun collectFiles(
        input: Path?,
        validExtensions: Set<String>,
        fileTypeName: String,
        logger: Logger,
        allowTextList: Boolean = true
    ): List<Path> {
        if (input == null) {
            logger.error("$fileTypeName input is required. Please specify the input option")
            exitProcess(1)
        }

        if (!input.exists()) {
            logger.error("Input path not found: $input")
            exitProcess(1)
        }

        val collectedFiles = mutableListOf<Path>()

        when {
            input.isDirectory() -> {
                logger.info("Collecting $fileTypeName files from directory: $input")
                input.listDirectoryEntries().forEach { file ->
                    if (file.isRegularFile() && hasValidExtension(file, validExtensions)) {
                        collectedFiles.add(file)
                    }
                }
                if (collectedFiles.isEmpty()) {
                    logger.error("No $fileTypeName files found in directory: $input")
                    exitProcess(1)
                }
                logger.info("Found ${collectedFiles.size} $fileTypeName file(s) in directory")
            }
            input.isRegularFile() -> {
                if (allowTextList && input.extension == Constants.TEXT_FILE_EXTENSION) {
                    logger.info("Reading $fileTypeName file paths from: $input")
                    input.readLines().forEach { line ->
                        val trimmedLine = line.trim()
                        if (trimmedLine.isNotEmpty() && !trimmedLine.startsWith("#")) {
                            val file = Path(trimmedLine)
                            if (file.exists() && file.isRegularFile()) {
                                collectedFiles.add(file)
                            } else {
                                logger.warn("$fileTypeName file not found or not a file: $trimmedLine")
                            }
                        }
                    }
                    if (collectedFiles.isEmpty()) {
                        logger.error("No valid $fileTypeName files found in list file: $input")
                        exitProcess(1)
                    }
                    logger.info("Found ${collectedFiles.size} $fileTypeName file(s) in list")
                } else if (hasValidExtension(input, validExtensions)) {
                    logger.info("Using single $fileTypeName file: $input")
                    collectedFiles.add(input)
                } else {
                    val extensionList = validExtensions.joinToString(", ") { ".$it" }
                    val textListNote = if (allowTextList) ", or be a .txt file with paths" else ""
                    logger.error("$fileTypeName file must have extension $extensionList$textListNote: $input")
                    exitProcess(1)
                }
            }
            else -> {
                logger.error("$fileTypeName input is neither a file nor a directory: $input")
                exitProcess(1)
            }
        }

        return collectedFiles
    }

    /**
     * Checks if a file has a valid extension from the provided set
     * Handles multi-part extensions like "g.vcf.gz"
     *
     * @param file The file path to check
     * @param validExtensions Set of valid extensions (can include dots or not)
     * @return true if file has a valid extension
     */
    fun hasValidExtension(file: Path, validExtensions: Set<String>): Boolean {
        val fileName = file.fileName.toString()
        return validExtensions.any { ext ->
            val normalizedExt = if (ext.startsWith(".")) ext else ".$ext"
            fileName.endsWith(normalizedExt)
        }
    }

    /**
     * Creates an output directory if it doesn't exist and logs the action
     *
     * @param outputDir The directory to create
     * @param logger Logger for debug and info messages
     */
    fun createOutputDirectory(outputDir: Path, logger: Logger) {
        if (!outputDir.exists()) {
            logger.debug("Creating output directory: $outputDir")
            outputDir.createDirectories()
            logger.info("Output directory created: $outputDir")
        }
    }

    /**
     * Writes a list of file paths to a text file
     *
     * @param files List of file paths to write
     * @param outputFile The output file path
     * @param logger Logger for success/error messages
     * @param fileTypeName Human-readable name for the file type (for log messages)
     */
    fun writeFilePaths(
        files: List<Path>,
        outputFile: Path,
        logger: Logger,
        fileTypeName: String = "File"
    ) {
        if (files.isEmpty()) {
            logger.warn("No files to write to paths file")
            return
        }

        try {
            outputFile.writeLines(files.map { it.toString() })
            logger.info("$fileTypeName paths written to: $outputFile")
        } catch (e: Exception) {
            logger.error("Failed to write $fileTypeName paths file: ${e.message}", e)
        }
    }

    /**
     * Resolves a default output directory with optional custom override
     *
     * @param workDir The working directory
     * @param customOutput Optional custom output directory
     * @param defaultSubDir The default subdirectory name (e.g., "01_anchorwave_results")
     * @return The resolved output directory path
     */
    fun resolveOutputDirectory(
        workDir: Path,
        customOutput: Path?,
        defaultSubDir: String
    ): Path {
        return customOutput ?: workDir.resolve("output").resolve(defaultSubDir)
    }

    /**
     * Auto-detects output from a previous pipeline step
     *
     * @param workDir The working directory
     * @param stepDirName The step directory name (e.g., "12_ropebwt_mem_results")
     * @param logger Logger for error messages
     * @param customMessage Optional custom error message
     * @return The detected directory path
     */
    fun autoDetectStepOutput(
        workDir: Path,
        stepDirName: String,
        logger: Logger,
        customMessage: String? = null
    ): Path {
        val stepOutputDir = workDir.resolve("output").resolve(stepDirName)

        if (!stepOutputDir.exists()) {
            logger.error("Cannot auto-detect output: directory not found at $stepOutputDir")
            if (customMessage != null) {
                logger.error(customMessage)
            }
            exitProcess(1)
        }

        logger.info("Auto-detected output directory: $stepOutputDir")
        return stepOutputDir
    }
}
