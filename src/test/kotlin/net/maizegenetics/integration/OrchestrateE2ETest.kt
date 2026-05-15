package net.maizegenetics.integration

import com.github.ajalt.clikt.core.parse
import net.maizegenetics.commands.Orchestrate
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.assertTrue

/**
 * End-to-end smoke test: run `orchestrate` against the smallseq test
 * pipeline and assert that the expected outputs are produced.
 *
 * Only runs inside the seq-sim-dev container (SEQ_SIM_IN_CONTAINER=1).
 */
@Tag("e2e")
class OrchestrateE2ETest {

    private val smallseqRoot: Path = File("src/test/resources/smallseq")
        .absoluteFile.toPath()

    /**
     * Create (or reset) a stable, inspectable working directory under
     * `build/test-output/` for a long-form E2E test. We deliberately avoid
     * [TempDir] here so that intermediate pipeline outputs survive the
     * test for post-mortem inspection. The directory is wiped on each run
     * to keep the test hermetic, and `./gradlew clean` removes it.
     */
    @OptIn(ExperimentalPathApi::class)
    private fun persistentWorkDir(testName: String): Path {
        val workDir = File("build/test-output/$testName").absoluteFile.toPath()
        if (workDir.exists()) {
            workDir.deleteRecursively()
        }
        workDir.createDirectories()
        return workDir
    }

    @Test
    fun orchestrateSmallseqPipelineProducesMafAndGvcf(@TempDir workDir: Path) {
        // align-assemblies now drives PHGv2 internally, so we need both the
        // phg binary and AnchorWave on PATH. The orchestrator's setup-environment
        // step takes care of populating <workDir>/src/phg_v2 from SEQ_SIM_PHG_DIR.
        IntegrationGuard.requirePhg()
        IntegrationGuard.requireAnchorwave()

        workDir.createDirectories()

        // Write a work-dir-local pipeline config pointing at the smallseq resources.
        val configPath = workDir.resolve("pipeline.yaml")
        configPath.writeText(
            """
            work_dir: "${workDir.toString()}"

            run_steps:
              - align_assemblies
              - maf_to_gvcf

            align_assemblies:
              ref_gff: "${smallseqRoot.resolve("anchors.gff")}"
              ref_fasta: "${smallseqRoot.resolve("Ref.fa")}"
              query_fasta: "${smallseqRoot.resolve("queries")}"
              threads: 2

            maf_to_gvcf:
              sample_name: "smallseq"
            """.trimIndent()
        )

        Orchestrate().parse(listOf("--config", configPath.toString()))

        val anchorwaveOut = workDir.resolve("output/01_anchorwave_results").toFile()
        assertTrue(anchorwaveOut.exists(), "AnchorWave output directory should exist")
        val mafPaths = File(anchorwaveOut, "maf_file_paths.txt")
        assertTrue(mafPaths.exists() && mafPaths.length() > 0, "maf_file_paths.txt should be non-empty")

        val gvcfPaths = workDir.resolve("output/02_gvcf_results/gvcf_file_paths.txt").toFile()
        assertTrue(gvcfPaths.exists(), "gvcf_file_paths.txt should exist")
        val gvcfLines = gvcfPaths.readLines().filter { it.isNotBlank() }
        assertTrue(gvcfLines.isNotEmpty(), "At least one gVCF should be listed")
        assertTrue(
            gvcfLines.all { File(it).exists() },
            "Every gVCF referenced in gvcf_file_paths.txt must exist on disk"
        )
    }

