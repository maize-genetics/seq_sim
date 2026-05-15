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
 * Unit tests for [MafToGvcf] (step 02 of the variant pipeline) that
 * don't actually shell out to `biokotlin-tools`. We install a
 * [RecordingProcessExecutor] and verify the exact command line that
 * seq-sim would send to `biokotlin-tools maf-to-gvcf-converter`, plus
 * that `gvcf_file_paths.txt` is written for downstream steps.
 */
class MafToGvcfUnitTest {

    private val smallseqRoot: Path = File("src/test/resources/smallseq")
        .absoluteFile.toPath()

    // The dev container sets SEQ_SIM_SKIP_PIXI_PREFIX=1, which makes
    // ProcessRunner silently drop a leading `pixi run` before it reaches the
    // executor. That's the right behavior at runtime (the container's PATH
    // already has the pixi tools on it), but it would break the positional
    // assertions below that verify *what the command builds*, not what
    // actually runs. We snapshot the flag, force it off for the duration of
    // each test, and restore it in @AfterEach so we don't leak state into
    // other test classes.
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

    /**
     * Create a fake biokotlin-tools layout (bin/biokotlin-tools) inside
     * [workDir] so the command's
     * [net.maizegenetics.utils.ValidationUtils.validateBiokotlinSetup] passes.
     */
    private fun stubBiokotlinBinary(workDir: Path): Path {
        val biokotlinDir = workDir.resolve("src/biokotlin-tools/bin")
        biokotlinDir.createDirectories()
        val binary = biokotlinDir.resolve("biokotlin-tools")
        binary.writeText("#!/bin/sh\nexit 0\n")
        binary.toFile().setExecutable(true)
        return binary
    }

    /**
     * Drop a few stub `.maf` files into [dir] so the command's
     * `collectMafFiles()` has something to iterate over. Content doesn't
     * matter because biokotlin-tools is mocked.
     */
    private fun stubMafFiles(dir: Path, names: List<String>): List<Path> {
        dir.createDirectories()
        return names.map { name ->
            val f = dir.resolve(name)
            f.writeText("##maf version=1\n")
            f
        }
    }

