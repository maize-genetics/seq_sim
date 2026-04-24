package net.maizegenetics.commands

import com.github.ajalt.clikt.core.parse
import net.maizegenetics.utils.ProcessRunner
import net.maizegenetics.utils.testing.RecordingProcessExecutor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [AlignAssemblies] that don't actually shell out to
 * anchorwave/minimap2 -- we install a [RecordingProcessExecutor] and verify
 * the exact command lines seq-sim would send.
 */
class AlignAssembliesUnitTest {

    private val smallseqRoot: Path = File("src/test/resources/smallseq")
        .absoluteFile.toPath()

    @AfterEach
    fun cleanup() {
        ProcessRunner.resetExecutor()
    }

    @Test
    fun gff2seqAndMinimap2AreInvokedOncePerReference(@TempDir workDir: Path) {
        val executor = RecordingProcessExecutor(defaultExitCode = 0)

        ProcessRunner.withExecutor(executor) {
            AlignAssemblies().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--ref-gff", smallseqRoot.resolve("anchors.gff").toString(),
                    "--ref-fasta", smallseqRoot.resolve("Ref.fa").toString(),
                    "--query-fasta", smallseqRoot.resolve("queries").toString(),
                    "--threads", "2"
                )
            )
        }

        // Exactly one gff2seq invocation for the reference.
        val gff2seqCalls = executor.invocations.filter {
            it.command.contains("gff2seq")
        }
        assertEquals(1, gff2seqCalls.size, "gff2seq should run once per reference")
        assertTrue(
            executor.containsSubsequence("anchorwave", "gff2seq"),
            "anchorwave gff2seq should be invoked"
        )

        // minimap2 is invoked once for the reference plus once per query (3).
        val minimap2Calls = executor.invocationsOf("pixi").filter {
            it.command.contains("minimap2")
        } + executor.invocationsOf("minimap2")
        assertEquals(
            4, minimap2Calls.size,
            "minimap2 should run once for the reference and once per query"
        )

        // proali is invoked once per query (3 queries in smallseq).
        val proaliCalls = executor.invocations.filter {
            it.command.contains("proali")
        }
        assertEquals(3, proaliCalls.size, "anchorwave proali should run once per query")
    }

    @Test
    fun anchorwaveProaliCommandIncludesRefGffAndQuerySam(@TempDir workDir: Path) {
        val executor = RecordingProcessExecutor(defaultExitCode = 0)

        ProcessRunner.withExecutor(executor) {
            AlignAssemblies().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--ref-gff", smallseqRoot.resolve("anchors.gff").toString(),
                    "--ref-fasta", smallseqRoot.resolve("Ref.fa").toString(),
                    "--query-fasta", smallseqRoot.resolve("queries/LineA.fa").toString(),
                    "--threads", "4"
                )
            )
        }

        val proali = executor.invocations.single { it.command.contains("proali") }
        assertEquals(smallseqRoot.resolve("anchors.gff").toString(), proali.argAfter("-i"))
        assertEquals(smallseqRoot.resolve("Ref.fa").toString(), proali.argAfter("-r"))
        assertEquals("4", proali.argAfter("-t"))
        assertEquals("1", proali.argAfter("-R"))
        assertEquals("1", proali.argAfter("-Q"))
    }

    @Test
    fun mafFilePathsTextFileIsWrittenForEachQuery(@TempDir workDir: Path) {
        val executor = RecordingProcessExecutor(defaultExitCode = 0)

        ProcessRunner.withExecutor(executor) {
            AlignAssemblies().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--ref-gff", smallseqRoot.resolve("anchors.gff").toString(),
                    "--ref-fasta", smallseqRoot.resolve("Ref.fa").toString(),
                    "--query-fasta", smallseqRoot.resolve("queries").toString()
                )
            )
        }

        val mafPathsFile = workDir.resolve("output/01_anchorwave_results/maf_file_paths.txt").toFile()
        assertTrue(mafPathsFile.exists(), "maf_file_paths.txt should be written")
        val lines = mafPathsFile.readLines().filter { it.isNotBlank() }
        assertEquals(3, lines.size, "Should list one MAF path per query")
    }
}
