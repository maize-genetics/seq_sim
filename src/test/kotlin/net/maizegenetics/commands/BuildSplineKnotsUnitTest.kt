package net.maizegenetics.commands

import com.github.ajalt.clikt.core.parse
import net.maizegenetics.utils.ProcessRunner
import net.maizegenetics.utils.testing.RecordingProcessExecutor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [BuildSplineKnots]. Verifies the PHG CLI invocation is
 * constructed exactly as expected without actually running phg.
 */
class BuildSplineKnotsUnitTest {

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

    @Test
    fun buildSplineKnotsInvokesPhgWithExpectedArgs(@TempDir workDir: Path) {
        stubPhgBinary(workDir)
        val vcfDir = workDir.resolve("vcfs").also { it.createDirectories() }
        vcfDir.resolve("sample.g.vcf").writeText("##fileformat=VCFv4.2\n")

        val executor = RecordingProcessExecutor(defaultExitCode = 0)
        ProcessRunner.withExecutor(executor) {
            BuildSplineKnots().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--vcf-dir", vcfDir.toString(),
                    "--vcf-type", "gvcf",
                    "--num-bps-per-knot", "1000",
                    "--random-seed", "99"
                )
            )
        }

        assertEquals(1, executor.invocations.size)
        val inv = executor.invocations.single()
        assertTrue(inv.command.first().endsWith("phg"), "First token should be the phg binary")
        assertEquals("build-spline-knots", inv.command[1])
        assertEquals(vcfDir.toString(), inv.argAfter("--vcf-dir"))
        assertEquals("gvcf", inv.argAfter("--vcf-type"))
        assertEquals("1000", inv.argAfter("--num-bps-per-knot"))
        assertEquals("99", inv.argAfter("--random-seed"))
        // --contig-list is optional and should NOT be present when not set
        assertTrue(!inv.command.contains("--contig-list"))
    }

    @Test
    fun contigListIsForwardedWhenProvided(@TempDir workDir: Path) {
        stubPhgBinary(workDir)
        val vcfDir = workDir.resolve("vcfs").also { it.createDirectories() }
        vcfDir.resolve("sample.g.vcf").writeText("##fileformat=VCFv4.2\n")

        val executor = RecordingProcessExecutor(defaultExitCode = 0)
        ProcessRunner.withExecutor(executor) {
            BuildSplineKnots().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--vcf-dir", vcfDir.toString(),
                    "--vcf-type", "hvcf",
                    "--contig-list", "chr1,chr2"
                )
            )
        }

        val inv = executor.invocations.single()
        assertEquals("chr1,chr2", inv.argAfter("--contig-list"))
    }
}
