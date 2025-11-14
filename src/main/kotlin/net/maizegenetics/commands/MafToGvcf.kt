package net.maizegenetics.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import net.maizegenetics.Constants
import net.maizegenetics.utils.LoggingUtils
import net.maizegenetics.utils.ProcessRunner
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.system.exitProcess

class MafToGvcf : CliktCommand(name = "maf-to-gvcf") {
    companion object {
        private const val LOG_FILE_NAME = "maf_to_gvcf.log"
        private const val OUTPUT_DIR = "output"
        private const val GVCF_RESULTS_DIR = "gvcf_results"
        private const val BIOKOTLIN_EXECUTABLE = "biokotlin-tools"
        private const val MAF_TO_GVCF_COMMAND = "maf-to-gvcf-converter"
    }

    private val logger: Logger = LogManager.getLogger(MafToGvcf::class.java)

    private val workDir by option(
        "--work-dir", "-w",
        help = "Working directory for files and scripts"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)
        .default(Path.of(Constants.DEFAULT_WORK_DIR))

    private val referenceFile by option(
        "--reference-file", "-r",
        help = "Path to the Reference FASTA file"
    ).path(mustExist = true, canBeFile = true, canBeDir = false)
        .required()

    private val mafInput by option(
        "--maf-file", "-m",
        help = "MAF file, directory of MAF files, or text file with paths to MAF files (one per line)"
    ).path(mustExist = true)
        .required()

    private val outputFile by option(
        "--output-file", "-o",
        help = "Name for the output GVCF file (optional for multiple MAF files, auto-generated if not provided)"
    ).path(mustExist = false, canBeFile = true, canBeDir = false)

    private val sampleName by option(
        "--sample-name", "-s",
        help = "Sample name to be used in the GVCF file (defaults to MAF file base name)"
    )

