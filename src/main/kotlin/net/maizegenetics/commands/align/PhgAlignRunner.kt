package net.maizegenetics.commands.align

import net.maizegenetics.Constants
import net.maizegenetics.utils.FileUtils
import net.maizegenetics.utils.LoggingUtils
import net.maizegenetics.utils.ProcessRunner
import net.maizegenetics.utils.SeqSimCommandException
import net.maizegenetics.utils.ValidationUtils
import org.apache.logging.log4j.Logger
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.writeLines

/**
 * Consolidated runner for every command that wraps PHGv2 `align-assemblies`.
 *
 * Validates the PHG binary, materializes a PHGv2 `--assembly-file-list` from
 * the caller's input, invokes the PHG CLI, and writes the standard
 * `maf_file_paths.txt` output. Per-caller variation (log filename, output
 * subdirectory, label used in log strings) is provided via [PhgAlignParams]
 * so adding a new align iteration is just a new thin Clikt wrapper -- no
 * runner changes required.
 */
object PhgAlignRunner {

    private const val MAF_PATHS_FILE = "maf_file_paths.txt"
    private const val ASSEMBLY_LIST_FILE = "assemblies_list.txt"

    /**
     * Run PHGv2 align-assemblies for [params] and return the resolved output
     * directory the run wrote to.
     */
    fun run(params: PhgAlignParams, logger: Logger): Path {
        // Validate working directory and PHG binary
        val phgBinary = ValidationUtils.validatePhgSetup(params.workDir, logger)

        // Configure file logging to working directory
        LoggingUtils.setupFileLogging(params.workDir, params.logFileName, logger)

        logger.info("Starting assembly alignment via PHGv2 `align-assemblies`")
        logger.info("Working directory: ${params.workDir}")
        logger.info("Reference GFF: ${params.refGff}")
        logger.info("Reference FASTA: ${params.refFasta}")
        logger.info("Total threads: ${params.threads}")
        params.inParallel?.let { logger.info("In-parallel: $it") }
        params.refMaxAlignCov?.let { logger.info("Ref max align cov (proali -R): $it") }
        params.queryMaxAlignCov?.let { logger.info("Query max align cov (proali -Q): $it") }
        params.condaEnvPrefix?.let { logger.info("Conda env prefix: $it") }
        if (params.justRefPrep) {
            logger.info("Just-ref-prep mode enabled (will not produce per-query MAFs)")
        }

        // Collect input files into a PHGv2-shaped assembly-file-list
        val inputFiles = FileUtils.collectFiles(
            params.queryInput,
            Constants.FASTA_EXTENSIONS,
            "FASTA",
            logger
        )
        logger.info("Processing ${inputFiles.size} ${params.inputKind} file(s)")

        // Create base output directory (use custom or default).
        // PHGv2 requires the output directory to exist before invocation.
        val baseOutputDir = FileUtils.resolveOutputDirectory(
            params.workDir,
            params.customOutputDir,
            params.outputSubdir
        )
        FileUtils.createOutputDirectory(baseOutputDir, logger)

        val assemblyListFile = writeAssemblyFileList(
            inputFiles,
            params.refFasta,
            params.inputKind,
            baseOutputDir,
            logger
        )

        // Build the PHGv2 align-assemblies command
        val commandArgs = mutableListOf(
            phgBinary.toString(),
            "align-assemblies",
            "--gff", params.refGff.toAbsolutePath().toString(),
            "--reference-file", params.refFasta.toAbsolutePath().toString(),
            "--assembly-file-list", assemblyListFile.toAbsolutePath().toString(),
            "--total-threads", params.threads.toString(),
            "-o", baseOutputDir.toAbsolutePath().toString()
        )
        params.inParallel?.let { commandArgs += listOf("--in-parallel", it.toString()) }
        params.refMaxAlignCov?.let { commandArgs += listOf("--ref-max-align-cov", it.toString()) }
        params.queryMaxAlignCov?.let { commandArgs += listOf("--query-max-align-cov", it.toString()) }
        params.condaEnvPrefix?.let {
            commandArgs += listOf("--conda-env-prefix", it.toAbsolutePath().toString())
        }
        if (params.justRefPrep) {
            commandArgs += "--just-ref-prep"
        }

        logger.info("Running PHG align-assemblies...")
        val exitCode = ProcessRunner.runCommand(
            *commandArgs.toTypedArray(),
            workingDir = params.workDir.toFile(),
            logger = logger
        )

        if (exitCode != 0) {
            logger.error("PHG align-assemblies failed with exit code $exitCode")
            throw SeqSimCommandException(
                "PHG align-assemblies failed with exit code $exitCode",
                exitCode
            )
        }

        if (params.justRefPrep) {
            logger.info("--just-ref-prep was set; skipping MAF collection.")
            logger.info("Reference-prep outputs written to: $baseOutputDir")
            return baseOutputDir
        }

        // Collect MAF outputs PHGv2 wrote into the output directory and
        // surface them via the standard maf_file_paths.txt contract so
        // downstream pipeline steps (maf-to-gvcf, create-chain-files, ...)
        // keep working unchanged.
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
        logger.info("PHG align-assemblies completed successfully")
        logger.info("Total assemblies aligned: ${inputFiles.size}")
        logger.info("MAF files written: ${mafFiles.size}")
        logger.info("Output directory: $baseOutputDir")

        return baseOutputDir
    }

    /**
     * Materializes a PHGv2 `--assembly-file-list` from whatever the caller
     * passed (a single FASTA, a directory, or a .txt list). The reference
     * FASTA is filtered out if it accidentally appears in the collected list
     * (PHGv2 warns against including the reference here).
     */
    private fun writeAssemblyFileList(
        inputFiles: List<Path>,
        refFasta: Path,
        inputKind: String,
        baseOutputDir: Path,
        logger: Logger
    ): Path {
        val refAbsolute = refFasta.toAbsolutePath().normalize()
        val filtered = inputFiles
            .map { it.toAbsolutePath().normalize() }
            .filter { it != refAbsolute }
            .distinct()

        if (filtered.size != inputFiles.size) {
            logger.warn(
                "Reference FASTA was present in the $inputKind input list and was removed; " +
                    "PHGv2 expects the reference to be passed only via --reference-file."
            )
        }

        val listFile = baseOutputDir.resolve(ASSEMBLY_LIST_FILE)
        listFile.writeLines(filtered.map { it.toString() })
        logger.info("Wrote PHGv2 assembly file list (${filtered.size} entries): $listFile")
        return listFile
    }
}
