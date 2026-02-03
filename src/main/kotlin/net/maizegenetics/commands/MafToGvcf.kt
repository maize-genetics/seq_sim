package net.maizegenetics.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
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

class MafToGvcf : CliktCommand(name = "maf-to-gvcf") {
    companion object {
        private const val LOG_FILE_NAME = "02_maf_to_gvcf.log"
        private const val OUTPUT_DIR = "output"
        private const val GVCF_RESULTS_DIR = "02_gvcf_results"
        private const val GVCF_PATHS_FILE = "gvcf_file_paths.txt"
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

    private val outputDirOption by option(
        "--output-dir", "-d",
        help = "Custom output directory (default: work_dir/output/02_gvcf_results)"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)

    private fun collectMafFiles(): List<Path> {
        return FileUtils.collectFiles(
            mafInput,
            Constants.MAF_EXTENSIONS,
            "MAF",
            logger
        )
    }

    override fun run() {
        // Validate working directory
        ValidationUtils.validateWorkingDirectory(workDir, logger)

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

        // Create output directory (use custom or default)
        val outputDir = FileUtils.resolveOutputDirectory(workDir, outputDirOption, GVCF_RESULTS_DIR)
        FileUtils.createOutputDirectory(outputDir, logger)

        // Validate biokotlin-tools
        val biokotlinPath = ValidationUtils.validateBiokotlinSetup(workDir, logger)

        // Process each MAF file
        var successCount = 0
        var failureCount = 0
        val gvcfFilePaths = mutableListOf<Path>()

        mafFiles.forEachIndexed { index, mafFile ->
            logger.info("=".repeat(80))
            logger.info("Processing MAF ${index + 1}/${mafFiles.size}: ${mafFile.name}")
            logger.info("=".repeat(80))

            try {
                val gvcfPath = convertMafToGvcf(mafFile, biokotlinPath, outputDir, mafFiles.size == 1)
                gvcfFilePaths.add(gvcfPath)
                successCount++
                logger.info("Successfully converted: ${mafFile.name}")
            } catch (e: Exception) {
                failureCount++
                logger.error("Failed to convert MAF: ${mafFile.name}", e)
                logger.error("Continuing with next MAF file...")
            }
        }

        // Write GVCF file paths to text file
        FileUtils.writeFilePaths(
            gvcfFilePaths,
            outputDir.resolve(GVCF_PATHS_FILE),
            logger,
            "GVCF file"
        )

        logger.info("=".repeat(80))
        logger.info("All conversions completed!")
        logger.info("Total MAF files processed: ${mafFiles.size}")
        logger.info("Successful: $successCount")
        logger.info("Failed: $failureCount")
        logger.info("Output directory: $outputDir")
    }

    private fun convertMafToGvcf(mafFile: Path, biokotlinPath: Path, outputDir: Path, isSingleMaf: Boolean): Path {
        val mafBaseName = mafFile.nameWithoutExtension

        // Determine sample name (use provided or default to MAF base name)
        val finalSampleName = sampleName ?: mafBaseName
        logger.info("Sample name: $finalSampleName")

        // Determine output file name
        // Note: biokotlin-tools automatically compresses and adds .gz extension
        val outputFileName: Path
        val expectedOutputPath: Path
        
        if (outputFile != null && isSingleMaf) {
            val userFileName = outputFile!!.fileName.toString()
            // If user specified .gz extension, strip it since biokotlin-tools adds it
            if (userFileName.endsWith(".gz")) {
                outputFileName = Path.of(userFileName.removeSuffix(".gz"))
                expectedOutputPath = outputDir.resolve(userFileName)
            } else {
                outputFileName = outputFile!!.fileName
                expectedOutputPath = outputDir.resolve("${userFileName}.gz")
            }
        } else {
            // Auto-generate output filename based on MAF filename
            // biokotlin-tools will add .gz when compressing
            outputFileName = Path.of("${mafBaseName}.g.vcf")
            expectedOutputPath = outputDir.resolve("${mafBaseName}.g.vcf.gz")
        }

        val fullOutputPath = outputDir.resolve(outputFileName)
        logger.info("Output file: $expectedOutputPath")

        // Run biokotlin-tools maf-to-gvcf-converter through pixi to use Java 21
        logger.info("Running biokotlin-tools maf-to-gvcf-converter")
        val exitCode = ProcessRunner.runCommand(
            "pixi", "run",
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

        // Return the GVCF file path (with .gz extension added by biokotlin-tools)
        return expectedOutputPath
    }
}