    private fun collectMafFiles(): List<Path> {
        val mafFiles = mutableListOf<Path>()

        when {
            mafInput.isDirectory() -> {
                // Collect all MAF files from directory
                logger.info("Collecting MAF files from directory: $mafInput")
                mafInput.listDirectoryEntries().forEach { file ->
                    if (file.isRegularFile() && file.extension in Constants.MAF_EXTENSIONS) {
                        mafFiles.add(file)
                    }
                }
                if (mafFiles.isEmpty()) {
                    logger.error("No MAF files (*.maf) found in directory: $mafInput")
                    exitProcess(1)
                }
                logger.info("Found ${mafFiles.size} MAF file(s) in directory")
            }
            mafInput.isRegularFile() -> {
                // Check if it's a .txt file with paths or a single MAF file
                if (mafInput.extension == Constants.TEXT_FILE_EXTENSION) {
                    // It's a text file with paths
                    logger.info("Reading MAF file paths from: $mafInput")
                    mafInput.readLines().forEach { line ->
                        val trimmedLine = line.trim()
                        if (trimmedLine.isNotEmpty() && !trimmedLine.startsWith("#")) {
                            val mafFile = Path(trimmedLine)
                            if (mafFile.exists() && mafFile.isRegularFile()) {
                                mafFiles.add(mafFile)
                            } else {
                                logger.warn("MAF file not found or not a file: $trimmedLine")
                            }
                        }
                    }
                    if (mafFiles.isEmpty()) {
                        logger.error("No valid MAF files found in list file: $mafInput")
                        exitProcess(1)
                    }
                    logger.info("Found ${mafFiles.size} MAF file(s) in list")
                } else if (mafInput.extension in Constants.MAF_EXTENSIONS) {
                    // It's a single MAF file
                    logger.info("Using single MAF file: $mafInput")
                    mafFiles.add(mafInput)
                } else {
                    logger.error("MAF file must have a valid MAF extension (.maf) or be a .txt file with paths: $mafInput")
                    exitProcess(1)
                }
            }
            else -> {
                logger.error("MAF input is neither a file nor a directory: $mafInput")
                exitProcess(1)
            }
        }

        return mafFiles
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

        logger.info("Starting MAF to GVCF conversion")
        logger.info("Working directory: $workDir")
        logger.info("Reference file: $referenceFile")

        // Collect MAF files
        val mafFiles = collectMafFiles()
        logger.info("Processing ${mafFiles.size} MAF file(s)")

        // Validate that if multiple MAF files, outputFile should not be specified
        if (mafFiles.size > 1 && outputFile != null) {
            logger.warn("Output file specified but multiple MAF files provided. Output file will be auto-generated for each MAF file.")
        }

        // Create output directory
        val outputDir = workDir.resolve(OUTPUT_DIR).resolve(GVCF_RESULTS_DIR)
        if (!outputDir.exists()) {
            logger.debug("Creating output directory: $outputDir")
            outputDir.createDirectories()
            logger.info("Output directory created: $outputDir")
        }

        // Construct path to biokotlin-tools executable
        val biokotlinPath = workDir.resolve(Constants.SRC_DIR)
            .resolve(Constants.BIOKOTLIN_TOOLS_DIR)
            .resolve("bin")
            .resolve(BIOKOTLIN_EXECUTABLE)

        // Validate biokotlin-tools exists
        if (!biokotlinPath.exists()) {
            logger.error("biokotlin-tools executable not found at: $biokotlinPath")
            logger.error("Please run 'setup-environment' command first")
            exitProcess(1)
        }

        // Process each MAF file
        var successCount = 0
        var failureCount = 0
        mafFiles.forEachIndexed { index, mafFile ->
            logger.info("=".repeat(80))
            logger.info("Processing MAF ${index + 1}/${mafFiles.size}: ${mafFile.name}")
            logger.info("=".repeat(80))

            try {
                convertMafToGvcf(mafFile, biokotlinPath, outputDir, mafFiles.size == 1)
                successCount++
                logger.info("Successfully converted: ${mafFile.name}")
            } catch (e: Exception) {
                failureCount++
                logger.error("Failed to convert MAF: ${mafFile.name}", e)
                logger.error("Continuing with next MAF file...")
            }
        }

        logger.info("=".repeat(80))
        logger.info("All conversions completed!")
        logger.info("Total MAF files processed: ${mafFiles.size}")
        logger.info("Successful: $successCount")
        logger.info("Failed: $failureCount")
        logger.info("Output directory: $outputDir")
    }

    private fun convertMafToGvcf(mafFile: Path, biokotlinPath: Path, outputDir: Path, isSingleMaf: Boolean) {
        val mafBaseName = mafFile.nameWithoutExtension

        // Determine sample name (use provided or default to MAF base name)
        val finalSampleName = sampleName ?: mafBaseName
        logger.info("Sample name: $finalSampleName")

        // Determine output file name
        val outputFileName = if (outputFile != null && isSingleMaf) {
            outputFile!!.fileName
        } else {
            // Auto-generate output filename based on MAF filename
            Path.of("${mafBaseName}.g.vcf")
        }

        val fullOutputPath = outputDir.resolve(outputFileName)
        logger.info("Output file: $fullOutputPath")

        // Run biokotlin-tools maf-to-gvcf-converter
        logger.info("Running biokotlin-tools maf-to-gvcf-converter")
        val exitCode = ProcessRunner.runCommand(
            biokotlinPath.toString(),
            MAF_TO_GVCF_COMMAND,
            "--reference-file=${referenceFile}",
            "--maf-file=${mafFile}",
            "--output-file=${fullOutputPath}",
            "--sample-name=${finalSampleName}",
            workingDir = workDir.toFile(),
            logger = logger
        )

        if (exitCode != 0) {
            throw RuntimeException("MAF to GVCF conversion failed with exit code $exitCode")
        }

        logger.info("Conversion completed for: ${mafFile.name}")
    }
}
