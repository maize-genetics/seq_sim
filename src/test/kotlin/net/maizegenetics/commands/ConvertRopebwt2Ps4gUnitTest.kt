package net.maizegenetics.commands

import com.github.ajalt.clikt.core.parse
import net.maizegenetics.utils.ProcessRunner
import net.maizegenetics.utils.testing.RecordingProcessExecutor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [ConvertRopebwt2Ps4g]. Verifies the PHG CLI invocation,
 * auto-detection of step-13 BED outputs and step-14 spline knots, and the
 * ps4g_file_paths.txt manifest -- without actually running phg.
 */
class ConvertRopebwt2Ps4gUnitTest {

    @AfterEach
    fun cleanup() {
        ProcessRunner.resetExecutor()
    }

    private fun stubPhgBinary(workDir: Path): Path {
        val phgDir = workDir.resolve("src/phg_v2/bin")
        phgDir.createDirectories()
        val phg = phgDir.resolve("phg")
        phg.writeText("#!/bin/sh\nexit 0\n")
        phg.toFile().setExecutable(true)
        return phg
    }

    /** Pretend step 13 already ran: drop BED files into the expected directory. */
    private fun stubUpstreamBeds(workDir: Path, sampleNames: List<String>): Path {
        val bedDir = workDir.resolve("output/13_ropebwt_mem_results")
            .also { it.createDirectories() }
        sampleNames.forEach { name ->
            bedDir.resolve("$name.bed").writeText("chr1\t0\t10\n")
        }
        return bedDir
    }

    /** Pretend step 14 already ran: create the spline-knots directory. */
    private fun stubUpstreamSplineKnots(workDir: Path): Path {
        return workDir.resolve("output/14_spline_knots_results")
            .also {
                it.createDirectories()
                it.resolve("chr1.knots").writeText("0\t100\n")
            }
    }

    /**
     * RecordingProcessExecutor that simulates PHG's side-effect of creating
     * a `<sample>.ps4g` file in the output dir on success.
     */
    private fun phgSucceedingExecutor() = RecordingProcessExecutor(defaultExitCode = 0) { inv ->
        val outputDir = inv.command.dropWhile { it != "--output-dir" }.getOrNull(1)?.let { File(it) }
        val bedArg = inv.command.dropWhile { it != "--ropebwt-bed" }.getOrNull(1)?.let { File(it) }
        if (outputDir != null && bedArg != null) {
            outputDir.mkdirs()
            File(outputDir, "${bedArg.nameWithoutExtension}.ps4g").writeText("PS4G\n")
        }
        0
    }

    @Test
    fun autoDetectsBedAndSplineDirAndWritesPs4gPaths(@TempDir workDir: Path) {
        stubPhgBinary(workDir)
        stubUpstreamBeds(workDir, listOf("sampleA", "sampleB"))
        stubUpstreamSplineKnots(workDir)

        val executor = phgSucceedingExecutor()
        ProcessRunner.withExecutor(executor) {
            ConvertRopebwt2Ps4g().parse(
                listOf(
                    "--work-dir", workDir.toString()
                )
            )
        }

        // Two BED inputs -> two phg invocations, each pointing at the
        // auto-detected step-13 and step-14 directories.
        assertEquals(2, executor.invocations.size)
        val expectedSplineDir = workDir.resolve("output/14_spline_knots_results")
            .toAbsolutePath().toString()
        val expectedOutputDir = workDir.resolve("output/15_convert_ropebwt2ps4g_results")
            .toAbsolutePath().toString()
        executor.invocations.forEach { inv ->
            assertTrue(inv.command.first().endsWith("phg"))
            assertEquals("convert-ropebwt2ps4g-file", inv.command[1])
            assertEquals(expectedSplineDir, inv.argAfter("--spline-knot-dir"))
            assertEquals(expectedOutputDir, inv.argAfter("--output-dir"))
            assertEquals("135", inv.argAfter("--min-mem-length"))
            assertEquals("16", inv.argAfter("--max-num-hits"))
        }

        // ps4g_file_paths.txt should enumerate the two PS4G outputs.
        val pathsFile = workDir.resolve("output/15_convert_ropebwt2ps4g_results/ps4g_file_paths.txt")
        assertTrue(pathsFile.exists(), "ps4g_file_paths.txt should be written")
        val lines = pathsFile.readLines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.endsWith(".ps4g") })
        assertTrue(lines.any { it.endsWith("/sampleA.ps4g") })
        assertTrue(lines.any { it.endsWith("/sampleB.ps4g") })
    }

    @Test
    fun customMemAndHitsParametersAreForwarded(@TempDir workDir: Path) {
        stubPhgBinary(workDir)
        stubUpstreamBeds(workDir, listOf("only"))
        stubUpstreamSplineKnots(workDir)

        val executor = phgSucceedingExecutor()
        ProcessRunner.withExecutor(executor) {
            ConvertRopebwt2Ps4g().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--min-mem-length", "148",
                    "--max-num-hits", "50"
                )
            )
        }

        val inv = executor.invocations.single()
        assertEquals("148", inv.argAfter("--min-mem-length"))
        assertEquals("50", inv.argAfter("--max-num-hits"))
    }

    @Test
    fun explicitBedAndSplineDirOverridesAreUsed(@TempDir workDir: Path) {
        stubPhgBinary(workDir)

        // Custom locations -- nothing under output/.
        val customBedDir = workDir.resolve("my_beds").also { it.createDirectories() }
        customBedDir.resolve("custom.bed").writeText("chr1\t0\t5\n")
        val customSplineDir = workDir.resolve("my_spline").also {
            it.createDirectories()
            it.resolve("chr1.knots").writeText("0\t100\n")
        }
        val customOut = workDir.resolve("my_ps4g_out")

        val executor = phgSucceedingExecutor()
        ProcessRunner.withExecutor(executor) {
            ConvertRopebwt2Ps4g().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--bed-input", customBedDir.toString(),
                    "--spline-knot-dir", customSplineDir.toString(),
                    "--output-dir", customOut.toString()
                )
            )
        }

        val inv = executor.invocations.single()
        assertEquals(
            customSplineDir.toAbsolutePath().toString(),
            inv.argAfter("--spline-knot-dir")
        )
        assertEquals(
            customOut.toAbsolutePath().toString(),
            inv.argAfter("--output-dir")
        )
        assertTrue(
            customOut.resolve("custom.ps4g").exists(),
            "PS4G output should land under --output-dir, not the default step-15 path"
        )
    }
}
