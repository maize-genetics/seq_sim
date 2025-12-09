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

class GenerateRecombinedSequences : CliktCommand(name = "generate-recombined-sequences") {
    companion object {
        private const val LOG_FILE_NAME = "09_generate_recombined_sequences.log"
        private const val RECOMBINED_RESULTS_DIR = "09_recombined_sequences"
        private const val RECOMBINED_FASTAS_DIR = "recombinate_fastas"
        private const val FASTA_PATHS_FILE = "recombined_fasta_paths.txt"
        private const val PYTHON_SCRIPT = "src/python/cross/write_fastas.py"
        private const val DEFAULT_FOUNDER_KEY_DIR = "08_coordinates_results"
    }

    private val logger: Logger = LogManager.getLogger(GenerateRecombinedSequences::class.java)

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

    private val chromosomeList by option(
        "--chromosome-list", "-c",
        help = "Text file containing chromosome names (one per line)"
    ).path(mustExist = true, canBeFile = true, canBeDir = false)
        .required()

    private val assemblyDir by option(
        "--assembly-dir", "-d",
        help = "Directory containing parent assembly FASTA files"
    ).path(mustExist = true, canBeFile = false, canBeDir = true)
        .required()

    private val founderKeyDir by option(
        "--founder-key-dir", "-k",
        help = "Directory containing founder key BED files (default: automatically detect from coordinates results)"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)

    private val outputDirOption by option(
        "--output-dir", "-o",
        help = "Custom output directory (default: work_dir/output/09_recombined_sequences)"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)

    override fun run() {
        // Validate working directory exists
        ValidationUtils.validateWorkingDirectory(workDir, logger)

        // Configure file logging to working directory
        LoggingUtils.setupFileLogging(workDir, LOG_FILE_NAME, logger)

        logger.info("Starting generate recombined sequences")
        logger.info("Working directory: $workDir")
        logger.info("Assembly list: $assemblyList")
        logger.info("Chromosome list: $chromosomeList")
        logger.info("Assembly directory: $assemblyDir")

        // Validate MLImpute directory exists
        val mlimputeDir = workDir.resolve(Constants.SRC_DIR).resolve(Constants.MLIMPUTE_DIR)
        ValidationUtils.validateBinaryExists(mlimputeDir, "MLImpute", logger)

        // Validate Python script exists
        val pythonScript = mlimputeDir.resolve(PYTHON_SCRIPT)
        if (!pythonScript.exists()) {
            logger.error("Python script not found: $pythonScript")
            exitProcess(1)
        }

        // Determine founder key directory
        val actualFounderKeyDir = founderKeyDir ?: FileUtils.autoDetectStepOutput(
            workDir,
            DEFAULT_FOUNDER_KEY_DIR,
            logger,
            "Please run 'convert-coordinates' command first or specify --founder-key-dir"
        )
        logger.info("Founder key directory: $actualFounderKeyDir")

        // Validate founder key files exist
        val founderKeyFiles = actualFounderKeyDir.listDirectoryEntries()
            .filter { it.isRegularFile() && it.name.matches(Regex("^\\d+_key\\.bed$")) }

        if (founderKeyFiles.isEmpty()) {
            logger.error("No founder key BED files (format: N_key.bed) found in: $actualFounderKeyDir")
            exitProcess(1)
        }
        logger.info("Found ${founderKeyFiles.size} founder key file(s)")

        // Create output directory (use custom or default)
        val outputDir = FileUtils.resolveOutputDirectory(workDir, outputDirOption, RECOMBINED_RESULTS_DIR)
        FileUtils.createOutputDirectory(outputDir, logger)

        // Copy founder key BED files to output directory if they're not already there
        logger.info("Preparing founder key BED files")
        founderKeyFiles.forEach { founderKeyFile ->
            val targetFile = outputDir.resolve(founderKeyFile.fileName)
            if (!targetFile.exists()) {
                founderKeyFile.copyTo(targetFile)
                logger.debug("Copied: ${founderKeyFile.fileName}")
            }
        }

        // Run write_fastas.py
        logger.info("Running write_fastas.py")
        val exitCode = ProcessRunner.runCommand(
            "pixi", "run",
            "python", pythonScript.toString(),
            "--assembly-list", assemblyList.toString(),
            "--chromosome-list", chromosomeList.toString(),
            "--assembly-dir", assemblyDir.toString(),
            workingDir = outputDir.toFile(),
            logger = logger
        )

        if (exitCode != 0) {
            logger.error("write_fastas.py failed with exit code $exitCode")
            exitProcess(exitCode)
        }

        logger.info("Generate recombined sequences completed successfully")

        // Collect generated FASTA files from recombinate_fastas subdirectory
        val recombinedFastasDir = outputDir.resolve(RECOMBINED_FASTAS_DIR)
        if (!recombinedFastasDir.exists()) {
            logger.warn("Recombined FASTA directory not found: $recombinedFastasDir")
            return
        }

        val recombinedFastas = recombinedFastasDir.listDirectoryEntries()
            .filter { it.isRegularFile() && it.extension in Constants.FASTA_EXTENSIONS }
            .sortedBy { it.nameWithoutExtension.toIntOrNull() ?: Int.MAX_VALUE }

        if (recombinedFastas.isEmpty()) {
            logger.warn("No recombined FASTA files generated")
        } else {
            logger.info("Generated ${recombinedFastas.size} recombined FASTA file(s):")
            recombinedFastas.forEach { logger.info("  $it") }

            // Write recombined FASTA file paths to text file
            FileUtils.writeFilePaths(
                recombinedFastas,
                outputDir.resolve(FASTA_PATHS_FILE),
                logger,
                "Recombined FASTA file"
            )
        }

        logger.info("Output directory: $outputDir")
        logger.info("Recombined FASTA files: $recombinedFastasDir")
    }
}
