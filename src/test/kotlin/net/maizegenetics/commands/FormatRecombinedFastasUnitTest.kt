package net.maizegenetics.commands

import com.github.ajalt.clikt.core.parse
import net.maizegenetics.utils.ProcessRunner
import net.maizegenetics.utils.testing.RecordingProcessExecutor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [FormatRecombinedFastas] (step 09 of the recombination
 * pipeline). The command shells out to `seqkit seq` via `pixi run` once
 * per input FASTA; we install a [RecordingProcessExecutor] and verify
 * the constructed command line, the auto-detection of the previous
 * step's `recombinate_fastas/` directory, and the per-step
 * `formatted_fasta_paths.txt` output contract.
 */
class FormatRecombinedFastasUnitTest {

    // FormatRecombinedFastas builds `pixi run seqkit seq ...`. Disable the
    // dev-container's `pixi run` stripping so we can assert on it.
    private val originalSkipPixi = ProcessRunner.skipPixiPrefix

    @BeforeEach
    fun disablePixiStripping() {
        ProcessRunner.skipPixiPrefix = false
    }

    @AfterEach
    fun cleanup() {
        ProcessRunner.resetExecutor()
        ProcessRunner.skipPixiPrefix = originalSkipPixi
    }

    private fun stubFastas(dir: Path, names: List<String>): List<Path> {
        dir.createDirectories()
        return names.map { name ->
            val f = dir.resolve("$name.fa")
            f.writeText(">1\nACGTACGTACGTACGTACGTACGTACGTACGT\n")
            f
        }
    }

    @Test
    fun seqkitIsInvokedOncePerFastaWithExpectedArgs(@TempDir workDir: Path) {
        val fastaDir = workDir.resolve("recombined").also { it.createDirectories() }
        stubFastas(fastaDir, listOf("0", "1"))

        val executor = RecordingProcessExecutor(defaultExitCode = 0)

        ProcessRunner.withExecutor(executor) {
            FormatRecombinedFastas().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--fasta-input", fastaDir.toString(),
                    "--line-width", "80",
                    "--threads", "4"
                )
            )
        }

        assertEquals(2, executor.invocations.size, "seqkit should be invoked once per FASTA file")

        val inv = executor.invocations.first()
        assertEquals("pixi", inv.command[0])
        assertEquals("run", inv.command[1])
        assertEquals("seqkit", inv.command[2])
        assertEquals("seq", inv.command[3])
        assertEquals("80", inv.argAfter("-w"))
        assertEquals("4", inv.argAfter("-j"))

        // The last positional arg is the FASTA path; verify both invocations
        // covered the staged inputs.
        val inputArgs = executor.invocations.map { it.command.last() }.toSet()
        assertEquals(
            setOf(
                fastaDir.resolve("0.fa").toString(),
                fastaDir.resolve("1.fa").toString()
            ),
            inputArgs
        )

        // Outputs are redirected via outputFile to the per-step output dir.
        val expectedOutDir = workDir.resolve("output/09_formatted_fastas").toFile().absoluteFile
        executor.invocations.forEach { invocation ->
            assertEquals(
                expectedOutDir,
                invocation.outputFile?.parentFile?.absoluteFile,
                "seqkit output should be redirected under $expectedOutDir"
            )
            assertTrue(
                invocation.outputFile?.name?.endsWith(".fa") == true,
                "Output filename should preserve the .fa extension; got: ${invocation.outputFile?.name}"
            )
        }
    }

    @Test
    fun defaultLineWidthAndThreadsAreApplied(@TempDir workDir: Path) {
        val fastaDir = workDir.resolve("recombined").also { it.createDirectories() }
        stubFastas(fastaDir, listOf("0"))

        val executor = RecordingProcessExecutor(defaultExitCode = 0)

        ProcessRunner.withExecutor(executor) {
            FormatRecombinedFastas().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--fasta-input", fastaDir.toString()
                )
            )
        }

        val inv = executor.invocations.single()
        assertEquals("60", inv.argAfter("-w"), "Default line width should be 60")
        assertEquals("8", inv.argAfter("-j"), "Default thread count should be 8")
    }

    @Test
    fun inputIsAutoDetectedFromStep08OutputWhenOmitted(@TempDir workDir: Path) {
        // Simulate step 8's output directory at the expected location.
        val recombinedFastasDir = workDir.resolve("output/08_recombined_sequences/recombinate_fastas")
        stubFastas(recombinedFastasDir, listOf("0", "1", "2"))

        val executor = RecordingProcessExecutor(defaultExitCode = 0)

        ProcessRunner.withExecutor(executor) {
            FormatRecombinedFastas().parse(
                listOf(
                    "--work-dir", workDir.toString()
                    // --fasta-input deliberately omitted
                )
            )
        }

        assertEquals(
            3,
            executor.invocations.size,
            "All three FASTAs from auto-detected step-8 output should be processed"
        )
        val inputArgs = executor.invocations.map { it.command.last() }.toSet()
        val expectedInputs = (0..2).map { recombinedFastasDir.resolve("$it.fa").toString() }.toSet()
        assertEquals(expectedInputs, inputArgs, "Auto-detected inputs should match step-8 outputs")
    }

    @Test
    fun formattedFastaPathsTextFileListsSuccessfullyFormattedFastas(@TempDir workDir: Path) {
        val fastaDir = workDir.resolve("recombined").also { it.createDirectories() }
        stubFastas(fastaDir, listOf("0", "1", "2"))

        val executor = RecordingProcessExecutor(defaultExitCode = 0)

        ProcessRunner.withExecutor(executor) {
            FormatRecombinedFastas().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--fasta-input", fastaDir.toString()
                )
            )
        }

        val pathsFile = workDir.resolve("output/09_formatted_fastas/formatted_fasta_paths.txt").toFile()
        assertTrue(pathsFile.exists(), "formatted_fasta_paths.txt should be written")
        val lines = pathsFile.readLines().filter { it.isNotBlank() }
        assertEquals(3, lines.size, "One formatted FASTA path per input")
        assertTrue(
            lines.all { it.endsWith(".fa") },
            "Every listed path should end with .fa; got: $lines"
        )
        assertTrue(
            lines.all { File(it).exists() },
            "Every listed FASTA should exist on disk (RecordingProcessExecutor pre-creates outputs)"
        )
    }

    @Test
    fun customOutputDirIsHonored(@TempDir workDir: Path) {
        val fastaDir = workDir.resolve("recombined").also { it.createDirectories() }
        stubFastas(fastaDir, listOf("0"))
        val customOutput = workDir.resolve("custom_formatted_out")

        val executor = RecordingProcessExecutor(defaultExitCode = 0)

        ProcessRunner.withExecutor(executor) {
            FormatRecombinedFastas().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--fasta-input", fastaDir.toString(),
                    "--output-dir", customOutput.toString()
                )
            )
        }

        val inv = executor.invocations.single()
        assertEquals(
            customOutput.toFile().absoluteFile,
            inv.outputFile?.parentFile?.absoluteFile,
            "seqkit output should land in the custom output dir"
        )
        assertTrue(
            customOutput.resolve("formatted_fasta_paths.txt").toFile().exists(),
            "formatted_fasta_paths.txt should be written under the custom output dir"
        )
    }
}