    /**
     * Full variant-pipeline (steps 1-4) E2E: align_assemblies ->
     * maf_to_gvcf -> downsample_gvcf -> convert_to_fasta. Validates that
     * every step's expected outputs are produced and chained together
     * correctly by the orchestrator.
     *
     * Unlike the other E2E test, this one does NOT use [TempDir] -- it
     * pins the working directory to a fixed location under `build/` so
     * the intermediate outputs persist for post-mortem inspection. The
     * location is logged at the start of the test and is wiped on each
     * fresh run to keep the test hermetic.
     */
    @Test
    fun orchestrateRunsVariantPipelineStepsOneThroughFour() {
        // Steps 1-4 require: PHG + AnchorWave (step 1), biokotlin-tools (step 2),
        // and MLImpute (steps 3-4). The orchestrator's auto-run of
        // setup-environment populates biokotlin-tools and MLImpute on first
        // run; the PHGv2 binary is picked up from SEQ_SIM_PHG_DIR.
        IntegrationGuard.requirePhg()
        IntegrationGuard.requireAnchorwave()

        val workDir = persistentWorkDir("orchestrate-steps-1-4")
        println(">>> Persisting variant-pipeline E2E outputs at: $workDir")

        val configPath = workDir.resolve("pipeline.yaml")
        configPath.writeText(
            """
            work_dir: "${workDir.toString()}"

            run_steps:
              - align_assemblies
              - maf_to_gvcf
              - downsample_gvcf
              - convert_to_fasta

            align_assemblies:
              ref_gff: "${smallseqRoot.resolve("anchors.gff")}"
              ref_fasta: "${smallseqRoot.resolve("Ref.fa")}"
              query_fasta: "${smallseqRoot.resolve("queries")}"
              threads: 2

            maf_to_gvcf:
              sample_name: "smallseq"

            downsample_gvcf:
              rates: "0.2,0.4"
              seed: 42
              keep_ref: true
              min_ref_block_size: 20

            convert_to_fasta:
              missing_records_as: "asRef"
              missing_genotype_as: "asN"
            """.trimIndent()
        )

        Orchestrate().parse(listOf("--config", configPath.toString()))

        // ---------------------------------------------------------------
        // Step 1: align_assemblies -> 01_anchorwave_results/
        // ---------------------------------------------------------------
        val step1Dir = workDir.resolve("output/01_anchorwave_results").toFile()
        assertTrue(step1Dir.exists() && step1Dir.isDirectory, "Step 1 output directory must exist")
        val mafPaths = File(step1Dir, "maf_file_paths.txt")
        assertTrue(mafPaths.exists() && mafPaths.length() > 0, "maf_file_paths.txt must be non-empty")
        val mafFiles = mafPaths.readLines().filter { it.isNotBlank() }.map { File(it) }
        assertTrue(mafFiles.isNotEmpty(), "At least one MAF should be produced")
        assertTrue(
            mafFiles.all { it.exists() && it.length() > 0 },
            "Every MAF listed must exist on disk and be non-empty"
        )

        // ---------------------------------------------------------------
        // Step 2: maf_to_gvcf -> 02_gvcf_results/
        // ---------------------------------------------------------------
        val step2Dir = workDir.resolve("output/02_gvcf_results").toFile()
        assertTrue(step2Dir.exists() && step2Dir.isDirectory, "Step 2 output directory must exist")
        val gvcfPaths = File(step2Dir, "gvcf_file_paths.txt")
        assertTrue(gvcfPaths.exists(), "gvcf_file_paths.txt must exist")
        val gvcfFiles = gvcfPaths.readLines().filter { it.isNotBlank() }.map { File(it) }
        assertTrue(gvcfFiles.isNotEmpty(), "At least one gVCF should be listed")
        assertTrue(
            gvcfFiles.all { it.exists() && it.length() > 0 },
            "Every gVCF referenced in gvcf_file_paths.txt must exist on disk and be non-empty"
        )
        assertTrue(
            gvcfFiles.all { it.name.endsWith(".g.vcf.gz") },
            "Every gVCF must be a compressed .g.vcf.gz (biokotlin compresses by default)"
        )

        // ---------------------------------------------------------------
        // Step 3: downsample_gvcf -> 03_downsample_results/
        // ---------------------------------------------------------------
        val step3Dir = workDir.resolve("output/03_downsample_results").toFile()
        assertTrue(step3Dir.exists() && step3Dir.isDirectory, "Step 3 output directory must exist")
        val downsampledFiles = step3Dir.listFiles()?.toList().orEmpty()
        val downsampledGvcfs = downsampledFiles.filter {
            it.name.endsWith(".gvcf") || it.name.endsWith(".g.vcf") ||
                it.name.endsWith(".gvcf.gz") || it.name.endsWith(".g.vcf.gz")
        }
        assertTrue(
            downsampledGvcfs.isNotEmpty(),
            "At least one downsampled GVCF should be produced (got: ${downsampledFiles.map { it.name }})"
        )
        assertTrue(
            downsampledGvcfs.all { it.length() > 0 },
            "Every downsampled GVCF must be non-empty"
        )

        // Step 3's temp_uncompressed_gvcf staging directory should be cleaned up
        // by default (keep_uncompressed is false).
        val tempStaging = workDir.resolve("temp_uncompressed_gvcf").toFile()
        assertTrue(
            !tempStaging.exists(),
            "Step 3's temp_uncompressed_gvcf dir should be cleaned up after the run"
        )

        // ---------------------------------------------------------------
        // Step 4: convert_to_fasta -> 04_fasta_results/
        // ---------------------------------------------------------------
        val step4Dir = workDir.resolve("output/04_fasta_results").toFile()
        assertTrue(step4Dir.exists() && step4Dir.isDirectory, "Step 4 output directory must exist")
        val fastaPaths = File(step4Dir, "fasta_file_paths.txt")
        assertTrue(fastaPaths.exists(), "fasta_file_paths.txt must exist")
        val fastaFiles = fastaPaths.readLines().filter { it.isNotBlank() }.map { File(it) }
        assertTrue(fastaFiles.isNotEmpty(), "At least one FASTA should be listed")
        assertTrue(
            fastaFiles.all { it.exists() && it.length() > 0 },
            "Every FASTA referenced in fasta_file_paths.txt must exist on disk and be non-empty"
        )
        assertTrue(
            fastaFiles.all { it.name.endsWith(".fasta") },
            "Every FASTA must end with .fasta"
        )

        // Step 4's temp_uncompressed_gvcf_fasta staging directory should be
        // cleaned up after the run.
        val step4Staging = workDir.resolve("temp_uncompressed_gvcf_fasta").toFile()
        assertTrue(
            !step4Staging.exists(),
            "Step 4's temp_uncompressed_gvcf_fasta dir should be cleaned up after the run"
        )

        // ---------------------------------------------------------------
        // Log file contract: each pipeline step writes its own log file.
        // ---------------------------------------------------------------
        val logsDir = workDir.resolve("logs").toFile()
        assertTrue(logsDir.exists() && logsDir.isDirectory, "logs/ should exist")
        val logNames = logsDir.listFiles()?.map { it.name }?.toSet().orEmpty()
        listOf(
            "00_orchestrate.log",
            "01_align_assemblies.log",
            "02_maf_to_gvcf.log",
            "03_downsample_gvcf.log",
            "04_convert_to_fasta.log"
        ).forEach { expected ->
            assertTrue(
                expected in logNames,
                "Expected log $expected to be present in logs/; saw $logNames"
            )
        }

        println(">>> Variant-pipeline E2E outputs preserved at: $workDir")
    }
}
