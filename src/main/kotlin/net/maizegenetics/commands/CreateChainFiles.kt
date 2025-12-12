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

class CreateChainFiles : CliktCommand(name = "create-chain-files") {
    companion object {
        private const val LOG_FILE_NAME = "07_create_chain_files.log"
        private const val CHAIN_RESULTS_DIR = "07_chain_results"
        private const val CHAIN_PATHS_FILE = "chain_file_paths.txt"
        private const val BASH_SCRIPT = "src/python/cross/create_chains.sh"
        private const val DEFAULT_JOBS = 8
        private val MAF_EXTENSIONS_WITH_GZ = Constants.MAF_EXTENSIONS + "maf.gz"
    }

    private val logger: Logger = LogManager.getLogger(CreateChainFiles::class.java)

    private val workDir by option(
        "--work-dir", "-w",
        help = "Working directory for files and scripts"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)
        .default(Path.of(Constants.DEFAULT_WORK_DIR))

    private val mafInput by option(
        "--maf-input", "-m",
        help = "MAF file, directory of MAF files, or text file with paths to MAF files (one per line)"
    ).path(mustExist = true)
        .required()

    private val jobs by option(
        "--jobs", "-j",
        help = "Number of parallel jobs"
    ).int()
        .default(DEFAULT_JOBS)

    private val outputDirOption by option(
        "--output-dir", "-o",
        help = "Custom output directory (default: work_dir/output/07_chain_results)"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)

    private fun collectMafFiles(): List<Path> {
        return FileUtils.collectFiles(
            mafInput,
            MAF_EXTENSIONS_WITH_GZ,
            "MAF",
            logger
        )
    }

    override fun run() {
        // Validate working directory exists
        ValidationUtils.validateWorkingDirectory(workDir, logger)

        // Configure file logging to working directory
        LoggingUtils.setupFileLogging(workDir, LOG_FILE_NAME, logger)

        logger.info("Starting create chain files")
        logger.info("Working directory: $workDir")
        logger.info("Parallel jobs: $jobs")

        // Validate MLImpute directory exists
        val mlimputeDir = workDir.resolve(Constants.SRC_DIR).resolve(Constants.MLIMPUTE_DIR)
        ValidationUtils.validateBinaryExists(mlimputeDir, "MLImpute", logger)

        // Validate bash script exists
        val bashScript = mlimputeDir.resolve(BASH_SCRIPT)
        if (!bashScript.exists()) {
            logger.error("Bash script not found: $bashScript")
            exitProcess(1)
        }

        // Collect MAF files
        val mafFiles = collectMafFiles()
        logger.info("Processing ${mafFiles.size} MAF file(s)")

        // Create output directory (use custom or default)
        val outputDir = FileUtils.resolveOutputDirectory(workDir, outputDirOption, CHAIN_RESULTS_DIR)
        FileUtils.createOutputDirectory(outputDir, logger)

        // Create temporary directory for MAF files
        val tempMafDir = outputDir.resolve("temp_maf_files")
        if (!tempMafDir.exists()) {
            tempMafDir.createDirectories()
        }

        // Copy/link MAF files to temporary directory
        logger.info("Preparing MAF files in temporary directory")
        mafFiles.forEach { mafFile ->
            val targetFile = tempMafDir.resolve(mafFile.fileName)
            if (!targetFile.exists()) {
                mafFile.copyTo(targetFile)
            }
        }

        // Run create_chains.sh
        logger.info("Running create_chains.sh")
        val exitCode = ProcessRunner.runCommand(
            "bash", bashScript.toString(),
            "-i", tempMafDir.toString(),
            "-o", outputDir.toString(),
            "-j", jobs.toString(),
            workingDir = workDir.toFile(),
            logger = logger
        )

        if (exitCode != 0) {
            logger.error("create_chains.sh failed with exit code $exitCode")
            exitProcess(exitCode)
        }

        logger.info("Create chain files completed successfully")

        // Clean up temporary directory
        logger.info("Cleaning up temporary MAF directory")
        tempMafDir.toFile().deleteRecursively()

        // Collect generated chain files
        val chainFiles = outputDir.listDirectoryEntries()
            .filter { it.isRegularFile() && it.extension == "chain" }
            .sorted()

        if (chainFiles.isEmpty()) {
            logger.warn("No chain files generated")
        } else {
            logger.info("Generated ${chainFiles.size} chain file(s):")
            chainFiles.forEach { logger.info("  $it") }

            // Write chain file paths to text file
            FileUtils.writeFilePaths(
                chainFiles,
                outputDir.resolve(CHAIN_PATHS_FILE),
                logger,
                "Chain file"
            )
        }

        logger.info("Output directory: $outputDir")
    }
}
