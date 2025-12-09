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

class PickCrossovers : CliktCommand(name = "pick-crossovers") {
    companion object {
        private const val LOG_FILE_NAME = "06_pick_crossovers.log"
        private const val CROSSOVERS_RESULTS_DIR = "06_crossovers_results"
        private const val REFKEY_PATHS_FILE = "refkey_file_paths.txt"
        private const val PYTHON_SCRIPT = "src/python/cross/pick_crossovers.py"
    }

    private val logger: Logger = LogManager.getLogger(PickCrossovers::class.java)

    private val workDir by option(
        "--work-dir", "-w",
        help = "Working directory for files and scripts"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)
        .default(Path.of(Constants.DEFAULT_WORK_DIR))

    private val refFasta by option(
        "--ref-fasta", "-r",
        help = "Reference FASTA file"
    ).path(mustExist = true, canBeFile = true, canBeDir = false)
        .required()

    private val assemblyList by option(
        "--assembly-list", "-a",
        help = "Text file containing assembly paths and names (tab-separated: path<TAB>name)"
    ).path(mustExist = true, canBeFile = true, canBeDir = false)
        .required()

    private val outputDirOption by option(
        "--output-dir", "-o",
        help = "Custom output directory (default: work_dir/output/06_crossovers_results)"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)

    override fun run() {
        // Validate working directory exists
        ValidationUtils.validateWorkingDirectory(workDir, logger)

        // Configure file logging to working directory
        LoggingUtils.setupFileLogging(workDir, LOG_FILE_NAME, logger)

        logger.info("Starting pick crossovers")
        logger.info("Working directory: $workDir")
        logger.info("Reference FASTA: $refFasta")
        logger.info("Assembly list: $assemblyList")

        // Validate MLImpute directory exists
        val mlimputeDir = workDir.resolve(Constants.SRC_DIR).resolve(Constants.MLIMPUTE_DIR)
        ValidationUtils.validateBinaryExists(mlimputeDir, "MLImpute", logger)

        // Validate Python script exists
        val pythonScript = mlimputeDir.resolve(PYTHON_SCRIPT)
        if (!pythonScript.exists()) {
            logger.error("Python script not found: $pythonScript")
            exitProcess(1)
        }

        // Create output directory (use custom or default)
        val outputDir = FileUtils.resolveOutputDirectory(workDir, outputDirOption, CROSSOVERS_RESULTS_DIR)
        FileUtils.createOutputDirectory(outputDir, logger)

        // Run pick_crossovers.py
        logger.info("Running pick_crossovers.py")
        val exitCode = ProcessRunner.runCommand(
            "pixi", "run",
            "python", pythonScript.toString(),
            "--ref-fasta", refFasta.toString(),
            "--assembly-list", assemblyList.toString(),
            workingDir = outputDir.toFile(),
            logger = logger
        )

        if (exitCode != 0) {
            logger.error("pick_crossovers.py failed with exit code $exitCode")
            exitProcess(exitCode)
        }

        logger.info("Pick crossovers completed successfully")

        // Collect generated refkey BED files
        val refkeyFiles = outputDir.listDirectoryEntries()
            .filter { it.isRegularFile() && it.name.endsWith("_refkey.bed") }
            .sorted()

        if (refkeyFiles.isEmpty()) {
            logger.warn("No refkey BED files generated")
        } else {
            logger.info("Generated ${refkeyFiles.size} refkey BED file(s):")
            refkeyFiles.forEach { logger.info("  $it") }

            // Write refkey file paths to text file
            FileUtils.writeFilePaths(
                refkeyFiles,
                outputDir.resolve(REFKEY_PATHS_FILE),
                logger,
                "Refkey file"
            )
        }

        logger.info("Output directory: $outputDir")
    }
}
