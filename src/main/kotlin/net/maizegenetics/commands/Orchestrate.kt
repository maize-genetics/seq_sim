package net.maizegenetics.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import net.maizegenetics.Constants
import net.maizegenetics.utils.LoggingUtils
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.yaml.snakeyaml.Yaml
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.system.exitProcess

/**
 * Data classes for YAML configuration structure
 */
data class PipelineConfig(
    val version: String? = null,
    val work_dir: String? = null,
    val run_steps: List<String>? = null,
    val align_assemblies: AlignAssembliesConfig? = null,
    val maf_to_gvcf: MafToGvcfConfig? = null,
    val split_gvcfs: SplitGvcfsConfig? = null,
    val mutate_assemblies: MutateAssembliesConfig? = null,
    val recombine_gvcfs: RecombineGvcfsConfig? = null,
    val sort_gvcfs: SortGvcfsConfig? = null,
    val downsample_gvcf: DownsampleGvcfConfig? = null,
    val convert_to_fasta: ConvertToFastaConfig? = null,
    val align_mutated_assemblies: AlignMutatedAssembliesConfig? = null,
    val pick_crossovers: PickCrossoversConfig? = null,
    val create_chain_files: CreateChainFilesConfig? = null,
    val convert_coordinates: ConvertCoordinatesConfig? = null,
    val generate_recombined_sequences: GenerateRecombinedSequencesConfig? = null,
    val format_recombined_fastas: FormatRecombinedFastasConfig? = null,
    val mutated_maf_to_gvcf: MutatedMafToGvcfConfig? = null,
    val rope_bwt_chr_index: RopeBwtChrIndexConfig? = null,
    val ropebwt_mem: RopebwtMemConfig? = null,
    val build_spline_knots: BuildSplineKnotsConfig? = null,
    val convert_ropebwt2ps4g: ConvertRopebwt2Ps4gConfig? = null,
    val ropebwt: RopebwtConfig? = null
)

data class AlignAssembliesConfig(
    val ref_gff: String,
    val ref_fasta: String,
    val query_fasta: String,
    val threads: Int? = null,                  // PHGv2 --total-threads
    val in_parallel: Int? = null,              // PHGv2 --in-parallel
    val ref_max_align_cov: Int? = null,        // PHGv2 --ref-max-align-cov (proali -R)
    val query_max_align_cov: Int? = null,      // PHGv2 --query-max-align-cov (proali -Q)
    val conda_env_prefix: String? = null,      // PHGv2 --conda-env-prefix
    val just_ref_prep: Boolean? = null,        // PHGv2 --just-ref-prep
    val output: String? = null                 // Custom output directory
)

data class MafToGvcfConfig(
    val reference_file: String? = null,  // Optional: Reference FASTA (uses align_assemblies.ref_fasta if not specified)
    val maf_file: String? = null,        // Optional: MAF file/directory/list (uses step 1 output if not specified)
    val output_file: String? = null,     // Optional: Output GVCF file name
    val sample_name: String? = null,     // Optional: Sample name for GVCF
    val output_dir: String? = null       // Optional: Custom GVCF output directory
)

data class SplitGvcfsConfig(
    val keyfile: String,              // Required: tab-delimited keyfile (Base, MutationDonor)
    val input: String? = null,        // Optional: GVCF input dir/list (defaults to maf_to_gvcf output)
    val output: String? = null        // Optional: custom output directory
)

data class MutateAssembliesConfig(
    val keyfile: String? = null,              // Optional: pairs file (defaults to split_gvcfs pairs.tsv)
    val base_input: String? = null,           // Optional: base gVCF dir (defaults to split_gvcfs base/)
    val mutation_donor_input: String? = null, // Optional: downsampled donor gVCF dir (defaults to downsample output)
    val output: String? = null                // Optional: custom output directory
)

data class RecombineGvcfsConfig(
    val ref_file: String? = null,    // Optional: Reference FASTA (uses align_assemblies.ref_fasta if omitted)
    val input_bed: String? = null,   // Optional: crossover BED dir (defaults to pick_crossovers output)
    val input_gvcf: String? = null,  // Optional: mutated base gVCF dir (defaults to mutate_assemblies output)
    val output: String? = null,      // Optional: custom output directory for recombined gVCFs
)

data class SortGvcfsConfig(
    val input: String? = null,    // Optional: recombined gVCF dir/list (defaults to recombine_gvcfs output)
    val threads: Int? = null,     // Optional: number of threads for bcftools
    val output: String? = null    // Optional: custom output directory for sorted gVCFs
)

data class DownsampleGvcfConfig(
    val ignore_contig: String? = null,
    val rates: String? = null,
    val seed: Int? = null,
    val keep_ref: Boolean? = null,
    val min_ref_block_size: Int? = null,
    val input: String? = null,   // Custom GVCF input directory
    val output: String? = null   // Custom output directory
)

