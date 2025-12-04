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

class RopeBwtMem : CliktCommand(name = "ropebwt-mem") {
    companion object {
        private const val LOG_FILE_NAME = "12_ropebwt_mem.log"
        private const val OUTPUT_DIR = "output"
        private const val ROPEBWT_MEM_RESULTS_DIR = "12_ropebwt_mem_results"
        private const val BED_FILE_PATHS_FILE = "bed_file_paths.txt"
        private const val DEFAULT_P_VALUE = 168
        private const val KEYFILE_NAME = "phg_keyfile.txt"
    }

    private val logger: Logger = LogManager.getLogger(RopeBwtMem::class.java)

    private val workDir by option(
        "--work-dir", "-w",
        help = "Working directory for files and scripts"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)
        .default(Path.of(Constants.DEFAULT_WORK_DIR))

    private val fastqInput by option(
        "--fastq-input", "-f",
        help = "FASTQ file, directory of FASTQ files, or text file with paths to FASTQ files (one per line)"
    ).path(mustExist = false)

    private val indexFile by option(
        "--index-file", "-i",
        help = "Path to the .fmd index file from rope-bwt-chr-index (auto-detected from step 11 if not specified)"
    ).path(mustExist = false, canBeFile = true, canBeDir = false)

    private val lValue by option(
        "--l-value", "-l",
        help = "The -l parameter value (auto-calculated as 2 * number of FASTA samples from step 11 if not specified)"
    ).int()

    private val pValue by option(
        "--p-value", "-p",
        help = "The -p parameter value"
    ).int()
        .default(DEFAULT_P_VALUE)

    private val threads by option(
        "--threads", "-t",
        help = "Number of threads for ropebwt3 mem"
    ).int()
        .default(1)

    private val outputDirOption by option(
        "--output-dir", "-o",
        help = "Custom output directory (default: work_dir/output/12_ropebwt_mem_results)"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)

    private fun collectFastqFiles(): List<Path> {
        val fastqFiles = mutableListOf<Path>()

        if (fastqInput == null) {
            logger.error("FASTQ input is required. Please specify --fastq-input")
            exitProcess(1)
        }

        val actualInput = fastqInput!!

        if (!actualInput.exists()) {
            logger.error("Input path not found: $actualInput")
            exitProcess(1)
        }

        when {
            actualInput.isDirectory() -> {
                logger.info("Collecting FASTQ files from directory: $actualInput")
                actualInput.listDirectoryEntries().forEach { file ->
                    if (file.isRegularFile() && isFastqFile(file)) {
                        fastqFiles.add(file)
                    }
                }
                if (fastqFiles.isEmpty()) {
                    logger.error("No FASTQ files found in directory: $actualInput")
                    exitProcess(1)
                }
                logger.info("Found ${fastqFiles.size} FASTQ file(s) in directory")
            }
            actualInput.isRegularFile() -> {
                if (actualInput.extension == Constants.TEXT_FILE_EXTENSION) {
                    logger.info("Reading FASTQ file paths from: $actualInput")
                    actualInput.readLines().forEach { line ->
                        val trimmedLine = line.trim()
                        if (trimmedLine.isNotEmpty() && !trimmedLine.startsWith("#")) {
                            val fastqFile = Path(trimmedLine)
                            if (fastqFile.exists() && fastqFile.isRegularFile()) {
                                fastqFiles.add(fastqFile)
                            } else {
                                logger.warn("FASTQ file not found or not a file: $trimmedLine")
                            }
                        }
                    }
                    if (fastqFiles.isEmpty()) {
                        logger.error("No valid FASTQ files found in list file: $actualInput")
                        exitProcess(1)
                    }
                    logger.info("Found ${fastqFiles.size} FASTQ file(s) in list")
                } else if (isFastqFile(actualInput)) {
                    logger.info("Using single FASTQ file: $actualInput")
                    fastqFiles.add(actualInput)
                } else {
                    logger.error("FASTQ file must have .fq, .fastq, .fq.gz, or .fastq.gz extension, or be a .txt file with paths: $actualInput")
                    exitProcess(1)
                }
            }
            else -> {
                logger.error("FASTQ input is neither a file nor a directory: $actualInput")
                exitProcess(1)
            }
        }

        return fastqFiles
    }

    private fun isFastqFile(file: Path): Boolean {
        val fileName = file.fileName.toString()
        return Constants.FASTQ_EXTENSIONS.any { ext ->
            fileName.endsWith(".$ext")
        }
    }

