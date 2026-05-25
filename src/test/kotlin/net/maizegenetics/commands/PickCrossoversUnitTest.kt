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
 * Unit tests for [PickCrossovers] (step 05 of the recombination pipeline).
 * The command shells out to MLImpute's `pick_crossovers.py` via
 * `pixi run sh -c "..."` -- we install a [RecordingProcessExecutor] and
 * verify the exact command line, the working directory, and the
 * `refkey_file_paths.txt` output contract that downstream steps rely on.
 */
class PickCrossoversUnitTest {

    private val smallseqRoot: Path = File("src/test/resources/smallseq")
        .absoluteFile.toPath()

    // PickCrossovers builds a `pixi run sh -c <shellCommand>` command. In the
    // dev container ProcessRunner.skipPixiPrefix=1 silently drops the leading
    // `pixi run` token, which would make positional assertions brittle.
    // Snapshot, force off for the test body, restore in @AfterEach.
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
     * Create the fake MLImpute layout including the python package and the
     * `pick_crossovers.py` script that the command's existence check looks
     * for at `<workDir>/src/MLImpute/src/python/cross/pick_crossovers.py`.
     */
    private fun stubMlimputePythonScript(workDir: Path): Path {
        val scriptDir = workDir.resolve("src/MLImpute/src/python/cross")
        scriptDir.createDirectories()
        val script = scriptDir.resolve("pick_crossovers.py")
        script.writeText("#!/usr/bin/env python\n")
        return script
    }

    /**
     * Write a minimal tab-separated assembly list at [path] referencing the
     * given assembly names. The path column doesn't need to exist on disk --
     * pick_crossovers.py is mocked.
     */
    private fun writeAssemblyList(path: Path, assemblies: List<String>) {
        path.writeText(
            assemblies.joinToString("\n") { name ->
                "/some/path/$name.fa\t$name"
            }
        )
    }

    /**
     * RecordingProcessExecutor hook that simulates pick_crossovers.py by
     * writing one `<assemblyName>_refkey.bed` per assembly name listed in
     * the assembly-list referenced by the shell command. The output
     * directory is derived from the executor invocation's `workingDir`,
     * which the command sets to the per-step output dir.
     */
    private fun pickCrossoversSimulator(
        assemblyListPath: Path,
    ): (RecordingProcessExecutor.Invocation) -> Int = { inv ->
        val outDir = inv.workingDir
        outDir?.mkdirs()
        assemblyListPath.toFile().readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { it.split("\t").getOrNull(1)?.trim() }
            .forEach { name ->
                File(outDir, "${name}_refkey.bed").writeText("# refkey for $name\n")
            }
        0
    }

