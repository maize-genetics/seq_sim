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
 * End-to-end test: run `orchestrate` with `version: "v2"` against the smallseq
 * test fixtures through every v2 pipeline step (1-12) and assert that each
 * step's expected outputs are produced.
 *
 * Mirrors [OrchestrateE2ETest] (the v1 full-pipeline test) in structure,
 * guards, and assertion style. Only runs inside the seq-sim-dev container
 * (SEQ_SIM_IN_CONTAINER=1).
 */
@Tag("e2e")
class OrchestrateV2E2ETest {

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
     * giving the ropebwt step a meaningful match rate against the index
     * built over the recombined FASTAs without checking external fixture
     * files into the repo.
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
     * Full v2 pipeline (steps 1-12) E2E. Validates that every step's expected
     * outputs are produced and that the orchestrator chains them together
     * correctly end-to-end:
     *
     *   1. align_assemblies      -> 01_anchorwave_results/
     *   2. maf_to_gvcf           -> 02_gvcf_results/
     *   3. split_gvcfs           -> 03_split_gvcfs_results/
     *   4. downsample_gvcf       -> 04_downsample_results/
     *   5. mutate_assemblies     -> 05_mutate_assemblies_results/
     *   6. pick_crossovers       -> 06_crossovers_results/
     *   7. recombine_gvcfs       -> 07_recombine_gvcfs_results/
     *   8. sort_gvcfs            -> 08_sort_gvcfs_results/
     *   9. convert_to_fasta      -> 09_convert_to_fasta_results/
     *  10. build_spline_knots    -> 10_build_spline_knots_results/
     *  11. ropebwt               -> 11_ropebwt_results/
     *  12. convert_ropebwt2ps4g  -> 12_convert_ropebwt2ps4g_results/
     *
     * Uses a persistent working directory under `build/test-output/` (not
     * [org.junit.jupiter.api.io.TempDir]) so intermediate pipeline outputs
     * survive the test for post-mortem inspection. The location is logged
     * at the start of the test and is wiped on each fresh run to keep the
     * test hermetic.
     */
    @Test
    fun orchestrateV2RunsFullPipelineStepsOneThroughTwelve() {
        // Every PHG-backed step needs the phg binary + anchorwave on PATH;
        // steps 11/12 additionally need `ropebwt3` (provided by the PHGv2
        // conda env that the dev container activates). The gVCF-space steps
        // (split/downsample/mutate/recombine/sort) rely on MLImpute +
        // biokotlin-tools + bcftools which the orchestrator's auto-run of
        // setup-environment installs on first run.
        IntegrationGuard.requirePhg()
        IntegrationGuard.requireAnchorwave()
        IntegrationGuard.logContainerMemoryBudget()

        val workDir = persistentWorkDir("orchestrate-v2-steps-1-12")
        println(">>> Persisting v2 full-pipeline E2E outputs at: $workDir")

        // Feed all three smallseq query FASTAs so each gets a gVCF in step 2.
        // The split keyfile then designates LineA/LineB as bases (an EVEN
        // count, required by pick-base-crossovers) and LineC as the shared
        // mutation donor.
        val queryListFile = workDir.resolve("queries.txt")
        queryListFile.writeText(
            listOf(
                smallseqRoot.resolve("queries/LineA.fa"),
                smallseqRoot.resolve("queries/LineB.fa"),
                smallseqRoot.resolve("queries/LineC.fa"),
            ).joinToString("\n") { it.toString() } + "\n"
        )

        // split_gvcfs keyfile (the one fixture v2 needs that v1 does not).
        // Sample names match the maf-to-gvcf gVCF basenames, which are derived
        // from the query FASTA names (LineA, LineB, LineC).
        val splitKeyfile = workDir.resolve("split_keyfile.txt")
        splitKeyfile.writeText(
            """
            Base	MutationDonor
            LineA	LineC
            LineB	LineC
            """.trimIndent() + "\n"
        )

        // Synthesize a tiny FASTQ for the ropebwt step from LineA. Reads are
        // true substrings of LineA so they have a high chance of matching the
        // index (built over the recombined founder FASTAs, themselves stitched
        // from LineA/LineB segments).
        val fastqDir = workDir.resolve("fastq_input").also { it.createDirectories() }
        synthesizeFastqFromFasta(
            sourceFasta = smallseqRoot.resolve("queries/LineA.fa"),
            target = fastqDir.resolve("synthA.fq"),
            sampleName = "synthA"
        )

        val configPath = workDir.resolve("pipeline.yaml")
        configPath.writeText(
            """
            version: "v2"
            work_dir: "${workDir.toString()}"

            run_steps:
              - align_assemblies
              - maf_to_gvcf
              - split_gvcfs
              - downsample_gvcf
              - mutate_assemblies
              - pick_crossovers
              - recombine_gvcfs
              - sort_gvcfs
              - convert_to_fasta
              - build_spline_knots
              - ropebwt
              - convert_ropebwt2ps4g

            align_assemblies:
              ref_gff: "${smallseqRoot.resolve("anchors.gff")}"
              ref_fasta: "${smallseqRoot.resolve("Ref.fa")}"
              query_fasta: "${queryListFile.toString()}"
              threads: 2

            maf_to_gvcf: {}

            split_gvcfs:
              keyfile: "${splitKeyfile.toString()}"

            downsample_gvcf:
              ignore_contig: "__NO_MATCH__"
              rates: "0.2,0.4"
              seed: 42
              keep_ref: true
              min_ref_block_size: 20

            mutate_assemblies: {}

            pick_crossovers: {}

            recombine_gvcfs: {}

            sort_gvcfs:
              threads: 2

            convert_to_fasta:
              missing_records_as: "asRef"
              missing_genotype_as: "asN"

            build_spline_knots:
              vcf_type: "gvcf"
              num_bps_per_knot: 1000
              random_seed: 42

            ropebwt:
              fastq_input: "${fastqDir.toString()}"
              threads: 2
              delete_fmr_index: true

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
        // Step 3: split_gvcfs -> 03_split_gvcfs_results/
        // ---------------------------------------------------------------
        val step3Dir = workDir.resolve("output/03_split_gvcfs_results").toFile()
        assertTrue(step3Dir.exists() && step3Dir.isDirectory, "Step 3 output directory must exist")
        val baseSplitDir = File(step3Dir, "base")
        val donorSplitDir = File(step3Dir, "mutation_donor")
        assertTrue(baseSplitDir.exists() && baseSplitDir.isDirectory, "Step 3 base/ subdir must exist")
        assertTrue(donorSplitDir.exists() && donorSplitDir.isDirectory, "Step 3 mutation_donor/ subdir must exist")

        val pairsFile = File(step3Dir, "pairs.tsv")
        assertTrue(pairsFile.exists() && pairsFile.length() > 0, "pairs.tsv must exist and be non-empty")

        val baseGvcfPaths = File(baseSplitDir, "base_gvcf_paths.txt")
        assertTrue(baseGvcfPaths.exists(), "base_gvcf_paths.txt must exist")
        val baseGvcfFiles = baseGvcfPaths.readLines().filter { it.isNotBlank() }.map { File(it) }
        assertTrue(baseGvcfFiles.isNotEmpty(), "At least one base gVCF should be listed")
        assertTrue(
            baseGvcfFiles.all { it.exists() && it.length() > 0 },
            "Every base gVCF must exist on disk and be non-empty"
        )

        val donorGvcfPaths = File(donorSplitDir, "mutation_donor_gvcf_paths.txt")
        assertTrue(donorGvcfPaths.exists(), "mutation_donor_gvcf_paths.txt must exist")
        val donorGvcfFiles = donorGvcfPaths.readLines().filter { it.isNotBlank() }.map { File(it) }
        assertTrue(donorGvcfFiles.isNotEmpty(), "At least one mutation-donor gVCF should be listed")
        assertTrue(
            donorGvcfFiles.all { it.exists() && it.length() > 0 },
            "Every mutation-donor gVCF must exist on disk and be non-empty"
        )

        // The keyfile designated LineA/LineB as bases and LineC as the donor.
        val baseSampleNames = baseGvcfFiles.map { it.name.substringBefore(".g.vcf") }
        val donorSampleNames = donorGvcfFiles.map { it.name.substringBefore(".g.vcf") }
        assertTrue(
            baseSampleNames.containsAll(listOf("LineA", "LineB")),
            "Base set must contain LineA and LineB (got: $baseSampleNames)"
        )
        assertTrue(
            donorSampleNames.contains("LineC"),
            "Mutation-donor set must contain LineC (got: $donorSampleNames)"
        )

        // ---------------------------------------------------------------
        // Step 4: downsample_gvcf -> 04_downsample_results/
        // ---------------------------------------------------------------
        val step4Dir = workDir.resolve("output/04_downsample_results").toFile()
        assertTrue(step4Dir.exists() && step4Dir.isDirectory, "Step 4 output directory must exist")
        val downsampledFiles = step4Dir.listFiles()?.toList().orEmpty()
        val downsampledGvcfs = downsampledFiles.filter {
            it.name.endsWith(".gvcf") || it.name.endsWith(".g.vcf") ||
                it.name.endsWith(".gvcf.gz") || it.name.endsWith(".g.vcf.gz")
        }
        assertTrue(
            downsampledGvcfs.isNotEmpty(),
            "At least one downsampled mutation-donor GVCF should be produced (got: ${downsampledFiles.map { it.name }})"
        )
        assertTrue(
            downsampledGvcfs.all { it.length() > 0 },
            "Every downsampled GVCF must be non-empty"
        )

        // ---------------------------------------------------------------
        // Step 5: mutate_assemblies -> 05_mutate_assemblies_results/
        // ---------------------------------------------------------------
        val step5Dir = workDir.resolve("output/05_mutate_assemblies_results").toFile()
        assertTrue(step5Dir.exists() && step5Dir.isDirectory, "Step 5 output directory must exist")
        val mutatedPaths = File(step5Dir, "mutated_gvcf_file_paths.txt")
        assertTrue(mutatedPaths.exists(), "mutated_gvcf_file_paths.txt must exist")
        val mutatedFiles = mutatedPaths.readLines().filter { it.isNotBlank() }.map { File(it) }
        assertTrue(mutatedFiles.isNotEmpty(), "At least one mutated base gVCF should be listed")
        assertTrue(
            mutatedFiles.all { it.exists() && it.length() > 0 },
            "Every mutated base gVCF must exist on disk and be non-empty"
        )
        assertTrue(
            mutatedFiles.all { it.name.contains("__") && it.name.endsWith("_mutated.g.vcf") },
            "Mutated gVCFs are named {base}__{donorVariant}_mutated.g.vcf (got: ${mutatedFiles.map { it.name }})"
        )

        // ---------------------------------------------------------------
        // Step 6: pick_crossovers (base assemblies) -> 06_crossovers_results/
        // ---------------------------------------------------------------
        val step6Dir = workDir.resolve("output/06_crossovers_results").toFile()
        assertTrue(step6Dir.exists() && step6Dir.isDirectory, "Step 6 output directory must exist")
        val baseAssemblyList = File(step6Dir, "base_assembly_list.txt")
        assertTrue(
            baseAssemblyList.exists() && baseAssemblyList.length() > 0,
            "base_assembly_list.txt must exist and be non-empty"
        )
        val refkeyBeds = step6Dir.listFiles { f -> f.name.endsWith("_refkey.bed") }?.toList().orEmpty()
        assertTrue(refkeyBeds.isNotEmpty(), "At least one {assembly}_refkey.bed should be produced")
        assertTrue(
            refkeyBeds.all { it.length() > 0 },
            "Every refkey BED must be non-empty"
        )

        // ---------------------------------------------------------------
        // Step 7: recombine_gvcfs -> 07_recombine_gvcfs_results/
        // ---------------------------------------------------------------
        val step7Dir = workDir.resolve("output/07_recombine_gvcfs_results").toFile()
        assertTrue(step7Dir.exists() && step7Dir.isDirectory, "Step 7 output directory must exist")
        val recombinedGvcfs = step7Dir.listFiles { f -> f.name.endsWith("_recombined.gvcf") }?.toList().orEmpty()
        assertTrue(
            recombinedGvcfs.isNotEmpty(),
            "At least one {target}_recombined.gvcf should be produced (got: ${step7Dir.listFiles()?.map { it.name } ?: emptyList()})"
        )
        assertTrue(
            recombinedGvcfs.all { it.length() > 0 },
            "Every recombined gVCF must be non-empty"
        )
        val resizedBedsDir = File(step7Dir, "resized_beds")
        assertTrue(resizedBedsDir.exists() && resizedBedsDir.isDirectory, "Step 7 resized_beds/ subdir must exist")
        val resizedBeds = resizedBedsDir.listFiles { f -> f.name.endsWith(".bed") }?.toList().orEmpty()
        assertTrue(resizedBeds.isNotEmpty(), "At least one resized BED should be produced")

        // ---------------------------------------------------------------
        // Step 8: sort_gvcfs -> 08_sort_gvcfs_results/
        // ---------------------------------------------------------------
        val step8Dir = workDir.resolve("output/08_sort_gvcfs_results").toFile()
        assertTrue(step8Dir.exists() && step8Dir.isDirectory, "Step 8 output directory must exist")
        val sortedPaths = File(step8Dir, "sorted_gvcf_paths.txt")
        assertTrue(sortedPaths.exists(), "sorted_gvcf_paths.txt must exist")
        val sortedFiles = sortedPaths.readLines().filter { it.isNotBlank() }.map { File(it) }
        assertTrue(sortedFiles.isNotEmpty(), "At least one sorted gVCF should be listed")
        assertTrue(
            sortedFiles.all { it.exists() && it.length() > 0 },
            "Every sorted gVCF must exist on disk and be non-empty"
        )
        assertTrue(
            sortedFiles.all { it.name.endsWith(".g.vcf.gz") },
            "Sorted gVCFs are bgzip-compressed (.g.vcf.gz)"
        )
        assertTrue(
            sortedFiles.all { File(it.parentFile, "${it.name}.csi").exists() },
            "Every sorted gVCF must have a matching .csi index file"
        )

        // ---------------------------------------------------------------
        // Step 9: convert_to_fasta -> 09_convert_to_fasta_results/
        // ---------------------------------------------------------------
        val step9Dir = workDir.resolve("output/09_convert_to_fasta_results").toFile()
        assertTrue(step9Dir.exists() && step9Dir.isDirectory, "Step 9 output directory must exist")
        val fastaPaths = File(step9Dir, "fasta_file_paths.txt")
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

        // ---------------------------------------------------------------
        // Step 10: build_spline_knots -> 10_build_spline_knots_results/
        // ---------------------------------------------------------------
        val step10Dir = workDir.resolve("output/10_build_spline_knots_results").toFile()
        assertTrue(step10Dir.exists() && step10Dir.isDirectory, "Step 10 output directory must exist")
        val splineFiles = step10Dir.listFiles { f -> f.isFile && f.length() > 0 }?.toList().orEmpty()
        assertTrue(
            splineFiles.isNotEmpty(),
            "Step 10 should drop at least one non-empty spline-knot file (got: ${step10Dir.listFiles()?.map { it.name } ?: emptyList()})"
        )

        // ---------------------------------------------------------------
        // Step 11: ropebwt -> 11_ropebwt_results/
        // ---------------------------------------------------------------
        val step11Dir = workDir.resolve("output/11_ropebwt_results").toFile()
        assertTrue(step11Dir.exists() && step11Dir.isDirectory, "Step 11 output directory must exist")
        val indexDir = File(step11Dir, "index")
        assertTrue(indexDir.exists() && indexDir.isDirectory, "Step 11 index/ subdir must exist")
        val keyfile = File(indexDir, "phg_keyfile.txt")
        assertTrue(
            keyfile.exists() && keyfile.length() > 0,
            "Step 11 must auto-generate a keyfile next to the .fmd index"
        )
        val fmdFiles = indexDir.listFiles { f -> f.name.endsWith(".fmd") }?.toList().orEmpty()
        assertTrue(
            fmdFiles.isNotEmpty() && fmdFiles.all { it.length() > 0 },
            "Step 11 must produce at least one non-empty .fmd index file"
        )
        val bedPaths = File(step11Dir, "bed_file_paths.txt")
        assertTrue(bedPaths.exists(), "bed_file_paths.txt must exist after ropebwt")
        val bedFiles = bedPaths.readLines().filter { it.isNotBlank() }.map { File(it) }
        assertTrue(bedFiles.isNotEmpty(), "Step 11 should produce at least one BED file")
        assertTrue(
            bedFiles.all { it.exists() },
            "Every BED listed must exist on disk"
        )
        assertTrue(
            bedFiles.all { it.name.endsWith("_ropebwt.bed") },
            "Step 11 names BED outputs as <sample>_ropebwt.bed"
        )

        // ---------------------------------------------------------------
        // Step 12: convert_ropebwt2ps4g -> 12_convert_ropebwt2ps4g_results/
        // ---------------------------------------------------------------
        val step12Dir = workDir.resolve("output/12_convert_ropebwt2ps4g_results").toFile()
        assertTrue(step12Dir.exists() && step12Dir.isDirectory, "Step 12 output directory must exist")
        val ps4gPaths = File(step12Dir, "ps4g_file_paths.txt")
        assertTrue(
            ps4gPaths.exists(),
            "Step 12 must write ps4g_file_paths.txt (synthesized FASTQ is built from " +
                "LineA so the BED produced in step 11 always has matches against the index)"
        )
        val ps4gFiles = ps4gPaths.readLines().filter { it.isNotBlank() }.map { File(it) }
        assertTrue(ps4gFiles.isNotEmpty(), "ps4g_file_paths.txt must list at least one PS4G")
        assertTrue(
            ps4gFiles.all { it.exists() },
            "Every PS4G file listed in ps4g_file_paths.txt must exist on disk"
        )
        assertTrue(
            ps4gFiles.all { it.name.endsWith(".ps4g") },
            "Step 12 names PS4G outputs with the .ps4g extension"
        )

        // The presence of a PS4G file alone isn't enough: PHG happily writes an
        // empty PS4G when every BED contig fails to resolve against the spline
        // knots (e.g. when recombined-gVCF sample names don't match the step-11
        // keyfile sample names). Assert that at least one PS4G has a positive
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
                "(saw files=${ps4gFiles.map { it.name }}). This usually means the recombined " +
                "gVCF sample names don't match the step-11 keyfile sample names, so " +
                "PHG's convert-ropebwt2ps4g-file couldn't resolve any BED contig " +
                "against the step-10 spline knots."
        )

        // ---------------------------------------------------------------
        // Log file contract: assert the logs we can guarantee from each
        // command's fixed LOG_FILE_NAME. Note: recombine-gvcfs logs via
        // println (no log file), and several commands reuse fixed names not
        // tied to the v2 step number, so we only assert the stable ones.
        // ---------------------------------------------------------------
        val logsDir = workDir.resolve("logs").toFile()
        assertTrue(logsDir.exists() && logsDir.isDirectory, "logs/ should exist")
        val logNames = logsDir.listFiles()?.map { it.name }?.toSet().orEmpty()
        listOf(
            "00_orchestrate.log",
            "01_align_assemblies.log",
            "02_maf_to_gvcf.log",
            "03_split_gvcfs.log",
            "05_mutate_assemblies.log",
            "06_pick_base_crossovers.log",
            "08_sort_gvcfs.log",
            "12_convert_ropebwt2ps4g.log"
        ).forEach { expected ->
            assertTrue(
                expected in logNames,
                "Expected log $expected to be present in logs/; saw $logNames"
            )
        }

        println(">>> v2 full-pipeline E2E outputs preserved at: $workDir")
    }
}
