package net.maizegenetics.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.boolean
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import net.maizegenetics.Constants
import net.maizegenetics.utils.FileUtils
import net.maizegenetics.utils.LoggingUtils
import net.maizegenetics.utils.ProcessRunner
import net.maizegenetics.utils.ValidationUtils
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.io.path.*
import kotlin.system.exitProcess

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class DownsampleGvcf : CliktCommand(name = "downsample-gvcf") {
    companion object {
        private const val LOG_FILE_NAME = "03_downsample_gvcf.log"
        private const val DOWNSAMPLE_RESULTS_DIR = "03_downsample_results"
        private const val TEMP_DIR_NAME = "temp_uncompressed_gvcf"
    }

    private val logger: Logger = LogManager.getLogger(DownsampleGvcf::class.java)

    private val workDir by option(
        "--work-dir", "-w",
        help = "Working directory for MLImpute and logs"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)
        .default(Path.of(Constants.DEFAULT_WORK_DIR))

    private val gvcfInput by option(
        "--gvcf-dir", "-g",
        help = "Directory containing GVCF files (.gvcf or .g.vcf.gz)"
    ).path(mustExist = true, canBeFile = false, canBeDir = true)
        .required()

    private val ignoreContig by option(
        "--ignore-contig",
        help = "Comma-separated list of string patterns to ignore"
    ).default("")

    private val rates by option(
        "--rates",
        help = "Comma-separated list of downsampling rates to use for each chromosome"
    ).default("0.01,0.05,0.1,0.15,0.2,0.3,0.35,0.4,0.45,0.49")

    private val seed by option(
        "--seed",
        help = "Random seed for reproducibility"
    ).int()

    private val keepRef by option(
        "--keep-ref",
        help = "Keep reference blocks"
    ).boolean().default(true)

    private val minRefBlockSize by option(
        "--min-ref-block-size",
        help = "Minimum reference block size to sample"
    ).int().default(20)

    private val keepUncompressed by option(
        "--keep-uncompressed",
        help = "Keep uncompressed .gvcf files after downsampling"
    ).flag(default = false)

    private val outputDirOption by option(
        "--output-dir", "-o",
        help = "Custom output directory (default: work_dir/output/03_downsample_results)"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)

    private fun decompressGvcfFiles(inputDir: Path, tempDir: Path): List<Path> {
        val decompressedFiles = mutableListOf<Path>()

        logger.info("Scanning directory for GVCF files: $inputDir")

        inputDir.listDirectoryEntries().forEach { file ->
            if (file.isRegularFile()) {
                val fileName = file.fileName.toString()

                when {
                    // Already uncompressed .gvcf files
                    fileName.endsWith(".gvcf") -> {
                        logger.info("File already in .gvcf format: ${file.fileName}")
                        decompressedFiles.add(file)
                    }
                    // Compressed .g.vcf.gz or .gvcf.gz files need decompression
                    fileName.endsWith(".g.vcf.gz") || fileName.endsWith(".gvcf.gz") -> {
                        logger.info("Decompressing: ${file.fileName}")
                        val baseName = when {
                            fileName.endsWith(".g.vcf.gz") -> fileName.removeSuffix(".g.vcf.gz")
                            else -> fileName.removeSuffix(".gvcf.gz")
                        }
                        val outputFile = tempDir.resolve("$baseName.gvcf")

                        try {
                            decompressFile(file, outputFile)
                            decompressedFiles.add(outputFile)
                            logger.info("Successfully decompressed: ${file.fileName} -> ${outputFile.fileName}")
                        } catch (e: Exception) {
                            logger.error("Failed to decompress ${file.fileName}: ${e.message}", e)
                        }
                    }
                    // Uncompressed .g.vcf or .vcf files need renaming
                    fileName.endsWith(".g.vcf") || fileName.endsWith(".vcf") -> {
                        logger.info("Copying and renaming: ${file.fileName}")
                        val baseName = when {
                            fileName.endsWith(".g.vcf") -> fileName.removeSuffix(".g.vcf")
                            else -> fileName.removeSuffix(".vcf")
                        }
                        val outputFile = tempDir.resolve("$baseName.gvcf")

                        try {
                            file.copyTo(outputFile, overwrite = true)
                            decompressedFiles.add(outputFile)
                            logger.info("Successfully copied: ${file.fileName} -> ${outputFile.fileName}")
                        } catch (e: Exception) {
                            logger.error("Failed to copy ${file.fileName}: ${e.message}", e)
                        }
                    }
                }
            }
        }

        if (decompressedFiles.isEmpty()) {
            logger.error("No valid GVCF files found in directory: $inputDir")
            exitProcess(1)
        }

        logger.info("Prepared ${decompressedFiles.size} GVCF file(s) for downsampling")
        return decompressedFiles
    }

    private fun decompressFile(inputFile: Path, outputFile: Path) {
        GZIPInputStream(BufferedInputStream(inputFile.inputStream())).use { gzipInput ->
            BufferedOutputStream(outputFile.outputStream()).use { output ->
                gzipInput.copyTo(output)
            }
        }
    }

    override fun run() {
        // Validate working directory exists
        ValidationUtils.validateWorkingDirectory(workDir, logger)

        // Configure file logging to working directory
        LoggingUtils.setupFileLogging(workDir, LOG_FILE_NAME, logger)

        logger.info("Starting GVCF downsampling")
        logger.info("Working directory: $workDir")
        logger.info("Input directory: $gvcfInput")

        // Create output directory (use custom or default)
        val outputDir = FileUtils.resolveOutputDirectory(workDir, outputDirOption, DOWNSAMPLE_RESULTS_DIR)
        FileUtils.createOutputDirectory(outputDir, logger)

        // Create temp directory for uncompressed files
        val tempDir = workDir.resolve(TEMP_DIR_NAME)
        FileUtils.createOutputDirectory(tempDir, logger)

        // Decompress and prepare GVCF files
        val preparedFiles = decompressGvcfFiles(gvcfInput, tempDir)
        logger.info("Prepared ${preparedFiles.size} files for downsampling")

        // Determine the actual input directory for MLImpute
        // If all files are already .gvcf in the original directory, use that
        // Otherwise, use the temp directory
        val mlimputeInputDir = if (preparedFiles.all { it.parent == gvcfInput }) {
            gvcfInput
        } else {
            tempDir
        }

        logger.info("Using input directory for MLImpute: $mlimputeInputDir")

        // Construct path to MLImpute kotlin project
        val mlimputeKotlinDir = workDir.resolve(Constants.SRC_DIR)
            .resolve(Constants.MLIMPUTE_DIR)
            .resolve("src")
            .resolve("kotlin")

        if (!mlimputeKotlinDir.exists()) {
            logger.error("MLImpute kotlin directory not found: $mlimputeKotlinDir")
            logger.error("Please run 'setup-environment' command first")
            exitProcess(1)
        }

        // Build the gradlew command arguments
        val args = buildList {
            add("downsample-gvcf")
            add("--gvcf-dir=${mlimputeInputDir.toAbsolutePath()}")
            add("--out-dir=${outputDir.toAbsolutePath()}")
            if (ignoreContig.isNotEmpty()) {
                add("--ignore-contig=$ignoreContig")
            }
            add("--rates=$rates")
            if (seed != null) {
                add("--seed=$seed")
            }
            add("--keep-ref=$keepRef")
            add("--min-ref-block-size=$minRefBlockSize")
        }

        // Run MLImpute DownsampleGvcf command
        logger.info("=".repeat(80))
        logger.info("Running MLImpute DownsampleGvcf")
        logger.info("Command: ./gradlew run --args=\"${args.joinToString(" ")}\"")
        logger.info("=".repeat(80))

        val exitCode = ProcessRunner.runCommand(
            "./gradlew", "run", "--args=${args.joinToString(" ")}",
            workingDir = mlimputeKotlinDir.toFile(),
            logger = logger
        )

        if (exitCode != 0) {
            logger.error("MLImpute DownsampleGvcf failed with exit code $exitCode")
            exitProcess(1)
        }

        logger.info("=".repeat(80))
        logger.info("Downsampling completed successfully!")
        logger.info("Output directory: $outputDir")

        // Clean up temp directory if not keeping uncompressed files
        if (!keepUncompressed && tempDir.exists() && mlimputeInputDir == tempDir) {
            logger.info("Cleaning up temporary uncompressed files")
            try {
                tempDir.deleteRecursively()
                logger.info("Temporary directory removed: $tempDir")
            } catch (e: Exception) {
                logger.warn("Failed to clean up temporary directory: ${e.message}")
            }
        }
    }
}