    @Test
    fun pickCrossoversInvokesPixiShellOnceWithExpectedArgs(@TempDir workDir: Path) {
        stubMlimputePythonScript(workDir)
        val assemblyList = workDir.resolve("assembly_list.txt")
        writeAssemblyList(assemblyList, listOf("LineA", "LineB"))

        val executor = RecordingProcessExecutor(
            defaultExitCode = 0,
            onInvoke = pickCrossoversSimulator(assemblyList)
        )

        ProcessRunner.withExecutor(executor) {
            PickCrossovers().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--ref-fasta", smallseqRoot.resolve("Ref.fa").toString(),
                    "--assembly-list", assemblyList.toString()
                )
            )
        }

        assertEquals(1, executor.invocations.size, "pick_crossovers.py should be invoked exactly once")
        val inv = executor.invocations.single()

        // Command shape: pixi run sh -c "<shellCommand>"
        assertEquals("pixi", inv.command[0])
        assertEquals("run", inv.command[1])
        assertEquals("sh", inv.command[2])
        assertEquals("-c", inv.command[3])
        val shellCommand = inv.command[4]

        // PYTHONPATH points to <workDir>/src/MLImpute/src so 'python.*' imports resolve.
        val expectedPythonPath = workDir.resolve("src/MLImpute/src").toAbsolutePath().toString()
        assertTrue(
            shellCommand.contains("PYTHONPATH='$expectedPythonPath'"),
            "Shell command should export PYTHONPATH=$expectedPythonPath; got: $shellCommand"
        )

        // The script is invoked with absolute paths so cwd-relativity doesn't bite.
        val expectedScript = workDir.resolve("src/MLImpute/src/python/cross/pick_crossovers.py")
            .toAbsolutePath().toString()
        assertTrue(
            shellCommand.contains("python '$expectedScript'"),
            "Shell command should invoke '$expectedScript'; got: $shellCommand"
        )
        assertTrue(
            shellCommand.contains("--ref-fasta '${smallseqRoot.resolve("Ref.fa").toAbsolutePath()}'"),
            "Shell command should pass --ref-fasta with absolute path; got: $shellCommand"
        )
        assertTrue(
            shellCommand.contains("--assembly-list '${assemblyList.toAbsolutePath()}'"),
            "Shell command should pass --assembly-list with absolute path; got: $shellCommand"
        )

        // The command sets workingDir to the output dir so relative outputs land
        // in the right place.
        val expectedOutputDir = workDir.resolve("output/05_crossovers_results").toFile().absoluteFile
        assertEquals(expectedOutputDir, inv.workingDir?.absoluteFile)
    }

    @Test
    fun refkeyFilePathsTextFileListsGeneratedBedFiles(@TempDir workDir: Path) {
        stubMlimputePythonScript(workDir)
        val assemblyList = workDir.resolve("assembly_list.txt")
        writeAssemblyList(assemblyList, listOf("LineA", "LineB", "LineC", "LineD"))

        val executor = RecordingProcessExecutor(
            defaultExitCode = 0,
            onInvoke = pickCrossoversSimulator(assemblyList)
        )

        ProcessRunner.withExecutor(executor) {
            PickCrossovers().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--ref-fasta", smallseqRoot.resolve("Ref.fa").toString(),
                    "--assembly-list", assemblyList.toString()
                )
            )
        }

        val refkeyPaths = workDir.resolve("output/05_crossovers_results/refkey_file_paths.txt").toFile()
        assertTrue(refkeyPaths.exists(), "refkey_file_paths.txt should be written")
        val lines = refkeyPaths.readLines().filter { it.isNotBlank() }
        assertEquals(4, lines.size, "One refkey BED path per assembly should be listed")
        assertTrue(
            lines.all { it.endsWith("_refkey.bed") },
            "Every listed path should end with _refkey.bed; got: $lines"
        )
        assertTrue(
            lines.all { File(it).exists() && File(it).length() > 0 },
            "Every listed refkey BED must exist on disk and be non-empty (from simulator)"
        )
    }

    @Test
    fun customOutputDirIsHonored(@TempDir workDir: Path) {
        stubMlimputePythonScript(workDir)
        val assemblyList = workDir.resolve("assembly_list.txt")
        writeAssemblyList(assemblyList, listOf("LineA", "LineB"))
        val customOutput = workDir.resolve("custom_crossovers_out")

        val executor = RecordingProcessExecutor(
            defaultExitCode = 0,
            onInvoke = pickCrossoversSimulator(assemblyList)
        )

        ProcessRunner.withExecutor(executor) {
            PickCrossovers().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--ref-fasta", smallseqRoot.resolve("Ref.fa").toString(),
                    "--assembly-list", assemblyList.toString(),
                    "--output-dir", customOutput.toString()
                )
            )
        }

        val inv = executor.invocations.single()
        assertEquals(customOutput.toFile().absoluteFile, inv.workingDir?.absoluteFile)
        assertTrue(
            customOutput.resolve("refkey_file_paths.txt").toFile().exists(),
            "refkey_file_paths.txt should be written under the custom output dir"
        )
    }
}
