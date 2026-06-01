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
                val gvcfOutputDir = (customOutputDir ?: workDir.resolve("output").resolve("02_gvcf_results"))
                    .toAbsolutePath().normalize()

                if (!gvcfOutputDir.exists()) {
                    throw RuntimeException("Expected GVCF output directory not found: $gvcfOutputDir")
                }

                logger.info("Step 2 completed successfully")
                logger.info("")
            } else {
                if (config.maf_to_gvcf != null) {
                    logger.info("Skipping maf-to-gvcf (not in run_steps)")
                } else {
                    logger.info("Skipping maf-to-gvcf (not configured)")
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
