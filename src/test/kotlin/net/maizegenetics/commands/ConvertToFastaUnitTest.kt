package net.maizegenetics.commands

import com.github.ajalt.clikt.core.parse
import net.maizegenetics.utils.ProcessRunner
import net.maizegenetics.utils.testing.RecordingProcessExecutor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [ConvertToFasta] (step 04 of the variant pipeline) that
 * don't actually shell out to MLImpute. We install a
 * [RecordingProcessExecutor] and verify the exact `./gradlew run --args=...`
 * invocation, the working directory passed to gradlew, the
 * decompression staging for compressed GVCFs, and the per-step
 * `fasta_file_paths.txt` output contract that downstream steps depend on.
 */
class ConvertToFastaUnitTest {

    private val smallseqRoot: Path = File("src/test/resources/smallseq")
        .absoluteFile.toPath()

    @AfterEach
    fun cleanup() {
        ProcessRunner.resetExecutor()
    }

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

    /** Parse the space-separated `key=value` pairs inside the `--args=` token. */
    private fun parseArgsValue(args: String): Map<String, String> =
        args.split(" ")
            .filter { it.startsWith("--") && it.contains("=") }
            .associate {
                val (k, v) = it.removePrefix("--").split("=", limit = 2)
                k to v
            }

    /**
     * Hook helper that simulates MLImpute writing a `.fasta` file for the
     * configured `--out-file` path so the command's `writeFilePaths()`
     * step can record real paths in `fasta_file_paths.txt`.
     */
    private fun mlimputeSimulator(): (RecordingProcessExecutor.Invocation) -> Int = { inv ->
        val args = inv.command.firstOrNull { it.startsWith("--args=") }?.removePrefix("--args=")
        args?.split(" ")
            ?.firstOrNull { it.startsWith("--out-file=") }
            ?.removePrefix("--out-file=")
            ?.let { out ->
                File(out).also { f ->
                    f.parentFile?.mkdirs()
                    f.writeText(">simulated\nACGT\n")
                }
            }
        0
    }

