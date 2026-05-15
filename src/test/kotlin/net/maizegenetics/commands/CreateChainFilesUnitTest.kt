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
 * Unit tests for [CreateChainFiles] (step 06 of the recombination pipeline).
 * The command shells out to MLImpute's `create_chains.sh` via `bash` --
 * no `pixi run` prefix here, so we can verify the exact argv directly.
 *
 * The test also asserts the rename-to-`_subsampled.chain` invariant and
 * the temp-MAF-dir cleanup behaviour that downstream steps rely on.
 */
class CreateChainFilesUnitTest {

    @AfterEach
    fun cleanup() {
        ProcessRunner.resetExecutor()
    }

    /**
     * Create the fake MLImpute layout with `create_chains.sh` present so
     * the command's existence check passes.
     */
    private fun stubMlimputeChainScript(workDir: Path): Path {
        val scriptDir = workDir.resolve("src/MLImpute/src/python/cross")
        scriptDir.createDirectories()
        val script = scriptDir.resolve("create_chains.sh")
        script.writeText("#!/bin/sh\nexit 0\n")
        script.toFile().setExecutable(true)
        return script
    }

    /**
     * Drop a few stub `.maf` files into [dir] so the command's
     * `collectMafFiles()` has something to iterate over.
     */
    private fun stubMafFiles(dir: Path, names: List<String>): List<Path> {
        dir.createDirectories()
        return names.map { name ->
            val f = dir.resolve(name)
            f.writeText("##maf version=1\n")
            f
        }
    }

    /**
     * Hook helper that simulates create_chains.sh by writing a `.chain`
     * file in the output directory for every MAF file staged into the
     * temporary input directory.
     */
    private fun createChainsSimulator(): (RecordingProcessExecutor.Invocation) -> Int = { inv ->
        val tempInputDir = inv.command.dropWhile { it != "-i" }.getOrNull(1)?.let { File(it) }
        val outDir = inv.command.dropWhile { it != "-o" }.getOrNull(1)?.let { File(it) }
        outDir?.mkdirs()
        tempInputDir?.listFiles { f -> f.name.endsWith(".maf") }?.forEach { mafFile ->
            val chainName = mafFile.nameWithoutExtension + ".chain"
            File(outDir, chainName).writeText("# chain for ${mafFile.name}\n")
        }
        0
    }

    @Test
    fun bashScriptIsInvokedWithExpectedArgs(@TempDir workDir: Path) {
        val bashScript = stubMlimputeChainScript(workDir)
        val mafDir = workDir.resolve("mafs")
        stubMafFiles(mafDir, listOf("LineA.maf", "LineB.maf"))

        val executor = RecordingProcessExecutor(defaultExitCode = 0, onInvoke = createChainsSimulator())

        ProcessRunner.withExecutor(executor) {
            CreateChainFiles().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--maf-input", mafDir.toString(),
                    "--jobs", "4"
                )
            )
        }

        assertEquals(1, executor.invocations.size, "create_chains.sh should be invoked exactly once")
        val inv = executor.invocations.single()

        assertEquals("bash", inv.command[0])
        assertEquals(bashScript.toString(), inv.command[1])
        assertEquals("4", inv.argAfter("-j"))

        // -i must point at a temp dir under the output dir
        val outputDir = workDir.resolve("output/06_chain_results")
        assertEquals(outputDir.toAbsolutePath().toString(), inv.argAfter("-o"))

        val tempInputArg = inv.argAfter("-i")!!
        assertTrue(
            tempInputArg.startsWith(outputDir.toAbsolutePath().toString()),
            "-i should point under the output directory (got: $tempInputArg)"
        )
        // The command sets workingDir to the (parent) work directory.
        assertEquals(workDir.toFile().absoluteFile, inv.workingDir?.absoluteFile)
    }

    @Test
    fun jobsDefaultsToEightWhenNotProvided(@TempDir workDir: Path) {
        stubMlimputeChainScript(workDir)
        val mafDir = workDir.resolve("mafs")
        stubMafFiles(mafDir, listOf("LineA.maf"))

        val executor = RecordingProcessExecutor(defaultExitCode = 0, onInvoke = createChainsSimulator())

        ProcessRunner.withExecutor(executor) {
            CreateChainFiles().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--maf-input", mafDir.toString()
                )
            )
        }

        val inv = executor.invocations.single()
        assertEquals("8", inv.argAfter("-j"))
    }

    @Test
    fun tempMafDirectoryIsCleanedUpAfterRun(@TempDir workDir: Path) {
        stubMlimputeChainScript(workDir)
        val mafDir = workDir.resolve("mafs")
        stubMafFiles(mafDir, listOf("LineA.maf"))

        val executor = RecordingProcessExecutor(defaultExitCode = 0, onInvoke = createChainsSimulator())

        ProcessRunner.withExecutor(executor) {
            CreateChainFiles().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--maf-input", mafDir.toString()
                )
            )
        }

        val tempMafDir = workDir.resolve("output/06_chain_results/temp_maf_files").toFile()
        assertTrue(
            !tempMafDir.exists(),
            "temp_maf_files should be removed after the run (got existing dir at $tempMafDir)"
        )
    }

    @Test
    fun chainFilesAreRenamedToSubsampledAndListedInPathsFile(@TempDir workDir: Path) {
        stubMlimputeChainScript(workDir)
        val mafDir = workDir.resolve("mafs")
        stubMafFiles(mafDir, listOf("LineA.maf", "LineB.maf", "LineC.maf"))

        val executor = RecordingProcessExecutor(defaultExitCode = 0, onInvoke = createChainsSimulator())

        ProcessRunner.withExecutor(executor) {
            CreateChainFiles().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--maf-input", mafDir.toString()
                )
            )
        }

        val outputDir = workDir.resolve("output/06_chain_results").toFile()
        val chainFiles = outputDir.listFiles { f -> f.name.endsWith(".chain") }!!
        assertEquals(3, chainFiles.size, "One chain per MAF should be produced")
        assertTrue(
            chainFiles.all { it.name.endsWith("_subsampled.chain") },
            "Chain files should be renamed with _subsampled suffix; got: ${chainFiles.map { it.name }}"
        )

        val pathsFile = outputDir.resolve("chain_file_paths.txt")
        assertTrue(pathsFile.exists(), "chain_file_paths.txt should be written")
        val lines = pathsFile.readLines().filter { it.isNotBlank() }
        assertEquals(3, lines.size, "One line per chain file")
        assertTrue(
            lines.all { it.endsWith("_subsampled.chain") },
            "Every listed path should end with _subsampled.chain"
        )
    }

    @Test
    fun customOutputDirIsHonored(@TempDir workDir: Path) {
        stubMlimputeChainScript(workDir)
        val mafDir = workDir.resolve("mafs")
        stubMafFiles(mafDir, listOf("LineA.maf"))
        val customOutput = workDir.resolve("custom_chain_out")

        val executor = RecordingProcessExecutor(defaultExitCode = 0, onInvoke = createChainsSimulator())

        ProcessRunner.withExecutor(executor) {
            CreateChainFiles().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--maf-input", mafDir.toString(),
                    "--output-dir", customOutput.toString()
                )
            )
        }

        val inv = executor.invocations.single()
        assertEquals(customOutput.toAbsolutePath().toString(), inv.argAfter("-o"))
        assertTrue(
            customOutput.resolve("chain_file_paths.txt").toFile().exists(),
            "chain_file_paths.txt should be written under the custom output dir"
        )
    }
}
