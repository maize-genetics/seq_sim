package net.maizegenetics.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import net.maizegenetics.Constants
import net.maizegenetics.utils.LoggingUtils
import net.maizegenetics.utils.ProcessRunner
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.system.exitProcess

/**
 * Data classes for YAML configuration structure
 */
data class PipelineConfig(
    val work_dir: String? = null,
    val run_steps: List<String>? = null,
    val align_assemblies: AlignAssembliesConfig? = null,
    val maf_to_gvcf: MafToGvcfConfig? = null,
    val downsample_gvcf: DownsampleGvcfConfig? = null,
    val convert_to_fasta: ConvertToFastaConfig? = null,
    val align_mutated_assemblies: AlignMutatedAssembliesConfig? = null,
    val pick_crossovers: PickCrossoversConfig? = null,
    val create_chain_files: CreateChainFilesConfig? = null,
    val convert_coordinates: ConvertCoordinatesConfig? = null,
    val generate_recombined_sequences: GenerateRecombinedSequencesConfig? = null,
    val format_recombined_fastas: FormatRecombinedFastasConfig? = null
)

data class AlignAssembliesConfig(
    val ref_gff: String,
    val ref_fasta: String,
    val query_fasta: String,
    val threads: Int? = null,
    val output: String? = null  // Custom output directory
)

data class MafToGvcfConfig(
    val sample_name: String? = null,
    val input: String? = null,   // Custom MAF file/directory path
    val output: String? = null   // Custom GVCF output directory
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
    val missing_records_as: String? = null,
    val missing_genotype_as: String? = null,
    val input: String? = null,   // Custom GVCF input file/directory
    val output: String? = null   // Custom FASTA output directory
)

data class AlignMutatedAssembliesConfig(
    val ref_gff: String? = null,      // Optional: Reference GFF (uses align_assemblies.ref_gff if not specified)
    val ref_fasta: String? = null,    // Optional: Reference FASTA (uses matching ref from step 4 output if not specified)
    val fasta_input: String? = null,  // Optional: Query FASTA input (uses non-ref files from step 4 if not specified)
    val threads: Int? = null,
    val output: String? = null        // Custom output directory
)

data class PickCrossoversConfig(
    val assembly_list: String,
    val ref_fasta: String? = null,  // Optional: Reference FASTA (uses align_assemblies.ref_fasta if not specified)
    val output: String? = null      // Custom output directory
)

data class CreateChainFilesConfig(
    val jobs: Int? = null,
    val input: String? = null,   // Custom MAF input file/directory
    val output: String? = null   // Custom output directory
)

data class ConvertCoordinatesConfig(
    val assembly_list: String,
    val input_chain: String? = null,   // Custom chain directory
    val input_refkey: String? = null,  // Custom refkey directory
    val output: String? = null         // Custom output directory
)

data class GenerateRecombinedSequencesConfig(
    val assembly_list: String,
    val chromosome_list: String,
    val assembly_dir: String,
    val input: String? = null,   // Custom founder key directory
    val output: String? = null   // Custom output directory
)

data class FormatRecombinedFastasConfig(
    val line_width: Int? = null,
    val threads: Int? = null,
    val input: String? = null,   // Custom FASTA input file/directory
    val output: String? = null   // Custom output directory
)

class Orchestrate : CliktCommand(name = "orchestrate") {
    companion object {
        private const val LOG_FILE_NAME = "00_orchestrate.log"
    }

    private val logger: Logger = LogManager.getLogger(Orchestrate::class.java)

    private val configFile by option(
        "--config", "-c",
        help = "Path to YAML configuration file"
    ).path(mustExist = true, canBeFile = true, canBeDir = false)
        .required()

    private fun shouldRunStep(stepName: String, config: PipelineConfig): Boolean {
        // If run_steps is not specified, run all configured steps
        if (config.run_steps == null) {
            return true
        }
        // If run_steps is specified, only run steps in the list
        return stepName in config.run_steps
    }

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

