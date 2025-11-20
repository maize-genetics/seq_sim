package net.maizegenetics.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import net.maizegenetics.Constants
import net.maizegenetics.utils.LoggingUtils
import net.maizegenetics.utils.ProcessRunner
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.system.exitProcess

class FormatRecombinedFastas : CliktCommand(name = "format-recombined-fastas") {
    companion object {
        private const val LOG_FILE_NAME = "10_format_recombined_fastas.log"
        private const val OUTPUT_DIR = "output"
        private const val FORMATTED_RESULTS_DIR = "10_formatted_fastas"
        private const val FORMATTED_FASTA_PATHS_FILE = "formatted_fasta_paths.txt"
        private const val DEFAULT_LINE_WIDTH = 60
        private const val DEFAULT_THREADS = 8
    }

    private val logger: Logger = LogManager.getLogger(FormatRecombinedFastas::class.java)

    private val workDir by option(
        "--work-dir", "-w",
        help = "Working directory for files and scripts"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)
        .default(Path.of(Constants.DEFAULT_WORK_DIR))

    private val fastaInput by option(
        "--fasta-input", "-f",
        help = "FASTA file, directory of FASTA files, or text file with paths to FASTA files (one per line)"
    ).path(mustExist = false)

    private val lineWidth by option(
        "--line-width", "-l",
        help = "Number of characters per line in formatted FASTA"
    ).int()
        .default(DEFAULT_LINE_WIDTH)

    private val threads by option(
        "--threads", "-t",
        help = "Number of threads for seqkit"
    ).int()
        .default(DEFAULT_THREADS)

    private fun collectFastaFiles(): List<Path> {
        val fastaFiles = mutableListOf<Path>()

        // If fastaInput is not provided, try to find recombined fastas from previous step
        val actualInput = fastaInput ?: workDir.resolve(OUTPUT_DIR)
            .resolve("09_recombined_sequences")
            .resolve("recombinate_fastas")

        if (!actualInput.exists()) {
            logger.error("Input path not found: $actualInput")
            logger.error("Please specify --fasta-input or run 'generate-recombined-sequences' first")
            exitProcess(1)
        }

        when {
            actualInput.isDirectory() -> {
                // Collect all FASTA files from directory
                logger.info("Collecting FASTA files from directory: $actualInput")
                actualInput.listDirectoryEntries().forEach { file ->
                    if (file.isRegularFile() && file.extension in Constants.FASTA_EXTENSIONS) {
                        fastaFiles.add(file)
                    }
                }
                if (fastaFiles.isEmpty()) {
                    logger.error("No FASTA files found in directory: $actualInput")
                    exitProcess(1)
                }
                logger.info("Found ${fastaFiles.size} FASTA file(s) in directory")
            }
            actualInput.isRegularFile() -> {
                // Check if it's a .txt file with paths or a single FASTA file
                if (actualInput.extension == Constants.TEXT_FILE_EXTENSION) {
                    // It's a text file with paths
                    logger.info("Reading FASTA file paths from: $actualInput")
                    actualInput.readLines().forEach { line ->
                        val trimmedLine = line.trim()
                        if (trimmedLine.isNotEmpty() && !trimmedLine.startsWith("#")) {
                            val fastaFile = Path(trimmedLine)
                            if (fastaFile.exists() && fastaFile.isRegularFile()) {
                                fastaFiles.add(fastaFile)
                            } else {
                                logger.warn("FASTA file not found or not a file: $trimmedLine")
                            }
                        }
                    }
                    if (fastaFiles.isEmpty()) {
                        logger.error("No valid FASTA files found in list file: $actualInput")
                        exitProcess(1)
                    }
                    logger.info("Found ${fastaFiles.size} FASTA file(s) in list")
                } else if (actualInput.extension in Constants.FASTA_EXTENSIONS) {
                    // It's a single FASTA file
                    logger.info("Using single FASTA file: $actualInput")
                    fastaFiles.add(actualInput)
                } else {
                    logger.error("FASTA file must have .fa or .fasta extension or be a .txt file with paths: $actualInput")
                    exitProcess(1)
                }
            }
            else -> {
                logger.error("FASTA input is neither a file nor a directory: $actualInput")
                exitProcess(1)
            }
        }

        return fastaFiles
    }

    override fun run() {
        // Validate working directory exists
        if (!workDir.exists()) {
            logger.error("Working directory does not exist: $workDir")
            logger.error("Please run 'setup-environment' command first")
            exitProcess(1)
        }

        // Configure file logging to working directory
        LoggingUtils.setupFileLogging(workDir, LOG_FILE_NAME, logger)

        logger.info("Starting format recombined fastas")
        logger.info("Working directory: $workDir")
        logger.info("Line width: $lineWidth")
        logger.info("Threads: $threads")

        // Collect FASTA files
        val fastaFiles = collectFastaFiles()
        logger.info("Processing ${fastaFiles.size} FASTA file(s)")

        // Create output directory
        val outputDir = workDir.resolve(OUTPUT_DIR).resolve(FORMATTED_RESULTS_DIR)
        if (!outputDir.exists()) {
            logger.debug("Creating output directory: $outputDir")
            outputDir.createDirectories()
            logger.info("Output directory created: $outputDir")
        }

        // Process each FASTA file with seqkit
        var successCount = 0
        var failureCount = 0
        val formattedFastas = mutableListOf<Path>()

        fastaFiles.forEach { fastaFile ->
            val outputFile = outputDir.resolve(fastaFile.fileName)
            logger.info("Formatting: ${fastaFile.fileName}")

            val exitCode = ProcessRunner.runCommand(
                "pixi", "run",
                "seqkit", "seq",
                "-w", lineWidth.toString(),
                "-j", threads.toString(),
                fastaFile.toString(),
                workingDir = workDir.toFile(),
                logger = logger,
                outputFile = outputFile.toFile()
            )

            if (exitCode == 0) {
                successCount++
                formattedFastas.add(outputFile)
                logger.info("Successfully formatted: ${fastaFile.fileName}")
            } else {
                failureCount++
                logger.error("Failed to format ${fastaFile.fileName} with exit code $exitCode")
            }
        }

        logger.info("Format recombined fastas completed")
        logger.info("Success: $successCount, Failures: $failureCount")

        if (formattedFastas.isNotEmpty()) {
            // Write formatted FASTA file paths to text file
            val formattedFastaPathsFile = outputDir.resolve(FORMATTED_FASTA_PATHS_FILE)
            try {
                formattedFastaPathsFile.writeLines(formattedFastas.map { it.toString() })
                logger.info("Formatted FASTA file paths written to: $formattedFastaPathsFile")
            } catch (e: Exception) {
                logger.error("Failed to write formatted FASTA paths file: ${e.message}", e)
            }
        }

        logger.info("Output directory: $outputDir")

        if (failureCount > 0) {
            exitProcess(1)
        }
    }
}
