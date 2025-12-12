package net.maizegenetics.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
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
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.system.exitProcess

class RopeBwtChrIndex : CliktCommand(name = "rope-bwt-chr-index") {
    companion object {
        private const val LOG_FILE_NAME = "11_rope_bwt_chr_index.log"
        private const val ROPE_BWT_RESULTS_DIR = "11_rope_bwt_index_results"
        private const val KEYFILE_NAME = "phg_keyfile.txt"
        private const val DEFAULT_INDEX_PREFIX = "phgIndex"
        private const val DEFAULT_THREADS = 20
        private const val DEFAULT_DELETE_FMR = true
    }

    private val logger: Logger = LogManager.getLogger(RopeBwtChrIndex::class.java)

    private val workDir by option(
        "--work-dir", "-w",
        help = "Working directory for files and scripts"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)
        .default(Path.of(Constants.DEFAULT_WORK_DIR))

    private val fastaInput by option(
        "--fasta-input", "-f",
        help = "FASTA file, directory of FASTA files, or text file with paths to FASTA files (one per line). Mutually exclusive with --keyfile."
    ).path(mustExist = false)

    private val keyfile by option(
        "--keyfile", "-k",
        help = "Pre-made keyfile (tab-delimited with 'Fasta' and 'SampleName' columns). Mutually exclusive with --fasta-input."
    ).path(mustExist = true, canBeFile = true, canBeDir = false)

    private val outputDirOption by option(
        "--output-dir", "-o",
        help = "Output directory for index files (default: work_dir/output/11_rope_bwt_index_results)"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)

    private val indexFilePrefix by option(
        "--index-file-prefix", "-p",
        help = "Prefix for generated index files (no extension)"
    ).default(DEFAULT_INDEX_PREFIX)

    private val threads by option(
        "--threads", "-t",
        help = "Number of threads for index creation"
    ).int()
        .default(DEFAULT_THREADS)

    private val deleteFmrIndex by option(
        "--delete-fmr-index",
        help = "Delete .fmr index files after converting to .fmd"
    ).boolean()
        .default(DEFAULT_DELETE_FMR)

    private fun collectFastaFiles(): List<Path> {
        return FileUtils.collectFiles(
            fastaInput,
            Constants.FASTA_EXTENSIONS,
            "FASTA",
            logger
        )
    }

    private fun generateKeyfile(fastaFiles: List<Path>, outputDir: Path): Path {
        logger.info("Generating keyfile from FASTA files")
        val keyfilePath = outputDir.resolve(KEYFILE_NAME)

        val keyfileLines = mutableListOf("Fasta\tSampleName")
        val problemNames = mutableListOf<String>()

        fastaFiles.forEach { fastaFile ->
            val sampleName = fastaFile.nameWithoutExtension
            keyfileLines.add("${fastaFile.toAbsolutePath()}\t$sampleName")

            // Check for underscores and warn
            if (sampleName.contains("_")) {
                problemNames.add(sampleName)
            }
        }

        // Write keyfile
        keyfilePath.writeLines(keyfileLines)
        logger.info("Generated keyfile: $keyfilePath")

        // Warn about underscores
        if (problemNames.isNotEmpty()) {
            logger.warn("WARNING: The following sample names contain underscores, which may cause issues with PHG:")
            problemNames.forEach { name ->
                logger.warn("  - $name")
            }
            logger.warn("PHG uses underscores internally for contig renaming (format: samplename_contig)")
            logger.warn("Consider renaming your FASTA files to avoid underscores in sample names")
        }

        return keyfilePath
    }

    private fun validateKeyfile(keyfilePath: Path) {
        logger.info("Validating keyfile: $keyfilePath")
        val lines = keyfilePath.readLines()

        if (lines.isEmpty()) {
            logger.error("Keyfile is empty: $keyfilePath")
            exitProcess(1)
        }

        val header = lines[0].split("\t")
        if (header.size != 2 || header[0] != "Fasta" || header[1] != "SampleName") {
            logger.error("Invalid keyfile header. Expected: 'Fasta\\tSampleName'")
            logger.error("Got: ${lines[0]}")
            exitProcess(1)
        }

        val problemNames = mutableListOf<String>()
        for (i in 1 until lines.size) {
            val parts = lines[i].split("\t")
            if (parts.size != 2) {
                logger.error("Invalid keyfile line $i: ${lines[i]}")
                logger.error("Expected 2 tab-separated columns")
                exitProcess(1)
            }

            val fastaPath = Path(parts[0])
            val sampleName = parts[1]

            if (!fastaPath.exists()) {
                logger.error("FASTA file not found: $fastaPath")
                exitProcess(1)
            }

            if (sampleName.contains("_")) {
                problemNames.add(sampleName)
            }
        }

        // Warn about underscores
        if (problemNames.isNotEmpty()) {
            logger.warn("WARNING: The following sample names contain underscores, which may cause issues with PHG:")
            problemNames.forEach { name ->
                logger.warn("  - $name")
            }
            logger.warn("PHG uses underscores internally for contig renaming (format: samplename_contig)")
        }

        logger.info("Keyfile validation passed")
    }

    override fun run() {
        // Validate mutually exclusive options
        if (fastaInput == null && keyfile == null) {
            logger.error("Either --fasta-input or --keyfile must be provided")
            exitProcess(1)
        }
        if (fastaInput != null && keyfile != null) {
            logger.error("--fasta-input and --keyfile are mutually exclusive. Provide only one.")
            exitProcess(1)
        }

        // Validate working directory and PHG binary
        val phgBinary = ValidationUtils.validatePhgSetup(workDir, logger)

        // Configure file logging to working directory
        LoggingUtils.setupFileLogging(workDir, LOG_FILE_NAME, logger)

        logger.info("Starting PHG rope-bwt-chr indexing")
        logger.info("Working directory: $workDir")

        // Create output directory (use custom or default)
        val outputDir = FileUtils.resolveOutputDirectory(workDir, outputDirOption, ROPE_BWT_RESULTS_DIR)
        logger.info("Output directory: $outputDir")
        logger.info("Index file prefix: $indexFilePrefix")
        logger.info("Threads: $threads")
        logger.info("Delete FMR index: $deleteFmrIndex")

        FileUtils.createOutputDirectory(outputDir, logger)

        // Determine keyfile to use
        val actualKeyfile = if (keyfile != null) {
            logger.info("Using provided keyfile: $keyfile")
            validateKeyfile(keyfile!!)
            keyfile!!
        } else {
            val fastaFiles = collectFastaFiles()
            logger.info("Collected ${fastaFiles.size} FASTA file(s)")
            generateKeyfile(fastaFiles, outputDir)
        }

        // Run PHG rope-bwt-chr-index command
        logger.info("Running PHG rope-bwt-chr-index...")
        val exitCode = ProcessRunner.runCommand(
            phgBinary.toString(),
            "rope-bwt-chr-index",
            "--keyfile", actualKeyfile.toString(),
            "--output-dir", outputDir.toString(),
            "--index-file-prefix", indexFilePrefix,
            "--threads", threads.toString(),
            "--delete-fmr-index", deleteFmrIndex.toString(),
            workingDir = workDir.toFile(),
            logger = logger
        )

        if (exitCode == 0) {
            logger.info("PHG rope-bwt-chr indexing completed successfully")
            logger.info("Index files created with prefix: $indexFilePrefix")
            logger.info("Output directory: $outputDir")
        } else {
            logger.error("PHG rope-bwt-chr indexing failed with exit code $exitCode")
            exitProcess(1)
        }
    }
}