        // All checks passed
        logger.info("Environment validation passed - all required tools are present")
        return true
    }

    private fun runSetupEnvironment(workDir: Path): Boolean {
        logger.info("=".repeat(80))
        logger.info("AUTO-SETUP: Running setup-environment")
        logger.info("=".repeat(80))

        val args = buildList {
            add("setup-environment")
            add("--work-dir=${workDir}")
        }

        val exitCode = ProcessRunner.runCommand(
            "./gradlew", "run", "--args=${args.joinToString(" ")}",
            workingDir = File("."),
            logger = logger
        )

        if (exitCode != 0) {
            logger.error("setup-environment failed with exit code $exitCode")
            return false
        }

        logger.info("setup-environment completed successfully")
        return true
    }

    private fun parseYamlConfig(configPath: Path): PipelineConfig {
        logger.info("Parsing configuration file: $configPath")

        try {
            val yaml = Yaml()
            val configMap = configPath.inputStream().use { input ->
                yaml.load<Map<String, Any>>(input)
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
                    output = it["output"] as? String
                )
            }

            // Parse maf_to_gvcf
            val mafToGvcfMap = configMap["maf_to_gvcf"] as? Map<String, Any>
            val mafToGvcf = mafToGvcfMap?.let {
                MafToGvcfConfig(
                    sample_name = it["sample_name"] as? String,
                    input = it["input"] as? String,
                    output = it["output"] as? String
                )
            }

            // Parse downsample_gvcf
            val downsampleGvcfMap = configMap["downsample_gvcf"] as? Map<String, Any>
            val downsampleGvcf = downsampleGvcfMap?.let {
                DownsampleGvcfConfig(
                    ignore_contig = it["ignore_contig"] as? String,
                    rates = it["rates"] as? String,
                    seed = it["seed"] as? Int,
                    keep_ref = it["keep_ref"] as? Boolean,
                    min_ref_block_size = it["min_ref_block_size"] as? Int,
                    input = it["input"] as? String,
                    output = it["output"] as? String
                )
            }

            // Parse convert_to_fasta
            val convertToFastaMap = configMap["convert_to_fasta"] as? Map<String, Any>
            val convertToFasta = convertToFastaMap?.let {
                ConvertToFastaConfig(
                    missing_records_as = it["missing_records_as"] as? String,
                    missing_genotype_as = it["missing_genotype_as"] as? String,
                    input = it["input"] as? String,
                    output = it["output"] as? String
                )
            }

            // Parse align_mutated_assemblies
            @Suppress("UNCHECKED_CAST")
            val alignMutatedAssembliesMap = configMap["align_mutated_assemblies"] as? Map<String, Any>
            val alignMutatedAssemblies = alignMutatedAssembliesMap?.let {
                AlignMutatedAssembliesConfig(
                    ref_gff = it["ref_gff"] as? String,
                    ref_fasta = it["ref_fasta"] as? String,
                    fasta_input = it["fasta_input"] as? String,
                    threads = it["threads"] as? Int,
                    output = it["output"] as? String
                )
            }

            // Parse pick_crossovers
            @Suppress("UNCHECKED_CAST")
            val pickCrossoversMap = configMap["pick_crossovers"] as? Map<String, Any>
            val pickCrossovers = pickCrossoversMap?.let {
                PickCrossoversConfig(
                    assembly_list = it["assembly_list"] as? String ?: throw IllegalArgumentException("pick_crossovers.assembly_list is required"),
                    ref_fasta = it["ref_fasta"] as? String,
                    output = it["output"] as? String
                )
            }

            // Parse create_chain_files
            @Suppress("UNCHECKED_CAST")
            val createChainFilesMap = configMap["create_chain_files"] as? Map<String, Any>
            val createChainFiles = createChainFilesMap?.let {
                CreateChainFilesConfig(
                    jobs = it["jobs"] as? Int,
                    input = it["input"] as? String,
                    output = it["output"] as? String
                )
            }

            // Parse convert_coordinates
            @Suppress("UNCHECKED_CAST")
            val convertCoordinatesMap = configMap["convert_coordinates"] as? Map<String, Any>
            val convertCoordinates = convertCoordinatesMap?.let {
                ConvertCoordinatesConfig(
                    assembly_list = it["assembly_list"] as? String ?: throw IllegalArgumentException("convert_coordinates.assembly_list is required"),
                    input_chain = it["input_chain"] as? String,
                    input_refkey = it["input_refkey"] as? String,
                    output = it["output"] as? String
                )
            }

            // Parse generate_recombined_sequences
            @Suppress("UNCHECKED_CAST")
            val generateRecombinedSequencesMap = configMap["generate_recombined_sequences"] as? Map<String, Any>
            val generateRecombinedSequences = generateRecombinedSequencesMap?.let {
                GenerateRecombinedSequencesConfig(
                    assembly_list = it["assembly_list"] as? String ?: throw IllegalArgumentException("generate_recombined_sequences.assembly_list is required"),
                    chromosome_list = it["chromosome_list"] as? String ?: throw IllegalArgumentException("generate_recombined_sequences.chromosome_list is required"),
                    assembly_dir = it["assembly_dir"] as? String ?: throw IllegalArgumentException("generate_recombined_sequences.assembly_dir is required"),
                    input = it["input"] as? String,
                    output = it["output"] as? String
                )
            }

            // Parse format_recombined_fastas
            @Suppress("UNCHECKED_CAST")
            val formatRecombinedFastasMap = configMap["format_recombined_fastas"] as? Map<String, Any>
            val formatRecombinedFastas = formatRecombinedFastasMap?.let {
                FormatRecombinedFastasConfig(
                    line_width = it["line_width"] as? Int,
                    threads = it["threads"] as? Int,
                    input = it["input"] as? String,
                    output = it["output"] as? String
                )
            }

            return PipelineConfig(
                work_dir = workDir,
                run_steps = runSteps,
                align_assemblies = alignAssemblies,
                maf_to_gvcf = mafToGvcf,
                downsample_gvcf = downsampleGvcf,
                convert_to_fasta = convertToFasta,
                align_mutated_assemblies = alignMutatedAssemblies,
                pick_crossovers = pickCrossovers,
                create_chain_files = createChainFiles,
                convert_coordinates = convertCoordinates,
                generate_recombined_sequences = generateRecombinedSequences,
                format_recombined_fastas = formatRecombinedFastas
            )
        } catch (e: Exception) {
            logger.error("Failed to parse configuration file: ${e.message}", e)
            throw e
        }
    }

    override fun run() {
        // Parse configuration
        val config = parseYamlConfig(configFile)

        // Determine working directory
        val workDir = Path.of(config.work_dir ?: Constants.DEFAULT_WORK_DIR)

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
        LoggingUtils.setupFileLogging(workDir, LOG_FILE_NAME, logger)

        logger.info("=".repeat(80))
        logger.info("Starting Pipeline Orchestration")
        logger.info("=".repeat(80))
        logger.info("Configuration file: $configFile")
        logger.info("Working directory: $workDir")

        // Log which steps will be executed
        if (config.run_steps != null) {
            logger.info("Steps to execute: ${config.run_steps.joinToString(", ")}")
        } else {
            logger.info("Will execute all configured steps")
        }
        logger.info("")

        // Track outputs between steps
        var mafFilePaths: Path? = null
        var gvcfOutputDir: Path? = null
        var downsampledGvcfOutputDir: Path? = null
        var fastaOutputDir: Path? = null
        var refFasta: Path? = null
        var refGff: Path? = null
        var mutatedRefFasta: Path? = null  // Mutated reference FASTA from step 5
        var refkeyOutputDir: Path? = null
        var chainOutputDir: Path? = null
        var coordinatesOutputDir: Path? = null
        var recombinedFastasDir: Path? = null
        var formattedFastasDir: Path? = null

        try {
            // Step 1: Align Assemblies (if configured and should run)
            if (config.align_assemblies != null && shouldRunStep("align_assemblies", config)) {
                logger.info("=".repeat(80))
                logger.info("STEP 1: Align Assemblies")
                logger.info("=".repeat(80))

                refFasta = Path.of(config.align_assemblies.ref_fasta)
                refGff = Path.of(config.align_assemblies.ref_gff)

                // Determine output directory (custom or default)
                val customOutput = config.align_assemblies.output?.let { Path.of(it) }

                val args = buildList {
                    add("align-assemblies")
                    add("--work-dir=${workDir}")
                    add("--ref-gff=${config.align_assemblies.ref_gff}")
                    add("--ref-fasta=${config.align_assemblies.ref_fasta}")
                    add("--query-fasta=${config.align_assemblies.query_fasta}")
                    if (config.align_assemblies.threads != null) {
                        add("--threads=${config.align_assemblies.threads}")
                    }
                    if (customOutput != null) {
                        add("--output-dir=${customOutput}")
                    }
                }

                val exitCode = ProcessRunner.runCommand(
                    "./gradlew", "run", "--args=${args.joinToString(" ")}",
                    workingDir = File("."),
                    logger = logger
                )

                if (exitCode != 0) {
                    throw RuntimeException("align-assemblies failed with exit code $exitCode")
                }

                // Get output path (use custom or default)
                val outputBase = customOutput ?: workDir.resolve("output").resolve("01_anchorwave_results")
                mafFilePaths = outputBase.resolve("maf_file_paths.txt")

                if (!mafFilePaths.exists()) {
                    throw RuntimeException("Expected MAF paths file not found: $mafFilePaths")
                }

                logger.info("Step 1 completed successfully")
                logger.info("")
            } else {
                // Check if step was skipped but outputs exist from previous run
                if (config.align_assemblies != null) {
                    logger.info("Skipping align-assemblies (not in run_steps)")

                    // Try to use outputs from previous run
                    refFasta = Path.of(config.align_assemblies.ref_fasta)
                    refGff = Path.of(config.align_assemblies.ref_gff)
                    
                    // Check custom output location first, then default
                    val customOutput = config.align_assemblies.output?.let { Path.of(it) }
                    val outputBase = customOutput ?: workDir.resolve("output").resolve("01_anchorwave_results")
                    val previousMafPaths = outputBase.resolve("maf_file_paths.txt")

                    if (previousMafPaths.exists()) {
                        mafFilePaths = previousMafPaths
                        logger.info("Using previous align-assemblies outputs: $mafFilePaths")
                    } else {
                        logger.warn("Previous align-assemblies outputs not found. Downstream steps may fail.")
                    }
                } else {
                    logger.info("Skipping align-assemblies (not configured)")
                }
                logger.info("")
            }

            // Step 2: MAF to GVCF (if configured and should run)
            if (config.maf_to_gvcf != null && shouldRunStep("maf_to_gvcf", config)) {
                logger.info("=".repeat(80))
                logger.info("STEP 2: MAF to GVCF Conversion")
                logger.info("=".repeat(80))

                // Determine input (custom or from previous step)
                val mafInput = config.maf_to_gvcf.input?.let { Path.of(it) } ?: mafFilePaths
                if (mafInput == null) {
                    throw RuntimeException("Cannot run maf-to-gvcf: no MAF input available (specify 'input' in config or run align-assemblies first)")
                }
                if (refFasta == null) {
                    throw RuntimeException("Cannot run maf-to-gvcf: reference FASTA not available")
                }

                // Determine output directory (custom or default)
                val customOutput = config.maf_to_gvcf.output?.let { Path.of(it) }

                val args = buildList {
                    add("maf-to-gvcf")
                    add("--work-dir=${workDir}")
                    add("--reference-file=${refFasta}")
                    add("--maf-file=${mafInput}")
                    if (config.maf_to_gvcf.sample_name != null) {
                        add("--sample-name=${config.maf_to_gvcf.sample_name}")
                    }
                    if (customOutput != null) {
                        add("--output-dir=${customOutput}")
                    }
                }

                val exitCode = ProcessRunner.runCommand(
                    "./gradlew", "run", "--args=${args.joinToString(" ")}",
                    workingDir = File("."),
                    logger = logger
                )

                if (exitCode != 0) {
                    throw RuntimeException("maf-to-gvcf failed with exit code $exitCode")
                }

                // Get output directory (use custom or default)
                gvcfOutputDir = customOutput ?: workDir.resolve("output").resolve("02_gvcf_results")

                if (!gvcfOutputDir.exists()) {
                    throw RuntimeException("Expected GVCF output directory not found: $gvcfOutputDir")
                }

                logger.info("Step 2 completed successfully")
                logger.info("")
            } else {
                // Check if step was skipped but outputs exist from previous run
                if (config.maf_to_gvcf != null) {
                    logger.info("Skipping maf-to-gvcf (not in run_steps)")

                    // Check custom output location first, then default
                    val customOutput = config.maf_to_gvcf.output?.let { Path.of(it) }
                    val previousGvcfDir = customOutput ?: workDir.resolve("output").resolve("02_gvcf_results")
                    if (previousGvcfDir.exists()) {
                        gvcfOutputDir = previousGvcfDir
                        logger.info("Using previous maf-to-gvcf outputs: $gvcfOutputDir")
                    } else {
                        logger.warn("Previous maf-to-gvcf outputs not found. Downstream steps may fail.")
                    }
                } else {
                    logger.info("Skipping maf-to-gvcf (not configured)")
                }
                logger.info("")
            }

            // Step 3: Downsample GVCF (if configured and should run)
            if (config.downsample_gvcf != null && shouldRunStep("downsample_gvcf", config)) {
                logger.info("=".repeat(80))
                logger.info("STEP 3: Downsample GVCF")
                logger.info("=".repeat(80))

                // Determine input (custom or from previous step)
                val gvcfInput = config.downsample_gvcf.input?.let { Path.of(it) } ?: gvcfOutputDir
                if (gvcfInput == null) {
                    throw RuntimeException("Cannot run downsample-gvcf: no GVCF input available (specify 'input' in config or run maf-to-gvcf first)")
                }

                // Determine output directory (custom or default)
                val customOutput = config.downsample_gvcf.output?.let { Path.of(it) }

                val args = buildList {
                    add("downsample-gvcf")
                    add("--work-dir=${workDir}")
                    add("--gvcf-dir=${gvcfInput}")
                    if (config.downsample_gvcf.ignore_contig != null) {
                        add("--ignore-contig=${config.downsample_gvcf.ignore_contig}")
                    }
                    if (config.downsample_gvcf.rates != null) {
                        add("--rates=${config.downsample_gvcf.rates}")
                    }
                    if (config.downsample_gvcf.seed != null) {
                        add("--seed=${config.downsample_gvcf.seed}")
                    }
                    if (config.downsample_gvcf.keep_ref != null) {
                        add("--keep-ref=${config.downsample_gvcf.keep_ref}")
                    }
                    if (config.downsample_gvcf.min_ref_block_size != null) {
                        add("--min-ref-block-size=${config.downsample_gvcf.min_ref_block_size}")
                    }
                    if (customOutput != null) {
                        add("--output-dir=${customOutput}")
                    }
                }

                val exitCode = ProcessRunner.runCommand(
                    "./gradlew", "run", "--args=${args.joinToString(" ")}",
                    workingDir = File("."),
                    logger = logger
                )

                if (exitCode != 0) {
                    throw RuntimeException("downsample-gvcf failed with exit code $exitCode")
                }

                // Get output directory (use custom or default)
                downsampledGvcfOutputDir = customOutput ?: workDir.resolve("output").resolve("03_downsample_results")

                if (!downsampledGvcfOutputDir.exists()) {
                    throw RuntimeException("Expected downsampled GVCF output directory not found: $downsampledGvcfOutputDir")
                }

                logger.info("Step 3 completed successfully")
                logger.info("")
            } else {
                // Check if step was skipped but outputs exist from previous run
                if (config.downsample_gvcf != null) {
                    logger.info("Skipping downsample-gvcf (not in run_steps)")

                    // Check custom output location first, then default
                    val customOutput = config.downsample_gvcf.output?.let { Path.of(it) }
                    val previousDownsampleDir = customOutput ?: workDir.resolve("output").resolve("03_downsample_results")
                    if (previousDownsampleDir.exists()) {
                        downsampledGvcfOutputDir = previousDownsampleDir
                        logger.info("Using previous downsample-gvcf outputs: $downsampledGvcfOutputDir")
                    } else {
                        logger.warn("Previous downsample-gvcf outputs not found. Downstream steps may fail.")
                    }
                } else {
                    logger.info("Skipping downsample-gvcf (not configured)")
                }
                logger.info("")
            }

            // Step 4: Convert to FASTA (if configured and should run)
            if (config.convert_to_fasta != null && shouldRunStep("convert_to_fasta", config)) {
                logger.info("=".repeat(80))
                logger.info("STEP 4: Convert to FASTA")
                logger.info("=".repeat(80))

                // Determine input (custom or from previous step)
                val gvcfInput = config.convert_to_fasta.input?.let { Path.of(it) } ?: downsampledGvcfOutputDir
                if (gvcfInput == null) {
                    throw RuntimeException("Cannot run convert-to-fasta: no GVCF input available (specify 'input' in config or run downsample-gvcf first)")
                }
                if (refFasta == null) {
                    throw RuntimeException("Cannot run convert-to-fasta: reference FASTA not available")
                }

                // Determine output directory (custom or default)
                val customOutput = config.convert_to_fasta.output?.let { Path.of(it) }

                val args = buildList {
                    add("convert-to-fasta")
                    add("--work-dir=${workDir}")
                    add("--gvcf-file=${gvcfInput}")
                    add("--ref-fasta=${refFasta}")
                    if (config.convert_to_fasta.missing_records_as != null) {
                        add("--missing-records-as=${config.convert_to_fasta.missing_records_as}")
                    }
                    if (config.convert_to_fasta.missing_genotype_as != null) {
                        add("--missing-genotype-as=${config.convert_to_fasta.missing_genotype_as}")
                    }
                    if (customOutput != null) {
                        add("--output-dir=${customOutput}")
                    }
                }

                val exitCode = ProcessRunner.runCommand(
                    "./gradlew", "run", "--args=${args.joinToString(" ")}",
                    workingDir = File("."),
                    logger = logger
                )

                if (exitCode != 0) {
                    throw RuntimeException("convert-to-fasta failed with exit code $exitCode")
                }

                // Get output directory for downstream use (use custom or default)
                fastaOutputDir = customOutput ?: workDir.resolve("output").resolve("04_fasta_results")

                logger.info("Step 4 completed successfully")
                logger.info("")
            } else {
                if (config.convert_to_fasta != null) {
                    logger.info("Skipping convert-to-fasta (not in run_steps)")

                    // Check custom output location first, then default
                    val customOutput = config.convert_to_fasta.output?.let { Path.of(it) }
                    val previousFastaDir = customOutput ?: workDir.resolve("output").resolve("04_fasta_results")
                    if (previousFastaDir.exists()) {
                        fastaOutputDir = previousFastaDir
                        logger.info("Using previous convert-to-fasta outputs: $fastaOutputDir")
                    } else {
                        logger.warn("Previous convert-to-fasta outputs not found. Downstream steps may fail.")
                    }
                } else {
                    logger.info("Skipping convert-to-fasta (not configured)")
                }
                logger.info("")
            }

            // Step 5: Align Mutated Assemblies (if configured and should run)
            if (config.align_mutated_assemblies != null && shouldRunStep("align_mutated_assemblies", config)) {
                logger.info("=".repeat(80))
                logger.info("STEP 5: Align Mutated Assemblies")
                logger.info("=".repeat(80))

                // Determine ref_gff (config value or from step 1)
                val step5RefGff = config.align_mutated_assemblies.ref_gff?.let { Path.of(it) } ?: refGff
                if (step5RefGff == null) {
                    throw RuntimeException("Cannot run align-mutated-assemblies: reference GFF not available (specify 'ref_gff' in config or run align_assemblies first)")
                }

                // Determine ref_fasta and fasta_input from step 4 output
                // If fastaOutputDir exists, find the reference FASTA (matching original ref name) and query FASTAs
                var step5RefFasta: Path? = config.align_mutated_assemblies.ref_fasta?.let { Path.of(it) }
                var step5FastaInput: Path? = config.align_mutated_assemblies.fasta_input?.let { Path.of(it) }

                // If not explicitly specified, try to derive from step 4 output
                if (step5RefFasta == null || step5FastaInput == null) {
                    if (fastaOutputDir != null && fastaOutputDir.exists()) {
                        // Get the reference FASTA filename (without path) to match against step 4 output
                        val refFastaName = refFasta?.fileName?.toString()?.replace(Regex("\\.(fa|fasta|fna)(\\.gz)?$"), "")

                        if (refFastaName != null) {
                            // Find all FASTA files in the output directory
                            val allFastaFiles = fastaOutputDir.toFile().listFiles { file ->
                                file.isFile && file.name.matches(Regex(".*\\.(fa|fasta|fna)(\\.gz)?$"))
                            }?.map { it.toPath() } ?: emptyList()

                            // Find the reference FASTA (filename contains the ref name)
                            val matchingRefFasta = allFastaFiles.find { path ->
                                path.fileName.toString().contains(refFastaName, ignoreCase = true)
                            }

                            // Get non-reference FASTAs
                            val queryFastas = allFastaFiles.filter { path ->
                                !path.fileName.toString().contains(refFastaName, ignoreCase = true)
                            }

                            if (step5RefFasta == null && matchingRefFasta != null) {
                                step5RefFasta = matchingRefFasta
                                logger.info("Auto-detected reference FASTA from step 4: $step5RefFasta")
                            }

                            if (step5FastaInput == null && queryFastas.isNotEmpty()) {
                                // Create a text file listing the query FASTAs
                                val queryListFile = fastaOutputDir.resolve("query_fastas.txt")
                                queryListFile.writeText(queryFastas.joinToString("\n") { it.toAbsolutePath().toString() })
                                step5FastaInput = queryListFile
                                logger.info("Auto-detected ${queryFastas.size} query FASTA files from step 4")
                            }
                        }
                    }

                    // Fall back to original behavior if auto-detection failed
                    if (step5RefFasta == null) {
                        step5RefFasta = refFasta
                    }
                    if (step5FastaInput == null) {
                        step5FastaInput = fastaOutputDir
                    }
                }

                if (step5FastaInput == null) {
                    throw RuntimeException("Cannot run align-mutated-assemblies: no FASTA input available (specify 'fasta_input' in config or run convert-to-fasta first)")
                }
                if (step5RefFasta == null) {
                    throw RuntimeException("Cannot run align-mutated-assemblies: reference FASTA not available (specify 'ref_fasta' in config or run convert-to-fasta first)")
                }

                // Determine output directory (custom or default)
                val customOutput = config.align_mutated_assemblies.output?.let { Path.of(it) }

                val args = buildList {
                    add("align-mutated-assemblies")
                    add("--work-dir=${workDir}")
                    add("--ref-gff=${step5RefGff}")
                    add("--ref-fasta=${step5RefFasta}")
                    add("--fasta-input=${step5FastaInput}")
                    if (config.align_mutated_assemblies.threads != null) {
                        add("--threads=${config.align_mutated_assemblies.threads}")
                    }
                    if (customOutput != null) {
                        add("--output-dir=${customOutput}")
                    }
                }

                val exitCode = ProcessRunner.runCommand(
                    "./gradlew", "run", "--args=${args.joinToString(" ")}",
                    workingDir = File("."),
                    logger = logger
                )

                if (exitCode != 0) {
                    throw RuntimeException("align-mutated-assemblies failed with exit code $exitCode")
                }

                // Save the mutated reference FASTA for use in step 6
                mutatedRefFasta = step5RefFasta

                logger.info("Step 5 completed successfully")
                logger.info("")
            } else {
                if (config.align_mutated_assemblies != null) {
                    logger.info("Skipping align-mutated-assemblies (not in run_steps)")

                    // Try to recover mutated ref FASTA from step 5 config or step 4 output
                    if (config.align_mutated_assemblies.ref_fasta != null) {
                        mutatedRefFasta = Path.of(config.align_mutated_assemblies.ref_fasta)
                        logger.info("Using configured mutated reference FASTA: $mutatedRefFasta")
                    } else if (fastaOutputDir != null && fastaOutputDir.exists()) {
                        // Try to auto-detect from step 4 output
                        val refFastaName = refFasta?.fileName?.toString()?.replace(Regex("\\.(fa|fasta|fna)(\\.gz)?$"), "")
                        if (refFastaName != null) {
                            val matchingRefFasta = fastaOutputDir.toFile().listFiles { file ->
                                file.isFile && file.name.matches(Regex(".*\\.(fa|fasta|fna)(\\.gz)?$")) &&
                                file.name.contains(refFastaName, ignoreCase = true)
                            }?.firstOrNull()?.toPath()
                            if (matchingRefFasta != null) {
                                mutatedRefFasta = matchingRefFasta
                                logger.info("Auto-detected mutated reference FASTA: $mutatedRefFasta")
                            }
                        }
                    }
                } else {
                    logger.info("Skipping align-mutated-assemblies (not configured)")
                }
                logger.info("")
            }

            // Step 6: Pick Crossovers (if configured and should run)
            if (config.pick_crossovers != null && shouldRunStep("pick_crossovers", config)) {
                logger.info("=".repeat(80))
                logger.info("STEP 6: Pick Crossovers")
                logger.info("=".repeat(80))

                // Use pick_crossovers.ref_fasta if specified, otherwise use mutated ref FASTA from step 5, 
                // finally fall back to original ref FASTA from step 1
                val pickCrossoversRefFasta = config.pick_crossovers.ref_fasta?.let { Path.of(it) } 
                    ?: mutatedRefFasta 
                    ?: refFasta
                if (pickCrossoversRefFasta == null) {
                    throw RuntimeException("Cannot run pick-crossovers: reference FASTA not available (specify 'ref_fasta' in pick_crossovers config, run align_mutated_assemblies, or run align_assemblies first)")
                }

                // Determine output directory (custom or default)
                val customOutput = config.pick_crossovers.output?.let { Path.of(it) }

                val args = buildList {
                    add("pick-crossovers")
                    add("--work-dir=${workDir}")
                    add("--ref-fasta=${pickCrossoversRefFasta}")
                    add("--assembly-list=${config.pick_crossovers.assembly_list}")
                    if (customOutput != null) {
                        add("--output-dir=${customOutput}")
                    }
                }

                val exitCode = ProcessRunner.runCommand(
                    "./gradlew", "run", "--args=${args.joinToString(" ")}",
                    workingDir = File("."),
                    logger = logger
                )

                if (exitCode != 0) {
                    throw RuntimeException("pick-crossovers failed with exit code $exitCode")
                }

                // Get output directory (use custom or default)
                refkeyOutputDir = customOutput ?: workDir.resolve("output").resolve("06_crossovers_results")

                if (!refkeyOutputDir.exists()) {
                    throw RuntimeException("Expected refkey output directory not found: $refkeyOutputDir")
                }

                logger.info("Step 6 completed successfully")
                logger.info("")
            } else {
                if (config.pick_crossovers != null) {
                    logger.info("Skipping pick-crossovers (not in run_steps)")

                    // Check custom output location first, then default
                    val customOutput = config.pick_crossovers.output?.let { Path.of(it) }
                    val previousRefkeyDir = customOutput ?: workDir.resolve("output").resolve("06_crossovers_results")
                    if (previousRefkeyDir.exists()) {
                        refkeyOutputDir = previousRefkeyDir
                        logger.info("Using previous pick-crossovers outputs: $refkeyOutputDir")
                    } else {
                        logger.warn("Previous pick-crossovers outputs not found. Downstream steps may fail.")
                    }
                } else {
                    logger.info("Skipping pick-crossovers (not configured)")
                }
                logger.info("")
            }

            // Step 7: Create Chain Files (if configured and should run)
            if (config.create_chain_files != null && shouldRunStep("create_chain_files", config)) {
                logger.info("=".repeat(80))
                logger.info("STEP 7: Create Chain Files")
                logger.info("=".repeat(80))

                // Determine input (custom or from previous step)
                val mafInput = config.create_chain_files.input?.let { Path.of(it) } ?: mafFilePaths
                if (mafInput == null) {
                    throw RuntimeException("Cannot run create-chain-files: no MAF input available (specify 'input' in config or run align-assemblies first)")
                }

                // Determine output directory (custom or default)
                val customOutput = config.create_chain_files.output?.let { Path.of(it) }

                val args = buildList {
                    add("create-chain-files")
                    add("--work-dir=${workDir}")
                    add("--maf-input=${mafInput}")
                    if (config.create_chain_files.jobs != null) {
                        add("--jobs=${config.create_chain_files.jobs}")
                    }
                    if (customOutput != null) {
                        add("--output-dir=${customOutput}")
                    }
                }

                val exitCode = ProcessRunner.runCommand(
                    "./gradlew", "run", "--args=${args.joinToString(" ")}",
                    workingDir = File("."),
                    logger = logger
                )

                if (exitCode != 0) {
                    throw RuntimeException("create-chain-files failed with exit code $exitCode")
                }

                // Get output directory (use custom or default)
                chainOutputDir = customOutput ?: workDir.resolve("output").resolve("07_chain_results")

                if (!chainOutputDir.exists()) {
                    throw RuntimeException("Expected chain output directory not found: $chainOutputDir")
                }

                logger.info("Step 7 completed successfully")
                logger.info("")
            } else {
                if (config.create_chain_files != null) {
                    logger.info("Skipping create-chain-files (not in run_steps)")

                    // Check custom output location first, then default
                    val customOutput = config.create_chain_files.output?.let { Path.of(it) }
                    val previousChainDir = customOutput ?: workDir.resolve("output").resolve("07_chain_results")
                    if (previousChainDir.exists()) {
                        chainOutputDir = previousChainDir
                        logger.info("Using previous create-chain-files outputs: $chainOutputDir")
                    } else {
                        logger.warn("Previous create-chain-files outputs not found. Downstream steps may fail.")
                    }
                } else {
                    logger.info("Skipping create-chain-files (not configured)")
                }
                logger.info("")
            }

            // Step 8: Convert Coordinates (if configured and should run)
            if (config.convert_coordinates != null && shouldRunStep("convert_coordinates", config)) {
                logger.info("=".repeat(80))
                logger.info("STEP 8: Convert Coordinates")
                logger.info("=".repeat(80))

                // Determine chain input (custom or from previous step)
                val chainInput = config.convert_coordinates.input_chain?.let { Path.of(it) } ?: chainOutputDir
                if (chainInput == null) {
                    throw RuntimeException("Cannot run convert-coordinates: no chain input available (specify 'input_chain' in config or run create-chain-files first)")
                }

                // Determine refkey input (custom or from previous step)
                val refkeyInput = config.convert_coordinates.input_refkey?.let { Path.of(it) } ?: refkeyOutputDir

                // Determine output directory (custom or default)
                val customOutput = config.convert_coordinates.output?.let { Path.of(it) }

                val args = buildList {
                    add("convert-coordinates")
                    add("--work-dir=${workDir}")
                    add("--assembly-list=${config.convert_coordinates.assembly_list}")
                    add("--chain-dir=${chainInput}")
                    if (refkeyInput != null) {
                        add("--refkey-dir=${refkeyInput}")
                    }
                    if (customOutput != null) {
                        add("--output-dir=${customOutput}")
                    }
                }

                val exitCode = ProcessRunner.runCommand(
                    "./gradlew", "run", "--args=${args.joinToString(" ")}",
                    workingDir = File("."),
                    logger = logger
                )

                if (exitCode != 0) {
                    throw RuntimeException("convert-coordinates failed with exit code $exitCode")
                }

                // Get output directory (use custom or default)
                coordinatesOutputDir = customOutput ?: workDir.resolve("output").resolve("08_coordinates_results")

                if (!coordinatesOutputDir.exists()) {
                    throw RuntimeException("Expected coordinates output directory not found: $coordinatesOutputDir")
                }

                logger.info("Step 8 completed successfully")
                logger.info("")
            } else {
                if (config.convert_coordinates != null) {
                    logger.info("Skipping convert-coordinates (not in run_steps)")

                    // Check custom output location first, then default
                    val customOutput = config.convert_coordinates.output?.let { Path.of(it) }
                    val previousCoordsDir = customOutput ?: workDir.resolve("output").resolve("08_coordinates_results")
                    if (previousCoordsDir.exists()) {
                        coordinatesOutputDir = previousCoordsDir
                        logger.info("Using previous convert-coordinates outputs: $coordinatesOutputDir")
                    } else {
                        logger.warn("Previous convert-coordinates outputs not found. Downstream steps may fail.")
                    }
                } else {
                    logger.info("Skipping convert-coordinates (not configured)")
                }
                logger.info("")
            }

            // Step 9: Generate Recombined Sequences (if configured and should run)
            if (config.generate_recombined_sequences != null && shouldRunStep("generate_recombined_sequences", config)) {
                logger.info("=".repeat(80))
                logger.info("STEP 9: Generate Recombined Sequences")
                logger.info("=".repeat(80))

                // Determine founder key input (custom or from previous step)
                val founderKeyInput = config.generate_recombined_sequences.input?.let { Path.of(it) } ?: coordinatesOutputDir
                if (founderKeyInput == null) {
                    throw RuntimeException("Cannot run generate-recombined-sequences: no founder key input available (specify 'input' in config or run convert-coordinates first)")
                }

                // Determine output directory (custom or default)
                val customOutput = config.generate_recombined_sequences.output?.let { Path.of(it) }

                val args = buildList {
                    add("generate-recombined-sequences")
                    add("--work-dir=${workDir}")
                    add("--assembly-list=${config.generate_recombined_sequences.assembly_list}")
                    add("--chromosome-list=${config.generate_recombined_sequences.chromosome_list}")
                    add("--assembly-dir=${config.generate_recombined_sequences.assembly_dir}")
                    add("--founder-key-dir=${founderKeyInput}")
                    if (customOutput != null) {
                        add("--output-dir=${customOutput}")
                    }
                }

                val exitCode = ProcessRunner.runCommand(
                    "./gradlew", "run", "--args=${args.joinToString(" ")}",
                    workingDir = File("."),
                    logger = logger
                )

                if (exitCode != 0) {
                    throw RuntimeException("generate-recombined-sequences failed with exit code $exitCode")
                }

                // Get output directory (use custom or default)
                val outputBase = customOutput ?: workDir.resolve("output").resolve("09_recombined_sequences")
                recombinedFastasDir = outputBase.resolve("recombinate_fastas")

                logger.info("Step 9 completed successfully")
                logger.info("")
            } else {
                if (config.generate_recombined_sequences != null) {
                    logger.info("Skipping generate-recombined-sequences (not in run_steps)")

                    // Check custom output location first, then default
                    val customOutput = config.generate_recombined_sequences.output?.let { Path.of(it) }
                    val outputBase = customOutput ?: workDir.resolve("output").resolve("09_recombined_sequences")
                    val previousRecombinedDir = outputBase.resolve("recombinate_fastas")
                    if (previousRecombinedDir.exists()) {
                        recombinedFastasDir = previousRecombinedDir
                        logger.info("Using previous generate-recombined-sequences outputs: $recombinedFastasDir")
                    } else {
                        logger.warn("Previous generate-recombined-sequences outputs not found.")
                    }
                } else {
                    logger.info("Skipping generate-recombined-sequences (not configured)")
                }
                logger.info("")
            }

            // Step 10: Format Recombined Fastas (if configured and should run)
            if (config.format_recombined_fastas != null && shouldRunStep("format_recombined_fastas", config)) {
                logger.info("=".repeat(80))
                logger.info("STEP 10: Format Recombined Fastas")
                logger.info("=".repeat(80))

                // Determine input (custom or from previous step)
                val fastaInput = config.format_recombined_fastas.input?.let { Path.of(it) } ?: recombinedFastasDir
                if (fastaInput == null) {
                    throw RuntimeException("Cannot run format-recombined-fastas: no FASTA input available (specify 'input' in config or run generate-recombined-sequences first)")
                }

                // Determine output directory (custom or default)
                val customOutput = config.format_recombined_fastas.output?.let { Path.of(it) }

                val args = buildList {
                    add("format-recombined-fastas")
                    add("--work-dir=${workDir}")
                    add("--fasta-input=${fastaInput}")
                    if (config.format_recombined_fastas.line_width != null) {
                        add("--line-width=${config.format_recombined_fastas.line_width}")
                    }
                    if (config.format_recombined_fastas.threads != null) {
                        add("--threads=${config.format_recombined_fastas.threads}")
                    }
                    if (customOutput != null) {
                        add("--output-dir=${customOutput}")
                    }
                }

                val exitCode = ProcessRunner.runCommand(
                    "./gradlew", "run", "--args=${args.joinToString(" ")}",
                    workingDir = File("."),
                    logger = logger
                )

                if (exitCode != 0) {
                    throw RuntimeException("format-recombined-fastas failed with exit code $exitCode")
                }

                // Get output directory (use custom or default)
                formattedFastasDir = customOutput ?: workDir.resolve("output").resolve("10_formatted_fastas")

                logger.info("Step 10 completed successfully")
                logger.info("")
            } else {
                if (config.format_recombined_fastas != null) {
                    logger.info("Skipping format-recombined-fastas (not in run_steps)")

                    // Check custom output location first, then default
                    val customOutput = config.format_recombined_fastas.output?.let { Path.of(it) }
                    val previousFormattedDir = customOutput ?: workDir.resolve("output").resolve("10_formatted_fastas")
                    if (previousFormattedDir.exists()) {
                        formattedFastasDir = previousFormattedDir
                        logger.info("Using previous format-recombined-fastas outputs: $formattedFastasDir")
                    } else {
                        logger.warn("Previous format-recombined-fastas outputs not found.")
                    }
                } else {
                    logger.info("Skipping format-recombined-fastas (not configured)")
                }
                logger.info("")
            }

            // Pipeline completed successfully
            logger.info("=".repeat(80))
            logger.info("PIPELINE COMPLETED SUCCESSFULLY!")
            logger.info("=".repeat(80))
            logger.info("All configured steps have been executed")
            logger.info("Working directory: $workDir")
            logger.info("Outputs are available in: ${workDir.resolve("output")}")

        } catch (e: Exception) {
            logger.error("=".repeat(80))
            logger.error("PIPELINE FAILED")
            logger.error("=".repeat(80))
            logger.error("Error: ${e.message}", e)
            exitProcess(1)
        }
    }
}