    @Test
    fun biokotlinIsInvokedOncePerMafWithExpectedArgs(@TempDir workDir: Path) {
        val biokotlinBinary = stubBiokotlinBinary(workDir)
        val mafDir = workDir.resolve("mafs")
        stubMafFiles(mafDir, listOf("LineA.maf", "LineB.maf"))

        val executor = RecordingProcessExecutor(defaultExitCode = 0) { inv ->
            // Simulate biokotlin-tools producing the .g.vcf.gz output file.
            val outputArg = inv.command.firstOrNull { it.startsWith("--output-file=") }
            outputArg?.removePrefix("--output-file=")?.let { outputPath ->
                File("$outputPath.gz").also { f ->
                    f.parentFile?.mkdirs()
                    f.writeText("##fileformat=VCFv4.2\n")
                }
            }
            0
        }

        ProcessRunner.withExecutor(executor) {
            MafToGvcf().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--reference-file", smallseqRoot.resolve("Ref.fa").toString(),
                    "--maf-file", mafDir.toString(),
                    "--sample-name", "smallseq"
                )
            )
        }

        // One invocation per MAF file
        assertEquals(2, executor.invocations.size, "biokotlin should be invoked once per MAF file")

        // Verify command shape on the first invocation
        val inv = executor.invocations.first()
        assertTrue(
            inv.command.first() == "pixi" && inv.command[1] == "run",
            "biokotlin-tools should be launched through `pixi run`"
        )
        assertEquals(biokotlinBinary.toString(), inv.command[2])
        assertEquals("maf-to-gvcf-converter", inv.command[3])

        val refArg = inv.command.firstOrNull { it.startsWith("--reference-file=") }
        assertEquals(
            "--reference-file=${smallseqRoot.resolve("Ref.fa")}",
            refArg
        )
        val sampleNameArg = inv.command.firstOrNull { it.startsWith("--sample-name=") }
        assertEquals("--sample-name=smallseq", sampleNameArg)

        // Auto-generated output files end with .g.vcf and live in default 02_gvcf_results
        val expectedOutDir = workDir.resolve("output/02_gvcf_results")
        val outputArgs = executor.invocations.map { inv2 ->
            inv2.command.first { it.startsWith("--output-file=") }
        }
        assertTrue(
            outputArgs.all { it.endsWith(".g.vcf") },
            "Auto-generated output filenames should end with .g.vcf (biokotlin adds .gz)"
        )
        assertTrue(
            outputArgs.all { it.contains(expectedOutDir.toString()) },
            "Outputs should live in <workDir>/output/02_gvcf_results by default"
        )
    }

    @Test
    fun sampleNameDefaultsToMafBaseNameWhenUnset(@TempDir workDir: Path) {
        stubBiokotlinBinary(workDir)
        val mafDir = workDir.resolve("mafs")
        stubMafFiles(mafDir, listOf("LineA.maf", "LineB.maf"))

        val executor = RecordingProcessExecutor(defaultExitCode = 0) { inv ->
            // Produce the expected .g.vcf.gz so writeFilePaths succeeds.
            val outputArg = inv.command.firstOrNull { it.startsWith("--output-file=") }
            outputArg?.removePrefix("--output-file=")?.let { outputPath ->
                File("$outputPath.gz").also { f ->
                    f.parentFile?.mkdirs()
                    f.writeText("##fileformat=VCFv4.2\n")
                }
            }
            0
        }

        ProcessRunner.withExecutor(executor) {
            MafToGvcf().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--reference-file", smallseqRoot.resolve("Ref.fa").toString(),
                    "--maf-file", mafDir.toString()
                )
            )
        }

        val sampleNames = executor.invocations.map { inv ->
            inv.command.first { it.startsWith("--sample-name=") }
                .removePrefix("--sample-name=")
        }
        assertEquals(setOf("LineA", "LineB"), sampleNames.toSet())
    }

    @Test
    fun gvcfFilePathsTextFileListsGeneratedGvcfs(@TempDir workDir: Path) {
        stubBiokotlinBinary(workDir)
        val mafDir = workDir.resolve("mafs")
        stubMafFiles(mafDir, listOf("LineA.maf", "LineB.maf", "LineC.maf"))

        // RecordingProcessExecutor doesn't run biokotlin, so simulate its
        // side-effect: write a .g.vcf.gz at the requested --output-file path
        // (with biokotlin's implicit .gz suffix added by the command itself).
        val executor = RecordingProcessExecutor(defaultExitCode = 0) { inv ->
            val outputArg = inv.command.firstOrNull { it.startsWith("--output-file=") }
            outputArg?.removePrefix("--output-file=")?.let { outputPath ->
                File("$outputPath.gz").also { f ->
                    f.parentFile?.mkdirs()
                    f.writeText("##fileformat=VCFv4.2\n")
                }
            }
            0
        }

        ProcessRunner.withExecutor(executor) {
            MafToGvcf().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--reference-file", smallseqRoot.resolve("Ref.fa").toString(),
                    "--maf-file", mafDir.toString()
                )
            )
        }

        val gvcfPaths = workDir.resolve("output/02_gvcf_results/gvcf_file_paths.txt").toFile()
        assertTrue(gvcfPaths.exists(), "gvcf_file_paths.txt should be written")
        val lines = gvcfPaths.readLines().filter { it.isNotBlank() }
        assertEquals(3, lines.size, "One GVCF path per MAF file should be listed")
        assertTrue(
            lines.all { it.endsWith(".g.vcf.gz") },
            "Every listed path should be a .g.vcf.gz file"
        )
        assertTrue(
            lines.all { File(it).exists() },
            "Every listed GVCF must exist on disk"
        )
    }

    @Test
    fun customOutputDirIsHonored(@TempDir workDir: Path) {
        stubBiokotlinBinary(workDir)
        val mafDir = workDir.resolve("mafs")
        stubMafFiles(mafDir, listOf("LineA.maf"))
        val customOutput = workDir.resolve("custom_gvcf_dir")

        val executor = RecordingProcessExecutor(defaultExitCode = 0) { inv ->
            val outputArg = inv.command.firstOrNull { it.startsWith("--output-file=") }
            outputArg?.removePrefix("--output-file=")?.let { outputPath ->
                File("$outputPath.gz").also { f ->
                    f.parentFile?.mkdirs()
                    f.writeText("##fileformat=VCFv4.2\n")
                }
            }
            0
        }

        ProcessRunner.withExecutor(executor) {
            MafToGvcf().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--reference-file", smallseqRoot.resolve("Ref.fa").toString(),
                    "--maf-file", mafDir.toString(),
                    "--output-dir", customOutput.toString()
                )
            )
        }

        val inv = executor.invocations.single()
        val outputArg = inv.command.first { it.startsWith("--output-file=") }
        assertTrue(
            outputArg.startsWith("--output-file=${customOutput.toAbsolutePath()}"),
            "Outputs should be written to the custom --output-dir: $outputArg"
        )
        assertTrue(
            customOutput.resolve("gvcf_file_paths.txt").toFile().exists(),
            "gvcf_file_paths.txt should be written under the custom output dir"
        )
    }

    @Test
    fun singleMafFileWithExplicitOutputFileNameIsHonored(@TempDir workDir: Path) {
        stubBiokotlinBinary(workDir)
        val mafFile = workDir.resolve("only.maf").also {
            it.writeText("##maf version=1\n")
        }
        val customOutputName = workDir.resolve("custom_name.g.vcf")

        val executor = RecordingProcessExecutor(defaultExitCode = 0) { inv ->
            val outputArg = inv.command.firstOrNull { it.startsWith("--output-file=") }
            outputArg?.removePrefix("--output-file=")?.let { outputPath ->
                File("$outputPath.gz").also { f ->
                    f.parentFile?.mkdirs()
                    f.writeText("##fileformat=VCFv4.2\n")
                }
            }
            0
        }

        ProcessRunner.withExecutor(executor) {
            MafToGvcf().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--reference-file", smallseqRoot.resolve("Ref.fa").toString(),
                    "--maf-file", mafFile.toString(),
                    "--output-file", customOutputName.toString()
                )
            )
        }

        val inv = executor.invocations.single()
        val outputArg = inv.command.first { it.startsWith("--output-file=") }
        // biokotlin adds .gz, so the .g.vcf form should be passed in
        assertTrue(
            outputArg.endsWith("/custom_name.g.vcf"),
            "Explicit --output-file should be respected (got: $outputArg)"
        )
    }
}