data class ConvertToFastaConfig(
    val reference_file: String? = null,  // Optional: Reference FASTA (uses align_assemblies.ref_fasta if not specified)
    val missing_records_as: String? = null,
    val missing_genotype_as: String? = null,
    val ignore_contig: String? = null,  // Comma-separated list of string patterns to ignore
    val input: String? = null,   // Custom GVCF input file/directory
    val output: String? = null   // Custom FASTA output directory
)

data class AlignMutatedAssembliesConfig(
    val ref_gff: String? = null,             // Optional: Reference GFF (uses align_assemblies.ref_gff if not specified)
    val ref_fasta: String? = null,           // Optional: Reference FASTA (uses align_assemblies.ref_fasta if not specified)
    val fasta_input: String? = null,         // Optional: Query FASTA input (uses format_recombined_fastas output if not specified)
    val threads: Int? = null,                // PHGv2 --total-threads
    val in_parallel: Int? = null,            // PHGv2 --in-parallel
    val ref_max_align_cov: Int? = null,      // PHGv2 --ref-max-align-cov (proali -R)
    val query_max_align_cov: Int? = null,    // PHGv2 --query-max-align-cov (proali -Q)
    val conda_env_prefix: String? = null,    // PHGv2 --conda-env-prefix
    val just_ref_prep: Boolean? = null,      // PHGv2 --just-ref-prep
    val output: String? = null               // Custom output directory
)

data class PickCrossoversConfig(
    val assembly_list: String? = null,  // Optional: If not specified, auto-generates from convert_to_fasta output
    val ref_fasta: String? = null,  // Optional: Reference FASTA (uses align_assemblies.ref_fasta if not specified)
    val base_input: String? = null, // v2 only: base gVCF dir/list (defaults to split_gvcfs base/ output)
    val query_fasta: String? = null, // v2 only: original assembly FASTAs (defaults to align_assemblies.query_fasta)
    val output: String? = null      // Custom output directory
)

data class CreateChainFilesConfig(
    val jobs: Int? = null,
    val maf_file_input: String? = null,   // Custom MAF input file/directory (default: step 5 MAF outputs)
    val output: String? = null            // Custom output directory
)

data class ConvertCoordinatesConfig(
    val assembly_list: String? = null,  // Optional: defaults to assembly list from pick_crossovers step
    val input_chain: String? = null,    // Custom chain directory
    val input_refkey: String? = null,   // Custom refkey directory
    val output: String? = null          // Custom output directory
)

data class GenerateRecombinedSequencesConfig(
    val assembly_list: String? = null,    // Optional: defaults to assembly list from pick_crossovers step
    val chromosome_list: String? = null,  // Optional: auto-derives from first assembly in assembly list
    val assembly_dir: String? = null      // Optional: defaults to step 4 FASTA output directory
)

data class FormatRecombinedFastasConfig(
    val line_width: Int? = null,
    val threads: Int? = null,
    val input: String? = null,   // Custom FASTA input file/directory
    val output: String? = null   // Custom output directory
)

data class MutatedMafToGvcfConfig(
    val reference_file: String? = null,  // Optional: Reference FASTA (uses align_assemblies.ref_fasta if not specified)
    val maf_file: String? = null,        // Optional: MAF file/directory/list (uses align_mutated_assemblies output if not specified)
    val output_file: String? = null,     // Optional: Output GVCF file name
    val sample_name: String? = null,     // Optional: Sample name for GVCF
    val output_dir: String? = null       // Optional: Custom GVCF output directory
)

data class RopeBwtChrIndexConfig(
    val keyfile: String? = null,           // Optional: Pre-made keyfile (if not specified, auto-generates from format_recombined_fastas output)
    val index_file_prefix: String? = null, // Optional: Prefix for index files (default: "phgIndex")
    val threads: Int? = null,              // Optional: Number of threads (default: 20)
    val delete_fmr_index: Boolean? = null, // Optional: Delete .fmr files after conversion (default: true)
    val output: String? = null             // Optional: Custom output directory
)

data class RopebwtMemConfig(
    val fastq_input: String,            // Required: FASTQ file, directory, or text list (no upstream auto-gen)
    val index_file: String? = null,     // Optional: .fmd index (defaults to step 12 output)
    val l_value: Int? = null,           // Optional: -l (defaults to 2 x FASTA count from step 12 keyfile)
    val p_value: Int? = null,           // Optional: -p (default: 168)
    val threads: Int? = null,           // Optional: number of threads (default: 1)
    val output: String? = null          // Optional: Custom output directory
)

data class BuildSplineKnotsConfig(
    val vcf_dir: String? = null,        // Optional: VCF directory (defaults to step 11 mutated GVCFs)
    val vcf_type: String? = null,       // Optional: "hvcf" or "gvcf" (default: "hvcf")
    val min_indel_length: Int? = null,  // Optional: gVCF only
    val num_bps_per_knot: Int? = null,  // Optional: knot density
    val contig_list: String? = null,    // Optional: comma-separated chromosomes
    val random_seed: Int? = null,       // Optional: deterministic downsampling seed
    val disable_asm_coordinates: Boolean? = null, // Optional: use a per-chromosome running count instead of ASM_Start/ASM_End
    val output: String? = null          // Optional: Custom output directory
)

