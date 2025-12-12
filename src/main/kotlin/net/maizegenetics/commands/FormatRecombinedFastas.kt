package net.maizegenetics.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import net.maizegenetics.Constants
import net.maizegenetics.utils.FileUtils
import net.maizegenetics.utils.LoggingUtils
import net.maizegenetics.utils.ProcessRunner
import net.maizegenetics.utils.ValidationUtils
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.system.exitProcess

class FormatRecombinedFastas : CliktCommand(name = "format-recombined-fastas") {
    companion object {
        private const val LOG_FILE_NAME = "10_format_recombined_fastas.log"
        private const val FORMATTED_RESULTS_DIR = "10_formatted_fastas"
        private const val FORMATTED_FASTA_PATHS_FILE = "formatted_fasta_paths.txt"
        private const val DEFAULT_LINE_WIDTH = 60
        private const val DEFAULT_THREADS = 8
        private const val DEFAULT_INPUT_DIR = "09_recombined_sequences/recombinate_fastas"
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

    private val outputDirOption by option(
        "--output-dir", "-o",
        help = "Custom output directory (default: work_dir/output/10_formatted_fastas)"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)

    private fun collectFastaFiles(): List<Path> {
        // If fastaInput is not provided, try to find recombined fastas from previous step
        val actualInput = fastaInput ?: run {
            logger.info("No FASTA input specified, attempting to auto-detect from step 09")
            FileUtils.autoDetectStepOutput(
                workDir,
                DEFAULT_INPUT_DIR,
                logger,
                "Please specify --fasta-input or run 'generate-recombined-sequences' first"
            )
        }

        return FileUtils.collectFiles(
            actualInput,
            Constants.FASTA_EXTENSIONS,
            "FASTA",
            logger
        )
    }

    override fun run() {
        // Validate working directory exists
        ValidationUtils.validateWorkingDirectory(workDir, logger)

        // Configure file logging to working directory
        LoggingUtils.setupFileLogging(workDir, LOG_FILE_NAME, logger)

        logger.info("Starting format recombined fastas")
        logger.info("Working directory: $workDir")
        logger.info("Line width: $lineWidth")
        logger.info("Threads: $threads")

        // Collect FASTA files
        val fastaFiles = collectFastaFiles()
        logger.info("Processing ${fastaFiles.size} FASTA file(s)")

        // Create output directory (use custom or default)
        val outputDir = FileUtils.resolveOutputDirectory(workDir, outputDirOption, FORMATTED_RESULTS_DIR)
        FileUtils.createOutputDirectory(outputDir, logger)

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

        // Write formatted FASTA file paths to text file
        FileUtils.writeFilePaths(
            formattedFastas,
            outputDir.resolve(FORMATTED_FASTA_PATHS_FILE),
            logger,
            "Formatted FASTA file"
        )

        logger.info("Output directory: $outputDir")

        if (failureCount > 0) {
            exitProcess(1)
        }
    }
}
