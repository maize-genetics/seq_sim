package net.maizegenetics.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import net.maizegenetics.Constants
import net.maizegenetics.utils.FileUtils
import net.maizegenetics.utils.LoggingUtils
import net.maizegenetics.utils.ProcessRunner
import net.maizegenetics.utils.SeqSimCommandException
import net.maizegenetics.utils.ValidationUtils
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.nio.file.Path
import kotlin.io.path.*

/**
 * Wraps the PHGv2 `align-assemblies` command for the "circular" mutated /
 * recombined FASTA realignment step (step 10). PHGv2 internally drives
 * AnchorWave + minimap2; this wrapper keeps seq_sim's existing inputs
 * (`--ref-gff`, `--ref-fasta`, `--fasta-input`, ...) and existing output
 * contract (`output/10_mutated_alignment_results/maf_file_paths.txt`) so
 * downstream pipeline steps continue to work unchanged. New PHGv2-specific
 * options (`--in-parallel`, `--ref-max-align-cov`, ...) are surfaced as
 * additional optional flags.
 *
 * See: https://phg.maizegenetics.net/build_and_load/#align-assemblies-parameters
 */
class AlignMutatedAssemblies : CliktCommand(name = "align-mutated-assemblies") {
    companion object {
        private const val LOG_FILE_NAME = "10_align_mutated_assemblies.log"
        private const val MUTATED_ALIGNMENT_RESULTS_DIR = "10_mutated_alignment_results"
        private const val MAF_PATHS_FILE = "maf_file_paths.txt"
        private const val ASSEMBLY_LIST_FILE = "assemblies_list.txt"

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
        help = "Reference GFF file (passed to PHGv2 as --gff)"
    ).path(mustExist = true, canBeFile = true, canBeDir = false)
        .required()

    private val refFasta by option(
        "--ref-fasta", "-r",
        help = "Reference FASTA file (passed to PHGv2 as --reference-file). For best results " +
            "this should be the output of `phg prepare-assemblies`."
    ).path(mustExist = true, canBeFile = true, canBeDir = false)
        .required()

    private val fastaInput by option(
        "--fasta-input", "-f",
        help = "FASTA file, directory of FASTA files, or text file with paths to FASTA files (one per line). " +
            "Translated to a PHGv2 --assembly-file-list internally."
    ).path(mustExist = true)
        .required()

    private val threads by option(
        "--threads", "-t",
        help = "Total number of threads available to PHGv2 (--total-threads)"
    ).int()
        .default(DEFAULT_THREADS)

    private val inParallel by option(
        "--in-parallel",
        help = "Number of alignments to run in parallel (PHGv2 --in-parallel). " +
            "If omitted, PHGv2 picks a value from system memory + thread count."
    ).int()

    private val refMaxAlignCov by option(
        "--ref-max-align-cov",
        help = "Maximum reference genome alignment coverage for AnchorWave proali (PHGv2 --ref-max-align-cov, " +
            "passed through as proali's `-R`). PHGv2 defaults this to 1."
    ).int()

    private val queryMaxAlignCov by option(
        "--query-max-align-cov",
        help = "Maximum query genome alignment coverage for AnchorWave proali (PHGv2 --query-max-align-cov, " +
            "passed through as proali's `-Q`). PHGv2 defaults this to 1."
    ).int()

    private val condaEnvPrefix by option(
        "--conda-env-prefix",
        help = "Path to a Conda environment that contains PHGv2's runtime dependencies " +
            "(anchorwave, minimap2, samtools, ...). Defaults to the `phgv2-conda` env in its standard location."
    ).path(mustExist = false, canBeFile = false, canBeDir = true)

    private val justRefPrep by option(
        "--just-ref-prep",
        help = "Only run PHGv2's reference-prep phase (writes ref.cds.fasta + Ref.sam) and stop. " +
            "Useful when feeding a SLURM array; skips writing maf_file_paths.txt because no MAFs are produced."
    ).flag()

    private val outputDir by option(
        "--output-dir", "-o",
        help = "Custom output directory (default: work_dir/output/10_mutated_alignment_results)"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)

    private fun collectFastaFiles(): List<Path> {
        return FileUtils.collectFiles(
            fastaInput,
            Constants.FASTA_EXTENSIONS,
            "FASTA",
            logger
        )
    }

