package net.maizegenetics.commands.align

import net.maizegenetics.utils.ProcessRunner
import net.maizegenetics.utils.testing.RecordingProcessExecutor
import org.apache.logging.log4j.LogManager
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
 * Unit tests for [PhgAlignRunner] -- the shared backend used by both
 * [net.maizegenetics.commands.AlignAssemblies] and
 * [net.maizegenetics.commands.AlignMutatedAssemblies]. The wrappers' own
 * tests still exercise the externally observable behaviour (command line,
 * `assemblies_list.txt`, `maf_file_paths.txt`), so these tests focus on
 * the runner's invariants directly: it should work for any caller, with
 * any combination of step metadata.
 */
class PhgAlignRunnerUnitTest {

    private val smallseqRoot: Path = File("src/test/resources/smallseq")
        .absoluteFile.toPath()

    private val logger = LogManager.getLogger(PhgAlignRunnerUnitTest::class.java)

    @AfterEach
    fun cleanup() {
        ProcessRunner.resetExecutor()
    }

    /**
     * Create a fake PHG layout (bin/phg) inside [workDir] so the runner's
     * [net.maizegenetics.utils.ValidationUtils.validatePhgSetup] passes.
     */
    private fun stubPhgBinary(workDir: Path) {
        val phgDir = workDir.resolve("src/phg_v2/bin")
        phgDir.createDirectories()
        val phg = phgDir.resolve("phg")
        phg.writeText("#!/bin/sh\nexit 0\n")
        phg.toFile().setExecutable(true)
    }

    private fun baseParams(workDir: Path, queryInput: Path) = PhgAlignParams(
        workDir = workDir,
        refGff = smallseqRoot.resolve("anchors.gff"),
        refFasta = smallseqRoot.resolve("Ref.fa"),
        queryInput = queryInput,
        threads = 2,
        logFileName = "test_align.log",
        outputSubdir = "test_align_results",
        inputKind = "query",
    )

    @Test
    fun happyPathInvokesPhgWithSharedArgs(@TempDir workDir: Path) {
        stubPhgBinary(workDir)
        val executor = RecordingProcessExecutor(defaultExitCode = 0)

        ProcessRunner.withExecutor(executor) {
            PhgAlignRunner.run(
                baseParams(workDir, smallseqRoot.resolve("queries")),
                logger,
            )
        }

        assertEquals(1, executor.invocations.size, "phg align-assemblies should be invoked exactly once")
        val inv = executor.invocations.single()
        assertTrue(inv.command.first().endsWith("phg"), "First token should be the phg binary")
        assertEquals("align-assemblies", inv.command[1])

        assertEquals(
            smallseqRoot.resolve("anchors.gff").toAbsolutePath().toString(),
            inv.argAfter("--gff")
        )
        assertEquals(
            smallseqRoot.resolve("Ref.fa").toAbsolutePath().toString(),
            inv.argAfter("--reference-file")
        )
        assertEquals("2", inv.argAfter("--total-threads"))

        // Output dir uses params.outputSubdir under <workDir>/output/
        val expectedOutputDir = workDir.resolve("output/test_align_results")
        assertEquals(expectedOutputDir.toAbsolutePath().toString(), inv.argAfter("-o"))

        // assemblies_list.txt is materialized inside the output dir
        val assemblyList = expectedOutputDir.resolve("assemblies_list.txt").toFile()
        assertTrue(assemblyList.exists(), "assemblies_list.txt should have been written")
        val listed = assemblyList.readLines().filter { it.isNotBlank() }
        assertEquals(3, listed.size, "Smallseq queries directory contains 3 FASTAs")

        // Optional knobs absent unless set
        assertTrue(!inv.command.contains("--in-parallel"))
        assertTrue(!inv.command.contains("--ref-max-align-cov"))
        assertTrue(!inv.command.contains("--query-max-align-cov"))
        assertTrue(!inv.command.contains("--conda-env-prefix"))
        assertTrue(!inv.command.contains("--just-ref-prep"))
    }

