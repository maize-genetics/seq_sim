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
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [AlignAssemblies] that don't actually shell out to the
 * PHGv2 binary -- we install a [RecordingProcessExecutor] and verify the
 * exact command line seq-sim would send to `phg align-assemblies`.
 */
class AlignAssembliesUnitTest {

    private val smallseqRoot: Path = File("src/test/resources/smallseq")
        .absoluteFile.toPath()

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
    fun phgAlignAssembliesIsInvokedExactlyOnce(@TempDir workDir: Path) {
        stubPhgBinary(workDir)
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

        assertEquals(1, executor.invocations.size, "phg align-assemblies should be invoked exactly once")
        val inv = executor.invocations.single()
        assertTrue(inv.command.first().endsWith("phg"), "First token should be the phg binary")
        assertEquals("align-assemblies", inv.command[1])

        // Required PHGv2 args are present
        assertEquals(
            smallseqRoot.resolve("anchors.gff").toAbsolutePath().toString(),
            inv.argAfter("--gff")
        )
        assertEquals(
            smallseqRoot.resolve("Ref.fa").toAbsolutePath().toString(),
            inv.argAfter("--reference-file")
        )
        assertEquals("2", inv.argAfter("--total-threads"))

        // PHGv2 expects the output dir to exist before running and we hand it
        // an assembly-file-list materialized inside that output dir.
        val expectedOutputDir = workDir.resolve("output/01_anchorwave_results")
        assertEquals(expectedOutputDir.toAbsolutePath().toString(), inv.argAfter("-o"))
        val assemblyList = expectedOutputDir.resolve("assemblies_list.txt").toFile()
        assertTrue(assemblyList.exists(), "assemblies_list.txt should have been written")
        val listed = assemblyList.readLines().filter { it.isNotBlank() }
        assertEquals(3, listed.size, "Smallseq queries directory contains 3 FASTAs")

        // Optional flags should NOT be present when not set
        assertTrue(!inv.command.contains("--in-parallel"))
        assertTrue(!inv.command.contains("--ref-max-align-cov"))
        assertTrue(!inv.command.contains("--query-max-align-cov"))
        assertTrue(!inv.command.contains("--conda-env-prefix"))
        assertTrue(!inv.command.contains("--just-ref-prep"))
    }

    @Test
    fun optionalPhgParametersAreForwardedWhenProvided(@TempDir workDir: Path) {
        stubPhgBinary(workDir)
        val executor = RecordingProcessExecutor(defaultExitCode = 0)
        val condaPrefix = workDir.resolve("conda_env").also { it.createDirectories() }

        ProcessRunner.withExecutor(executor) {
            AlignAssemblies().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--ref-gff", smallseqRoot.resolve("anchors.gff").toString(),
                    "--ref-fasta", smallseqRoot.resolve("Ref.fa").toString(),
                    "--query-fasta", smallseqRoot.resolve("queries/LineA.fa").toString(),
                    "--threads", "4",
                    "--in-parallel", "2",
                    "--ref-max-align-cov", "3",
                    "--query-max-align-cov", "5",
                    "--conda-env-prefix", condaPrefix.toString()
                )
            )
        }

        val inv = executor.invocations.single()
        assertEquals("4", inv.argAfter("--total-threads"))
        assertEquals("2", inv.argAfter("--in-parallel"))
        assertEquals("3", inv.argAfter("--ref-max-align-cov"))
        assertEquals("5", inv.argAfter("--query-max-align-cov"))
        assertEquals(condaPrefix.toAbsolutePath().toString(), inv.argAfter("--conda-env-prefix"))
    }

    @Test
    fun justRefPrepSkipsMafFilePathsTextFile(@TempDir workDir: Path) {
        stubPhgBinary(workDir)
        val executor = RecordingProcessExecutor(defaultExitCode = 0)

        ProcessRunner.withExecutor(executor) {
            AlignAssemblies().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--ref-gff", smallseqRoot.resolve("anchors.gff").toString(),
                    "--ref-fasta", smallseqRoot.resolve("Ref.fa").toString(),
                    "--query-fasta", smallseqRoot.resolve("queries").toString(),
                    "--just-ref-prep"
                )
            )
        }

        val inv = executor.invocations.single()
        assertTrue(inv.command.contains("--just-ref-prep"), "--just-ref-prep should be forwarded")

        val mafPathsFile = workDir.resolve("output/01_anchorwave_results/maf_file_paths.txt").toFile()
        assertTrue(
            !mafPathsFile.exists(),
            "maf_file_paths.txt should NOT be written when --just-ref-prep is set"
        )
    }

    @Test
    fun mafFilePathsTextFileListsMafsWrittenByPhg(@TempDir workDir: Path) {
        stubPhgBinary(workDir)

        // RecordingProcessExecutor doesn't actually run phg, so simulate its
        // side-effect: drop one .maf file per query into the output directory
        // before the phg invocation "returns".
        val executor = RecordingProcessExecutor(defaultExitCode = 0) { inv ->
            val outputDir = inv.command.dropWhile { it != "-o" }.getOrNull(1)?.let { File(it) }
            outputDir?.mkdirs()
            val listFile = inv.command.dropWhile { it != "--assembly-file-list" }.getOrNull(1)?.let { File(it) }
            listFile?.readLines()?.filter { it.isNotBlank() }?.forEach { fastaPath ->
                val sampleName = File(fastaPath).nameWithoutExtension
                File(outputDir, "$sampleName.maf").writeText("##maf version=1\n")
            }
            0
        }

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
        assertEquals(3, lines.size, "Should list one MAF path per simulated phg output")
        assertTrue(lines.all { it.endsWith(".maf") }, "Every listed path should be a .maf file")
    }
}
