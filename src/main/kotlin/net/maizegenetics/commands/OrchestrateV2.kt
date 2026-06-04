package net.maizegenetics.commands

import com.github.ajalt.clikt.core.parse
import net.maizegenetics.commands.OrchestrateShared.appendPhgAlignSharedArgs
import net.maizegenetics.commands.OrchestrateShared.shouldRunStep
import org.apache.logging.log4j.Logger
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.system.exitProcess

/**
 * v2 pipeline: a trimmed variant pipeline that currently runs only the
 * first two steps -- align-assemblies (01) and maf-to-gvcf (02). It is
 * intentionally kept independent from [OrchestrateV1] so it can diverge
 * as the v2 workflow grows. Step chaining, `run_steps` filtering, default
 * output directories, and skip/reuse-previous-output handling mirror v1
 * so logs and outputs stay consistent.
 *
 * Driven by [Orchestrate], which performs config parsing and environment
 * setup before delegating here. Shared step helpers live in
 * [OrchestrateShared].
 */
class OrchestrateV2(
    private val logger: Logger,
    private val configFile: Path,
) {

    /**
     * Restores the orchestrator's log file after a step command has run.
     * Each step command sets up its own log file, so we need to restore
     * the orchestrator's log file to ensure orchestrator messages go to
     * the correct log file.
     */
    private fun restoreOrchestratorLogging(workDir: Path) {
        OrchestrateShared.restoreOrchestratorLogging(workDir, logger)
    }

    fun run(config: PipelineConfig, workDir: Path) {
        logger.info("=".repeat(80))
        logger.info("Starting Pipeline Orchestration")
        logger.info("Pipeline version: v2 (variant pipeline)")
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
        var refFasta: Path? = null
        var gvcfOutputDir: Path? = null
        var splitBaseDir: Path? = null
        var splitDonorDir: Path? = null
        var pairsFile: Path? = null
        var downsampledDonorDir: Path? = null
        var mutatedGvcfDir: Path? = null
        var crossoverBedDir: Path? = null
        var recombinedGvcfDir: Path? = null

        try {
            // Step 1: Align Assemblies (if configured and should run)
            if (config.align_assemblies != null && shouldRunStep("align_assemblies", config)) {
                logger.info("=".repeat(80))
                logger.info("STEP 1: Align Assemblies")
                logger.info("=".repeat(80))

                // Resolve all paths to absolute paths for consistency
                refFasta = Path.of(config.align_assemblies.ref_fasta).toAbsolutePath().normalize()
                val refGff = Path.of(config.align_assemblies.ref_gff).toAbsolutePath().normalize()
                val queryFasta = Path.of(config.align_assemblies.query_fasta).toAbsolutePath().normalize()

                // Determine output directory (custom or default) - also resolve to absolute path
                val customOutput = config.align_assemblies.output?.let {
                    Path.of(it).toAbsolutePath().normalize()
                }

                logger.info("Reference GFF: $refGff")
                logger.info("Reference FASTA: $refFasta")
                logger.info("Query FASTA: $queryFasta")

                val args = mutableListOf(
                    "--work-dir=$workDir",
                    "--ref-gff=$refGff",
                    "--ref-fasta=$refFasta",
                    "--query-fasta=$queryFasta",
                )
                appendPhgAlignSharedArgs(
                    args,
                    threads = config.align_assemblies.threads,
                    inParallel = config.align_assemblies.in_parallel,
                    refMaxAlignCov = config.align_assemblies.ref_max_align_cov,
                    queryMaxAlignCov = config.align_assemblies.query_max_align_cov,
                    condaEnvPrefix = config.align_assemblies.conda_env_prefix,
                    justRefPrep = config.align_assemblies.just_ref_prep,
                    customOutput = customOutput,
                )

                AlignAssemblies().parse(args)
                restoreOrchestratorLogging(workDir)

                // Get output path (use custom or default)
                val outputBase = customOutput ?: workDir.resolve("output").resolve("01_anchorwave_results")
                mafFilePaths = outputBase.toAbsolutePath().normalize().resolve("maf_file_paths.txt")

                if (!mafFilePaths.exists()) {
                    throw RuntimeException("Expected MAF paths file not found: $mafFilePaths")
                }

                logger.info("Step 1 completed successfully")
                logger.info("")
            } else {
                // Check if step was skipped but outputs exist from previous run
                if (config.align_assemblies != null) {
                    logger.info("Skipping align-assemblies (not in run_steps)")

                    // Try to use outputs from previous run - resolve to absolute paths
                    refFasta = Path.of(config.align_assemblies.ref_fasta).toAbsolutePath().normalize()

                    // Check custom output location first, then default
                    val customOutput = config.align_assemblies.output?.let {
                        Path.of(it).toAbsolutePath().normalize()
                    }
                    val outputBase = (customOutput ?: workDir.resolve("output").resolve("01_anchorwave_results"))
                        .toAbsolutePath().normalize()
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

                // Determine reference file (custom or from step 1) - resolve to absolute path
                val step2RefFasta = config.maf_to_gvcf.reference_file?.let {
                    Path.of(it).toAbsolutePath().normalize()
                } ?: refFasta
                if (step2RefFasta == null) {
                    throw RuntimeException("Cannot run maf-to-gvcf: reference FASTA not available (specify 'reference_file' in config or run align-assemblies first)")
                }

                // Determine MAF input (custom or from step 1) - resolve to absolute path
                val mafInput = config.maf_to_gvcf.maf_file?.let {
                    Path.of(it).toAbsolutePath().normalize()
                } ?: mafFilePaths
                if (mafInput == null) {
                    throw RuntimeException("Cannot run maf-to-gvcf: no MAF input available (specify 'maf_file' in config or run align-assemblies first)")
                }

                // Determine output directory (custom or default) - resolve to absolute path
                val customOutputDir = config.maf_to_gvcf.output_dir?.let {
                    Path.of(it).toAbsolutePath().normalize()
                }

                // Determine output file if specified - resolve to absolute path
                val outputFile = config.maf_to_gvcf.output_file?.let {
                    Path.of(it).toAbsolutePath().normalize()
                }

                logger.info("Reference FASTA: $step2RefFasta")
                logger.info("MAF input: $mafInput")

                val args = buildList {
                    add("--work-dir=$workDir")
                    add("--reference-file=$step2RefFasta")
                    add("--maf-file=$mafInput")
                    if (outputFile != null) {
                        add("--output-file=$outputFile")
                    }
                    if (config.maf_to_gvcf.sample_name != null) {
                        add("--sample-name=${config.maf_to_gvcf.sample_name}")
                    }
                    if (customOutputDir != null) {
                        add("--output-dir=$customOutputDir")
                    }
                }

                MafToGvcf().parse(args)
                restoreOrchestratorLogging(workDir)

                // Get output directory (use custom or default)
                gvcfOutputDir = (customOutputDir ?: workDir.resolve("output").resolve("02_gvcf_results"))
                    .toAbsolutePath().normalize()

                if (!gvcfOutputDir.exists()) {
                    throw RuntimeException("Expected GVCF output directory not found: $gvcfOutputDir")
                }

                logger.info("Step 2 completed successfully")
                logger.info("")
            } else {
                if (config.maf_to_gvcf != null) {
                    logger.info("Skipping maf-to-gvcf (not in run_steps)")

                    // Try to use outputs from previous run
                    val customOutputDir = config.maf_to_gvcf.output_dir?.let {
                        Path.of(it).toAbsolutePath().normalize()
                    }
                    val previousGvcfDir = (customOutputDir ?: workDir.resolve("output").resolve("02_gvcf_results"))
                        .toAbsolutePath().normalize()
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

            // Step 3: Split GVCFs into base / mutation-donor (if configured and should run)
            if (config.split_gvcfs != null && shouldRunStep("split_gvcfs", config)) {
                logger.info("=".repeat(80))
                logger.info("STEP 3: Split GVCFs (base / mutation donor)")
                logger.info("=".repeat(80))

                val keyfile = Path.of(config.split_gvcfs.keyfile).toAbsolutePath().normalize()
                val gvcfInput = config.split_gvcfs.input?.let {
                    Path.of(it).toAbsolutePath().normalize()
                } ?: gvcfOutputDir
                if (gvcfInput == null) {
                    throw RuntimeException("Cannot run split-gvcfs: no GVCF input available (specify 'input' in config or run maf-to-gvcf first)")
                }

                val customOutput = config.split_gvcfs.output?.let {
                    Path.of(it).toAbsolutePath().normalize()
                }
                val outputBase = (customOutput ?: workDir.resolve("output").resolve("03_split_gvcfs_results"))
                    .toAbsolutePath().normalize()

                logger.info("Keyfile: $keyfile")
                logger.info("GVCF input: $gvcfInput")

                val args = buildList {
                    add("--work-dir=$workDir")
                    add("--keyfile=$keyfile")
                    add("--gvcf-dir=$gvcfInput")
                    if (customOutput != null) {
                        add("--output-dir=$customOutput")
                    }
                }

                SplitGvcfs().parse(args)
                restoreOrchestratorLogging(workDir)

                splitBaseDir = outputBase.resolve("base")
                splitDonorDir = outputBase.resolve("mutation_donor")
                pairsFile = outputBase.resolve("pairs.tsv")

                if (!pairsFile.exists()) {
                    throw RuntimeException("Expected split-gvcfs pairs file not found: $pairsFile")
                }

                logger.info("Step 3 completed successfully")
                logger.info("")
            } else {
                if (config.split_gvcfs != null) {
                    logger.info("Skipping split-gvcfs (not in run_steps)")

                    val customOutput = config.split_gvcfs.output?.let {
                        Path.of(it).toAbsolutePath().normalize()
                    }
                    val outputBase = (customOutput ?: workDir.resolve("output").resolve("03_split_gvcfs_results"))
                        .toAbsolutePath().normalize()
                    if (outputBase.exists()) {
                        splitBaseDir = outputBase.resolve("base")
                        splitDonorDir = outputBase.resolve("mutation_donor")
                        pairsFile = outputBase.resolve("pairs.tsv")
                        logger.info("Using previous split-gvcfs outputs: $outputBase")
                    } else {
                        logger.warn("Previous split-gvcfs outputs not found. Downstream steps may fail.")
                    }
                } else {
                    logger.info("Skipping split-gvcfs (not configured)")
                }
                logger.info("")
            }

            // Step 4: Downsample the mutation-donor GVCFs (if configured and should run)
            if (config.downsample_gvcf != null && shouldRunStep("downsample_gvcf", config)) {
                logger.info("=".repeat(80))
                logger.info("STEP 4: Downsample mutation-donor GVCFs")
                logger.info("=".repeat(80))

                val gvcfInput = config.downsample_gvcf.input?.let {
                    Path.of(it).toAbsolutePath().normalize()
                } ?: splitDonorDir
                if (gvcfInput == null) {
                    throw RuntimeException("Cannot run downsample-gvcf: no mutation-donor GVCF input available (specify 'input' in config or run split-gvcfs first)")
                }

                val customOutput = config.downsample_gvcf.output?.let {
                    Path.of(it).toAbsolutePath().normalize()
                }
                val outputBase = (customOutput ?: workDir.resolve("output").resolve("04_downsample_results"))
                    .toAbsolutePath().normalize()

                logger.info("Mutation-donor GVCF input: $gvcfInput")

                val args = buildList {
                    add("--work-dir=$workDir")
                    add("--gvcf-dir=$gvcfInput")
                    add("--output-dir=$outputBase")
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
                }

                DownsampleGvcf().parse(args)
                restoreOrchestratorLogging(workDir)

                downsampledDonorDir = outputBase
                if (!downsampledDonorDir.exists()) {
                    throw RuntimeException("Expected downsampled GVCF output directory not found: $downsampledDonorDir")
                }

                logger.info("Step 4 completed successfully")
                logger.info("")
            } else {
                if (config.downsample_gvcf != null) {
                    logger.info("Skipping downsample-gvcf (not in run_steps)")

                    val customOutput = config.downsample_gvcf.output?.let {
                        Path.of(it).toAbsolutePath().normalize()
                    }
                    val previousDir = (customOutput ?: workDir.resolve("output").resolve("04_downsample_results"))
                        .toAbsolutePath().normalize()
                    if (previousDir.exists()) {
                        downsampledDonorDir = previousDir
                        logger.info("Using previous downsample-gvcf outputs: $downsampledDonorDir")
                    } else {
                        logger.warn("Previous downsample-gvcf outputs not found. Downstream steps may fail.")
                    }
                } else {
                    logger.info("Skipping downsample-gvcf (not configured)")
                }
                logger.info("")
            }

            // Step 5: Mutate assemblies (base + downsampled mutation donor -> mutated base GVCFs)
            if (config.mutate_assemblies != null && shouldRunStep("mutate_assemblies", config)) {
                logger.info("=".repeat(80))
                logger.info("STEP 5: Mutate assemblies")
                logger.info("=".repeat(80))

                val keyfile = config.mutate_assemblies.keyfile?.let {
                    Path.of(it).toAbsolutePath().normalize()
                } ?: pairsFile
                if (keyfile == null) {
                    throw RuntimeException("Cannot run mutate-assemblies: no pairs keyfile available (specify 'keyfile' in config or run split-gvcfs first)")
                }

                val baseInput = config.mutate_assemblies.base_input?.let {
                    Path.of(it).toAbsolutePath().normalize()
                } ?: splitBaseDir
                if (baseInput == null) {
                    throw RuntimeException("Cannot run mutate-assemblies: no base gVCF directory available (specify 'base_input' in config or run split-gvcfs first)")
                }

                val donorInput = config.mutate_assemblies.mutation_donor_input?.let {
                    Path.of(it).toAbsolutePath().normalize()
                } ?: downsampledDonorDir
                if (donorInput == null) {
                    throw RuntimeException("Cannot run mutate-assemblies: no downsampled mutation-donor directory available (specify 'mutation_donor_input' in config or run downsample-gvcf first)")
                }

                val customOutput = config.mutate_assemblies.output?.let {
                    Path.of(it).toAbsolutePath().normalize()
                }
                val outputBase = (customOutput ?: workDir.resolve("output").resolve("05_mutate_assemblies_results"))
                    .toAbsolutePath().normalize()

                logger.info("Pairs keyfile: $keyfile")
                logger.info("Base gVCF dir: $baseInput")
                logger.info("Mutation-donor gVCF dir: $donorInput")

                val args = buildList {
                    add("--work-dir=$workDir")
                    add("--keyfile=$keyfile")
                    add("--base-dir=$baseInput")
                    add("--mutation-donor-dir=$donorInput")
                    add("--output-dir=$outputBase")
                }

                MutateAssemblies().parse(args)
                restoreOrchestratorLogging(workDir)

                if (!outputBase.exists()) {
                    throw RuntimeException("Expected mutated GVCF output directory not found: $outputBase")
                }

                mutatedGvcfDir = outputBase
                logger.info("Step 5 completed successfully")
                logger.info("")
            } else {
                if (config.mutate_assemblies != null) {
                    logger.info("Skipping mutate-assemblies (not in run_steps)")

                    val customOutput = config.mutate_assemblies.output?.let {
                        Path.of(it).toAbsolutePath().normalize()
                    }
                    val previousDir = (customOutput ?: workDir.resolve("output").resolve("05_mutate_assemblies_results"))
                        .toAbsolutePath().normalize()
                    if (previousDir.exists()) {
                        mutatedGvcfDir = previousDir
                        logger.info("Using previous mutate-assemblies outputs: $mutatedGvcfDir")
                    } else {
                        logger.warn("Previous mutate-assemblies outputs not found. Downstream steps may fail.")
                    }
                } else {
                    logger.info("Skipping mutate-assemblies (not configured)")
                }
                logger.info("")
            }

            // Step 6: Pick Crossovers on the base assemblies (if configured and should run)
            if (config.pick_crossovers != null && shouldRunStep("pick_crossovers", config)) {
                logger.info("=".repeat(80))
                logger.info("STEP 6: Pick Crossovers (base assemblies)")
                logger.info("=".repeat(80))

                // Reference FASTA (custom or from step 1)
                val pickRefFasta = config.pick_crossovers.ref_fasta?.let {
                    Path.of(it).toAbsolutePath().normalize()
                } ?: refFasta
                if (pickRefFasta == null) {
                    throw RuntimeException("Cannot run pick-crossovers: reference FASTA not available (specify 'ref_fasta' in pick_crossovers config or run align-assemblies first)")
                }

                // Original assembly FASTAs (custom or the align-assemblies query input)
                val queryFasta = config.pick_crossovers.query_fasta?.let {
                    Path.of(it).toAbsolutePath().normalize()
                } ?: config.align_assemblies?.query_fasta?.let {
                    Path.of(it).toAbsolutePath().normalize()
                }
                if (queryFasta == null) {
                    throw RuntimeException("Cannot run pick-crossovers: no query FASTA available (specify 'query_fasta' in pick_crossovers config or configure align_assemblies)")
                }

                // Base gVCFs (custom or from split-gvcfs base/ output)
                val baseInput = config.pick_crossovers.base_input?.let {
                    Path.of(it).toAbsolutePath().normalize()
                } ?: splitBaseDir
                if (baseInput == null) {
                    throw RuntimeException("Cannot run pick-crossovers: no base gVCF directory available (specify 'base_input' in config or run split-gvcfs first)")
                }

                val customOutput = config.pick_crossovers.output?.let {
                    Path.of(it).toAbsolutePath().normalize()
                }
                val outputBase = (customOutput ?: workDir.resolve("output").resolve("06_crossovers_results"))
                    .toAbsolutePath().normalize()

                logger.info("Reference FASTA: $pickRefFasta")
                logger.info("Query FASTA input: $queryFasta")
                logger.info("Base gVCF input: $baseInput")

                val args = buildList {
                    add("--work-dir=$workDir")
                    add("--ref-fasta=$pickRefFasta")
                    add("--query-fasta=$queryFasta")
                    add("--base-input=$baseInput")
                    if (customOutput != null) {
                        add("--output-dir=$customOutput")
                    }
                }

                PickBaseCrossovers().parse(args)
                restoreOrchestratorLogging(workDir)

                if (!outputBase.exists()) {
                    throw RuntimeException("Expected pick-crossovers output directory not found: $outputBase")
                }

                crossoverBedDir = outputBase
                logger.info("Step 6 completed successfully")
                logger.info("")
            } else {
                if (config.pick_crossovers != null) {
                    logger.info("Skipping pick-crossovers (not in run_steps)")

                    val customOutput = config.pick_crossovers.output?.let {
                        Path.of(it).toAbsolutePath().normalize()
                    }
                    val previousDir = (customOutput ?: workDir.resolve("output").resolve("06_crossovers_results"))
                        .toAbsolutePath().normalize()
                    if (previousDir.exists()) {
                        crossoverBedDir = previousDir
                        logger.info("Using previous pick-crossovers outputs: $crossoverBedDir")
                    } else {
                        logger.warn("Previous pick-crossovers outputs not found. Downstream steps may fail.")
                    }
                } else {
                    logger.info("Skipping pick-crossovers (not configured)")
                }
                logger.info("")
            }

            // Step 7: Recombine GVCFs (mutated base gVCFs + crossover BEDs -> recombined gVCFs)
            if (config.recombine_gvcfs != null && shouldRunStep("recombine_gvcfs", config)) {
                logger.info("=".repeat(80))
                logger.info("STEP 7: Recombine GVCFs")
                logger.info("=".repeat(80))

                // Reference FASTA (custom or from step 1)
                val recombineRefFasta = config.recombine_gvcfs.ref_file?.let {
                    Path.of(it).toAbsolutePath().normalize()
                } ?: refFasta
                if (recombineRefFasta == null) {
                    throw RuntimeException("Cannot run recombine-gvcfs: reference FASTA not available (specify 'ref_file' in recombine_gvcfs config or run align-assemblies first)")
                }

                // Crossover BED input (custom or from step 6)
                val inputBedDir = config.recombine_gvcfs.input_bed?.let {
                    Path.of(it).toAbsolutePath().normalize()
                } ?: crossoverBedDir
                if (inputBedDir == null) {
                    throw RuntimeException("Cannot run recombine-gvcfs: no crossover BED directory available (specify 'input_bed' in config or run pick-crossovers first)")
                }

                // Mutated base gVCF input (custom or from step 5)
                val inputGvcfDir = config.recombine_gvcfs.input_gvcf?.let {
                    Path.of(it).toAbsolutePath().normalize()
                } ?: mutatedGvcfDir
                if (inputGvcfDir == null) {
                    throw RuntimeException("Cannot run recombine-gvcfs: no mutated base gVCF directory available (specify 'input_gvcf' in config or run mutate-assemblies first)")
                }

                val customOutput = config.recombine_gvcfs.output?.let {
                    Path.of(it).toAbsolutePath().normalize()
                }
                val outputBase = (customOutput ?: workDir.resolve("output").resolve("07_recombine_gvcfs_results"))
                    .toAbsolutePath().normalize()
                val outputBedDir = config.recombine_gvcfs.output_bed?.let {
                    Path.of(it).toAbsolutePath().normalize()
                } ?: outputBase.resolve("resized_beds")

                // RecombineGvcfs keeps no auto dir-creation, so create the
                // output directories here before invoking it.
                outputBase.createDirectories()
                outputBedDir.createDirectories()

                logger.info("Reference FASTA: $recombineRefFasta")
                logger.info("Crossover BED input: $inputBedDir")
                logger.info("Mutated base gVCF input: $inputGvcfDir")

                val args = listOf(
                    "--input-bed-dir=$inputBedDir",
                    "--input-gvcf-dir=$inputGvcfDir",
                    "--ref-file=$recombineRefFasta",
                    "--output-dir=$outputBase",
                    "--output-bed-dir=$outputBedDir",
                )

                RecombineGvcfs().parse(args)
                restoreOrchestratorLogging(workDir)

                if (!outputBase.exists()) {
                    throw RuntimeException("Expected recombine-gvcfs output directory not found: $outputBase")
                }

                recombinedGvcfDir = outputBase
                logger.info("Step 7 completed successfully")
                logger.info("")
            } else {
                if (config.recombine_gvcfs != null) {
                    logger.info("Skipping recombine-gvcfs (not in run_steps)")

                    val customOutput = config.recombine_gvcfs.output?.let {
                        Path.of(it).toAbsolutePath().normalize()
                    }
                    val previousDir = (customOutput ?: workDir.resolve("output").resolve("07_recombine_gvcfs_results"))
                        .toAbsolutePath().normalize()
                    if (previousDir.exists()) {
                        recombinedGvcfDir = previousDir
                        logger.info("Using previous recombine-gvcfs outputs: $recombinedGvcfDir")
                    } else {
                        logger.warn("Previous recombine-gvcfs outputs not found. Downstream steps may fail.")
                    }
                } else {
                    logger.info("Skipping recombine-gvcfs (not configured)")
                }
                logger.info("")
            }

            // Step 8: Sort the recombined GVCFs with bcftools (if configured and should run)
            if (config.sort_gvcfs != null && shouldRunStep("sort_gvcfs", config)) {
                logger.info("=".repeat(80))
                logger.info("STEP 8: Sort GVCFs")
                logger.info("=".repeat(80))

                // Recombined gVCF input (custom or from step 7)
                val gvcfInput = config.sort_gvcfs.input?.let {
                    Path.of(it).toAbsolutePath().normalize()
                } ?: recombinedGvcfDir
                if (gvcfInput == null) {
                    throw RuntimeException("Cannot run sort-gvcfs: no recombined gVCF input available (specify 'input' in config or run recombine-gvcfs first)")
                }

                val customOutput = config.sort_gvcfs.output?.let {
                    Path.of(it).toAbsolutePath().normalize()
                }
                val outputBase = (customOutput ?: workDir.resolve("output").resolve("08_sort_gvcfs_results"))
                    .toAbsolutePath().normalize()

                logger.info("Recombined gVCF input: $gvcfInput")

                val args = buildList {
                    add("--work-dir=$workDir")
                    add("--gvcf-input=$gvcfInput")
                    add("--output-dir=$outputBase")
                    if (config.sort_gvcfs.threads != null) {
                        add("--threads=${config.sort_gvcfs.threads}")
                    }
                }

                SortGvcfs().parse(args)
                restoreOrchestratorLogging(workDir)

                if (!outputBase.exists()) {
                    throw RuntimeException("Expected sort-gvcfs output directory not found: $outputBase")
                }

                logger.info("Step 8 completed successfully")
                logger.info("")
            } else {
                if (config.sort_gvcfs != null) {
                    logger.info("Skipping sort-gvcfs (not in run_steps)")
                } else {
                    logger.info("Skipping sort-gvcfs (not configured)")
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
