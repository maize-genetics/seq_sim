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

class ConvertCoordinates : CliktCommand(name = "convert-coordinates") {
    companion object {
        private const val LOG_FILE_NAME = "08_convert_coordinates.log"
        private const val COORDS_RESULTS_DIR = "08_coordinates_results"
        private const val KEY_PATHS_FILE = "key_file_paths.txt"
        private const val FOUNDER_KEY_PATHS_FILE = "founder_key_file_paths.txt"
        private const val PYTHON_SCRIPT = "src/python/cross/convert_coords.py"
        private const val DEFAULT_REFKEY_DIR = "06_crossovers_results"
    }

    private val logger: Logger = LogManager.getLogger(ConvertCoordinates::class.java)

    private val workDir by option(
        "--work-dir", "-w",
        help = "Working directory for files and scripts"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)
        .default(Path.of(Constants.DEFAULT_WORK_DIR))

    private val assemblyList by option(
        "--assembly-list", "-a",
        help = "Text file containing assembly paths and names (tab-separated: path<TAB>name)"
    ).path(mustExist = true, canBeFile = true, canBeDir = false)
        .required()

    private val chainDir by option(
        "--chain-dir", "-c",
        help = "Directory containing chain files"
    ).path(mustExist = true, canBeFile = false, canBeDir = true)
        .required()

    private val refkeyDir by option(
        "--refkey-dir", "-r",
        help = "Directory containing refkey BED files (default: automatically detect from crossovers results)"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)

    private val outputDirOption by option(
        "--output-dir", "-o",
        help = "Custom output directory (default: work_dir/output/08_coordinates_results)"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)

    override fun run() {
        // Validate working directory exists
        ValidationUtils.validateWorkingDirectory(workDir, logger)

        // Configure file logging to working directory
        LoggingUtils.setupFileLogging(workDir, LOG_FILE_NAME, logger)

        logger.info("Starting convert coordinates")
        logger.info("Working directory: $workDir")
        logger.info("Assembly list: $assemblyList")
        logger.info("Chain directory: $chainDir")

        // Validate MLImpute directory exists
        val mlimputeDir = workDir.resolve(Constants.SRC_DIR).resolve(Constants.MLIMPUTE_DIR)
        ValidationUtils.validateBinaryExists(mlimputeDir, "MLImpute", logger)

        // Validate Python script exists
        val pythonScript = mlimputeDir.resolve(PYTHON_SCRIPT)
        if (!pythonScript.exists()) {
            logger.error("Python script not found: $pythonScript")
            exitProcess(1)
        }

        // Determine refkey directory
        val actualRefkeyDir = refkeyDir ?: FileUtils.autoDetectStepOutput(
            workDir,
            DEFAULT_REFKEY_DIR,
            logger,
            "Please run 'pick-crossovers' command first or specify --refkey-dir"
        )
        logger.info("Refkey directory: $actualRefkeyDir")

        // Create output directory (use custom or default)
        val outputDir = FileUtils.resolveOutputDirectory(workDir, outputDirOption, COORDS_RESULTS_DIR)
        FileUtils.createOutputDirectory(outputDir, logger)

        // Copy refkey BED files to output directory if they're not already there
        logger.info("Preparing refkey BED files")
        val refkeyFiles = actualRefkeyDir.listDirectoryEntries()
            .filter { it.isRegularFile() && it.name.endsWith("_refkey.bed") }

        if (refkeyFiles.isEmpty()) {
            logger.error("No refkey BED files found in: $actualRefkeyDir")
            exitProcess(1)
        }

        refkeyFiles.forEach { refkeyFile ->
            val targetFile = outputDir.resolve(refkeyFile.fileName)
            if (!targetFile.exists()) {
                refkeyFile.copyTo(targetFile)
                logger.debug("Copied: ${refkeyFile.fileName}")
            }
        }

        // Run convert_coords.py
        logger.info("Running convert_coords.py")
        val exitCode = ProcessRunner.runCommand(
            "pixi", "run",
            "python", pythonScript.toString(),
            "--assembly-list", assemblyList.toString(),
            "--chain-dir", chainDir.toString(),
            workingDir = outputDir.toFile(),
            logger = logger
        )

        if (exitCode != 0) {
            logger.error("convert_coords.py failed with exit code $exitCode")
            exitProcess(exitCode)
        }

        logger.info("Convert coordinates completed successfully")

        // Collect generated key BED files (assembly keys)
        val keyFiles = outputDir.listDirectoryEntries()
            .filter { it.isRegularFile() && it.name.endsWith("_key.bed") && !it.name.matches(Regex("^\\d+_key\\.bed$")) }
            .sorted()

        // Collect generated founder key BED files
        val founderKeyFiles = outputDir.listDirectoryEntries()
            .filter { it.isRegularFile() && it.name.matches(Regex("^\\d+_key\\.bed$")) }
            .sorted()

        if (keyFiles.isEmpty() && founderKeyFiles.isEmpty()) {
            logger.warn("No key BED files generated")
        } else {
            if (keyFiles.isNotEmpty()) {
                logger.info("Generated ${keyFiles.size} assembly key BED file(s):")
                keyFiles.forEach { logger.info("  $it") }

                // Write key file paths to text file
                FileUtils.writeFilePaths(
                    keyFiles,
                    outputDir.resolve(KEY_PATHS_FILE),
                    logger,
                    "Assembly key file"
                )
            }

            if (founderKeyFiles.isNotEmpty()) {
                logger.info("Generated ${founderKeyFiles.size} founder key BED file(s):")
                founderKeyFiles.forEach { logger.info("  $it") }

                // Write founder key file paths to text file
                FileUtils.writeFilePaths(
                    founderKeyFiles,
                    outputDir.resolve(FOUNDER_KEY_PATHS_FILE),
                    logger,
                    "Founder key file"
                )
            }
        }

        logger.info("Output directory: $outputDir")
    }
}