data class ConvertRopebwt2Ps4gConfig(
    val bed_input: String? = null,        // Optional: BED file/dir/list (defaults to step 13)
    val spline_knot_dir: String? = null,  // Optional: spline knot dir (defaults to step 14)
    val min_mem_length: Int? = null,      // Optional: minimum MEM length threshold
    val max_num_hits: Int? = null,        // Optional: maximum haplotype hits per alignment
    val output: String? = null            // Optional: Custom output directory
)

data class RopebwtConfig(
    val fastq_input: String,                // Required: user FASTQ file, directory, or text list
    val fasta_input: String? = null,        // Optional: recombined FASTAs to index (defaults to convert_to_fasta output)
    val index_file_prefix: String? = null,  // Optional: Prefix for index files (default: "phgIndex")
    val threads: Int? = null,               // Optional: threads for index creation and mem alignment
    val delete_fmr_index: Boolean? = null,  // Optional: Delete .fmr files after conversion
    val l_value: Int? = null,               // Optional: -l (defaults to 2 x FASTA count from generated keyfile)
    val p_value: Int? = null,               // Optional: -p (default: 168)
    val output: String? = null              // Optional: Custom output directory
)

/**
 * Helpers and constants shared by both pipeline versions ([OrchestrateV1]
 * and [OrchestrateV2]). Kept separate from the [Orchestrate] command so
 * each pipeline file depends only on this small, version-agnostic surface.
 */
object OrchestrateShared {
    const val LOG_FILE_NAME = "00_orchestrate.log"

    // Width of the "=" borders used for orchestrate banner logging
    const val BANNER_WIDTH = 80

    /**
     * Logs a banner: a "=" border line, one line per [lines] message, then a
     * closing "=" border. Logs at error level when [error] is true, otherwise
     * info level. Replaces the repeated three-line banner idiom in the
     * orchestrators.
     */
    fun logBanner(logger: Logger, vararg lines: String, error: Boolean = false) {
        val border = "=".repeat(BANNER_WIDTH)
        val log: (String) -> Unit = if (error) logger::error else logger::info
        log(border)
        lines.forEach(log)
        log(border)
    }

    // Regex patterns reused across multiple operations
    val FASTA_FILE_PATTERN = Regex(".*\\.(fa|fasta|fna)(\\.gz)?$")
    val FASTA_EXTENSION_PATTERN = Regex("\\.(fa|fasta|fna)(\\.gz)?$")

    fun shouldRunStep(stepName: String, config: PipelineConfig): Boolean {
        // If run_steps is not specified, run all configured steps
        if (config.run_steps == null) {
            return true
        }
        // If run_steps is specified, only run steps in the list
        return stepName in config.run_steps
    }

    /**
     * Appends the optional PHGv2 align-assemblies knobs shared by every
     * align step (threads, in-parallel, proali coverage caps, conda env
     * prefix, just-ref-prep, output dir override) to [args]. Each option
     * is included only when its config field is non-null/true, matching
     * the existing inline behaviour for both align_assemblies and
     * align_mutated_assemblies.
     */
    fun appendPhgAlignSharedArgs(
        args: MutableList<String>,
        threads: Int?,
        inParallel: Int?,
        refMaxAlignCov: Int?,
        queryMaxAlignCov: Int?,
        condaEnvPrefix: String?,
        justRefPrep: Boolean?,
        customOutput: Path?,
    ) {
        if (threads != null) {
            args.add("--threads=$threads")
        }
        if (inParallel != null) {
            args.add("--in-parallel=$inParallel")
        }
        if (refMaxAlignCov != null) {
            args.add("--ref-max-align-cov=$refMaxAlignCov")
        }
        if (queryMaxAlignCov != null) {
            args.add("--query-max-align-cov=$queryMaxAlignCov")
        }
        if (condaEnvPrefix != null) {
            args.add("--conda-env-prefix=$condaEnvPrefix")
        }
        if (justRefPrep == true) {
            args.add("--just-ref-prep")
        }
        if (customOutput != null) {
            args.add("--output-dir=$customOutput")
        }
    }

    /**
     * Restores the orchestrator's log file after a step command has run.
     * Each step command sets up its own log file, so we need to restore
     * the orchestrator's log file to ensure orchestrator messages go to
     * the correct log file.
     */
    fun restoreOrchestratorLogging(workDir: Path, logger: Logger) {
        LoggingUtils.setupFileLogging(workDir, LOG_FILE_NAME, logger)
    }

