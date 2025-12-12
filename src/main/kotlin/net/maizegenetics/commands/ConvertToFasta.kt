package net.maizegenetics.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
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
class ConvertToFasta : CliktCommand(name = "convert-to-fasta") {
    companion object {
        private const val LOG_FILE_NAME = "04_convert_to_fasta.log"
        private const val FASTA_RESULTS_DIR = "04_fasta_results"
        private const val FASTA_PATHS_FILE = "fasta_file_paths.txt"
        private const val TEMP_DIR_NAME = "temp_uncompressed_gvcf_fasta"
    }

    private val logger: Logger = LogManager.getLogger(ConvertToFasta::class.java)

    private val workDir by option(
        "--work-dir", "-w",
        help = "Working directory for MLImpute and logs"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)
        .default(Path.of(Constants.DEFAULT_WORK_DIR))

    private val gvcfInput by option(
        "--gvcf-file", "-g",
        help = "GVCF file, directory of GVCF files, or text file with paths to GVCF files (one per line)"
    ).path(mustExist = true)
        .required()

    private val refFasta by option(
        "--ref-fasta", "-r",
        help = "Path to the reference FASTA file"
    ).path(mustExist = true, canBeFile = true, canBeDir = false)
        .required()

    private val missingRecordsAs by option(
        "--missing-records-as",
        help = "How to handle missing GVCF records: asN (fill with N's), asRef (use reference), asNone (omit)"
    ).choice("asN", "asRef", "asNone", ignoreCase = true)
        .default("asRef")

    private val missingGenotypeAs by option(
        "--missing-genotype-as",
        help = "How to handle missing genotypes (.): asN (fill with N's), asRef (use reference), asNone (omit)"
    ).choice("asN", "asRef", "asNone", ignoreCase = true)
        .default("asN")

    private val outputDirOption by option(
        "--output-dir", "-o",
        help = "Custom output directory (default: work_dir/output/04_fasta_results)"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)

    private fun collectGvcfFiles(): List<Path> {
        return FileUtils.collectFiles(
            gvcfInput,
            Constants.GVCF_EXTENSIONS,
            "GVCF",
            logger
        )
    }

