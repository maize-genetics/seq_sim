package net.maizegenetics.commands

import com.github.ajalt.clikt.core.parse
import net.maizegenetics.utils.ProcessRunner
import net.maizegenetics.utils.testing.RecordingProcessExecutor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [DownsampleGvcf] (step 03 of the variant pipeline) that
 * don't actually shell out to MLImpute. We install a
 * [RecordingProcessExecutor] and verify the exact `./gradlew run --args=...`
 * invocation, the working directory passed to gradlew, and the staging
 * behavior for compressed / mis-named GVCFs.
 */
class DownsampleGvcfUnitTest {

    @AfterEach
    fun cleanup() {
        ProcessRunner.resetExecutor()
    }

    /**
     * Create the fake MLImpute kotlin subproject inside [workDir] so the
     * command's existence checks pass.
     */
    private fun stubMlimpute(workDir: Path): Path {
        val mlimputeKotlinDir = workDir.resolve("src/MLImpute/src/kotlin")
        mlimputeKotlinDir.createDirectories()
        val gradlew = mlimputeKotlinDir.resolve("gradlew")
        gradlew.writeText("#!/bin/sh\nexit 0\n")
        gradlew.toFile().setExecutable(true)
        return mlimputeKotlinDir
    }

    private fun writeGzipped(path: Path, contents: String) {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { it.write(contents.toByteArray()) }
        path.writeBytes(baos.toByteArray())
    }

    private fun parseArgsValue(args: String): Map<String, String> {
        // The single --args= string contains space-separated key=value pairs.
        // It also has the "downsample-gvcf" subcommand as the first token.
        return args.split(" ")
            .filter { it.startsWith("--") && it.contains("=") }
            .associate {
                val (k, v) = it.removePrefix("--").split("=", limit = 2)
                k to v
            }
    }

