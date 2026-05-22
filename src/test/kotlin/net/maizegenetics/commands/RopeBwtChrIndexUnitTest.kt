package net.maizegenetics.commands

import com.github.ajalt.clikt.core.parse
import net.maizegenetics.utils.ProcessRunner
import net.maizegenetics.utils.testing.RecordingProcessExecutor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [RopeBwtChrIndex]. Verifies the PHG CLI invocation, the
 * auto-generated keyfile contents, and the --delete-fmr-index presence-flag
 * wiring -- all without actually shelling out to phg.
 */
class RopeBwtChrIndexUnitTest {

    @AfterEach
    fun cleanup() {
        ProcessRunner.resetExecutor()
    }

    /**
     * Create a fake PHG layout (bin/phg) inside [workDir] so the command's
     * [net.maizegenetics.utils.ValidationUtils.validatePhgSetup] passes.
     */
    private fun stubPhgBinary(workDir: Path): Path {
        val phgDir = workDir.resolve("src/phg_v2/bin")
        phgDir.createDirectories()
        val phg = phgDir.resolve("phg")
        phg.writeText("#!/bin/sh\nexit 0\n")
        phg.toFile().setExecutable(true)
        return phg
    }

    private fun writeFasta(path: Path, contig: String = ">chr1\nACGT\n") {
        path.parent?.createDirectories()
        path.writeText(contig)
    }

    @Test
    fun fastaInputAutoGeneratesKeyfileAndInvokesPhg(@TempDir workDir: Path) {
        stubPhgBinary(workDir)
        val fastaDir = workDir.resolve("fastas").also { it.createDirectories() }
        writeFasta(fastaDir.resolve("sampleA.fa"))
        writeFasta(fastaDir.resolve("sampleB.fa"))

        val executor = RecordingProcessExecutor(defaultExitCode = 0)
        ProcessRunner.withExecutor(executor) {
            RopeBwtChrIndex().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--fasta-input", fastaDir.toString(),
                    "--threads", "4"
                )
            )
        }

        assertEquals(1, executor.invocations.size, "phg should be invoked exactly once")
        val inv = executor.invocations.single()
        assertTrue(inv.command.first().endsWith("phg"))
        assertEquals("rope-bwt-chr-index", inv.command[1])
        assertEquals("4", inv.argAfter("--threads"))
        assertEquals("phgIndex", inv.argAfter("--index-file-prefix"))

        // --delete-fmr-index is a presence flag and must be absent by default.
        assertTrue(!inv.command.contains("--delete-fmr-index"))

        // Output directory matches the v0.2 step-12 layout.
        val expectedOutput = workDir.resolve("output/12_rope_bwt_index_results")
        assertEquals(expectedOutput.toAbsolutePath().toString(), inv.argAfter("--output-dir"))
        assertTrue(expectedOutput.exists(), "Output directory should be created")

        // The keyfile must be generated next to the index files, contain one
        // line per FASTA, and have no header (downstream `ropebwt-mem` reads
        // it as raw `<path>\t<sample>` rows).
        val keyfile = expectedOutput.resolve("phg_keyfile.txt")
        assertTrue(keyfile.exists(), "Auto-generated keyfile should exist")
        val keyLines = keyfile.readLines().filter { it.isNotBlank() }
        assertEquals(2, keyLines.size, "Two FASTAs -> two keyfile rows")
        assertTrue(keyLines.all { it.split("\t").size == 2 }, "Each row is path<TAB>sample")
        val sampleNames = keyLines.map { it.split("\t")[1] }
        assertEquals(setOf("sampleA", "sampleB"), sampleNames.toSet())
    }

    @Test
    fun providedKeyfileIsForwardedWithoutRegeneration(@TempDir workDir: Path) {
        stubPhgBinary(workDir)

        // Real on-disk FASTA so validateKeyfile passes.
        val fastaDir = workDir.resolve("fastas").also { it.createDirectories() }
        val fastaA = fastaDir.resolve("a.fa").also { writeFasta(it) }

        val outputDir = workDir.resolve("phg_index")
        val keyfile = workDir.resolve("custom_keyfile.txt").apply {
            writeText("${fastaA.toAbsolutePath()}\tcustomSample\n")
        }

        val executor = RecordingProcessExecutor(defaultExitCode = 0)
        ProcessRunner.withExecutor(executor) {
            RopeBwtChrIndex().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--keyfile", keyfile.toString(),
                    "--output-dir", outputDir.toString(),
                    "--index-file-prefix", "myIndex",
                    "--delete-fmr-index"
                )
            )
        }

        val inv = executor.invocations.single()
        assertEquals(keyfile.toAbsolutePath().toString(), inv.argAfter("--keyfile"))
        assertEquals(outputDir.toAbsolutePath().toString(), inv.argAfter("--output-dir"))
        assertEquals("myIndex", inv.argAfter("--index-file-prefix"))
        assertTrue(inv.command.contains("--delete-fmr-index"))

        // Should NOT have written its own keyfile alongside the index when one
        // was passed in explicitly.
        assertTrue(
            !outputDir.resolve("phg_keyfile.txt").exists(),
            "Provided keyfile must not trigger an auto-generated one"
        )
    }
}
