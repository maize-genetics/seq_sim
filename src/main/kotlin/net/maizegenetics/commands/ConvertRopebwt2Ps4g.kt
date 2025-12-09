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

class ConvertRopebwt2Ps4g : CliktCommand(name = "convert-ropebwt2ps4g") {
    companion object {
        private const val LOG_FILE_NAME = "14_convert_ropebwt2ps4g.log"
        private const val OUTPUT_DIR = "output"
        private const val CONVERT_RESULTS_DIR = "14_convert_ropebwt2ps4g_results"
        private const val PS4G_FILE_PATHS_FILE = "ps4g_file_paths.txt"
        private const val DEFAULT_MIN_MEM_LENGTH = 135
        private const val DEFAULT_MAX_NUM_HITS = 16
        private const val BED_EXTENSION = "bed"
    }

    private val logger: Logger = LogManager.getLogger(ConvertRopebwt2Ps4g::class.java)

    private val workDir by option(
        "--work-dir", "-w",
        help = "Working directory for files and scripts"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)
        .default(Path.of(Constants.DEFAULT_WORK_DIR))

    private val bedInput by option(
        "--bed-input", "-b",
        help = "BED file, directory of BED files, or text file with paths to BED files (one per line)"
    ).path(mustExist = false)

    private val outputDirOption by option(
        "--output-dir", "-o",
        help = "Output directory for PS4G files (default: work_dir/output/14_convert_ropebwt2ps4g_results)"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)

    private val splineKnotDirOption by option(
        "--spline-knot-dir", "-s",
        help = "Directory containing spline knots from step 13 (auto-detected if not specified)"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)

    private val minMemLength by option(
        "--min-mem-length", "-m",
        help = "Minimum MEM length threshold"
    ).int()
        .default(DEFAULT_MIN_MEM_LENGTH)

    private val maxNumHits by option(
        "--max-num-hits", "-x",
        help = "Maximum allowable haplotype hits per alignment"
    ).int()
        .default(DEFAULT_MAX_NUM_HITS)

    private fun collectBedFiles(): List<Path> {
        // If no input specified, try to auto-detect from step 12
        val actualInput = bedInput ?: run {
            logger.info("No BED input specified, attempting to auto-detect from step 12")
            FileUtils.autoDetectStepOutput(
                workDir,
                "12_ropebwt_mem_results",
                logger,
                "Please specify --bed-input or ensure step 12 (ropebwt-mem) has been run"
            )
        }

        return FileUtils.collectFiles(
            actualInput,
            setOf(BED_EXTENSION),
            "BED",
            logger
        )
    }

    private fun findSplineKnotDir(): Path {
        return FileUtils.autoDetectStepOutput(
            workDir,
            "13_spline_knots_results",
            logger,
            "Please specify --spline-knot-dir manually or ensure step 13 (build-spline-knots) has been run"
        )
    }

    override fun run() {
        // Validate working directory and PHG binary
        val phgBinary = ValidationUtils.validatePhgSetup(workDir, logger)

        // Configure file logging to working directory
        LoggingUtils.setupFileLogging(workDir, LOG_FILE_NAME, logger)

        logger.info("Starting PHG convert-ropebwt2ps4g-file")
        logger.info("Working directory: $workDir")

        // Collect BED files
        val bedFiles = collectBedFiles()
        logger.info("Processing ${bedFiles.size} BED file(s)")

        // Determine spline knot directory
        val splineKnotDir = splineKnotDirOption ?: findSplineKnotDir()
        if (!splineKnotDir.exists()) {
            logger.error("Spline knot directory not found: $splineKnotDir")
            exitProcess(1)
        }
        logger.info("Using spline knot directory: $splineKnotDir")

        // Log parameters
        logger.info("Min MEM length: $minMemLength")
        logger.info("Max num hits: $maxNumHits")

        // Create output directory (use custom or default)
        val outputDir = FileUtils.resolveOutputDirectory(workDir, outputDirOption, CONVERT_RESULTS_DIR)
        FileUtils.createOutputDirectory(outputDir, logger)

        // Process each BED file with PHG convert-ropebwt2ps4g-file
        var successCount = 0
        var failureCount = 0
        val ps4gFiles = mutableListOf<Path>()

        bedFiles.forEach { bedFile ->
            val sampleName = bedFile.nameWithoutExtension
            val outputFile = outputDir.resolve("${sampleName}.ps4g")

            logger.info("Processing: ${bedFile.fileName} -> ${outputFile.fileName}")

            val exitCode = ProcessRunner.runCommand(
                phgBinary.toString(),
                "convert-ropebwt2ps4g-file",
                "--ropebwt-bed", bedFile.toString(),
                "--output-dir", outputDir.toString(),
                "--spline-knot-dir", splineKnotDir.toString(),
                "--min-mem-length", minMemLength.toString(),
                "--max-num-hits", maxNumHits.toString(),
                workingDir = workDir.toFile(),
                logger = logger
            )

            if (exitCode == 0) {
                successCount++
                // PHG creates output file with same base name as input BED
                if (outputFile.exists()) {
                    ps4gFiles.add(outputFile)
                    logger.info("Successfully processed: ${bedFile.fileName}")
                } else {
                    logger.warn("Command succeeded but output file not found: $outputFile")
                }
            } else {
                failureCount++
                logger.error("Failed to process ${bedFile.fileName} with exit code $exitCode")
            }
        }

        logger.info("PHG convert-ropebwt2ps4g-file completed")
        logger.info("Success: $successCount, Failures: $failureCount")

        // Write PS4G file paths to text file
        FileUtils.writeFilePaths(
            ps4gFiles,
            outputDir.resolve(PS4G_FILE_PATHS_FILE),
            logger,
            "PS4G file"
        )

        logger.info("Output directory: $outputDir")

        if (failureCount > 0) {
            exitProcess(1)
        }
    }
}