    @Test
    fun gradlewIsInvokedOncePerGvcfWithExpectedArgs(@TempDir workDir: Path) {
        val mlimputeKotlinDir = stubMlimpute(workDir)
        val gvcfDir = workDir.resolve("gvcfs").also { it.createDirectories() }
        gvcfDir.resolve("LineA.gvcf").writeText("##fileformat=VCFv4.2\n")
        gvcfDir.resolve("LineB.gvcf").writeText("##fileformat=VCFv4.2\n")

        val executor = RecordingProcessExecutor(defaultExitCode = 0, onInvoke = mlimputeSimulator())
        ProcessRunner.withExecutor(executor) {
            ConvertToFasta().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--gvcf-file", gvcfDir.toString(),
                    "--ref-fasta", smallseqRoot.resolve("Ref.fa").toString(),
                    "--missing-records-as", "asN",
                    "--missing-genotype-as", "asRef"
                )
            )
        }

        assertEquals(2, executor.invocations.size, "gradlew should be invoked once per GVCF file")

        val inv = executor.invocations.first()
        assertEquals("./gradlew", inv.command[0])
        assertEquals("run", inv.command[1])
        assertEquals(
            mlimputeKotlinDir.toFile().absoluteFile,
            inv.workingDir?.absoluteFile,
            "gradlew should run inside the MLImpute kotlin project"
        )

        val argsToken = inv.command.first { it.startsWith("--args=") }.removePrefix("--args=")
        assertTrue(
            argsToken.startsWith("convert-to-fasta "),
            "First token in --args should be the MLImpute subcommand `convert-to-fasta`"
        )
        val argMap = parseArgsValue(argsToken)
        assertEquals(
            smallseqRoot.resolve("Ref.fa").toAbsolutePath().toString(),
            argMap["fasta-file"]
        )
        assertEquals("asN", argMap["missing-records-as"])
        assertEquals("asRef", argMap["missing-genotype-as"])

        // out-file must be a .fasta path under the default output directory.
        val expectedOutDir = workDir.resolve("output/04_fasta_results")
            .toAbsolutePath().toString()
        val outFiles = executor.invocations.map { inv2 ->
            inv2.command.first { it.startsWith("--args=") }
                .let { parseArgsValue(it.removePrefix("--args=")) }["out-file"]!!
        }
        assertEquals(setOf(true), outFiles.map { it.endsWith(".fasta") }.toSet())
        assertTrue(
            outFiles.all { it.startsWith(expectedOutDir) },
            "All FASTA outputs should land in $expectedOutDir; got: $outFiles"
        )
    }

    @Test
    fun ignoreContigIsForwardedOnlyWhenProvided(@TempDir workDir: Path) {
        stubMlimpute(workDir)
        val gvcfDir = workDir.resolve("gvcfs").also { it.createDirectories() }
        gvcfDir.resolve("LineA.gvcf").writeText("##fileformat=VCFv4.2\n")

        val executor = RecordingProcessExecutor(defaultExitCode = 0, onInvoke = mlimputeSimulator())
        ProcessRunner.withExecutor(executor) {
            ConvertToFasta().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--gvcf-file", gvcfDir.toString(),
                    "--ref-fasta", smallseqRoot.resolve("Ref.fa").toString(),
                    "--ignore-contig", "chrUn,chloro"
                )
            )
        }

        val inv = executor.invocations.single()
        val argsToken = inv.command.first { it.startsWith("--args=") }.removePrefix("--args=")
        val argMap = parseArgsValue(argsToken)
        assertEquals("chrUn,chloro", argMap["ignore-contig"])
    }

    @Test
    fun ignoreContigIsOmittedWhenEmpty(@TempDir workDir: Path) {
        stubMlimpute(workDir)
        val gvcfDir = workDir.resolve("gvcfs").also { it.createDirectories() }
        gvcfDir.resolve("LineA.gvcf").writeText("##fileformat=VCFv4.2\n")

        val executor = RecordingProcessExecutor(defaultExitCode = 0, onInvoke = mlimputeSimulator())
        ProcessRunner.withExecutor(executor) {
            ConvertToFasta().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--gvcf-file", gvcfDir.toString(),
                    "--ref-fasta", smallseqRoot.resolve("Ref.fa").toString()
                )
            )
        }

        val argsToken = executor.invocations.single().command
            .first { it.startsWith("--args=") }
            .removePrefix("--args=")
        assertTrue(
            !argsToken.contains("--ignore-contig="),
            "--ignore-contig should not be forwarded when not provided"
        )
    }

    @Test
    fun compressedGvcfsAreDecompressedBeforeMlimputeIsCalled(@TempDir workDir: Path) {
        stubMlimpute(workDir)
        val gvcfDir = workDir.resolve("gvcfs").also { it.createDirectories() }
        writeGzipped(gvcfDir.resolve("LineA.g.vcf.gz"), "##fileformat=VCFv4.2\nA\n")
        writeGzipped(gvcfDir.resolve("LineB.gvcf.gz"), "##fileformat=VCFv4.2\nB\n")

        val executor = RecordingProcessExecutor(defaultExitCode = 0, onInvoke = mlimputeSimulator())
        ProcessRunner.withExecutor(executor) {
            ConvertToFasta().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--gvcf-file", gvcfDir.toString(),
                    "--ref-fasta", smallseqRoot.resolve("Ref.fa").toString()
                )
            )
        }

        // Each invocation's `--gvcf-file` argument must point at the
        // decompressed temp dir, NOT the original .g.vcf.gz inputs.
        val gvcfArgs = executor.invocations.map { inv ->
            inv.command.first { it.startsWith("--args=") }
                .let { parseArgsValue(it.removePrefix("--args=")) }["gvcf-file"]!!
        }
        assertEquals(2, gvcfArgs.size)
        assertTrue(
            gvcfArgs.all { it.endsWith(".gvcf") },
            "MLImpute must always be handed a .gvcf path; got: $gvcfArgs"
        )
        assertTrue(
            gvcfArgs.none { it.endsWith(".gz") },
            "Compressed inputs must be decompressed before forwarding; got: $gvcfArgs"
        )
    }

    @Test
    fun fastaFilePathsTextFileListsGeneratedFastas(@TempDir workDir: Path) {
        stubMlimpute(workDir)
        val gvcfDir = workDir.resolve("gvcfs").also { it.createDirectories() }
        gvcfDir.resolve("LineA.gvcf").writeText("##fileformat=VCFv4.2\n")
        gvcfDir.resolve("LineB.gvcf").writeText("##fileformat=VCFv4.2\n")
        gvcfDir.resolve("LineC.gvcf").writeText("##fileformat=VCFv4.2\n")

        val executor = RecordingProcessExecutor(defaultExitCode = 0, onInvoke = mlimputeSimulator())
        ProcessRunner.withExecutor(executor) {
            ConvertToFasta().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--gvcf-file", gvcfDir.toString(),
                    "--ref-fasta", smallseqRoot.resolve("Ref.fa").toString()
                )
            )
        }

        val fastaPaths = workDir.resolve("output/04_fasta_results/fasta_file_paths.txt").toFile()
        assertTrue(fastaPaths.exists(), "fasta_file_paths.txt should be written")
        val lines = fastaPaths.readLines().filter { it.isNotBlank() }
        assertEquals(3, lines.size, "One FASTA path per GVCF should be listed")
        assertTrue(
            lines.all { it.endsWith(".fasta") },
            "Every listed path should end with .fasta"
        )
        assertTrue(
            lines.all { File(it).exists() && File(it).length() > 0 },
            "Every listed FASTA must exist on disk and be non-empty (from simulator)"
        )
    }

    @Test
    fun tempUncompressedDirIsAlwaysCleanedUp(@TempDir workDir: Path) {
        stubMlimpute(workDir)
        val gvcfDir = workDir.resolve("gvcfs").also { it.createDirectories() }
        writeGzipped(gvcfDir.resolve("LineA.g.vcf.gz"), "##fileformat=VCFv4.2\n")

        val executor = RecordingProcessExecutor(defaultExitCode = 0, onInvoke = mlimputeSimulator())
        ProcessRunner.withExecutor(executor) {
            ConvertToFasta().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--gvcf-file", gvcfDir.toString(),
                    "--ref-fasta", smallseqRoot.resolve("Ref.fa").toString()
                )
            )
        }

        val tempDir = workDir.resolve("temp_uncompressed_gvcf_fasta")
        assertTrue(!tempDir.exists(), "Temp uncompressed dir should be removed after the run")
    }

    @Test
    fun customOutputDirIsHonored(@TempDir workDir: Path) {
        stubMlimpute(workDir)
        val gvcfDir = workDir.resolve("gvcfs").also { it.createDirectories() }
        gvcfDir.resolve("LineA.gvcf").writeText("##fileformat=VCFv4.2\n")
        val customOutput = workDir.resolve("custom_fasta_dir")

        val executor = RecordingProcessExecutor(defaultExitCode = 0, onInvoke = mlimputeSimulator())
        ProcessRunner.withExecutor(executor) {
            ConvertToFasta().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--gvcf-file", gvcfDir.toString(),
                    "--ref-fasta", smallseqRoot.resolve("Ref.fa").toString(),
                    "--output-dir", customOutput.toString()
                )
            )
        }

        val argsToken = executor.invocations.single().command
            .first { it.startsWith("--args=") }
            .removePrefix("--args=")
        val argMap = parseArgsValue(argsToken)
        val outFile = argMap["out-file"]!!
        assertTrue(
            outFile.startsWith(customOutput.toAbsolutePath().toString()),
            "Custom --output-dir should be respected (got out-file=$outFile)"
        )
        assertTrue(
            customOutput.resolve("fasta_file_paths.txt").toFile().exists(),
            "fasta_file_paths.txt should be written under the custom output dir"
        )
    }
}