    /**
     * Writes a `pick-crossovers` assembly list (`absPath<TAB>name`, one per
     * line) for [fastaFiles] into [destDir]/[fileName]. The assembly name is
     * the file name with its FASTA extension stripped (a `_mutated` suffix is
     * intentionally preserved). Returns the written list file.
     *
     * Shared by [OrchestrateV1] (auto-generating from convert-to-fasta output)
     * and [PickBaseCrossovers] (base-sample-filtered assemblies).
     */
    fun writeAssemblyList(
        fastaFiles: List<Path>,
        destDir: Path,
        fileName: String = "auto_assembly_list.txt",
        logger: Logger,
    ): Path {
        val assemblyListFile = destDir.resolve(fileName)
        val lines = fastaFiles.map { fastaPath ->
            val name = fastaPath.fileName.toString().replace(FASTA_EXTENSION_PATTERN, "")
            "${fastaPath.toAbsolutePath()}\t$name"
        }
        assemblyListFile.writeText(lines.joinToString("\n"))
        logger.info("Generated assembly list file: $assemblyListFile")
        logger.info("  Contains ${fastaFiles.size} assemblies")
        return assemblyListFile
    }

    /**
     * Validates that [listFile] contains an even number of assemblies, since
     * `pick-crossovers` pairs assemblies for crossover simulation. Throws a
     * [RuntimeException] when the count is odd.
     */
    fun validateEvenAssemblyCount(listFile: Path, logger: Logger) {
        val assemblyCount = listFile.readLines().filter { it.isNotBlank() }.size
        if (assemblyCount % 2 != 0) {
            throw RuntimeException(
                "Cannot run pick-crossovers: assembly list contains $assemblyCount assemblies, " +
                    "but this step requires an even number of assembly files to work (assemblies are paired for crossover simulation)"
            )
        }
        logger.info("Assembly list contains $assemblyCount assemblies (validated: even count)")
    }

    /**
     * Invokes the [PickCrossovers] command for [assemblyList] against
     * [refFasta], writing to [outputDir], then restores orchestrator logging
     * and verifies the output directory exists. Returns [outputDir].
     *
     * This is the single `PickCrossovers().parse(...)` invocation point shared
     * by both pipeline versions and [PickBaseCrossovers].
     */
    fun runPickCrossovers(
        workDir: Path,
        refFasta: Path,
        assemblyList: Path,
        outputDir: Path,
        logger: Logger,
    ): Path {
        val args = listOf(
            "--work-dir=$workDir",
            "--ref-fasta=$refFasta",
            "--assembly-list=$assemblyList",
            "--output-dir=$outputDir",
        )

        PickCrossovers().parse(args)
        restoreOrchestratorLogging(workDir, logger)

        if (!outputDir.exists()) {
            throw RuntimeException("Expected pick-crossovers output directory not found: $outputDir")
        }
        return outputDir
    }
}

/**
 * The `orchestrate` command: parses the YAML config, ensures the
 * environment is set up, then dispatches to the pipeline matching the
 * config's `version` field ([OrchestrateV1] for "v1"/default,
 * [OrchestrateV2] for "v2").
 */
class Orchestrate : CliktCommand(name = "orchestrate") {

    private val logger: Logger = LogManager.getLogger(Orchestrate::class.java)

    private val configFile by option(
        "--config", "-c",
        help = "Path to YAML configuration file"
    ).path(mustExist = true, canBeFile = true, canBeDir = false)
        .required()

    private fun validateEnvironment(workDir: Path): Boolean {
        // Check if working directory exists
        if (!workDir.exists()) {
            logger.info("Working directory does not exist: $workDir")
            return false
        }

        // Check if MLImpute directory exists
        val mlimputeDir = workDir.resolve(Constants.SRC_DIR).resolve(Constants.MLIMPUTE_DIR)
        if (!mlimputeDir.exists()) {
            logger.info("MLImpute directory not found: $mlimputeDir")
            return false
        }

        // Check if MLImpute gradlew is executable
        val mlimputeGradlew = mlimputeDir.resolve("src").resolve("kotlin").resolve("gradlew")
        if (!mlimputeGradlew.exists()) {
            logger.info("MLImpute gradlew not found: $mlimputeGradlew")
            return false
        }

        // Check if biokotlin-tools directory exists
        val biokotlinDir = workDir.resolve(Constants.SRC_DIR).resolve(Constants.BIOKOTLIN_TOOLS_DIR)
        if (!biokotlinDir.exists()) {
            logger.info("biokotlin-tools directory not found: $biokotlinDir")
            return false
        }

        // Check if PHGv2 binary exists (align-assemblies + later steps shell out to it)
        val phgBinary = workDir.resolve(Constants.SRC_DIR)
            .resolve(Constants.PHGV2_DIR)
            .resolve("bin")
            .resolve("phg")
        if (!phgBinary.exists()) {
            logger.info("PHGv2 binary not found: $phgBinary")
            return false
        }

        // All checks passed
        logger.info("Environment validation passed - all required tools are present")
        return true
    }

    private fun runSetupEnvironment(workDir: Path): Boolean {
        logger.info("=".repeat(80))
        logger.info("AUTO-SETUP: Running setup-environment")
        logger.info("=".repeat(80))

        return try {
            SetupEnvironment().parse(listOf("--work-dir=$workDir"))
            logger.info("setup-environment completed successfully")
            true
        } catch (e: Exception) {
            logger.error("setup-environment failed: ${e.message}", e)
            false
        }
    }

