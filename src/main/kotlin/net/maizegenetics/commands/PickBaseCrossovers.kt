package net.maizegenetics.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import net.maizegenetics.Constants
import net.maizegenetics.commands.OrchestrateShared.FASTA_EXTENSION_PATTERN
import net.maizegenetics.utils.FileUtils
import net.maizegenetics.utils.LoggingUtils
import net.maizegenetics.utils.ValidationUtils
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.system.exitProcess

/**
 * v2 pipeline step 06: runs `pick-crossovers` on the "base" assemblies.
 *
 * The "base" samples are designated by [SplitGvcfs] (step 03, `base/`
 * directory). This command resolves those base sample names and maps each to
 * its assembly FASTA counterpart from the original `align-assemblies` query
 * input (matching by base name, e.g. base sample `B73` <-> `B73.fa`). It then
 * writes a `pick-crossovers` assembly list (`path<TAB>name`) and delegates to
 * the shared pick-crossovers helpers in [OrchestrateShared] - the same code
 * path the v1 orchestrator uses, so [PickCrossovers] itself is unchanged.
 *
 * A base sample without a matching assembly FASTA is a hard error.
 *
 * Outputs:
 *  - `{outputDir}/base_assembly_list.txt`
 *  - `{outputDir}/{assemblyName}_refkey.bed` (written by pick-crossovers)
 *  - `logs/06_pick_base_crossovers.log`
 */
class PickBaseCrossovers : CliktCommand(name = "pick-base-crossovers") {
    companion object {
        private const val LOG_FILE_NAME = "06_pick_base_crossovers.log"
        private const val CROSSOVERS_RESULTS_DIR = "06_crossovers_results"
        private const val ASSEMBLY_LIST_FILE = "base_assembly_list.txt"
        private const val DEFAULT_BASE_INPUT_DIR = "03_split_gvcfs_results/base"
    }

    private val logger: Logger = LogManager.getLogger(PickBaseCrossovers::class.java)

    private val workDir by option(
        "--work-dir", "-w",
        help = "Working directory for files and logs"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)
        .default(Path.of(Constants.DEFAULT_WORK_DIR))

    private val refFasta by option(
        "--ref-fasta", "-r",
        help = "Reference FASTA file"
    ).path(mustExist = true, canBeFile = true, canBeDir = false)
        .required()

    private val baseInput by option(
        "--base-input", "-b",
        help = "Base gVCF file, directory, or text list (default: step 03 split-gvcfs base/ output)"
    ).path(mustExist = false)

    private val queryFasta by option(
        "--query-fasta", "-q",
        help = "Original assembly FASTA file, directory, or text list (the align-assemblies query input)"
    ).path(mustExist = true)
        .required()

    private val outputDirOption by option(
        "--output-dir", "-o",
        help = "Custom output directory (default: work_dir/output/06_crossovers_results)"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)

    override fun run() {
        ValidationUtils.validateWorkingDirectory(workDir, logger)
        LoggingUtils.setupFileLogging(workDir, LOG_FILE_NAME, logger)

        logger.info("Starting pick-base-crossovers")
        logger.info("Working directory: $workDir")
        logger.info("Reference FASTA: $refFasta")
        logger.info("Query FASTA input: $queryFasta")

        // Resolve the base sample names from the split-gvcfs base output.
        val actualBaseInput = baseInput ?: FileUtils.autoDetectStepOutput(
            workDir,
            DEFAULT_BASE_INPUT_DIR,
            logger,
            "Please specify --base-input or run 'split-gvcfs' first"
        )
        val baseGvcfs = FileUtils.collectFiles(actualBaseInput, Constants.GVCF_EXTENSIONS, "base GVCF", logger)
        val baseSamples = baseGvcfs
            .map { stripGvcfExtension(it.fileName.toString()) }
            .distinct()
            .sorted()
        logger.info("Resolved ${baseSamples.size} base sample(s): ${baseSamples.joinToString(", ")}")

        // Index the original query assemblies by base name.
        val queryFastas = FileUtils.collectFiles(queryFasta, Constants.FASTA_EXTENSIONS, "query FASTA", logger)
        val fastaBySample = mutableMapOf<String, Path>()
        queryFastas.forEach { fasta ->
            val name = fasta.fileName.toString().replace(FASTA_EXTENSION_PATTERN, "")
            val existing = fastaBySample.put(name, fasta)
            if (existing != null) {
                logger.warn("Multiple FASTAs resolve to assembly '$name'; using ${fasta.fileName}")
            }
        }

        // Match each base sample to its assembly FASTA. A base sample without
        // a matching assembly is a hard error.
        val matchedFastas = mutableListOf<Path>()
        val unmatched = mutableListOf<String>()
        baseSamples.forEach { sample ->
            val fasta = fastaBySample[sample]
            if (fasta == null) {
                unmatched.add(sample)
            } else {
                matchedFastas.add(fasta)
                logger.info("Matched base sample '$sample' -> ${fasta.fileName}")
            }
        }
        if (unmatched.isNotEmpty()) {
            logger.error(
                "No matching assembly FASTA found for base sample(s): ${unmatched.joinToString(", ")}"
            )
            logger.error(
                "Each base sample must have an assembly FASTA (matched by base name) in the --query-fasta input"
            )
            exitProcess(1)
        }

        // Create the output directory and build the assembly list, then reuse
        // the shared pick-crossovers helpers (same path as the v1 orchestrator).
        val outputDir = FileUtils.resolveOutputDirectory(workDir, outputDirOption, CROSSOVERS_RESULTS_DIR)
        FileUtils.createOutputDirectory(outputDir, logger)

        val assemblyList = OrchestrateShared.writeAssemblyList(
            matchedFastas,
            outputDir,
            ASSEMBLY_LIST_FILE,
            logger
        )
        OrchestrateShared.validateEvenAssemblyCount(assemblyList, logger)
        OrchestrateShared.runPickCrossovers(
            workDir = workDir,
            refFasta = refFasta.toAbsolutePath(),
            assemblyList = assemblyList,
            outputDir = outputDir,
            logger = logger,
        )

        // runPickCrossovers restores orchestrator logging; restore this
        // command's log so the final messages land in the right file.
        LoggingUtils.setupFileLogging(workDir, LOG_FILE_NAME, logger)

        logger.info("pick-base-crossovers completed successfully")
        logger.info("Output directory: $outputDir")
    }

    /**
     * Strips any recognized gVCF extension (longest first so "g.vcf.gz" wins
     * over "gz"/"vcf") to recover the sample name from a gVCF file name.
     */
    private fun stripGvcfExtension(fileName: String): String {
        val sorted = Constants.GVCF_EXTENSIONS.sortedByDescending { it.length }
        for (ext in sorted) {
            val suffix = ".$ext"
            if (fileName.endsWith(suffix)) {
                return fileName.removeSuffix(suffix)
            }
        }
        return fileName.substringBeforeLast(".")
    }
}