    private fun prepareGvcfFiles(gvcfFiles: List<Path>, tempDir: Path): List<Path> {
        val preparedFiles = mutableListOf<Path>()

        gvcfFiles.forEach { file ->
            val fileName = file.fileName.toString()

            when {
                // Already uncompressed .gvcf files - can use directly
                fileName.endsWith(".gvcf") -> {
                    logger.info("File already in .gvcf format: ${file.fileName}")
                    preparedFiles.add(file)
                }
                // Compressed files need decompression
                fileName.endsWith(".g.vcf.gz") || fileName.endsWith(".gvcf.gz") -> {
                    logger.info("Decompressing: ${file.fileName}")
                    val baseName = when {
                        fileName.endsWith(".g.vcf.gz") -> fileName.removeSuffix(".g.vcf.gz")
                        else -> fileName.removeSuffix(".gvcf.gz")
                    }
                    val outputFile = tempDir.resolve("$baseName.gvcf")

                    try {
                        decompressFile(file, outputFile)
                        preparedFiles.add(outputFile)
                        logger.info("Successfully decompressed: ${file.fileName} -> ${outputFile.fileName}")
                    } catch (e: Exception) {
                        logger.error("Failed to decompress ${file.fileName}: ${e.message}", e)
                    }
                }
                // Uncompressed .g.vcf or .vcf files need renaming to .gvcf
                fileName.endsWith(".g.vcf") || fileName.endsWith(".vcf") -> {
                    logger.info("Copying and renaming: ${file.fileName}")
                    val baseName = when {
                        fileName.endsWith(".g.vcf") -> fileName.removeSuffix(".g.vcf")
                        else -> fileName.removeSuffix(".vcf")
                    }
                    val outputFile = tempDir.resolve("$baseName.gvcf")

                    try {
                        file.copyTo(outputFile, overwrite = true)
                        preparedFiles.add(outputFile)
                        logger.info("Successfully copied: ${file.fileName} -> ${outputFile.fileName}")
                    } catch (e: Exception) {
                        logger.error("Failed to copy ${file.fileName}: ${e.message}", e)
                    }
                }
            }
        }

        if (preparedFiles.isEmpty()) {
            logger.error("No valid GVCF files could be prepared")
            exitProcess(1)
        }

        logger.info("Prepared ${preparedFiles.size} GVCF file(s) for conversion")
        return preparedFiles
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

        logger.info("Starting GVCF to FASTA conversion")
        logger.info("Working directory: $workDir")
        logger.info("Reference FASTA: $refFasta")
        logger.info("Missing records as: $missingRecordsAs")
        logger.info("Missing genotype as: $missingGenotypeAs")

        // Collect GVCF files
        val gvcfFiles = collectGvcfFiles()
        logger.info("Processing ${gvcfFiles.size} GVCF file(s)")

        // Create output directory (use custom or default)
        val outputDir = FileUtils.resolveOutputDirectory(workDir, outputDirOption, FASTA_RESULTS_DIR)
        FileUtils.createOutputDirectory(outputDir, logger)

        // Create temp directory for uncompressed files if needed
        val tempDir = workDir.resolve(TEMP_DIR_NAME)
        FileUtils.createOutputDirectory(tempDir, logger)

        // Prepare GVCF files (decompress if needed)
        val preparedFiles = prepareGvcfFiles(gvcfFiles, tempDir)

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

        // Process each GVCF file
        var successCount = 0
        var failureCount = 0
        val fastaFilePaths = mutableListOf<Path>()

        preparedFiles.forEachIndexed { index, gvcfFile ->
            logger.info("=".repeat(80))
            logger.info("Processing GVCF ${index + 1}/${preparedFiles.size}: ${gvcfFile.name}")
            logger.info("=".repeat(80))

            try {
                val fastaPath = convertGvcfToFasta(gvcfFile, mlimputeKotlinDir, outputDir)
                fastaFilePaths.add(fastaPath)
                successCount++
                logger.info("Successfully converted: ${gvcfFile.name}")
            } catch (e: Exception) {
                failureCount++
                logger.error("Failed to convert GVCF: ${gvcfFile.name}", e)
                logger.error("Continuing with next GVCF file...")
            }
        }

        // Write FASTA file paths to text file
        FileUtils.writeFilePaths(
            fastaFilePaths,
            outputDir.resolve(FASTA_PATHS_FILE),
            logger,
            "FASTA file"
        )

        // Clean up temp directory
        if (tempDir.exists() && preparedFiles.any { it.parent == tempDir }) {
            logger.info("Cleaning up temporary uncompressed files")
            try {
                tempDir.deleteRecursively()
                logger.info("Temporary directory removed: $tempDir")
            } catch (e: Exception) {
                logger.warn("Failed to clean up temporary directory: ${e.message}")
            }
        }

        logger.info("=".repeat(80))
        logger.info("All conversions completed!")
        logger.info("Total GVCF files processed: ${gvcfFiles.size}")
        logger.info("Successful: $successCount")
        logger.info("Failed: $failureCount")
        logger.info("Output directory: $outputDir")
    }

    private fun convertGvcfToFasta(gvcfFile: Path, mlimputeKotlinDir: Path, outputDir: Path): Path {
        val gvcfBaseName = gvcfFile.nameWithoutExtension

        // Generate output filename
        val outputFileName = "${gvcfBaseName}.fasta"
        val fullOutputPath = outputDir.resolve(outputFileName)
        logger.info("Output file: $fullOutputPath")

        // Build the gradlew command arguments for MLImpute ConvertToFasta
        val args = buildList {
            add("convert-to-fasta")
            add("--gvcf-file=${gvcfFile.toAbsolutePath()}")
            add("--out-file=${fullOutputPath.toAbsolutePath()}")
            add("--fasta-file=${refFasta.toAbsolutePath()}")
            add("--missing-records-as=$missingRecordsAs")
            add("--missing-genotype-as=$missingGenotypeAs")
        }

        // Run MLImpute ConvertToFasta command
        logger.info("Running MLImpute ConvertToFasta")
        logger.info("Command: ./gradlew run --args=\"${args.joinToString(" ")}\"")

        val exitCode = ProcessRunner.runCommand(
            "./gradlew", "run", "--args=${args.joinToString(" ")}",
            workingDir = mlimputeKotlinDir.toFile(),
            logger = logger
        )

        if (exitCode != 0) {
            throw RuntimeException("GVCF to FASTA conversion failed with exit code $exitCode")
        }

        logger.info("Conversion completed for: ${gvcfFile.name}")

        // Return the FASTA file path
        return fullOutputPath
    }
}
