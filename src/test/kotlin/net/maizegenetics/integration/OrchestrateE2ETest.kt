package net.maizegenetics.integration

import com.github.ajalt.clikt.core.parse
import net.maizegenetics.commands.Orchestrate
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
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
}
