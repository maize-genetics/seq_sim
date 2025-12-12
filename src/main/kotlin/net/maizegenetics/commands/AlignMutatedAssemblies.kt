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

class AlignMutatedAssemblies : CliktCommand(name = "align-mutated-assemblies") {
    companion object {
        private const val LOG_FILE_NAME = "05_align_mutated_assemblies.log"
        private const val MUTATED_ALIGNMENT_RESULTS_DIR = "05_mutated_alignment_results"
        private const val MAF_PATHS_FILE = "maf_file_paths.txt"

        // minimap2 parameters
        private const val MINIMAP2_PRESET = "splice"
        private const val MINIMAP2_KMER_SIZE = "12"
        private const val MINIMAP2_P_VALUE = "0.4"
        private const val MINIMAP2_N_VALUE = "20"

        // anchorwave proali parameters
        private const val ANCHORWAVE_R_VALUE = "1"
        private const val ANCHORWAVE_Q_VALUE = "1"

        // Default values
        private const val DEFAULT_THREADS = 1
    }

    private val logger: Logger = LogManager.getLogger(AlignMutatedAssemblies::class.java)

    private val workDir by option(
        "--work-dir", "-w",
        help = "Working directory for files and scripts"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)
        .default(Path.of(Constants.DEFAULT_WORK_DIR))

    private val refGff by option(
        "--ref-gff", "-g",
        help = "Reference GFF file"
    ).path(mustExist = true, canBeFile = true, canBeDir = false)
        .required()

    private val refFasta by option(
        "--ref-fasta", "-r",
        help = "Reference FASTA file"
    ).path(mustExist = true, canBeFile = true, canBeDir = false)
        .required()

    private val fastaInput by option(
        "--fasta-input", "-f",
        help = "FASTA file, directory of FASTA files, or text file with paths to FASTA files (one per line)"
    ).path(mustExist = true)
        .required()

    private val threads by option(
        "--threads", "-t",
        help = "Number of threads to use"
    ).int()
        .default(DEFAULT_THREADS)

    private val outputDir by option(
        "--output-dir", "-o",
        help = "Custom output directory (default: work_dir/output/05_mutated_alignment_results)"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)

    private fun collectFastaFiles(): List<Path> {
        return FileUtils.collectFiles(
            fastaInput,
            Constants.FASTA_EXTENSIONS,
            "FASTA",
            logger
        )
    }

    override fun run() {
        // Validate working directory exists
        ValidationUtils.validateWorkingDirectory(workDir, logger)

        // Configure file logging to working directory
        LoggingUtils.setupFileLogging(workDir, LOG_FILE_NAME, logger)

        logger.info("Starting mutated assembly alignment")
        logger.info("Working directory: $workDir")
        logger.info("Reference GFF: $refGff")
        logger.info("Reference FASTA: $refFasta")
        logger.info("Threads: $threads")

        // Collect FASTA files
        val fastaFiles = collectFastaFiles()
        logger.info("Processing ${fastaFiles.size} FASTA file(s)")

        // Create base output directory (use custom or default)
        val baseOutputDir = FileUtils.resolveOutputDirectory(workDir, outputDir, MUTATED_ALIGNMENT_RESULTS_DIR)
        FileUtils.createOutputDirectory(baseOutputDir, logger)

        // Derive reference base name
        val refBase = refFasta.nameWithoutExtension
        logger.info("Reference base name: $refBase")

        // Step 1: Run anchorwave gff2seq (once for reference)
        logger.info("Step 1: Extracting CDS sequences with anchorwave gff2seq")
        val cdsFile = baseOutputDir.resolve("${refBase}_cds.fa")
        val gff2seqExitCode = ProcessRunner.runCommand(
            "pixi", "run", "anchorwave", "gff2seq",
            "-i", refGff.toString(),
            "-r", refFasta.toString(),
            "-o", cdsFile.toString(),
            workingDir = workDir.toFile(),
            logger = logger
        )
        if (gff2seqExitCode != 0) {
            logger.error("anchorwave gff2seq failed with exit code $gff2seqExitCode")
            exitProcess(gff2seqExitCode)
        }
        logger.info("CDS file created: $cdsFile")

        // Step 2: Run minimap2 for reference (once for all queries)
        logger.info("Step 2: Running minimap2 alignment for reference")
        val refSam = baseOutputDir.resolve("${refBase}.sam")
        val minimap2RefExitCode = ProcessRunner.runCommand(
            "pixi", "run", "minimap2",
            "-x", MINIMAP2_PRESET,
            "-t", threads.toString(),
            "-k", MINIMAP2_KMER_SIZE,
            "-a",
            "-p", MINIMAP2_P_VALUE,
            "-N", MINIMAP2_N_VALUE,
            refFasta.toString(),
            cdsFile.toString(),
            workingDir = workDir.toFile(),
            outputFile = refSam.toFile(),
            logger = logger
        )
        if (minimap2RefExitCode != 0) {
            logger.error("minimap2 (reference) failed with exit code $minimap2RefExitCode")
            exitProcess(minimap2RefExitCode)
        }
        logger.info("Reference SAM file created: $refSam")

        // Step 3: Process each FASTA file
        var successCount = 0
        var failureCount = 0
        val mafFilePaths = mutableListOf<Path>()

        fastaFiles.forEachIndexed { index, fastaFile ->
            logger.info("=".repeat(80))
            logger.info("Processing FASTA ${index + 1}/${fastaFiles.size}: ${fastaFile.name}")
            logger.info("=".repeat(80))

            try {
                val mafPath = alignFasta(fastaFile, refBase, refSam, cdsFile, baseOutputDir)
                mafFilePaths.add(mafPath)
                successCount++
                logger.info("Successfully completed alignment for: ${fastaFile.name}")
            } catch (e: Exception) {
                failureCount++
                logger.error("Failed to align FASTA: ${fastaFile.name}", e)
                logger.error("Continuing with next FASTA...")
            }
        }

        // Write MAF file paths to text file
        FileUtils.writeFilePaths(
            mafFilePaths,
            baseOutputDir.resolve(MAF_PATHS_FILE),
            logger,
            "MAF file"
        )

        logger.info("=".repeat(80))
        logger.info("All alignments completed!")
        logger.info("Total FASTA files processed: ${fastaFiles.size}")
        logger.info("Successful: $successCount")
        logger.info("Failed: $failureCount")
        logger.info("Output directory: $baseOutputDir")
    }