    @Test
    fun runnerHonoursPerCallerLogAndOutputMetadata(@TempDir workDir: Path) {
        stubPhgBinary(workDir)
        val executor = RecordingProcessExecutor(defaultExitCode = 0)

        // Different log filename + subdir from the default test params
        val params = baseParams(workDir, smallseqRoot.resolve("queries")).copy(
            logFileName = "99_custom_step.log",
            outputSubdir = "99_custom_step_results",
            inputKind = "FASTA",
        )

        ProcessRunner.withExecutor(executor) {
            PhgAlignRunner.run(params, logger)
        }

        val inv = executor.invocations.single()
        val expectedOutputDir = workDir.resolve("output/99_custom_step_results")
        assertEquals(expectedOutputDir.toAbsolutePath().toString(), inv.argAfter("-o"))
        assertTrue(
            workDir.resolve("logs/99_custom_step.log").toFile().exists(),
            "Custom log file should be created under <workDir>/logs/"
        )
    }

    @Test
    fun justRefPrepSkipsMafFilePathsTextFile(@TempDir workDir: Path) {
        stubPhgBinary(workDir)
        val executor = RecordingProcessExecutor(defaultExitCode = 0)

        val params = baseParams(workDir, smallseqRoot.resolve("queries")).copy(
            justRefPrep = true,
        )

        ProcessRunner.withExecutor(executor) {
            PhgAlignRunner.run(params, logger)
        }

        val inv = executor.invocations.single()
        assertTrue(inv.command.contains("--just-ref-prep"), "--just-ref-prep should be forwarded")

        val mafPathsFile = workDir.resolve("output/test_align_results/maf_file_paths.txt").toFile()
        assertTrue(
            !mafPathsFile.exists(),
            "maf_file_paths.txt should NOT be written when --just-ref-prep is set"
        )
    }

    @Test
    fun referenceFastaIsFilteredOutOfAssemblyList(@TempDir workDir: Path) {
        stubPhgBinary(workDir)
        val executor = RecordingProcessExecutor(defaultExitCode = 0)

        // Build a text-list input that intentionally includes Ref.fa alongside
        // the three real queries -- the runner should drop the reference.
        val refFasta = smallseqRoot.resolve("Ref.fa").toAbsolutePath()
        val queryList = workDir.resolve("queries_with_ref.txt")
        queryList.writeText(
            buildString {
                appendLine(refFasta.toString())
                appendLine(smallseqRoot.resolve("queries/LineA.fa").toAbsolutePath().toString())
                appendLine(smallseqRoot.resolve("queries/LineB.fa").toAbsolutePath().toString())
                appendLine(smallseqRoot.resolve("queries/LineC.fa").toAbsolutePath().toString())
            }
        )

        ProcessRunner.withExecutor(executor) {
            PhgAlignRunner.run(baseParams(workDir, queryList), logger)
        }

        val inv = executor.invocations.single()
        val assemblyListPath = inv.argAfter("--assembly-file-list")
            ?: error("--assembly-file-list not present in command")
        val listed = File(assemblyListPath).readLines().filter { it.isNotBlank() }
        assertEquals(
            3,
            listed.size,
            "Reference FASTA should have been filtered out, leaving the 3 queries"
        )
        assertTrue(
            listed.none { it == refFasta.toString() },
            "Reference FASTA should not be present in the assembly file list"
        )
    }

    @Test
    fun optionalKnobsAreForwardedWhenProvided(@TempDir workDir: Path) {
        stubPhgBinary(workDir)
        val executor = RecordingProcessExecutor(defaultExitCode = 0)
        val condaPrefix = workDir.resolve("conda_env").also { it.createDirectories() }

        val params = baseParams(workDir, smallseqRoot.resolve("queries/LineA.fa")).copy(
            threads = 4,
            inParallel = 2,
            refMaxAlignCov = 3,
            queryMaxAlignCov = 5,
            condaEnvPrefix = condaPrefix,
        )

        ProcessRunner.withExecutor(executor) {
            PhgAlignRunner.run(params, logger)
        }

        val inv = executor.invocations.single()
        assertEquals("4", inv.argAfter("--total-threads"))
        assertEquals("2", inv.argAfter("--in-parallel"))
        assertEquals("3", inv.argAfter("--ref-max-align-cov"))
        assertEquals("5", inv.argAfter("--query-max-align-cov"))
        assertEquals(condaPrefix.toAbsolutePath().toString(), inv.argAfter("--conda-env-prefix"))
    }
}
