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
 * Unit tests for [RopeBwtMem]. Each test installs a [RecordingProcessExecutor]
 * so we can assert on the exact `pixi run ropebwt3 mem ...` invocation line,
 * the auto-detected -l value, and the per-sample BED layout, without
 * actually running ropebwt3.
 */
class RopeBwtMemUnitTest {

    @AfterEach
    fun cleanup() {
        ProcessRunner.resetExecutor()
    }

    /**
     * Lay out a fake "upstream" `12_rope_bwt_index_results/` directory:
     *  - a .fmd index file so findIndexFile() succeeds
     *  - a no-header keyfile so calculateLValue() returns 2 * N
     */
    private fun stubUpstreamRopeBwtIndex(workDir: Path, sampleCount: Int = 3): Path {
        val upstream = workDir.resolve("output/12_rope_bwt_index_results")
            .also { it.createDirectories() }
        upstream.resolve("phgIndex.fmd").writeText("FMD")
        val keyfile = upstream.resolve("phg_keyfile.txt")
        val lines = (1..sampleCount).map { i -> "/dev/null/sample$i.fa\tsample$i" }
        keyfile.writeText(lines.joinToString("\n"))
        return upstream
    }

    private fun writeFastq(path: Path) {
        path.parent?.createDirectories()
        path.writeText(
            """
            @read1
            ACGTACGTAC
            +
            !!!!!!!!!!

            """.trimIndent()
        )
    }

    @Test
    fun ropebwtMemAutoDetectsIndexAndLValue(@TempDir workDir: Path) {
        stubUpstreamRopeBwtIndex(workDir, sampleCount = 4)

        val fastqDir = workDir.resolve("fastqs").also { it.createDirectories() }
        writeFastq(fastqDir.resolve("sampleA.fq"))
        writeFastq(fastqDir.resolve("sampleB.fq"))

        val executor = RecordingProcessExecutor(defaultExitCode = 0)
        ProcessRunner.withExecutor(executor) {
            RopeBwtMem().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--fastq-input", fastqDir.toString(),
                    "--threads", "8"
                )
            )
        }

        // Two FASTQ files -> two ropebwt3 invocations.
        assertEquals(2, executor.invocations.size, "One ropebwt3 mem call per FASTQ")

        // Every invocation should target the auto-detected .fmd index, use the
        // calculated -l value (2 x 4 = 8), and forward --threads / -p defaults.
        val expectedIndex = workDir.resolve("output/12_rope_bwt_index_results/phgIndex.fmd")
            .toAbsolutePath().toString()
        executor.invocations.forEach { inv ->
            // RecordingProcessExecutor receives the command AFTER ProcessRunner
            // strips the leading "pixi run" prefix when SEQ_SIM_SKIP_PIXI_PREFIX
            // is enabled. Support both shapes so the test is independent of
            // that env var.
            val cmd = inv.command
            val firstTool = if (cmd.size >= 2 && cmd[0] == "pixi" && cmd[1] == "run") cmd[2] else cmd[0]
            assertEquals("ropebwt3", firstTool, "First non-pixi token should be ropebwt3")
            assertTrue(cmd.contains("mem"), "Subcommand should be `mem`")
            assertEquals("8", inv.argAfter("-t"))
            assertEquals("8", inv.argAfter("-l"))
            assertEquals("168", inv.argAfter("-p"))
            // Index path is one of the positional args near the end.
            assertTrue(cmd.contains(expectedIndex), "Index path should appear: $expectedIndex")
        }

        // BED outputs should land alongside per-sample suffix in step-13 dir.
        val expectedDir = workDir.resolve("output/13_ropebwt_mem_results")
        assertTrue(expectedDir.exists(), "Step 13 output dir should be created")
        assertTrue(
            expectedDir.resolve("sampleA_ropebwt.bed").exists(),
            "sampleA BED file should be created (RecordingProcessExecutor stubs outputFile)"
        )
        assertTrue(
            expectedDir.resolve("sampleB_ropebwt.bed").exists(),
            "sampleB BED file should be created"
        )

        // bed_file_paths.txt must enumerate every successful BED in absolute form.
        val bedPathsFile = expectedDir.resolve("bed_file_paths.txt")
        assertTrue(bedPathsFile.exists())
        val bedLines = bedPathsFile.readLines().filter { it.isNotBlank() }
        assertEquals(2, bedLines.size)
        assertTrue(bedLines.all { it.endsWith("_ropebwt.bed") })
    }

    @Test
    fun explicitLValueAndIndexOverrideAutoDetect(@TempDir workDir: Path) {
        // No upstream index dir -- we're overriding both explicitly.
        workDir.createDirectories()
        // RopeBwtMem still requires the working directory to exist (for
        // ValidationUtils.validateWorkingDirectory).

        val fastq = workDir.resolve("only.fq").also { writeFastq(it) }
        val explicitIndex = workDir.resolve("custom_index.fmd").apply { writeText("FMD") }

        val executor = RecordingProcessExecutor(defaultExitCode = 0)
        ProcessRunner.withExecutor(executor) {
            RopeBwtMem().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--fastq-input", fastq.toString(),
                    "--index-file", explicitIndex.toString(),
                    "--l-value", "33",
                    "--p-value", "250",
                    "--threads", "2"
                )
            )
        }

        val inv = executor.invocations.single()
        assertEquals("2", inv.argAfter("-t"))
        assertEquals("33", inv.argAfter("-l"))
        assertEquals("250", inv.argAfter("-p"))
        assertTrue(
            inv.command.contains(explicitIndex.toAbsolutePath().toString()),
            "Explicit index path should be forwarded verbatim"
        )
    }

    @Test
    fun customOutputDirIsRespected(@TempDir workDir: Path) {
        stubUpstreamRopeBwtIndex(workDir, sampleCount = 1)
        val fastq = workDir.resolve("solo.fastq").also { writeFastq(it) }
        val customOut = workDir.resolve("custom_bed_out")

        val executor = RecordingProcessExecutor(defaultExitCode = 0)
        ProcessRunner.withExecutor(executor) {
            RopeBwtMem().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--fastq-input", fastq.toString(),
                    "--output-dir", customOut.toString()
                )
            )
        }

        // BED file lands in the custom output directory, not the default.
        assertTrue(customOut.resolve("solo_ropebwt.bed").exists())
        assertTrue(
            !workDir.resolve("output/13_ropebwt_mem_results").exists(),
            "Default output dir should NOT be created when --output-dir is provided"
        )
    }
}