    private fun calculateLValue(): Int {
        // Try to find keyfile from step 11
        val step11OutputDir = workDir.resolve(OUTPUT_DIR).resolve("11_rope_bwt_index_results")
        val keyfilePath = step11OutputDir.resolve(KEYFILE_NAME)

        if (!keyfilePath.exists()) {
            logger.error("Cannot auto-calculate -l value: keyfile not found at $keyfilePath")
            logger.error("Please specify --l-value manually or ensure step 11 (rope-bwt-chr-index) has been run")
            exitProcess(1)
        }

        logger.info("Reading keyfile from step 11: $keyfilePath")
        val lines = keyfilePath.readLines()

        // Count lines excluding header
        val fastaCount = lines.size - 1
        if (fastaCount <= 0) {
            logger.error("Keyfile has no FASTA entries: $keyfilePath")
            exitProcess(1)
        }

        val calculatedL = fastaCount * 2
        logger.info("Calculated -l value: $calculatedL (from $fastaCount FASTA samples)")
        return calculatedL
    }

    private fun findIndexFile(): Path {
        val step11OutputDir = workDir.resolve(OUTPUT_DIR).resolve("11_rope_bwt_index_results")

        if (!step11OutputDir.exists()) {
            logger.error("Cannot auto-detect index file: step 11 output directory not found at $step11OutputDir")
            logger.error("Please specify --index-file manually or ensure step 11 (rope-bwt-chr-index) has been run")
            exitProcess(1)
        }

        // Look for .fmd files in the directory
        val fmdFiles = step11OutputDir.listDirectoryEntries("*.fmd")

        if (fmdFiles.isEmpty()) {
            logger.error("Cannot auto-detect index file: no .fmd files found in $step11OutputDir")
            logger.error("Please specify --index-file manually")
            exitProcess(1)
        }

        if (fmdFiles.size > 1) {
            logger.warn("Multiple .fmd files found in $step11OutputDir")
            logger.warn("Using the first one: ${fmdFiles[0].fileName}")
        }

        val detectedIndexFile = fmdFiles[0]
        logger.info("Auto-detected index file: $detectedIndexFile")
        return detectedIndexFile
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

        logger.info("Starting ropebwt3 mem alignment")
        logger.info("Working directory: $workDir")

        // Collect FASTQ files
        val fastqFiles = collectFastqFiles()
        logger.info("Processing ${fastqFiles.size} FASTQ file(s)")

        // Determine index file
        val actualIndexFile = indexFile ?: findIndexFile()
        if (!actualIndexFile.exists()) {
            logger.error("Index file not found: $actualIndexFile")
            exitProcess(1)
        }
        logger.info("Using index file: $actualIndexFile")

        // Determine -l value
        val actualLValue = lValue ?: calculateLValue()
        logger.info("Using -l value: $actualLValue")
        logger.info("Using -p value: $pValue")
        logger.info("Using threads: $threads")

        // Create output directory (use custom or default)
        val outputDir = outputDirOption ?: workDir.resolve(OUTPUT_DIR).resolve(ROPEBWT_MEM_RESULTS_DIR)
        if (!outputDir.exists()) {
            logger.debug("Creating output directory: $outputDir")
            outputDir.createDirectories()
            logger.info("Output directory created: $outputDir")
        }

        // Process each FASTQ file with ropebwt3 mem
        var successCount = 0
        var failureCount = 0
        val bedFiles = mutableListOf<Path>()

        fastqFiles.forEach { fastqFile ->
            val sampleName = fastqFile.nameWithoutExtension
                .replace(".fq", "")  // Handle .fq.gz -> remove extra .fq
                .replace(".fastq", "")  // Handle .fastq.gz -> remove extra .fastq
            val outputFile = outputDir.resolve("${sampleName}_ropebwt.bed")

            logger.info("Processing: ${fastqFile.fileName} -> ${outputFile.fileName}")

            val exitCode = ProcessRunner.runCommand(
                "pixi", "run",
                "ropebwt3", "mem",
                "-t", threads.toString(),
                "-l", actualLValue.toString(),
                "-p", pValue.toString(),
                actualIndexFile.toString(),
                fastqFile.toString(),
                workingDir = workDir.toFile(),
                logger = logger,
                outputFile = outputFile.toFile()
            )

            if (exitCode == 0) {
                successCount++
                bedFiles.add(outputFile)
                logger.info("Successfully processed: ${fastqFile.fileName}")
            } else {
                failureCount++
                logger.error("Failed to process ${fastqFile.fileName} with exit code $exitCode")
            }
        }

        logger.info("ropebwt3 mem alignment completed")
        logger.info("Success: $successCount, Failures: $failureCount")

        if (bedFiles.isNotEmpty()) {
            // Write BED file paths to text file
            val bedFilePathsFile = outputDir.resolve(BED_FILE_PATHS_FILE)
            try {
                bedFilePathsFile.writeLines(bedFiles.map { it.toString() })
                logger.info("BED file paths written to: $bedFilePathsFile")
            } catch (e: Exception) {
                logger.error("Failed to write BED paths file: ${e.message}", e)
            }
        }

        logger.info("Output directory: $outputDir")

        if (failureCount > 0) {
            exitProcess(1)
        }
    }
}