    private fun alignFasta(fastaFile: Path, refBase: String, refSam: Path, cdsFile: Path, baseOutputDir: Path): Path {
        val fastaName = fastaFile.nameWithoutExtension

        // Create FASTA-specific output directory
        val fastaOutputDir = baseOutputDir.resolve(fastaName)
        if (!fastaOutputDir.exists()) {
            fastaOutputDir.createDirectories()
        }

        // Step 1: Run minimap2 for FASTA
        logger.info("Running minimap2 alignment for FASTA")
        val fastaSam = fastaOutputDir.resolve("${fastaName}.sam")
        val minimap2FastaExitCode = ProcessRunner.runCommand(
            "pixi", "run", "minimap2",
            "-x", MINIMAP2_PRESET,
            "-t", threads.toString(),
            "-k", MINIMAP2_KMER_SIZE,
            "-a",
            "-p", MINIMAP2_P_VALUE,
            "-N", MINIMAP2_N_VALUE,
            fastaFile.toString(),
            cdsFile.toString(),
            workingDir = workDir.toFile(),
            outputFile = fastaSam.toFile(),
            logger = logger
        )
        if (minimap2FastaExitCode != 0) {
            throw RuntimeException("minimap2 (FASTA) failed with exit code $minimap2FastaExitCode")
        }
        logger.info("FASTA SAM file created: $fastaSam")

        // Step 2: Run anchorwave proali
        logger.info("Running anchorwave proali")
        val anchorsFile = fastaOutputDir.resolve("${refBase}_R${ANCHORWAVE_R_VALUE}_${fastaName}_Q${ANCHORWAVE_Q_VALUE}.anchors")
        val mafFile = fastaOutputDir.resolve("${refBase}_R${ANCHORWAVE_R_VALUE}_${fastaName}_Q${ANCHORWAVE_Q_VALUE}.maf")
        val fMafFile = fastaOutputDir.resolve("${refBase}_R${ANCHORWAVE_R_VALUE}_${fastaName}_Q${ANCHORWAVE_Q_VALUE}.f.maf")

        val proaliExitCode = ProcessRunner.runCommand(
            "pixi", "run", "anchorwave", "proali",
            "-i", refGff.toString(),
            "-as", cdsFile.toString(),
            "-r", refFasta.toString(),
            "-a", fastaSam.toString(),
            "-ar", refSam.toString(),
            "-s", fastaFile.toString(),
            "-n", anchorsFile.toString(),
            "-R", ANCHORWAVE_R_VALUE,
            "-Q", ANCHORWAVE_Q_VALUE,
            "-o", mafFile.toString(),
            "-f", fMafFile.toString(),
            "-t", threads.toString(),
            workingDir = workDir.toFile(),
            logger = logger
        )
        if (proaliExitCode != 0) {
            throw RuntimeException("anchorwave proali failed with exit code $proaliExitCode")
        }

        logger.info("Output files for ${fastaName}:")
        logger.info("  FASTA SAM: $fastaSam")
        logger.info("  Anchors: $anchorsFile")
        logger.info("  MAF: $mafFile")
        logger.info("  Filtered MAF: $fMafFile")

        // Return the MAF file path (not the filtered one)
        return mafFile
    }
}
