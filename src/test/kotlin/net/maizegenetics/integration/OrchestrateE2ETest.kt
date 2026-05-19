package net.maizegenetics.integration

import com.github.ajalt.clikt.core.parse
import net.maizegenetics.commands.Orchestrate
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.assertTrue

/**
 * End-to-end test: run `orchestrate` against the smallseq test fixtures
 * through every pipeline step (1-9) and assert that each step's expected
 * outputs are produced.
 *
 * Only runs inside the seq-sim-dev container (SEQ_SIM_IN_CONTAINER=1).
 */
@Tag("e2e")
class OrchestrateE2ETest {

    private val smallseqRoot: Path = File("src/test/resources/smallseq")
        .absoluteFile.toPath()

    /**
     * Create (or reset) a stable, inspectable working directory under
     * `build/test-output/` for the E2E test. We deliberately avoid
     * [org.junit.jupiter.api.io.TempDir] so that intermediate pipeline
     * outputs survive the test for post-mortem inspection. The directory
     * is wiped on each run to keep the test hermetic, and `./gradlew clean`
     * removes it.
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

    /**
     * Full pipeline (steps 1-9) E2E: align_assemblies -> maf_to_gvcf ->
     * downsample_gvcf -> convert_to_fasta -> pick_crossovers ->
     * create_chain_files -> convert_coordinates ->
     * generate_recombined_sequences -> format_recombined_fastas.
     *
     * Validates that every step's expected outputs are produced and that
     * the orchestrator chains them together correctly end-to-end.
     *
     * Uses a persistent working directory under `build/test-output/` (not
     * [org.junit.jupiter.api.io.TempDir]) so intermediate pipeline outputs
     * survive the test for post-mortem inspection. The location is logged
     * at the start of the test and is wiped on each fresh run to keep the
     * test hermetic.
     */
    @Test
    fun orchestrateRunsFullPipelineStepsOneThroughNine() {
        // Steps 1-9 require: PHG + AnchorWave (step 1), biokotlin-tools
        // (step 2), MLImpute (steps 3-4 and the python scripts that back
        // pick_crossovers / convert_coordinates / generate_recombined_sequences),
        // and seqkit (step 9). The orchestrator's auto-run of
        // setup-environment populates biokotlin-tools and MLImpute on first
        // run; the PHGv2 binary is picked up from SEQ_SIM_PHG_DIR.
        IntegrationGuard.requirePhg()
        IntegrationGuard.requireAnchorwave()
        IntegrationGuard.logContainerMemoryBudget()

        val workDir = persistentWorkDir("orchestrate-steps-1-9")
        println(">>> Persisting full-pipeline E2E outputs at: $workDir")

        // pick_crossovers requires an EVEN number of assemblies (they're
        // paired for crossover simulation). smallseq ships 3 query FASTAs
        // (LineA/LineB/LineC) and each input flows through to exactly one
        // downsampled GVCF + one FASTA, so we feed only 2 queries through
        // the pipeline to keep the assembly count even end-to-end.
        val queryListFile = workDir.resolve("queries.txt")
        queryListFile.writeText(
            listOf(
                smallseqRoot.resolve("queries/LineA.fa"),
                smallseqRoot.resolve("queries/LineB.fa"),
            ).joinToString("\n") { it.toString() } + "\n"
        )

        val configPath = workDir.resolve("pipeline.yaml")
        configPath.writeText(
            """
            work_dir: "${workDir.toString()}"

            run_steps:
              - align_assemblies
              - maf_to_gvcf
              - downsample_gvcf
              - convert_to_fasta
              - pick_crossovers
              - create_chain_files
              - convert_coordinates
              - generate_recombined_sequences
              - format_recombined_fastas

            align_assemblies:
              ref_gff: "${smallseqRoot.resolve("anchors.gff")}"
              ref_fasta: "${smallseqRoot.resolve("Ref.fa")}"
              query_fasta: "${queryListFile.toString()}"
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

            pick_crossovers: {}

            create_chain_files:
              jobs: 2

            convert_coordinates: {}

            generate_recombined_sequences: {}

            format_recombined_fastas:
              line_width: 60
              threads: 2
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
        // Step 5: pick_crossovers -> 05_crossovers_results/
        // ---------------------------------------------------------------
        val step5Dir = workDir.resolve("output/05_crossovers_results").toFile()
        assertTrue(step5Dir.exists() && step5Dir.isDirectory, "Step 5 output directory must exist")
        val refkeyPaths = File(step5Dir, "refkey_file_paths.txt")
        assertTrue(refkeyPaths.exists(), "refkey_file_paths.txt must exist")
        val refkeyFiles = refkeyPaths.readLines().filter { it.isNotBlank() }.map { File(it) }
        assertTrue(refkeyFiles.isNotEmpty(), "At least one refkey BED should be produced")
        assertTrue(
            refkeyFiles.all { it.exists() && it.length() > 0 },
            "Every refkey BED referenced in refkey_file_paths.txt must exist and be non-empty"
        )
        assertTrue(
            refkeyFiles.all { it.name.endsWith("_refkey.bed") },
            "Every refkey path must end with _refkey.bed"
        )

        // ---------------------------------------------------------------
        // Step 6: create_chain_files -> 06_chain_results/
        // ---------------------------------------------------------------
        val step6Dir = workDir.resolve("output/06_chain_results").toFile()
        assertTrue(step6Dir.exists() && step6Dir.isDirectory, "Step 6 output directory must exist")
        val chainPaths = File(step6Dir, "chain_file_paths.txt")
        assertTrue(chainPaths.exists(), "chain_file_paths.txt must exist")
        val chainFiles = chainPaths.readLines().filter { it.isNotBlank() }.map { File(it) }
        assertTrue(chainFiles.isNotEmpty(), "At least one chain file should be produced")
        assertTrue(
            chainFiles.all { it.exists() && it.length() > 0 },
            "Every chain file referenced in chain_file_paths.txt must exist and be non-empty"
        )
        assertTrue(
            chainFiles.all { it.name.endsWith("_subsampled.chain") },
            "Every chain file should be renamed with _subsampled suffix"
        )

        // Step 6 should clean up its temporary MAF staging directory.
        val tempMafDir = workDir.resolve("output/06_chain_results/temp_maf_files").toFile()
        assertTrue(
            !tempMafDir.exists(),
            "Step 6's temp_maf_files dir should be cleaned up after the run"
        )

        // ---------------------------------------------------------------
        // Step 7: convert_coordinates -> 07_coordinates_results/
        // ---------------------------------------------------------------
        val step7Dir = workDir.resolve("output/07_coordinates_results").toFile()
        assertTrue(step7Dir.exists() && step7Dir.isDirectory, "Step 7 output directory must exist")
        val keyPaths = File(step7Dir, "key_file_paths.txt")
        assertTrue(keyPaths.exists(), "key_file_paths.txt must exist")
        val keyFiles = keyPaths.readLines().filter { it.isNotBlank() }.map { File(it) }
        assertTrue(keyFiles.isNotEmpty(), "At least one assembly key BED should be listed")
        assertTrue(
            keyFiles.all { it.exists() && it.length() > 0 },
            "Every assembly key referenced in key_file_paths.txt must exist and be non-empty"
        )

        val founderKeyPaths = File(step7Dir, "founder_key_file_paths.txt")
        assertTrue(founderKeyPaths.exists(), "founder_key_file_paths.txt must exist")
        val founderKeyFiles = founderKeyPaths.readLines().filter { it.isNotBlank() }.map { File(it) }
        assertTrue(founderKeyFiles.isNotEmpty(), "At least one founder key BED should be listed")
        assertTrue(
            founderKeyFiles.all { it.name.matches(Regex("^\\d+_key\\.bed$")) },
            "Founder keys must match the N_key.bed pattern"
        )

        // ---------------------------------------------------------------
        // Step 8: generate_recombined_sequences -> 08_recombined_sequences/
        // ---------------------------------------------------------------
        val step8Dir = workDir.resolve("output/08_recombined_sequences").toFile()
        assertTrue(step8Dir.exists() && step8Dir.isDirectory, "Step 8 output directory must exist")
        val recombinedFastasDir = File(step8Dir, "recombinate_fastas")
        assertTrue(
            recombinedFastasDir.exists() && recombinedFastasDir.isDirectory,
            "Step 8's recombinate_fastas/ subdir must exist"
        )
        val recombinedPaths = File(step8Dir, "recombined_fasta_paths.txt")
        assertTrue(recombinedPaths.exists(), "recombined_fasta_paths.txt must exist")
        val recombinedFiles = recombinedPaths.readLines().filter { it.isNotBlank() }.map { File(it) }
        assertTrue(recombinedFiles.isNotEmpty(), "At least one recombined FASTA should be listed")
        assertTrue(
            recombinedFiles.all { it.exists() && it.length() > 0 },
            "Every recombined FASTA must exist on disk and be non-empty"
        )
        assertTrue(
            recombinedFiles.all { it.parentFile.name == "recombinate_fastas" },
            "Recombined FASTAs must live under recombinate_fastas/"
        )

        // ---------------------------------------------------------------
        // Step 9: format_recombined_fastas -> 09_formatted_fastas/
        // ---------------------------------------------------------------
        val step9Dir = workDir.resolve("output/09_formatted_fastas").toFile()
        assertTrue(step9Dir.exists() && step9Dir.isDirectory, "Step 9 output directory must exist")
        val formattedPaths = File(step9Dir, "formatted_fasta_paths.txt")
        assertTrue(formattedPaths.exists(), "formatted_fasta_paths.txt must exist")
        val formattedFiles = formattedPaths.readLines().filter { it.isNotBlank() }.map { File(it) }
        assertTrue(formattedFiles.isNotEmpty(), "At least one formatted FASTA should be listed")
        assertTrue(
            formattedFiles.all { it.exists() && it.length() > 0 },
            "Every formatted FASTA must exist on disk and be non-empty"
        )
        // seqkit should have honored --line-width 60; sample one file and check.
        // Multi-contig FASTAs have one trailing (possibly short) line per contig,
        // so we must split interior vs trailing lines by walking the file rather
        // than flattening all sequence lines and dropping only the last one.
        val sample = formattedFiles.first()
        val allLines = sample.readLines()
        val interiorLineLengths = mutableListOf<Int>()
        val trailingLineLengths = mutableListOf<Int>()
        for (i in allLines.indices) {
            val line = allLines[i]
            if (line.startsWith(">") || line.isBlank()) continue
            val next = allLines.getOrNull(i + 1)
            val isTrailingForContig = next == null ||
                next.startsWith(">") ||
                next.isBlank()
            if (isTrailingForContig) {
                trailingLineLengths.add(line.length)
            } else {
                interiorLineLengths.add(line.length)
            }
        }
        if (interiorLineLengths.isNotEmpty()) {
            assertTrue(
                interiorLineLengths.all { it == 60 },
                "All interior sequence lines should be 60 chars wide in $sample; " +
                    "saw interior widths=${interiorLineLengths.distinct().sorted()}, " +
                    "trailing widths=${trailingLineLengths.distinct().sorted()}"
            )
        }
        assertTrue(
            trailingLineLengths.all { it in 1..60 },
            "Trailing sequence lines must be 1..60 chars wide in $sample; " +
                "saw trailing widths=${trailingLineLengths.distinct().sorted()}"
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
            "04_convert_to_fasta.log",
            "05_pick_crossovers.log",
            "06_create_chain_files.log",
            "07_convert_coordinates.log",
            "08_generate_recombined_sequences.log",
            "09_format_recombined_fastas.log"
        ).forEach { expected ->
            assertTrue(
                expected in logNames,
                "Expected log $expected to be present in logs/; saw $logNames"
            )
        }

        println(">>> Full-pipeline E2E outputs preserved at: $workDir")
    }
}
