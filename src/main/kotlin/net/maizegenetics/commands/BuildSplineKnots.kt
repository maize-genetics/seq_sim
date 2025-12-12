package net.maizegenetics.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
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

class BuildSplineKnots : CliktCommand(name = "build-spline-knots") {
    companion object {
        private const val LOG_FILE_NAME = "13_build_spline_knots.log"
        private const val OUTPUT_DIR = "output"
        private const val SPLINE_KNOTS_RESULTS_DIR = "13_spline_knots_results"
        private const val DEFAULT_VCF_TYPE = "hvcf"
        private const val DEFAULT_MIN_INDEL_LENGTH = 10
        private const val DEFAULT_NUM_BPS_PER_KNOT = 50000
        private const val DEFAULT_RANDOM_SEED = 12345
    }

    private val logger: Logger = LogManager.getLogger(BuildSplineKnots::class.java)

    private val workDir by option(
        "--work-dir", "-w",
        help = "Working directory for files and scripts"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)
        .default(Path.of(Constants.DEFAULT_WORK_DIR))

    private val vcfDir by option(
        "--vcf-dir", "-v",
        help = "Directory containing the hVCF or gVCF files"
    ).path(mustExist = true, canBeFile = false, canBeDir = true)
        .required()

    private val vcfType by option(
        "--vcf-type", "-t",
        help = "Type of VCFs to build the splines from (hvcf or gvcf)"
    ).default(DEFAULT_VCF_TYPE)

    private val outputDirOption by option(
        "--output-dir", "-o",
        help = "Output directory to write the spline knots to (default: work_dir/output/13_spline_knots_results)"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)

    private val minIndelLength by option(
        "--min-indel-length", "-m",
        help = "Minimum length of an indel to break up the running block for spline creation (gVCF only)"
    ).int()
        .default(DEFAULT_MIN_INDEL_LENGTH)

    private val numBpsPerKnot by option(
        "--num-bps-per-knot", "-n",
        help = "Maximum number of base pairs per knot for each contig's spline"
    ).int()
        .default(DEFAULT_NUM_BPS_PER_KNOT)

    private val contigList by option(
        "--contig-list", "-c",
        help = "Comma-separated list of chromosomes to include in spline generation (omit to include all)"
    )

    private val randomSeed by option(
        "--random-seed", "-r",
        help = "Random seed used for downsampling the number of points per chromosome"
    ).int()
        .default(DEFAULT_RANDOM_SEED)

    override fun run() {
        // Validate working directory and PHG binary
        val phgBinary = ValidationUtils.validatePhgSetup(workDir, logger)

        // Validate VCF type
        if (vcfType !in setOf("hvcf", "gvcf")) {
            logger.error("Invalid VCF type: $vcfType. Must be 'hvcf' or 'gvcf'")
            exitProcess(1)
        }

        // Configure file logging to working directory
        LoggingUtils.setupFileLogging(workDir, LOG_FILE_NAME, logger)

        logger.info("Starting PHG build-spline-knots")
        logger.info("Working directory: $workDir")
        logger.info("VCF directory: $vcfDir")
        logger.info("VCF type: $vcfType")
        logger.info("Min indel length: $minIndelLength")
        logger.info("Num BPs per knot: $numBpsPerKnot")
        logger.info("Random seed: $randomSeed")
        if (contigList != null) {
            logger.info("Contig list: $contigList")
        } else {
            logger.info("Contig list: all chromosomes")
        }

        // Create output directory (use custom or default)
        val outputDir = FileUtils.resolveOutputDirectory(workDir, outputDirOption, SPLINE_KNOTS_RESULTS_DIR)
        logger.info("Output directory: $outputDir")
        FileUtils.createOutputDirectory(outputDir, logger)

        // Build command arguments
        val commandArgs = mutableListOf(
            phgBinary.toString(),
            "build-spline-knots",
            "--vcf-dir", vcfDir.toString(),
            "--vcf-type", vcfType,
            "--output-dir", outputDir.toString(),
            "--min-indel-length", minIndelLength.toString(),
            "--num-bps-per-knot", numBpsPerKnot.toString(),
            "--random-seed", randomSeed.toString()
        )

        // Add contig list if specified
        if (contigList != null) {
            commandArgs.add("--contig-list")
            commandArgs.add(contigList!!)
        }

        // Run PHG build-spline-knots command
        logger.info("Running PHG build-spline-knots...")
        val exitCode = ProcessRunner.runCommand(
            *commandArgs.toTypedArray(),
            workingDir = workDir.toFile(),
            logger = logger
        )

        if (exitCode == 0) {
            logger.info("PHG build-spline-knots completed successfully")
            logger.info("Spline knots written to: $outputDir")
        } else {
            logger.error("PHG build-spline-knots failed with exit code $exitCode")
            exitProcess(1)
        }
    }
}
