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
 * through every pipeline step (1-15) and assert that each step's expected
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
     * Synthesize a tiny but well-formed FASTQ file from one of the smallseq
     * query FASTAs. We slide a fixed-width window across the concatenated
     * sequence so every read is a true substring of a parent assembly --
     * giving step 13 (`ropebwt3 mem`) a meaningful match rate against the
     * step-12 PHG index without checking external fixture files into the
     * repo.
     *
     * Phred+33 quality is set to `I` (Q40) across the board; ropebwt3 does
     * not use quality scores for matching but ignores nothing either, so we
     * keep the FASTQ syntactically valid.
     */
    private fun synthesizeFastqFromFasta(
        sourceFasta: Path,
        target: Path,
        readLength: Int = 150,
        numReads: Int = 200,
        sampleName: String = "synthetic"
    ) {
        val sequence = buildString {
            sourceFasta.toFile().useLines { lines ->
                lines.forEach { line ->
                    if (!line.startsWith(">") && line.isNotBlank()) append(line.trim())
                }
            }
        }
        require(sequence.length >= readLength) {
            "Source FASTA $sourceFasta is shorter than the requested read length ($readLength)"
        }

        val step = ((sequence.length - readLength) / numReads).coerceAtLeast(1)
        val quality = "I".repeat(readLength)

        target.parent?.createDirectories()
        target.toFile().bufferedWriter().use { out ->
            var written = 0
            var offset = 0
            while (written < numReads && offset + readLength <= sequence.length) {
                val read = sequence.substring(offset, offset + readLength)
                out.write("@${sampleName}_read${written + 1}\n")
                out.write("$read\n")
                out.write("+\n")
                out.write("$quality\n")
                written += 1
                offset += step
            }
        }
    }

    /**
     * Full pipeline (steps 1-15) E2E. Validates that every step's expected
     * outputs are produced and that the orchestrator chains them together
     * correctly end-to-end:
     *
     *   1. align_assemblies              -> 01_anchorwave_results/
     *   2. maf_to_gvcf                   -> 02_gvcf_results/
     *   3. downsample_gvcf               -> 03_downsample_results/
     *   4. convert_to_fasta              -> 04_fasta_results/
     *   5. pick_crossovers               -> 05_crossovers_results/
     *   6. create_chain_files            -> 06_chain_results/
     *   7. convert_coordinates           -> 07_coordinates_results/
     *   8. generate_recombined_sequences -> 08_recombined_sequences/
     *   9. format_recombined_fastas      -> 09_formatted_fastas/
     *  10. align_mutated_assemblies      -> 10_mutated_alignment_results/
     *  11. mutated_maf_to_gvcf           -> 11_mutated_gvcf_results/
     *  12. rope_bwt_chr_index            -> 12_rope_bwt_index_results/
     *  13. ropebwt_mem                   -> 13_ropebwt_mem_results/
     *  14. build_spline_knots            -> 14_spline_knots_results/
     *  15. convert_ropebwt2ps4g          -> 15_convert_ropebwt2ps4g_results/
     *
     * Uses a persistent working directory under `build/test-output/` (not
     * [org.junit.jupiter.api.io.TempDir]) so intermediate pipeline outputs
     * survive the test for post-mortem inspection. The location is logged
     * at the start of the test and is wiped on each fresh run to keep the
     * test hermetic.
     */
    @Test
    fun orchestrateRunsFullPipelineStepsOneThroughFifteen() {
        // Every PHG-backed step needs the phg binary + anchorwave on PATH;
        // steps 13/15 additionally need `ropebwt3` (provided by the PHGv2
        // conda env that the dev container activates). Steps 3-4 / 5-9 rely
        // on MLImpute + biokotlin-tools + seqkit which the orchestrator's
        // auto-run of setup-environment installs on first run.
        IntegrationGuard.requirePhg()
        IntegrationGuard.requireAnchorwave()
        IntegrationGuard.logContainerMemoryBudget()

        val workDir = persistentWorkDir("orchestrate-steps-1-15")
        println(">>> Persisting full-pipeline E2E outputs at: $workDir")

        // pick_crossovers requires an EVEN number of assemblies (they're
        // paired for crossover simulation). smallseq ships 3 query FASTAs
        // (LineA/LineB/LineC); we feed only LineA + LineB so the crossover
        // pairing succeeds end-to-end.
        val queryListFile = workDir.resolve("queries.txt")
        queryListFile.writeText(
            listOf(
                smallseqRoot.resolve("queries/LineA.fa"),
                smallseqRoot.resolve("queries/LineB.fa"),
            ).joinToString("\n") { it.toString() } + "\n"
        )

        // Synthesize a tiny FASTQ for step 13 from LineA. Reads are true
        // substrings of LineA so they have a high chance of matching the
        // step-12 PHG index (which is built over the recombined founder
        // FASTAs, themselves stitched from LineA/LineB segments).
        val fastqDir = workDir.resolve("fastq_input").also { it.createDirectories() }
        synthesizeFastqFromFasta(
            sourceFasta = smallseqRoot.resolve("queries/LineA.fa"),
            target = fastqDir.resolve("synthA.fq"),
            sampleName = "synthA"
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
              - align_mutated_assemblies
              - mutated_maf_to_gvcf
              - rope_bwt_chr_index
              - ropebwt_mem
              - build_spline_knots
              - convert_ropebwt2ps4g

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

            align_mutated_assemblies:
              threads: 2

            # Intentionally omit sample_name: pinning a single sample name across
            # multiple mutated MAFs collapses every gVCF (and thus every spline
            # knot gamete) into one name, which then cannot match the per-FASTA
            # sample names that step 12 (rope-bwt-chr-index) bakes into the BWT
            # index. Leaving sample_name unset makes MafToGvcf derive each gVCF's
            # sample name from the MAF basename (0, 1, ...), which lines up with
            # the auto-generated step-12 keyfile and lets step 15 produce a
            # non-empty PS4G.
            mutated_maf_to_gvcf: {}

            rope_bwt_chr_index:
              threads: 2
              delete_fmr_index: true

            ropebwt_mem:
              fastq_input: "${fastqDir.toString()}"
              threads: 2

            build_spline_knots:
              vcf_type: "gvcf"
              num_bps_per_knot: 1000
              random_seed: 42

            convert_ropebwt2ps4g:
              min_mem_length: 50
              max_num_hits: 32
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
        // Step 10: align_mutated_assemblies -> 10_mutated_alignment_results/
        // ---------------------------------------------------------------
        val step10Dir = workDir.resolve("output/10_mutated_alignment_results").toFile()
        assertTrue(step10Dir.exists() && step10Dir.isDirectory, "Step 10 output directory must exist")
        val mutatedMafPaths = File(step10Dir, "maf_file_paths.txt")
        assertTrue(
            mutatedMafPaths.exists() && mutatedMafPaths.length() > 0,
            "Step 10's maf_file_paths.txt must be non-empty"
        )
        val mutatedMafFiles = mutatedMafPaths.readLines().filter { it.isNotBlank() }.map { File(it) }
        assertTrue(mutatedMafFiles.isNotEmpty(), "Step 10 should produce at least one mutated MAF")
        assertTrue(
            mutatedMafFiles.all { it.exists() && it.length() > 0 },
            "Every mutated MAF listed must exist and be non-empty"
        )

        // ---------------------------------------------------------------
        // Step 11: mutated_maf_to_gvcf -> 11_mutated_gvcf_results/
        // ---------------------------------------------------------------
        val step11Dir = workDir.resolve("output/11_mutated_gvcf_results").toFile()
        assertTrue(step11Dir.exists() && step11Dir.isDirectory, "Step 11 output directory must exist")
        val mutatedGvcfPathsFile = File(step11Dir, "gvcf_file_paths.txt")
        assertTrue(mutatedGvcfPathsFile.exists(), "Step 11 gvcf_file_paths.txt must exist")
        val mutatedGvcfFiles = mutatedGvcfPathsFile.readLines().filter { it.isNotBlank() }.map { File(it) }
        assertTrue(mutatedGvcfFiles.isNotEmpty(), "Step 11 should produce at least one mutated GVCF")
        assertTrue(
            mutatedGvcfFiles.all { it.exists() && it.length() > 0 },
            "Every mutated GVCF listed must exist on disk and be non-empty"
        )
        assertTrue(
            mutatedGvcfFiles.all { it.name.endsWith(".g.vcf.gz") },
            "Every mutated GVCF should be biokotlin-compressed (.g.vcf.gz)"
        )

        // ---------------------------------------------------------------
        // Step 12: rope_bwt_chr_index -> 12_rope_bwt_index_results/
        // ---------------------------------------------------------------
        val step12Dir = workDir.resolve("output/12_rope_bwt_index_results").toFile()
        assertTrue(step12Dir.exists() && step12Dir.isDirectory, "Step 12 output directory must exist")
        val keyfile = File(step12Dir, "phg_keyfile.txt")
        assertTrue(
            keyfile.exists() && keyfile.length() > 0,
            "Step 12 must auto-generate a keyfile next to the .fmd index"
        )
        val keyfileLines = keyfile.readLines().filter { it.isNotBlank() }
        assertTrue(keyfileLines.isNotEmpty(), "Keyfile must contain at least one row")
        assertTrue(
            keyfileLines.all { it.split("\t").size == 2 },
            "Auto-generated keyfile rows are <fasta_path>\\t<sample_name> (no header)"
        )
        val fmdFiles = step12Dir.listFiles { f -> f.name.endsWith(".fmd") }?.toList().orEmpty()
        assertTrue(
            fmdFiles.isNotEmpty() && fmdFiles.all { it.length() > 0 },
            "Step 12 must produce at least one non-empty .fmd index file"
        )

        // ---------------------------------------------------------------
        // Step 13: ropebwt_mem -> 13_ropebwt_mem_results/
        // ---------------------------------------------------------------
        val step13Dir = workDir.resolve("output/13_ropebwt_mem_results").toFile()
        assertTrue(step13Dir.exists() && step13Dir.isDirectory, "Step 13 output directory must exist")
        val bedPaths = File(step13Dir, "bed_file_paths.txt")
        assertTrue(bedPaths.exists(), "bed_file_paths.txt must exist after ropebwt-mem")
        val bedFiles = bedPaths.readLines().filter { it.isNotBlank() }.map { File(it) }
        assertTrue(bedFiles.isNotEmpty(), "Step 13 should produce at least one BED file")
        assertTrue(
            bedFiles.all { it.exists() },
            "Every BED listed must exist on disk"
        )
        assertTrue(
            bedFiles.all { it.name.endsWith("_ropebwt.bed") },
            "Step 13 names BED outputs as <sample>_ropebwt.bed"
        )

        // ---------------------------------------------------------------
        // Step 14: build_spline_knots -> 14_spline_knots_results/
        // ---------------------------------------------------------------
        val step14Dir = workDir.resolve("output/14_spline_knots_results").toFile()
        assertTrue(step14Dir.exists() && step14Dir.isDirectory, "Step 14 output directory must exist")
        val splineFiles = step14Dir.listFiles { f -> f.isFile && f.length() > 0 }?.toList().orEmpty()
        assertTrue(
            splineFiles.isNotEmpty(),
            "Step 14 should drop at least one non-empty spline-knot file (got: ${step14Dir.listFiles()?.map { it.name } ?: emptyList()})"
        )

        // ---------------------------------------------------------------
        // Step 15: convert_ropebwt2ps4g -> 15_convert_ropebwt2ps4g_results/
        // ---------------------------------------------------------------
        val step15Dir = workDir.resolve("output/15_convert_ropebwt2ps4g_results").toFile()
        assertTrue(step15Dir.exists() && step15Dir.isDirectory, "Step 15 output directory must exist")
        val ps4gPaths = File(step15Dir, "ps4g_file_paths.txt")
        assertTrue(
            ps4gPaths.exists(),
            "Step 15 must write ps4g_file_paths.txt (synthesized FASTQ is built from " +
                "LineA so the BED produced in step 13 always has matches against the index)"
        )
        val ps4gFiles = ps4gPaths.readLines().filter { it.isNotBlank() }.map { File(it) }
        assertTrue(ps4gFiles.isNotEmpty(), "ps4g_file_paths.txt must list at least one PS4G")
        assertTrue(
            ps4gFiles.all { it.exists() },
            "Every PS4G file listed in ps4g_file_paths.txt must exist on disk"
        )
        assertTrue(
            ps4gFiles.all { it.name.endsWith(".ps4g") },
            "Step 15 names PS4G outputs with the .ps4g extension"
        )

        // The presence of a PS4G file alone isn't enough: PHG happily writes an
        // empty PS4G when every BED contig fails to resolve against the spline
        // knots (e.g. when step-11 sample names don't match the step-12 keyfile
        // sample names). Assert that at least one PS4G has a positive
        // #TotalUniqueCounts header AND at least one data row beyond the
        // gameteSet/refContig/refPosBinned/count header line.
        val ps4gWithData = ps4gFiles.filter { ps4g ->
            val lines = ps4g.readLines()
            val totalUnique = lines
                .firstOrNull { it.startsWith("#TotalUniqueCounts:") }
                ?.substringAfter(":")
                ?.trim()
                ?.toLongOrNull() ?: 0L
            val dataRows = lines.count { it.isNotBlank() && !it.startsWith("#") } - 1
            totalUnique > 0 && dataRows > 0
        }
        assertTrue(
            ps4gWithData.isNotEmpty(),
            "At least one PS4G must contain alignment data; every PS4G is empty " +
                "(saw files=${ps4gFiles.map { it.name }}). This usually means step-11 " +
                "gVCF sample names don't match the step-12 keyfile sample names, so " +
                "PHG's convert-ropebwt2ps4g-file couldn't resolve any BED contig " +
                "against the step-14 spline knots."
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
            "09_format_recombined_fastas.log",
            "10_align_mutated_assemblies.log",
            // Step 11 reuses MafToGvcf, which writes its own LOG_FILE_NAME
            // ("02_maf_to_gvcf.log"); that file is already covered above and
            // gets appended to when the orchestrator drives step 11 too.
            "12_rope_bwt_chr_index.log",
            "13_ropebwt_mem.log",
            "14_build_spline_knots.log",
            "15_convert_ropebwt2ps4g.log"
        ).forEach { expected ->
            assertTrue(
                expected in logNames,
                "Expected log $expected to be present in logs/; saw $logNames"
            )
        }

        println(">>> Full-pipeline E2E outputs preserved at: $workDir")
    }
}
