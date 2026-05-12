package net.maizegenetics.integration

import com.github.ajalt.clikt.core.parse
import net.maizegenetics.commands.AlignAssemblies
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.test.assertTrue

/**
 * Integration test that actually invokes `phg align-assemblies` (which itself
 * drives AnchorWave + minimap2) against the smallseq test resources.
 *
 * Runs only inside the seq-sim-dev container (gated by [IntegrationGuard]).
 * Outside the container this is a no-op assumption skip.
 */
@Tag("integration")
class AlignAssembliesIntegrationTest {

    private val smallseqRoot: Path = File("src/test/resources/smallseq")
        .absoluteFile.toPath()

    @Test
    fun alignsQueryAgainstSmallseqReference(@TempDir workDir: Path) {
        IntegrationGuard.requirePhg()
        IntegrationGuard.requireAnchorwave()

        // seq-sim expects a pre-existing work dir (validateWorkingDirectory).
        // We also need a phg binary at <workDir>/src/phg_v2/bin/phg.
        workDir.createDirectories()
        val phgSrcDir = workDir.resolve("src/phg_v2/bin").also { it.createDirectories() }
        val phgFromEnv = File("${IntegrationGuard.phgDir}/bin/phg")
        java.nio.file.Files.createSymbolicLink(phgSrcDir.resolve("phg"), phgFromEnv.toPath())

        // Copy the single query into a dir so align-assemblies globs it.
        val queriesDir = workDir.resolve("queries").also { it.createDirectories() }
        smallseqRoot.resolve("queries/LineA.fa")
            .copyTo(queriesDir.resolve("LineA.fa"), overwrite = true)

        AlignAssemblies().parse(
            listOf(
                "--work-dir", workDir.toString(),
                "--ref-gff", smallseqRoot.resolve("anchors.gff").toString(),
                "--ref-fasta", smallseqRoot.resolve("Ref.fa").toString(),
                "--query-fasta", queriesDir.toString(),
                "--threads", "2"
            )
        )

        val mafPaths = workDir.resolve("output/01_anchorwave_results/maf_file_paths.txt").toFile()
        assertTrue(mafPaths.exists(), "maf_file_paths.txt should be produced")
        val lines = mafPaths.readLines().filter { it.isNotBlank() }
        assertTrue(lines.isNotEmpty(), "At least one MAF path should be recorded")

        val maf = File(lines.first())
        assertTrue(maf.exists() && maf.length() > 0, "MAF output file should exist and be non-empty")
    }
}
