package net.maizegenetics.commands

import biokotlin.seqIO.NucSeqIO
import com.github.ajalt.clikt.core.parse
import net.maizegenetics.commands.OrchestrateShared.FASTA_EXTENSION_PATTERN
import net.maizegenetics.commands.OrchestrateShared.FASTA_FILE_PATTERN
import net.maizegenetics.commands.OrchestrateShared.appendPhgAlignSharedArgs
import net.maizegenetics.commands.OrchestrateShared.shouldRunStep
import org.apache.logging.log4j.Logger
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.system.exitProcess

/**
 * v1 pipeline: the original full 15-step orchestration. This is the
 * default whenever the YAML config does not specify `version: "v2"`.
 *
 * Driven by [Orchestrate], which performs config parsing and environment
 * setup before delegating here. Shared step helpers live in
 * [OrchestrateShared].
 */
class OrchestrateV1(
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
        logger.info("Pipeline version: v1 (full pipeline)")
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

        // Pre-flight sanity check: pinning a single sample_name in step 11 while
        // step 12 is auto-generating its keyfile from FASTA basenames guarantees
        // a name mismatch between step-14 spline knots (keyed by VCF sample name)
        // and step-13 BED contigs (keyed by step-12 keyfile sample names). PHG's
        // convert-ropebwt2ps4g-file silently drops every record in that case, so
        // step 15 produces a 0-row PS4G. Warn loudly when both conditions hold.
        val step11SampleNamePinned =
            config.mutated_maf_to_gvcf?.sample_name != null &&
                shouldRunStep("mutated_maf_to_gvcf", config)
        val step12AutoKeyfile =
            config.rope_bwt_chr_index != null &&
                config.rope_bwt_chr_index.keyfile == null &&
                shouldRunStep("rope_bwt_chr_index", config)
        if (step11SampleNamePinned && step12AutoKeyfile) {
            logger.warn("=".repeat(80))
            logger.warn(
                "WARNING: mutated_maf_to_gvcf.sample_name is pinned to " +
                    "'${config.mutated_maf_to_gvcf!!.sample_name}', but rope_bwt_chr_index " +
                    "is auto-generating its keyfile from FASTA basenames. This will collapse " +
                    "every mutated gVCF into a single VCF sample, so step-14 spline knots will " +
                    "be keyed by '${config.mutated_maf_to_gvcf.sample_name}' while step-13 BED " +
                    "contigs will be keyed by FASTA basenames (e.g. '0', '1'). " +
                    "PHG convert-ropebwt2ps4g-file will then silently drop every record and " +
                    "step 15 will produce an empty PS4G."
            )
            logger.warn(
                "Recommended fix: omit mutated_maf_to_gvcf.sample_name so each gVCF is sampled " +
                    "by its MAF basename, which matches the auto-generated step-12 keyfile."
            )
            logger.warn("=".repeat(80))
        }

        // Track outputs between steps
        var mafFilePaths: Path? = null
        var gvcfOutputDir: Path? = null
        var downsampledGvcfOutputDir: Path? = null
        var fastaOutputDir: Path? = null
        var refFasta: Path? = null
        var refGff: Path? = null
        var assemblyListPath: Path? = null  // Assembly list from step 5 (pick_crossovers)
        var refkeyOutputDir: Path? = null
        var chainOutputDir: Path? = null
        var coordinatesOutputDir: Path? = null
        var recombinedFastasDir: Path? = null
        var formattedFastasDir: Path? = null
        var mutatedMafFilePaths: Path? = null  // MAF file paths from step 10 (align_mutated_assemblies)
        var mutatedGvcfOutputDir: Path? = null  // Mutated GVCF output directory from step 11
        var ropeBwtIndexDir: Path? = null  // RopeBWT index output directory from step 12
        var ropeBwtMemOutputDir: Path? = null  // BED output directory from step 13 (ropebwt_mem)
        var splineKnotsOutputDir: Path? = null  // Spline-knots output directory from step 14 (build_spline_knots)

        try {
            // Step 1: Align Assemblies (if configured and should run)
            if (config.align_assemblies != null && shouldRunStep("align_assemblies", config)) {
                logger.info("=".repeat(80))
                logger.info("STEP 1: Align Assemblies")
                logger.info("=".repeat(80))

                // Resolve all paths to absolute paths for consistency
                refFasta = Path.of(config.align_assemblies.ref_fasta).toAbsolutePath().normalize()
                refGff = Path.of(config.align_assemblies.ref_gff).toAbsolutePath().normalize()
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
                    refGff = Path.of(config.align_assemblies.ref_gff).toAbsolutePath().normalize()
                    
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
                // Check if step was skipped but outputs exist from previous run
                if (config.maf_to_gvcf != null) {
                    logger.info("Skipping maf-to-gvcf (not in run_steps)")

                    // Check custom output location first, then default
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

                DownsampleGvcf().parse(args)
                restoreOrchestratorLogging(workDir)

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
                    add("--work-dir=${workDir}")
                    add("--gvcf-file=${gvcfInput}")
                    add("--ref-fasta=${refFasta}")
                    if (config.convert_to_fasta.missing_records_as != null) {
                        add("--missing-records-as=${config.convert_to_fasta.missing_records_as}")
                    }
                    if (config.convert_to_fasta.missing_genotype_as != null) {
                        add("--missing-genotype-as=${config.convert_to_fasta.missing_genotype_as}")
                    }
                    if (!config.convert_to_fasta.ignore_contig.isNullOrEmpty()) {
                        add("--ignore-contig=${config.convert_to_fasta.ignore_contig}")
                    }
                    if (customOutput != null) {
                        add("--output-dir=${customOutput}")
                    }
                }

                ConvertToFasta().parse(args)
                restoreOrchestratorLogging(workDir)

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

            // Step 5: Pick Crossovers (if configured and should run)
            if (config.pick_crossovers != null && shouldRunStep("pick_crossovers", config)) {
                logger.info("=".repeat(80))
                logger.info("STEP 5: Pick Crossovers")
                logger.info("=".repeat(80))

                // Use pick_crossovers.ref_fasta if specified, otherwise use ref FASTA from step 1
                val pickCrossoversRefFasta = config.pick_crossovers.ref_fasta?.let { Path.of(it) } 
                    ?: refFasta
                if (pickCrossoversRefFasta == null) {
                    throw RuntimeException("Cannot run pick-crossovers: reference FASTA not available (specify 'ref_fasta' in pick_crossovers config or run align_assemblies first)")
                }

                // Determine assembly list (custom or auto-generated from step 4)
                val step6AssemblyList: Path = if (config.pick_crossovers.assembly_list != null) {
                    Path.of(config.pick_crossovers.assembly_list).toAbsolutePath().normalize()
                } else {
                    // Auto-generate assembly list from step 4 output (fastaOutputDir)
                    if (fastaOutputDir == null || !fastaOutputDir.exists()) {
                        throw RuntimeException("Cannot run pick-crossovers: no assembly_list provided and no FASTA output directory available from convert_to_fasta step")
                    }

                    // Get all FASTA files from step 4 output
                    val fastaFiles = fastaOutputDir.toFile().listFiles { file ->
                        file.isFile && file.name.matches(FASTA_FILE_PATTERN)
                    }?.map { it.toPath() }?.sorted() ?: emptyList()

                    if (fastaFiles.isEmpty()) {
                        throw RuntimeException("Cannot run pick-crossovers: no FASTA files found in $fastaOutputDir")
                    }

                    OrchestrateShared.writeAssemblyList(fastaFiles, fastaOutputDir, logger = logger)
                }

                OrchestrateShared.validateEvenAssemblyCount(step6AssemblyList, logger)

                // Save assembly list path for use in steps 8 and 9
                assemblyListPath = step6AssemblyList

                // Determine output directory (custom or default)
                val customOutput = config.pick_crossovers.output?.let { Path.of(it) }
                val pickCrossoversOutputDir = customOutput ?: workDir.resolve("output").resolve("05_crossovers_results")

                refkeyOutputDir = OrchestrateShared.runPickCrossovers(
                    workDir = workDir,
                    refFasta = pickCrossoversRefFasta,
                    assemblyList = step6AssemblyList,
                    outputDir = pickCrossoversOutputDir,
                    logger = logger,
                )

                logger.info("Step 5 completed successfully")
                logger.info("")
            } else {
                if (config.pick_crossovers != null) {
                    logger.info("Skipping pick-crossovers (not in run_steps)")

                    // Check custom output location first, then default
                    val customOutput = config.pick_crossovers.output?.let { Path.of(it) }
                    val previousRefkeyDir = customOutput ?: workDir.resolve("output").resolve("05_crossovers_results")
                    if (previousRefkeyDir.exists()) {
                        refkeyOutputDir = previousRefkeyDir
                        logger.info("Using previous pick-crossovers outputs: $refkeyOutputDir")
                    } else {
                        logger.warn("Previous pick-crossovers outputs not found. Downstream steps may fail.")
                    }

                    // Try to recover assembly list from config or auto-generated file
                    if (config.pick_crossovers.assembly_list != null) {
                        assemblyListPath = Path.of(config.pick_crossovers.assembly_list).toAbsolutePath().normalize()
                        logger.info("Using configured assembly list: $assemblyListPath")
                    } else if (fastaOutputDir != null) {
                        val autoGeneratedList = fastaOutputDir.resolve("auto_assembly_list.txt")
                        if (autoGeneratedList.exists()) {
                            assemblyListPath = autoGeneratedList
                            logger.info("Using auto-generated assembly list from previous run: $assemblyListPath")
                        }
                    }
                } else {
                    logger.info("Skipping pick-crossovers (not configured)")
                }
                logger.info("")
            }

            // Step 6: Create Chain Files (if configured and should run)
            if (config.create_chain_files != null && shouldRunStep("create_chain_files", config)) {
                logger.info("=".repeat(80))
                logger.info("STEP 6: Create Chain Files")
                logger.info("=".repeat(80))

                // Determine input (custom or step 1 MAF files)
                val mafInput = config.create_chain_files.maf_file_input?.let { Path.of(it) } 
                    ?: mafFilePaths
                if (mafInput == null) {
                    throw RuntimeException("Cannot run create-chain-files: no MAF input available (specify 'maf_file_input' in config or run align-assemblies first)")
                }
                logger.info("MAF input: $mafInput")

                // Determine output directory (custom or default)
                val customOutput = config.create_chain_files.output?.let { Path.of(it) }

                val args = buildList {
                    add("--work-dir=${workDir}")
                    add("--maf-input=${mafInput}")
                    if (config.create_chain_files.jobs != null) {
                        add("--jobs=${config.create_chain_files.jobs}")
                    }
                    if (customOutput != null) {
                        add("--output-dir=${customOutput}")
                    }
                }

                CreateChainFiles().parse(args)
                restoreOrchestratorLogging(workDir)

                // Get output directory (use custom or default)
                chainOutputDir = customOutput ?: workDir.resolve("output").resolve("06_chain_results")

                if (!chainOutputDir.exists()) {
                    throw RuntimeException("Expected chain output directory not found: $chainOutputDir")
                }

                logger.info("Step 6 completed successfully")
                logger.info("")
            } else {
                if (config.create_chain_files != null) {
                    logger.info("Skipping create-chain-files (not in run_steps)")

                    // Check custom output location first, then default
                    val customOutput = config.create_chain_files.output?.let { Path.of(it) }
                    val previousChainDir = customOutput ?: workDir.resolve("output").resolve("06_chain_results")
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

            // Step 7: Convert Coordinates (if configured and should run)
            if (config.convert_coordinates != null && shouldRunStep("convert_coordinates", config)) {
                logger.info("=".repeat(80))
                logger.info("STEP 7: Convert Coordinates")
                logger.info("=".repeat(80))

                // Determine chain input (custom or from previous step)
                val chainInput = config.convert_coordinates.input_chain?.let { Path.of(it) } ?: chainOutputDir
                if (chainInput == null) {
                    throw RuntimeException("Cannot run convert-coordinates: no chain input available (specify 'input_chain' in config or run create-chain-files first)")
                }

                // Determine refkey input (custom or from previous step)
                val refkeyInput = config.convert_coordinates.input_refkey?.let { Path.of(it) } ?: refkeyOutputDir

                // Determine assembly list (custom or from step 6)
                val step8AssemblyList = config.convert_coordinates.assembly_list?.let { 
                    Path.of(it).toAbsolutePath().normalize() 
                } ?: assemblyListPath
                if (step8AssemblyList == null) {
                    throw RuntimeException("Cannot run convert-coordinates: no assembly_list available (specify 'assembly_list' in config or run pick-crossovers first)")
                }
                logger.info("Assembly list: $step8AssemblyList")

                // Determine output directory (custom or default)
                val customOutput = config.convert_coordinates.output?.let { Path.of(it) }

                val args = buildList {
                    add("--work-dir=${workDir}")
                    add("--assembly-list=${step8AssemblyList}")
                    add("--chain-dir=${chainInput}")
                    if (refkeyInput != null) {
                        add("--refkey-dir=${refkeyInput}")
                    }
                    if (customOutput != null) {
                        add("--output-dir=${customOutput}")
                    }
                }

                ConvertCoordinates().parse(args)
                restoreOrchestratorLogging(workDir)

                // Get output directory (use custom or default)
                coordinatesOutputDir = customOutput ?: workDir.resolve("output").resolve("07_coordinates_results")

                if (!coordinatesOutputDir.exists()) {
                    throw RuntimeException("Expected coordinates output directory not found: $coordinatesOutputDir")
                }

                logger.info("Step 7 completed successfully")
                logger.info("")
            } else {
                if (config.convert_coordinates != null) {
                    logger.info("Skipping convert-coordinates (not in run_steps)")

                    // Check custom output location first, then default
                    val customOutput = config.convert_coordinates.output?.let { Path.of(it) }
                    val previousCoordsDir = customOutput ?: workDir.resolve("output").resolve("07_coordinates_results")
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

            // Step 8: Generate Recombined Sequences (if configured and should run)
            if (config.generate_recombined_sequences != null && shouldRunStep("generate_recombined_sequences", config)) {
                logger.info("=".repeat(80))
                logger.info("STEP 8: Generate Recombined Sequences")
                logger.info("=".repeat(80))

                // Use founder key input from previous step (convert_coordinates)
                if (coordinatesOutputDir == null) {
                    throw RuntimeException("Cannot run generate-recombined-sequences: no founder key input available (run convert-coordinates first)")
                }
                logger.info("Founder key directory: $coordinatesOutputDir")

                // Determine assembly list (custom or from step 6)
                val step9AssemblyList = config.generate_recombined_sequences.assembly_list?.let { 
                    Path.of(it).toAbsolutePath().normalize() 
                } ?: assemblyListPath
                if (step9AssemblyList == null) {
                    throw RuntimeException("Cannot run generate-recombined-sequences: no assembly_list available (specify 'assembly_list' in config or run pick-crossovers first)")
                }
                logger.info("Assembly list: $step9AssemblyList")

                // Determine chromosome list (custom or auto-derived from first assembly)
                val step9ChromosomeList: Path = if (config.generate_recombined_sequences.chromosome_list != null) {
                    Path.of(config.generate_recombined_sequences.chromosome_list).toAbsolutePath().normalize()
                } else {
                    // Auto-derive chromosome list from the first assembly in the assembly list
                    val firstLine = step9AssemblyList.readLines().firstOrNull { it.isNotBlank() }
                        ?: throw RuntimeException("Cannot run generate-recombined-sequences: assembly list is empty")
                    
                    // Assembly list format: path<TAB>name - extract the path (first column)
                    val firstAssemblyPath = firstLine.split("\t").firstOrNull()?.trim()
                        ?: throw RuntimeException("Cannot run generate-recombined-sequences: invalid assembly list format")
                    
                    logger.info("Auto-deriving chromosome list from first assembly: $firstAssemblyPath")
                    
                    // Use BioKotlin to read the FASTA and extract chromosome IDs
                    val seq = NucSeqIO(firstAssemblyPath).readAll()
                    val chromosomeIds = seq.keys.toList()
                    
                    if (chromosomeIds.isEmpty()) {
                        throw RuntimeException("Cannot run generate-recombined-sequences: no chromosomes found in $firstAssemblyPath")
                    }
                    
                    // Write chromosome list to a temporary file
                    val chromosomeListFile = workDir.resolve("output").resolve("08_recombined_sequences").resolve("auto_chromosome_list.txt")
                    chromosomeListFile.parent.createDirectories()
                    chromosomeListFile.writeText(chromosomeIds.joinToString("\n"))
                    logger.info("Auto-generated chromosome list: $chromosomeListFile")
                    logger.info("  Contains ${chromosomeIds.size} chromosomes: ${chromosomeIds.take(5).joinToString(", ")}${if (chromosomeIds.size > 5) ", ..." else ""}")
                    
                    chromosomeListFile
                }

                // Determine output directory (step 8 default output)
                val outputBase = workDir.resolve("output").resolve("08_recombined_sequences")

                // Determine assembly directory (custom or from step 4 FASTA output)
                // The Python script needs to read parent FASTA files which are in the FASTA output directory
                val step9AssemblyDir = config.generate_recombined_sequences.assembly_dir?.let {
                    Path.of(it).toAbsolutePath().normalize()
                } ?: fastaOutputDir ?: throw RuntimeException("Cannot run generate-recombined-sequences: no assembly directory available (specify 'assembly_dir' in config or run convert-to-fasta first)")
                logger.info("Assembly directory: $step9AssemblyDir")

                val args = buildList {
                    add("--work-dir=${workDir}")
                    add("--assembly-list=${step9AssemblyList}")
                    add("--chromosome-list=${step9ChromosomeList}")
                    add("--assembly-dir=${step9AssemblyDir}")
                    add("--founder-key-dir=${coordinatesOutputDir}")
                }

                GenerateRecombinedSequences().parse(args)
                restoreOrchestratorLogging(workDir)

                // Get output directory
                recombinedFastasDir = outputBase.resolve("recombinate_fastas")

                logger.info("Step 8 completed successfully")
                logger.info("")
            } else {
                if (config.generate_recombined_sequences != null) {
                    logger.info("Skipping generate-recombined-sequences (not in run_steps)")

                    // Check default output location
                    val outputBase = workDir.resolve("output").resolve("08_recombined_sequences")
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

            // Step 9: Format Recombined Fastas (if configured and should run)
            if (config.format_recombined_fastas != null && shouldRunStep("format_recombined_fastas", config)) {
                logger.info("=".repeat(80))
                logger.info("STEP 9: Format Recombined Fastas")
                logger.info("=".repeat(80))

                // Determine input (custom or from previous step)
                val fastaInput = config.format_recombined_fastas.input?.let { Path.of(it) } ?: recombinedFastasDir
                if (fastaInput == null) {
                    throw RuntimeException("Cannot run format-recombined-fastas: no FASTA input available (specify 'input' in config or run generate-recombined-sequences first)")
                }

                // Determine output directory (custom or default)
                val customOutput = config.format_recombined_fastas.output?.let { Path.of(it) }

                val args = buildList {
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

                FormatRecombinedFastas().parse(args)
                restoreOrchestratorLogging(workDir)

                // Get output directory (use custom or default)
                formattedFastasDir = customOutput ?: workDir.resolve("output").resolve("09_formatted_fastas")

                logger.info("Step 9 completed successfully")
                logger.info("")
            } else {
                if (config.format_recombined_fastas != null) {
                    logger.info("Skipping format-recombined-fastas (not in run_steps)")

                    // Check custom output location first, then default
                    val customOutput = config.format_recombined_fastas.output?.let { Path.of(it) }
                    val previousFormattedDir = customOutput ?: workDir.resolve("output").resolve("09_formatted_fastas")
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

            // Step 10: Align Mutated Assemblies (if configured and should run)
            if (config.align_mutated_assemblies != null && shouldRunStep("align_mutated_assemblies", config)) {
                logger.info("=".repeat(80))
                logger.info("STEP 10: Align Mutated Assemblies")
                logger.info("=".repeat(80))

                // Determine ref_gff (config value or from step 1)
                val step10RefGff = config.align_mutated_assemblies.ref_gff?.let { Path.of(it) } ?: refGff
                if (step10RefGff == null) {
                    throw RuntimeException("Cannot run align-mutated-assemblies: reference GFF not available (specify 'ref_gff' in config or run align_assemblies first)")
                }

                // Determine ref_fasta (config value or from step 1)
                val step10RefFasta = config.align_mutated_assemblies.ref_fasta?.let { Path.of(it) } ?: refFasta
                if (step10RefFasta == null) {
                    throw RuntimeException("Cannot run align-mutated-assemblies: reference FASTA not available (specify 'ref_fasta' in config or run align_assemblies first)")
                }

                // Determine fasta_input (config value or from format_recombined_fastas output)
                val step10FastaInput = config.align_mutated_assemblies.fasta_input?.let { Path.of(it) } ?: formattedFastasDir
                if (step10FastaInput == null) {
                    throw RuntimeException("Cannot run align-mutated-assemblies: no FASTA input available (specify 'fasta_input' in config or run format-recombined-fastas first)")
                }

                logger.info("Reference GFF: $step10RefGff")
                logger.info("Reference FASTA: $step10RefFasta")
                logger.info("FASTA input: $step10FastaInput")

                // Determine output directory (custom or default)
                val customOutput = config.align_mutated_assemblies.output?.let { Path.of(it) }

                val args = mutableListOf(
                    "--work-dir=$workDir",
                    "--ref-gff=$step10RefGff",
                    "--ref-fasta=$step10RefFasta",
                    "--fasta-input=$step10FastaInput",
                )
                appendPhgAlignSharedArgs(
                    args,
                    threads = config.align_mutated_assemblies.threads,
                    inParallel = config.align_mutated_assemblies.in_parallel,
                    refMaxAlignCov = config.align_mutated_assemblies.ref_max_align_cov,
                    queryMaxAlignCov = config.align_mutated_assemblies.query_max_align_cov,
                    condaEnvPrefix = config.align_mutated_assemblies.conda_env_prefix,
                    justRefPrep = config.align_mutated_assemblies.just_ref_prep,
                    customOutput = customOutput,
                )

                AlignMutatedAssemblies().parse(args)
                restoreOrchestratorLogging(workDir)

                // Get output path (use custom or default)
                val outputBase = customOutput ?: workDir.resolve("output").resolve("10_mutated_alignment_results")
                mutatedMafFilePaths = outputBase.toAbsolutePath().normalize().resolve("maf_file_paths.txt")

                if (!mutatedMafFilePaths.exists()) {
                    throw RuntimeException("Expected MAF paths file not found: $mutatedMafFilePaths")
                }

                logger.info("Step 10 completed successfully")
                logger.info("")
            } else {
                if (config.align_mutated_assemblies != null) {
                    logger.info("Skipping align-mutated-assemblies (not in run_steps)")

                    // Check custom output location first, then default
                    val customOutput = config.align_mutated_assemblies.output?.let { 
                        Path.of(it).toAbsolutePath().normalize() 
                    }
                    val outputBase = (customOutput ?: workDir.resolve("output").resolve("10_mutated_alignment_results"))
                        .toAbsolutePath().normalize()
                    val previousMafPaths = outputBase.resolve("maf_file_paths.txt")

                    if (previousMafPaths.exists()) {
                        mutatedMafFilePaths = previousMafPaths
                        logger.info("Using previous align-mutated-assemblies outputs: $mutatedMafFilePaths")
                    } else {
                        logger.warn("Previous align-mutated-assemblies outputs not found. Downstream steps may fail.")
                    }
                } else {
                    logger.info("Skipping align-mutated-assemblies (not configured)")
                }
                logger.info("")
            }

            // Step 11: Mutated MAF to GVCF (if configured and should run)
            if (config.mutated_maf_to_gvcf != null && shouldRunStep("mutated_maf_to_gvcf", config)) {
                logger.info("=".repeat(80))
                logger.info("STEP 11: Mutated MAF to GVCF Conversion")
                logger.info("=".repeat(80))

                // Determine reference file (custom or from step 1) - resolve to absolute path
                val step11RefFasta = config.mutated_maf_to_gvcf.reference_file?.let { 
                    Path.of(it).toAbsolutePath().normalize() 
                } ?: refFasta
                if (step11RefFasta == null) {
                    throw RuntimeException("Cannot run mutated-maf-to-gvcf: reference FASTA not available (specify 'reference_file' in config or run align-assemblies first)")
                }

                // Determine MAF input (custom or from step 10) - resolve to absolute path
                val mafInput = config.mutated_maf_to_gvcf.maf_file?.let { 
                    Path.of(it).toAbsolutePath().normalize() 
                } ?: mutatedMafFilePaths
                if (mafInput == null) {
                    throw RuntimeException("Cannot run mutated-maf-to-gvcf: no MAF input available (specify 'maf_file' in config or run align-mutated-assemblies first)")
                }

                // Determine output directory (custom or default) - resolve to absolute path
                // Always use step 11 output directory by default (not MafToGvcf's default)
                val step11OutputDir = (config.mutated_maf_to_gvcf.output_dir?.let { 
                    Path.of(it).toAbsolutePath().normalize() 
                } ?: workDir.resolve("output").resolve("11_mutated_gvcf_results"))
                    .toAbsolutePath().normalize()
                mutatedGvcfOutputDir = step11OutputDir

                // Determine output file if specified - resolve to absolute path
                val outputFile = config.mutated_maf_to_gvcf.output_file?.let { 
                    Path.of(it).toAbsolutePath().normalize() 
                }

                logger.info("Reference FASTA: $step11RefFasta")
                logger.info("MAF input: $mafInput")
                logger.info("Output directory: $step11OutputDir")

                val args = buildList {
                    add("--work-dir=$workDir")
                    add("--reference-file=$step11RefFasta")
                    add("--maf-file=$mafInput")
                    add("--output-dir=$step11OutputDir")  // Always pass output dir to ensure step 11 location
                    if (outputFile != null) {
                        add("--output-file=$outputFile")
                    }
                    if (config.mutated_maf_to_gvcf.sample_name != null) {
                        add("--sample-name=${config.mutated_maf_to_gvcf.sample_name}")
                    }
                }

                MafToGvcf().parse(args)
                restoreOrchestratorLogging(workDir)

                if (!step11OutputDir.exists()) {
                    throw RuntimeException("Expected mutated GVCF output directory not found: $step11OutputDir")
                }

                logger.info("Step 11 completed successfully")
                logger.info("")
            } else {
                if (config.mutated_maf_to_gvcf != null) {
                    logger.info("Skipping mutated-maf-to-gvcf (not in run_steps)")

                    // Check custom output location first, then default
                    val previousMutatedGvcfDir = (config.mutated_maf_to_gvcf.output_dir?.let { 
                        Path.of(it).toAbsolutePath().normalize() 
                    } ?: workDir.resolve("output").resolve("11_mutated_gvcf_results"))
                        .toAbsolutePath().normalize()
                    if (previousMutatedGvcfDir.exists()) {
                        mutatedGvcfOutputDir = previousMutatedGvcfDir
                        logger.info("Using previous mutated-maf-to-gvcf outputs: $mutatedGvcfOutputDir")
                    } else {
                        logger.warn("Previous mutated-maf-to-gvcf outputs not found. Downstream steps may fail.")
                    }
                } else {
                    logger.info("Skipping mutated-maf-to-gvcf (not configured)")
                }
                logger.info("")
            }

            // Step 12: RopeBWT Chr Index (if configured and should run)
            if (config.rope_bwt_chr_index != null && shouldRunStep("rope_bwt_chr_index", config)) {
                logger.info("=".repeat(80))
                logger.info("STEP 12: RopeBWT Chr Index")
                logger.info("=".repeat(80))

                // Determine output directory (custom or default) - resolve to absolute path
                val customOutput = config.rope_bwt_chr_index.output?.let { 
                    Path.of(it).toAbsolutePath().normalize() 
                }
                val outputBase = customOutput ?: workDir.resolve("output").resolve("12_rope_bwt_index_results")
                outputBase.createDirectories()

                // Determine keyfile - either provided or auto-generated from format_recombined_fastas output
                val actualKeyfile: Path = if (config.rope_bwt_chr_index.keyfile != null) {
                    // Use provided keyfile
                    val keyfilePath = Path.of(config.rope_bwt_chr_index.keyfile).toAbsolutePath().normalize()
                    logger.info("Using provided keyfile: $keyfilePath")
                    keyfilePath
                } else {
                    // Auto-generate keyfile from format_recombined_fastas output
                    if (formattedFastasDir == null || !formattedFastasDir.exists()) {
                        throw RuntimeException("Cannot run rope-bwt-chr-index: no FASTA input available (specify 'keyfile' in config or run format-recombined-fastas first)")
                    }
                    
                    logger.info("Auto-generating keyfile from formatted FASTA files in: $formattedFastasDir")
                    
                    // Collect FASTA files
                    val fastaFiles = formattedFastasDir.toFile().listFiles { file ->
                        file.isFile && file.name.matches(FASTA_FILE_PATTERN)
                    }?.map { it.toPath() }?.sorted() ?: emptyList()
                    
                    if (fastaFiles.isEmpty()) {
                        throw RuntimeException("Cannot run rope-bwt-chr-index: no FASTA files found in $formattedFastasDir")
                    }
                    
                    logger.info("Found ${fastaFiles.size} FASTA files")
                    
                    // Generate keyfile with sample names derived from filenames (no header)
                    val keyfilePath = outputBase.resolve("phg_keyfile.txt")
                    val keyfileLines = mutableListOf<String>()
                    val renamedSamples = mutableListOf<Pair<String, String>>()  // original -> fixed
                    
                    fastaFiles.forEach { fastaFile ->
                        var sampleName = fastaFile.fileName.toString()
                            .replace(FASTA_EXTENSION_PATTERN, "")
                        
                        // Replace underscores with hyphens and warn
                        if (sampleName.contains("_")) {
                            val originalName = sampleName
                            sampleName = sampleName.replace("_", "-")
                            renamedSamples.add(Pair(originalName, sampleName))
                        }
                        
                        keyfileLines.add("${fastaFile.toAbsolutePath()}\t$sampleName")
                    }
                    
                    // Write keyfile
                    keyfilePath.writeText(keyfileLines.joinToString("\n"))
                    logger.info("Generated keyfile: $keyfilePath")
                    
                    // Warn about renamed samples
                    if (renamedSamples.isNotEmpty()) {
                        logger.warn("WARNING: The following sample names contained underscores and were converted to hyphens:")
                        renamedSamples.forEach { (original, fixed) ->
                            logger.warn("  '$original' -> '$fixed'")
                        }
                        logger.warn("PHG uses underscores internally for contig renaming (format: samplename_contig)")
                    }
                    
                    keyfilePath
                }

                logger.info("Keyfile: $actualKeyfile")

                val args = buildList {
                    add("--work-dir=$workDir")
                    add("--keyfile=$actualKeyfile")
                    if (config.rope_bwt_chr_index.index_file_prefix != null) {
                        add("--index-file-prefix=${config.rope_bwt_chr_index.index_file_prefix}")
                    }
                    if (config.rope_bwt_chr_index.threads != null) {
                        add("--threads=${config.rope_bwt_chr_index.threads}")
                    }
                    // delete-fmr-index is a presence flag (include only when true)
                    if (config.rope_bwt_chr_index.delete_fmr_index == true) {
                        add("--delete-fmr-index")
                    }
                    if (customOutput != null) {
                        add("--output-dir=$customOutput")
                    }
                }

                RopeBwtChrIndex().parse(args)
                restoreOrchestratorLogging(workDir)

                // Track output directory
                ropeBwtIndexDir = outputBase

                if (!ropeBwtIndexDir.exists()) {
                    throw RuntimeException("Expected RopeBWT index output directory not found: $ropeBwtIndexDir")
                }

                logger.info("Step 12 completed successfully")
                logger.info("")
            } else {
                if (config.rope_bwt_chr_index != null) {
                    logger.info("Skipping rope-bwt-chr-index (not in run_steps)")

                    // Check custom output location first, then default
                    val customOutput = config.rope_bwt_chr_index.output?.let { 
                        Path.of(it).toAbsolutePath().normalize() 
                    }
                    val previousIndexDir = (customOutput ?: workDir.resolve("output").resolve("12_rope_bwt_index_results"))
                        .toAbsolutePath().normalize()
                    if (previousIndexDir.exists()) {
                        ropeBwtIndexDir = previousIndexDir
                        logger.info("Using previous rope-bwt-chr-index outputs: $ropeBwtIndexDir")
                    } else {
                        logger.warn("Previous rope-bwt-chr-index outputs not found. Downstream steps may fail.")
                    }
                } else {
                    logger.info("Skipping rope-bwt-chr-index (not configured)")
                }
                logger.info("")
            }

            // Step 13: RopeBWT MEM Alignment (if configured and should run)
            if (config.ropebwt_mem != null && shouldRunStep("ropebwt_mem", config)) {
                logger.info("=".repeat(80))
                logger.info("STEP 13: RopeBWT MEM Alignment")
                logger.info("=".repeat(80))

                // fastq_input is required when this step is configured
                val fastqInput = Path.of(config.ropebwt_mem.fastq_input).toAbsolutePath().normalize()
                if (!fastqInput.exists()) {
                    throw RuntimeException("Cannot run ropebwt-mem: FASTQ input not found at $fastqInput")
                }

                // Resolve optional index_file override (defaults to .fmd discovered in step 12 output by RopeBwtMem itself)
                val indexFileOverride = config.ropebwt_mem.index_file?.let { 
                    Path.of(it).toAbsolutePath().normalize() 
                }

                // Determine output directory (custom or default) - resolve to absolute path
                val customOutput = config.ropebwt_mem.output?.let { 
                    Path.of(it).toAbsolutePath().normalize() 
                }
                val step13OutputDir = (customOutput ?: workDir.resolve("output").resolve("13_ropebwt_mem_results"))
                    .toAbsolutePath().normalize()

                logger.info("FASTQ input: $fastqInput")
                if (indexFileOverride != null) {
                    logger.info("Index file (override): $indexFileOverride")
                } else if (ropeBwtIndexDir != null) {
                    logger.info("Index will be auto-detected from step 12 output: $ropeBwtIndexDir")
                }

                val args = buildList {
                    add("--work-dir=$workDir")
                    add("--fastq-input=$fastqInput")
                    if (indexFileOverride != null) {
                        add("--index-file=$indexFileOverride")
                    }
                    if (config.ropebwt_mem.l_value != null) {
                        add("--l-value=${config.ropebwt_mem.l_value}")
                    }
                    if (config.ropebwt_mem.p_value != null) {
                        add("--p-value=${config.ropebwt_mem.p_value}")
                    }
                    if (config.ropebwt_mem.threads != null) {
                        add("--threads=${config.ropebwt_mem.threads}")
                    }
                    if (customOutput != null) {
                        add("--output-dir=$customOutput")
                    }
                }

                RopeBwtMem().parse(args)
                restoreOrchestratorLogging(workDir)

                ropeBwtMemOutputDir = step13OutputDir

                if (!ropeBwtMemOutputDir.exists()) {
                    throw RuntimeException("Expected ropebwt-mem output directory not found: $ropeBwtMemOutputDir")
                }

                logger.info("Step 13 completed successfully")
                logger.info("")
            } else {
                if (config.ropebwt_mem != null) {
                    logger.info("Skipping ropebwt-mem (not in run_steps)")

                    val customOutput = config.ropebwt_mem.output?.let { 
                        Path.of(it).toAbsolutePath().normalize() 
                    }
                    val previousMemDir = (customOutput ?: workDir.resolve("output").resolve("13_ropebwt_mem_results"))
                        .toAbsolutePath().normalize()
                    if (previousMemDir.exists()) {
                        ropeBwtMemOutputDir = previousMemDir
                        logger.info("Using previous ropebwt-mem outputs: $ropeBwtMemOutputDir")
                    } else {
                        logger.warn("Previous ropebwt-mem outputs not found. Downstream steps may fail.")
                    }
                } else {
                    logger.info("Skipping ropebwt-mem (not configured)")
                }
                logger.info("")
            }

            // Step 14: Build Spline Knots (if configured and should run)
            if (config.build_spline_knots != null && shouldRunStep("build_spline_knots", config)) {
                logger.info("=".repeat(80))
                logger.info("STEP 14: Build Spline Knots")
                logger.info("=".repeat(80))

                // Determine VCF input directory. Prefer config.vcf_dir; otherwise
                // chain from step 11 (mutated GVCFs) when available.
                val vcfDir = config.build_spline_knots.vcf_dir?.let {
                    Path.of(it).toAbsolutePath().normalize()
                } ?: mutatedGvcfOutputDir
                if (vcfDir == null) {
                    throw RuntimeException("Cannot run build-spline-knots: no VCF input available (specify 'vcf_dir' in config or run mutated-maf-to-gvcf first)")
                }
                if (!vcfDir.exists()) {
                    throw RuntimeException("Cannot run build-spline-knots: VCF input directory not found at $vcfDir")
                }
                logger.info("VCF directory: $vcfDir")

                // Default to "gvcf" when chaining from step 11 (which produces gVCFs)
                // and the user hasn't pinned a vcf_type explicitly.
                val vcfType = config.build_spline_knots.vcf_type
                    ?: if (config.build_spline_knots.vcf_dir == null) "gvcf" else null
                if (vcfType != null) {
                    logger.info("VCF type: $vcfType")
                }

                // Determine output directory (custom or default)
                val customOutput = config.build_spline_knots.output?.let {
                    Path.of(it).toAbsolutePath().normalize()
                }
                val step14OutputDir = (customOutput ?: workDir.resolve("output").resolve("14_spline_knots_results"))
                    .toAbsolutePath().normalize()

                val args = buildList {
                    add("--work-dir=$workDir")
                    add("--vcf-dir=$vcfDir")
                    if (vcfType != null) {
                        add("--vcf-type=$vcfType")
                    }
                    if (config.build_spline_knots.min_indel_length != null) {
                        add("--min-indel-length=${config.build_spline_knots.min_indel_length}")
                    }
                    if (config.build_spline_knots.num_bps_per_knot != null) {
                        add("--num-bps-per-knot=${config.build_spline_knots.num_bps_per_knot}")
                    }
                    if (config.build_spline_knots.contig_list != null) {
                        add("--contig-list=${config.build_spline_knots.contig_list}")
                    }
                    if (config.build_spline_knots.random_seed != null) {
                        add("--random-seed=${config.build_spline_knots.random_seed}")
                    }
                    if (customOutput != null) {
                        add("--output-dir=$customOutput")
                    }
                }

                BuildSplineKnots().parse(args)
                restoreOrchestratorLogging(workDir)

                splineKnotsOutputDir = step14OutputDir

                if (!splineKnotsOutputDir.exists()) {
                    throw RuntimeException("Expected build-spline-knots output directory not found: $splineKnotsOutputDir")
                }

                logger.info("Step 14 completed successfully")
                logger.info("")
            } else {
                if (config.build_spline_knots != null) {
                    logger.info("Skipping build-spline-knots (not in run_steps)")

                    val customOutput = config.build_spline_knots.output?.let {
                        Path.of(it).toAbsolutePath().normalize()
                    }
                    val previousSplineDir = (customOutput ?: workDir.resolve("output").resolve("14_spline_knots_results"))
                        .toAbsolutePath().normalize()
                    if (previousSplineDir.exists()) {
                        splineKnotsOutputDir = previousSplineDir
                        logger.info("Using previous build-spline-knots outputs: $splineKnotsOutputDir")
                    } else {
                        logger.warn("Previous build-spline-knots outputs not found. Downstream steps may fail.")
                    }
                } else {
                    logger.info("Skipping build-spline-knots (not configured)")
                }
                logger.info("")
            }

            // Step 15: Convert RopeBWT to PS4G (if configured and should run)
            if (config.convert_ropebwt2ps4g != null && shouldRunStep("convert_ropebwt2ps4g", config)) {
                logger.info("=".repeat(80))
                logger.info("STEP 15: Convert RopeBWT to PS4G")
                logger.info("=".repeat(80))

                // BED input: explicit override, else chain from step 13
                val bedInput = config.convert_ropebwt2ps4g.bed_input?.let {
                    Path.of(it).toAbsolutePath().normalize()
                } ?: ropeBwtMemOutputDir
                if (bedInput == null) {
                    throw RuntimeException("Cannot run convert-ropebwt2ps4g: no BED input available (specify 'bed_input' in config or run ropebwt-mem first)")
                }
                logger.info("BED input: $bedInput")

                // Spline knots: explicit override, else chain from step 14
                val splineKnotDir = config.convert_ropebwt2ps4g.spline_knot_dir?.let {
                    Path.of(it).toAbsolutePath().normalize()
                } ?: splineKnotsOutputDir
                if (splineKnotDir == null) {
                    throw RuntimeException("Cannot run convert-ropebwt2ps4g: no spline-knot directory available (specify 'spline_knot_dir' in config or run build-spline-knots first)")
                }
                logger.info("Spline knot directory: $splineKnotDir")

                // Determine output directory (custom or default)
                val customOutput = config.convert_ropebwt2ps4g.output?.let {
                    Path.of(it).toAbsolutePath().normalize()
                }
                val step15OutputDir = (customOutput ?: workDir.resolve("output").resolve("15_convert_ropebwt2ps4g_results"))
                    .toAbsolutePath().normalize()

                val args = buildList {
                    add("--work-dir=$workDir")
                    add("--bed-input=$bedInput")
                    add("--spline-knot-dir=$splineKnotDir")
                    if (config.convert_ropebwt2ps4g.min_mem_length != null) {
                        add("--min-mem-length=${config.convert_ropebwt2ps4g.min_mem_length}")
                    }
                    if (config.convert_ropebwt2ps4g.max_num_hits != null) {
                        add("--max-num-hits=${config.convert_ropebwt2ps4g.max_num_hits}")
                    }
                    if (customOutput != null) {
                        add("--output-dir=$customOutput")
                    }
                }

                ConvertRopebwt2Ps4g().parse(args)
                restoreOrchestratorLogging(workDir)

                if (!step15OutputDir.exists()) {
                    throw RuntimeException("Expected convert-ropebwt2ps4g output directory not found: $step15OutputDir")
                }

                logger.info("Step 15 completed successfully")
                logger.info("")
            } else {
                if (config.convert_ropebwt2ps4g != null) {
                    logger.info("Skipping convert-ropebwt2ps4g (not in run_steps)")
                } else {
                    logger.info("Skipping convert-ropebwt2ps4g (not configured)")
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