    /**
     * Materializes a PHGv2 `--assembly-file-list` from whatever the user passed
     * via `--fasta-input` (a single FASTA, a directory, or a .txt list). The
     * reference FASTA is filtered out if it accidentally appears in the list
     * (PHGv2 warns against including the reference here).
     */
    private fun writeAssemblyFileList(fastaFiles: List<Path>, baseOutputDir: Path): Path {
        val refAbsolute = refFasta.toAbsolutePath().normalize()
        val filtered = fastaFiles
            .map { it.toAbsolutePath().normalize() }
            .filter { it != refAbsolute }
            .distinct()

        if (filtered.size != fastaFiles.size) {
            logger.warn(
                "Reference FASTA was present in the FASTA input list and was removed; " +
                    "PHGv2 expects the reference to be passed only via --reference-file."
            )
        }

        val listFile = baseOutputDir.resolve(ASSEMBLY_LIST_FILE)
        listFile.writeLines(filtered.map { it.toString() })
        logger.info("Wrote PHGv2 assembly file list (${filtered.size} entries): $listFile")
        return listFile
    }

    override fun run() {
        // Validate working directory and PHG binary
        val phgBinary = ValidationUtils.validatePhgSetup(workDir, logger)

        // Configure file logging to working directory
        LoggingUtils.setupFileLogging(workDir, LOG_FILE_NAME, logger)

        logger.info("Starting mutated assembly alignment via PHGv2 `align-assemblies`")
        logger.info("Working directory: $workDir")
        logger.info("Reference GFF: $refGff")
        logger.info("Reference FASTA: $refFasta")
        logger.info("Total threads: $threads")
        inParallel?.let { logger.info("In-parallel: $it") }
        refMaxAlignCov?.let { logger.info("Ref max align cov (proali -R): $it") }
        queryMaxAlignCov?.let { logger.info("Query max align cov (proali -Q): $it") }
        condaEnvPrefix?.let { logger.info("Conda env prefix: $it") }
        if (justRefPrep) {
            logger.info("Just-ref-prep mode enabled (will not produce per-query MAFs)")
        }

        // Collect FASTA files into a PHGv2-shaped assembly-file-list
        val fastaFiles = collectFastaFiles()
        logger.info("Processing ${fastaFiles.size} FASTA file(s)")

        // Create base output directory (use custom or default).
        // PHGv2 requires the output directory to exist before invocation.
        val baseOutputDir = FileUtils.resolveOutputDirectory(workDir, outputDir, MUTATED_ALIGNMENT_RESULTS_DIR)
        FileUtils.createOutputDirectory(baseOutputDir, logger)

        val assemblyListFile = writeAssemblyFileList(fastaFiles, baseOutputDir)

        // Build the PHGv2 align-assemblies command
        val commandArgs = mutableListOf(
            phgBinary.toString(),
            "align-assemblies",
            "--gff", refGff.toAbsolutePath().toString(),
            "--reference-file", refFasta.toAbsolutePath().toString(),
            "--assembly-file-list", assemblyListFile.toAbsolutePath().toString(),
            "--total-threads", threads.toString(),
            "-o", baseOutputDir.toAbsolutePath().toString()
        )
        inParallel?.let { commandArgs += listOf("--in-parallel", it.toString()) }
        refMaxAlignCov?.let { commandArgs += listOf("--ref-max-align-cov", it.toString()) }
        queryMaxAlignCov?.let { commandArgs += listOf("--query-max-align-cov", it.toString()) }
        condaEnvPrefix?.let { commandArgs += listOf("--conda-env-prefix", it.toAbsolutePath().toString()) }
        if (justRefPrep) {
            commandArgs += "--just-ref-prep"
        }

        logger.info("Running PHG align-assemblies (mutated)...")
        val exitCode = ProcessRunner.runCommand(
            *commandArgs.toTypedArray(),
            workingDir = workDir.toFile(),
            logger = logger
        )

        if (exitCode != 0) {
            logger.error("PHG align-assemblies (mutated) failed with exit code $exitCode")
            throw SeqSimCommandException(
                "PHG align-assemblies (mutated) failed with exit code $exitCode",
                exitCode
            )
        }

        if (justRefPrep) {
            logger.info("--just-ref-prep was set; skipping MAF collection.")
            logger.info("Reference-prep outputs written to: $baseOutputDir")
            return
        }

        // Collect MAF outputs PHGv2 wrote into the output directory and
        // surface them via the standard maf_file_paths.txt contract so
        // downstream pipeline steps (mutated_maf_to_gvcf, ...) keep working
        // unchanged.
        val mafFiles = baseOutputDir.listDirectoryEntries()
            .filter { it.isRegularFile() && it.name.endsWith(".maf") }
            .sorted()

        FileUtils.writeFilePaths(
            mafFiles,
            baseOutputDir.resolve(MAF_PATHS_FILE),
            logger,
            "MAF file"
        )

        logger.info("=".repeat(80))
        logger.info("PHG align-assemblies (mutated) completed successfully")
        logger.info("Total assemblies aligned: ${fastaFiles.size}")
        logger.info("MAF files written: ${mafFiles.size}")
        logger.info("Output directory: $baseOutputDir")
    }
}