    @Test
    fun gradlewIsInvokedWithExpectedDownsampleArgs(@TempDir workDir: Path) {
        val mlimputeKotlinDir = stubMlimpute(workDir)
        val gvcfDir = workDir.resolve("gvcfs").also { it.createDirectories() }
        gvcfDir.resolve("LineA.gvcf").writeText("##fileformat=VCFv4.2\n")

        val executor = RecordingProcessExecutor(defaultExitCode = 0)
        ProcessRunner.withExecutor(executor) {
            DownsampleGvcf().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--gvcf-dir", gvcfDir.toString(),
                    "--rates", "0.1,0.2",
                    "--seed", "42",
                    "--keep-ref", "false",
                    "--min-ref-block-size", "30"
                )
            )
        }

        assertEquals(1, executor.invocations.size, "gradlew should be invoked exactly once")
        val inv = executor.invocations.single()

        assertEquals("./gradlew", inv.command[0])
        assertEquals("run", inv.command[1])
        assertEquals(
            mlimputeKotlinDir.toFile().absoluteFile,
            inv.workingDir?.absoluteFile,
            "gradlew should run inside the MLImpute kotlin project"
        )

        val argsToken = inv.command.first { it.startsWith("--args=") }.removePrefix("--args=")
        assertTrue(
            argsToken.startsWith("downsample-gvcf "),
            "First token in --args should be the MLImpute subcommand `downsample-gvcf`"
        )
        val argMap = parseArgsValue(argsToken)
        assertEquals("0.1,0.2", argMap["rates"])
        assertEquals("42", argMap["seed"])
        assertEquals("false", argMap["keep-ref"])
        assertEquals("30", argMap["min-ref-block-size"])

        // Output directory defaults to workDir/output/03_downsample_results
        val expectedOutDir = workDir.resolve("output/03_downsample_results")
            .toAbsolutePath().toString()
        assertEquals(expectedOutDir, argMap["out-dir"])

        // Already-uncompressed .gvcf -- MLImpute reads directly from the input dir
        assertEquals(gvcfDir.toAbsolutePath().toString(), argMap["gvcf-dir"])

        // --ignore-contig is empty by default and should NOT be forwarded
        assertTrue(!argsToken.contains("--ignore-contig="))
    }

    @Test
    fun ignoreContigIsForwardedOnlyWhenProvided(@TempDir workDir: Path) {
        stubMlimpute(workDir)
        val gvcfDir = workDir.resolve("gvcfs").also { it.createDirectories() }
        gvcfDir.resolve("LineA.gvcf").writeText("##fileformat=VCFv4.2\n")

        val executor = RecordingProcessExecutor(defaultExitCode = 0)
        ProcessRunner.withExecutor(executor) {
            DownsampleGvcf().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--gvcf-dir", gvcfDir.toString(),
                    "--ignore-contig", "chrM,chrPt"
                )
            )
        }

        val inv = executor.invocations.single()
        val argsToken = inv.command.first { it.startsWith("--args=") }.removePrefix("--args=")
        val argMap = parseArgsValue(argsToken)
        assertEquals("chrM,chrPt", argMap["ignore-contig"])
    }

    @Test
    fun compressedGvcfsAreDecompressedToTempDirBeforeForwarding(@TempDir workDir: Path) {
        stubMlimpute(workDir)
        val gvcfDir = workDir.resolve("gvcfs").also { it.createDirectories() }
        // Two compressed inputs forces the command to use a temp directory
        // for MLImpute since none of the staged files live under gvcfDir.
        writeGzipped(gvcfDir.resolve("LineA.g.vcf.gz"), "##fileformat=VCFv4.2\nA\n")
        writeGzipped(gvcfDir.resolve("LineB.gvcf.gz"), "##fileformat=VCFv4.2\nB\n")

        val executor = RecordingProcessExecutor(defaultExitCode = 0)
        ProcessRunner.withExecutor(executor) {
            DownsampleGvcf().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--gvcf-dir", gvcfDir.toString(),
                    "--keep-uncompressed"
                )
            )
        }

        val inv = executor.invocations.single()
        val argsToken = inv.command.first { it.startsWith("--args=") }.removePrefix("--args=")
        val argMap = parseArgsValue(argsToken)

        val mlimputeInputDir = Path.of(argMap["gvcf-dir"]!!)
        assertTrue(
            mlimputeInputDir.toAbsolutePath().startsWith(workDir.toAbsolutePath()),
            "MLImpute input dir should be under the working directory"
        )
        assertTrue(
            mlimputeInputDir.fileName.toString() == "temp_uncompressed_gvcf",
            "Compressed inputs should be staged in the temp_uncompressed_gvcf dir, " +
                "got: $mlimputeInputDir"
        )

        // With --keep-uncompressed the temp directory survives so we can
        // assert decompression actually occurred.
        val tempDir = workDir.resolve("temp_uncompressed_gvcf")
        assertTrue(tempDir.exists(), "Temp uncompressed dir should exist")
        val decompressed = tempDir.toFile().listFiles()!!.map { it.name }.toSet()
        assertEquals(setOf("LineA.gvcf", "LineB.gvcf"), decompressed)
    }

    @Test
    fun tempUncompressedDirIsCleanedUpByDefault(@TempDir workDir: Path) {
        stubMlimpute(workDir)
        val gvcfDir = workDir.resolve("gvcfs").also { it.createDirectories() }
        writeGzipped(gvcfDir.resolve("LineA.g.vcf.gz"), "##fileformat=VCFv4.2\n")

        val executor = RecordingProcessExecutor(defaultExitCode = 0)
        ProcessRunner.withExecutor(executor) {
            DownsampleGvcf().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--gvcf-dir", gvcfDir.toString()
                    // --keep-uncompressed NOT set; temp dir should be cleaned up
                )
            )
        }

        val tempDir = workDir.resolve("temp_uncompressed_gvcf")
        assertTrue(!tempDir.exists(), "Temp uncompressed dir should be removed after run")
    }

    @Test
    fun customOutputDirIsForwarded(@TempDir workDir: Path) {
        stubMlimpute(workDir)
        val gvcfDir = workDir.resolve("gvcfs").also { it.createDirectories() }
        gvcfDir.resolve("LineA.gvcf").writeText("##fileformat=VCFv4.2\n")
        val customOutput = workDir.resolve("custom_downsample_out")

        val executor = RecordingProcessExecutor(defaultExitCode = 0)
        ProcessRunner.withExecutor(executor) {
            DownsampleGvcf().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--gvcf-dir", gvcfDir.toString(),
                    "--output-dir", customOutput.toString()
                )
            )
        }

        val inv = executor.invocations.single()
        val argsToken = inv.command.first { it.startsWith("--args=") }.removePrefix("--args=")
        val argMap = parseArgsValue(argsToken)
        assertEquals(customOutput.toAbsolutePath().toString(), argMap["out-dir"])
        assertTrue(customOutput.exists(), "Custom output dir should have been created")
    }
}