    private fun parseYamlConfig(configPath: Path): PipelineConfig {
        logger.info("Parsing configuration file: $configPath")

        try {
            val yaml = Yaml()
            val configMap = configPath.inputStream().use { input ->
                yaml.load<Map<String, Any>>(input)
            }

            // Parse and validate version (default to "v1" when absent for backward compatibility)
            val version = (configMap["version"] as? String)?.trim()?.lowercase() ?: "v1"
            require(version == "v1" || version == "v2") {
                "Unsupported pipeline version '$version' (expected 'v1' or 'v2')"
            }

            // Parse work_dir
            val workDir = configMap["work_dir"] as? String

            // Parse run_steps
            @Suppress("UNCHECKED_CAST")
            val runSteps = configMap["run_steps"] as? List<String>

            // Parse align_assemblies
            @Suppress("UNCHECKED_CAST")
            val alignAssembliesMap = configMap["align_assemblies"] as? Map<String, Any>
            val alignAssemblies = alignAssembliesMap?.let {
                AlignAssembliesConfig(
                    ref_gff = it["ref_gff"] as? String ?: throw IllegalArgumentException("align_assemblies.ref_gff is required"),
                    ref_fasta = it["ref_fasta"] as? String ?: throw IllegalArgumentException("align_assemblies.ref_fasta is required"),
                    query_fasta = it["query_fasta"] as? String ?: throw IllegalArgumentException("align_assemblies.query_fasta is required"),
                    threads = it["threads"] as? Int,
                    in_parallel = it["in_parallel"] as? Int,
                    ref_max_align_cov = it["ref_max_align_cov"] as? Int,
                    query_max_align_cov = it["query_max_align_cov"] as? Int,
                    conda_env_prefix = it["conda_env_prefix"] as? String,
                    just_ref_prep = it["just_ref_prep"] as? Boolean,
                    output = it["output"] as? String
                )
            }

            // Parse maf_to_gvcf - check if key exists (even with empty/null value means "run with defaults")
            @Suppress("UNCHECKED_CAST")
            val mafToGvcfMap = configMap["maf_to_gvcf"] as? Map<String, Any>
            val mafToGvcf = if (configMap.containsKey("maf_to_gvcf")) {
                MafToGvcfConfig(
                    reference_file = mafToGvcfMap?.get("reference_file") as? String,
                    maf_file = mafToGvcfMap?.get("maf_file") as? String,
                    output_file = mafToGvcfMap?.get("output_file") as? String,
                    sample_name = mafToGvcfMap?.get("sample_name") as? String,
                    output_dir = mafToGvcfMap?.get("output_dir") as? String
                )
            } else null

            // Parse split_gvcfs - keyfile is required when the section is present
            @Suppress("UNCHECKED_CAST")
            val splitGvcfsMap = configMap["split_gvcfs"] as? Map<String, Any>
            val splitGvcfs = if (configMap.containsKey("split_gvcfs")) {
                SplitGvcfsConfig(
                    keyfile = splitGvcfsMap?.get("keyfile") as? String
                        ?: throw IllegalArgumentException("split_gvcfs.keyfile is required"),
                    input = splitGvcfsMap["input"] as? String,
                    output = splitGvcfsMap["output"] as? String
                )
            } else null

            // Parse mutate_assemblies - check if key exists (even with empty/null value means "run with defaults")
            @Suppress("UNCHECKED_CAST")
            val mutateAssembliesMap = configMap["mutate_assemblies"] as? Map<String, Any>
            val mutateAssemblies = if (configMap.containsKey("mutate_assemblies")) {
                MutateAssembliesConfig(
                    keyfile = mutateAssembliesMap?.get("keyfile") as? String,
                    base_input = mutateAssembliesMap?.get("base_input") as? String,
                    mutation_donor_input = mutateAssembliesMap?.get("mutation_donor_input") as? String,
                    output = mutateAssembliesMap?.get("output") as? String
                )
            } else null

            // Parse recombine_gvcfs - check if key exists (even with empty/null value means "run with defaults")
            @Suppress("UNCHECKED_CAST")
            val recombineGvcfsMap = configMap["recombine_gvcfs"] as? Map<String, Any>
            val recombineGvcfs = if (configMap.containsKey("recombine_gvcfs")) {
                RecombineGvcfsConfig(
                    ref_file = recombineGvcfsMap?.get("ref_file") as? String,
                    input_bed = recombineGvcfsMap?.get("input_bed") as? String,
                    input_gvcf = recombineGvcfsMap?.get("input_gvcf") as? String,
                    output = recombineGvcfsMap?.get("output") as? String,
                )
            } else null

            // Parse sort_gvcfs - check if key exists (even with empty/null value means "run with defaults")
            @Suppress("UNCHECKED_CAST")
            val sortGvcfsMap = configMap["sort_gvcfs"] as? Map<String, Any>
            val sortGvcfs = if (configMap.containsKey("sort_gvcfs")) {
                SortGvcfsConfig(
                    input = sortGvcfsMap?.get("input") as? String,
                    threads = sortGvcfsMap?.get("threads") as? Int,
                    output = sortGvcfsMap?.get("output") as? String
                )
            } else null

            // Parse downsample_gvcf - check if key exists (even with empty/null value means "run with defaults")
            @Suppress("UNCHECKED_CAST")
            val downsampleGvcfMap = configMap["downsample_gvcf"] as? Map<String, Any>
            val downsampleGvcf = if (configMap.containsKey("downsample_gvcf")) {
                DownsampleGvcfConfig(
                    ignore_contig = downsampleGvcfMap?.get("ignore_contig") as? String,
                    rates = downsampleGvcfMap?.get("rates") as? String,
                    seed = downsampleGvcfMap?.get("seed") as? Int,
                    keep_ref = downsampleGvcfMap?.get("keep_ref") as? Boolean,
                    min_ref_block_size = downsampleGvcfMap?.get("min_ref_block_size") as? Int,
                    input = downsampleGvcfMap?.get("input") as? String,
                    output = downsampleGvcfMap?.get("output") as? String
                )
            } else null

            // Parse convert_to_fasta - check if key exists (even with empty/null value means "run with defaults")
            @Suppress("UNCHECKED_CAST")
            val convertToFastaMap = configMap["convert_to_fasta"] as? Map<String, Any>
            val convertToFasta = if (configMap.containsKey("convert_to_fasta")) {
                ConvertToFastaConfig(
                    reference_file = convertToFastaMap?.get("reference_file") as? String,
                    missing_records_as = convertToFastaMap?.get("missing_records_as") as? String,
                    missing_genotype_as = convertToFastaMap?.get("missing_genotype_as") as? String,
                    ignore_contig = convertToFastaMap?.get("ignore_contig") as? String,
                    input = convertToFastaMap?.get("input") as? String,
                    output = convertToFastaMap?.get("output") as? String
                )
            } else null

            // Parse align_mutated_assemblies - check if key exists (even with empty/null value means "run with defaults")
            @Suppress("UNCHECKED_CAST")
            val alignMutatedAssembliesMap = configMap["align_mutated_assemblies"] as? Map<String, Any>
            val alignMutatedAssemblies = if (configMap.containsKey("align_mutated_assemblies")) {
                AlignMutatedAssembliesConfig(
                    ref_gff = alignMutatedAssembliesMap?.get("ref_gff") as? String,
                    ref_fasta = alignMutatedAssembliesMap?.get("ref_fasta") as? String,
                    fasta_input = alignMutatedAssembliesMap?.get("fasta_input") as? String,
                    threads = alignMutatedAssembliesMap?.get("threads") as? Int,
                    in_parallel = alignMutatedAssembliesMap?.get("in_parallel") as? Int,
                    ref_max_align_cov = alignMutatedAssembliesMap?.get("ref_max_align_cov") as? Int,
                    query_max_align_cov = alignMutatedAssembliesMap?.get("query_max_align_cov") as? Int,
                    conda_env_prefix = alignMutatedAssembliesMap?.get("conda_env_prefix") as? String,
                    just_ref_prep = alignMutatedAssembliesMap?.get("just_ref_prep") as? Boolean,
                    output = alignMutatedAssembliesMap?.get("output") as? String
                )
            } else null

            // Parse pick_crossovers - check if key exists (even with empty/null value means "run with defaults")
            @Suppress("UNCHECKED_CAST")
            val pickCrossoversMap = configMap["pick_crossovers"] as? Map<String, Any>
            val pickCrossovers = if (configMap.containsKey("pick_crossovers")) {
                PickCrossoversConfig(
                    assembly_list = pickCrossoversMap?.get("assembly_list") as? String,
                    ref_fasta = pickCrossoversMap?.get("ref_fasta") as? String,
                    base_input = pickCrossoversMap?.get("base_input") as? String,
                    query_fasta = pickCrossoversMap?.get("query_fasta") as? String,
                    output = pickCrossoversMap?.get("output") as? String
                )
            } else null

            // Parse create_chain_files - check if key exists (even with empty/null value means "run with defaults")
            @Suppress("UNCHECKED_CAST")
            val createChainFilesMap = configMap["create_chain_files"] as? Map<String, Any>
            val createChainFiles = if (configMap.containsKey("create_chain_files")) {
                CreateChainFilesConfig(
                    jobs = createChainFilesMap?.get("jobs") as? Int,
                    maf_file_input = createChainFilesMap?.get("maf_file_input") as? String,
                    output = createChainFilesMap?.get("output") as? String
                )
            } else null

            // Parse convert_coordinates - check if key exists (even with empty/null value means "run with defaults")
            @Suppress("UNCHECKED_CAST")
            val convertCoordinatesMap = configMap["convert_coordinates"] as? Map<String, Any>
            val convertCoordinates = if (configMap.containsKey("convert_coordinates")) {
                ConvertCoordinatesConfig(
                    assembly_list = convertCoordinatesMap?.get("assembly_list") as? String,
                    input_chain = convertCoordinatesMap?.get("input_chain") as? String,
                    input_refkey = convertCoordinatesMap?.get("input_refkey") as? String,
                    output = convertCoordinatesMap?.get("output") as? String
                )
            } else null

            // Parse generate_recombined_sequences - check if key exists (even with empty/null value means "run with defaults")
            @Suppress("UNCHECKED_CAST")
            val generateRecombinedSequencesMap = configMap["generate_recombined_sequences"] as? Map<String, Any>
            val generateRecombinedSequences = if (configMap.containsKey("generate_recombined_sequences")) {
                GenerateRecombinedSequencesConfig(
                    assembly_list = generateRecombinedSequencesMap?.get("assembly_list") as? String,
                    chromosome_list = generateRecombinedSequencesMap?.get("chromosome_list") as? String,
                    assembly_dir = generateRecombinedSequencesMap?.get("assembly_dir") as? String
                )
            } else null

            // Parse format_recombined_fastas - check if key exists (even with empty/null value means "run with defaults")
            @Suppress("UNCHECKED_CAST")
            val formatRecombinedFastasMap = configMap["format_recombined_fastas"] as? Map<String, Any>
            val formatRecombinedFastas = if (configMap.containsKey("format_recombined_fastas")) {
                FormatRecombinedFastasConfig(
                    line_width = formatRecombinedFastasMap?.get("line_width") as? Int,
                    threads = formatRecombinedFastasMap?.get("threads") as? Int,
                    input = formatRecombinedFastasMap?.get("input") as? String,
                    output = formatRecombinedFastasMap?.get("output") as? String
                )
            } else null

            // Parse mutated_maf_to_gvcf - check if key exists (even with empty/null value means "run with defaults")
            @Suppress("UNCHECKED_CAST")
            val mutatedMafToGvcfMap = configMap["mutated_maf_to_gvcf"] as? Map<String, Any>
            val mutatedMafToGvcf = if (configMap.containsKey("mutated_maf_to_gvcf")) {
                MutatedMafToGvcfConfig(
                    reference_file = mutatedMafToGvcfMap?.get("reference_file") as? String,
                    maf_file = mutatedMafToGvcfMap?.get("maf_file") as? String,
                    output_file = mutatedMafToGvcfMap?.get("output_file") as? String,
                    sample_name = mutatedMafToGvcfMap?.get("sample_name") as? String,
                    output_dir = mutatedMafToGvcfMap?.get("output_dir") as? String
                )
            } else null

            // Parse rope_bwt_chr_index - check if key exists (even with empty/null value means "run with defaults")
            @Suppress("UNCHECKED_CAST")
            val ropeBwtChrIndexMap = configMap["rope_bwt_chr_index"] as? Map<String, Any>
            val ropeBwtChrIndex = if (configMap.containsKey("rope_bwt_chr_index")) {
                RopeBwtChrIndexConfig(
                    keyfile = ropeBwtChrIndexMap?.get("keyfile") as? String,
                    index_file_prefix = ropeBwtChrIndexMap?.get("index_file_prefix") as? String,
                    threads = ropeBwtChrIndexMap?.get("threads") as? Int,
                    delete_fmr_index = ropeBwtChrIndexMap?.get("delete_fmr_index") as? Boolean,
                    output = ropeBwtChrIndexMap?.get("output") as? String
                )
            } else null

            // Parse ropebwt_mem - fastq_input is required when the section is present
            @Suppress("UNCHECKED_CAST")
            val ropebwtMemMap = configMap["ropebwt_mem"] as? Map<String, Any>
            val ropebwtMem = if (configMap.containsKey("ropebwt_mem")) {
                RopebwtMemConfig(
                    fastq_input = ropebwtMemMap?.get("fastq_input") as? String
                        ?: throw IllegalArgumentException("ropebwt_mem.fastq_input is required"),
                    index_file = ropebwtMemMap["index_file"] as? String,
                    l_value = ropebwtMemMap["l_value"] as? Int,
                    p_value = ropebwtMemMap["p_value"] as? Int,
                    threads = ropebwtMemMap["threads"] as? Int,
                    output = ropebwtMemMap["output"] as? String
                )
            } else null

            // Parse build_spline_knots - check if key exists (even with empty/null value means "run with defaults")
            @Suppress("UNCHECKED_CAST")
            val buildSplineKnotsMap = configMap["build_spline_knots"] as? Map<String, Any>
            val buildSplineKnots = if (configMap.containsKey("build_spline_knots")) {
                BuildSplineKnotsConfig(
                    vcf_dir = buildSplineKnotsMap?.get("vcf_dir") as? String,
                    vcf_type = buildSplineKnotsMap?.get("vcf_type") as? String,
                    min_indel_length = buildSplineKnotsMap?.get("min_indel_length") as? Int,
                    num_bps_per_knot = buildSplineKnotsMap?.get("num_bps_per_knot") as? Int,
                    contig_list = buildSplineKnotsMap?.get("contig_list") as? String,
                    random_seed = buildSplineKnotsMap?.get("random_seed") as? Int,
                    disable_asm_coordinates = buildSplineKnotsMap?.get("disable_asm_coordinates") as? Boolean,
                    output = buildSplineKnotsMap?.get("output") as? String
                )
            } else null

            // Parse convert_ropebwt2ps4g - check if key exists (even with empty/null value means "run with defaults")
            @Suppress("UNCHECKED_CAST")
            val convertRopebwt2Ps4gMap = configMap["convert_ropebwt2ps4g"] as? Map<String, Any>
            val convertRopebwt2Ps4g = if (configMap.containsKey("convert_ropebwt2ps4g")) {
                ConvertRopebwt2Ps4gConfig(
                    bed_input = convertRopebwt2Ps4gMap?.get("bed_input") as? String,
                    spline_knot_dir = convertRopebwt2Ps4gMap?.get("spline_knot_dir") as? String,
                    min_mem_length = convertRopebwt2Ps4gMap?.get("min_mem_length") as? Int,
                    max_num_hits = convertRopebwt2Ps4gMap?.get("max_num_hits") as? Int,
                    output = convertRopebwt2Ps4gMap?.get("output") as? String
                )
            } else null

            // Parse ropebwt - fastq_input is required when the section is present
            @Suppress("UNCHECKED_CAST")
            val ropebwtMap = configMap["ropebwt"] as? Map<String, Any>
            val ropebwt = if (configMap.containsKey("ropebwt")) {
                RopebwtConfig(
                    fastq_input = ropebwtMap?.get("fastq_input") as? String
                        ?: throw IllegalArgumentException("ropebwt.fastq_input is required"),
                    fasta_input = ropebwtMap["fasta_input"] as? String,
                    index_file_prefix = ropebwtMap["index_file_prefix"] as? String,
                    threads = ropebwtMap["threads"] as? Int,
                    delete_fmr_index = ropebwtMap["delete_fmr_index"] as? Boolean,
                    l_value = ropebwtMap["l_value"] as? Int,
                    p_value = ropebwtMap["p_value"] as? Int,
                    output = ropebwtMap["output"] as? String
                )
            } else null

            return PipelineConfig(
                version = version,
                work_dir = workDir,
                run_steps = runSteps,
                align_assemblies = alignAssemblies,
                maf_to_gvcf = mafToGvcf,
                split_gvcfs = splitGvcfs,
                mutate_assemblies = mutateAssemblies,
                recombine_gvcfs = recombineGvcfs,
                sort_gvcfs = sortGvcfs,
                downsample_gvcf = downsampleGvcf,
                convert_to_fasta = convertToFasta,
                align_mutated_assemblies = alignMutatedAssemblies,
                pick_crossovers = pickCrossovers,
                create_chain_files = createChainFiles,
                convert_coordinates = convertCoordinates,
                generate_recombined_sequences = generateRecombinedSequences,
                format_recombined_fastas = formatRecombinedFastas,
                mutated_maf_to_gvcf = mutatedMafToGvcf,
                rope_bwt_chr_index = ropeBwtChrIndex,
                ropebwt_mem = ropebwtMem,
                build_spline_knots = buildSplineKnots,
                convert_ropebwt2ps4g = convertRopebwt2Ps4g,
                ropebwt = ropebwt
            )
        } catch (e: Exception) {
            logger.error("Failed to parse configuration file: ${e.message}", e)
            throw e
        }
    }

    override fun run() {
        // Parse configuration
        val config = parseYamlConfig(configFile)

        // Determine working directory and resolve to absolute path for consistency
        val workDir = Path.of(config.work_dir ?: Constants.DEFAULT_WORK_DIR).toAbsolutePath().normalize()

        // Auto-detect and run setup-environment if needed
        logger.info("Validating environment setup...")
        if (!validateEnvironment(workDir)) {
            logger.info("Environment setup required - running setup-environment automatically")
            if (!runSetupEnvironment(workDir)) {
                logger.error("Failed to set up environment")
                exitProcess(1)
            }
            logger.info("Environment setup completed successfully")
        } else {
            logger.info("Environment already set up - skipping setup-environment")
        }

        // Configure file logging
        LoggingUtils.setupFileLogging(workDir, OrchestrateShared.LOG_FILE_NAME, logger)

        // Dispatch to the requested pipeline version. "v1" (the default when
        // the YAML omits `version`) runs the full 15-step pipeline; "v2" runs
        // the trimmed variant pipeline (align-assemblies + maf-to-gvcf).
        when (config.version) {
            "v2" -> OrchestrateV2(logger, configFile).run(config, workDir)
            else -> OrchestrateV1(logger, configFile).run(config, workDir)
        }
    }
}
