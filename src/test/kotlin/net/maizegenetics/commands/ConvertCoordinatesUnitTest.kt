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
 * Unit tests for [ConvertCoordinates] (step 07 of the recombination pipeline).
 * The command shells out to MLImpute's `convert_coords.py` via
 * `pixi run sh -c "..."`. The tests verify the constructed command line,
 * the refkey-staging side effect into the output dir, and the
 * key/founder-key path files that downstream steps depend on.
 */
class ConvertCoordinatesUnitTest {

    // ConvertCoordinates builds a `pixi run sh -c <shellCommand>` command.
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
     * Create the fake MLImpute layout including the python package and
     * `convert_coords.py` so the command's existence check passes.
     */
    private fun stubMlimputePythonScript(workDir: Path): Path {
        val scriptDir = workDir.resolve("src/MLImpute/src/python/cross")
        scriptDir.createDirectories()
        val script = scriptDir.resolve("convert_coords.py")
        script.writeText("#!/usr/bin/env python\n")
        return script
    }

    private fun writeAssemblyList(path: Path, assemblies: List<String>) {
        path.writeText(
            assemblies.joinToString("\n") { name -> "/some/path/$name.fa\t$name" }
        )
    }

    /**
     * Drop `<name>_refkey.bed` files into [dir] so the command's refkey
     * staging step (which copies them into the output dir before running
     * the python script) has something to act on.
     */
    private fun stubRefkeyDir(dir: Path, assemblies: List<String>) {
        dir.createDirectories()
        assemblies.forEach { name ->
            dir.resolve("${name}_refkey.bed").writeText("# refkey for $name\n")
        }
    }

    /**
     * Simulate convert_coords.py: for each `<name>_refkey.bed` in the
     * working dir, write a `<name>_key.bed` and additionally produce a
     * single founder key `<N>_key.bed` for every pair of assemblies.
     */
    private fun convertCoordsSimulator(): (RecordingProcessExecutor.Invocation) -> Int = { inv ->
        val outDir = inv.workingDir
        outDir?.mkdirs()
        val refkeyBeds = outDir?.listFiles { f -> f.name.endsWith("_refkey.bed") }
            ?.toList().orEmpty()
        refkeyBeds.forEachIndexed { idx, refkey ->
            val name = refkey.name.removeSuffix("_refkey.bed")
            File(outDir, "${name}_key.bed").writeText("# key for $name\n")
            // Write a founder key for every other assembly so we exercise both
            // path-file branches.
            if (idx % 2 == 0) {
                val founder = idx / 2
                File(outDir, "${founder}_key.bed").writeText("# founder key $founder\n")
            }
        }
        0
    }

    @Test
    fun convertCoordsInvokesPixiShellOnceWithExpectedArgs(@TempDir workDir: Path) {
        stubMlimputePythonScript(workDir)
        val assemblyList = workDir.resolve("assembly_list.txt")
        writeAssemblyList(assemblyList, listOf("LineA", "LineB"))

        val chainDir = workDir.resolve("chains").also { it.createDirectories() }
        chainDir.resolve("LineA_subsampled.chain").writeText("")

        val refkeyDir = workDir.resolve("refkeys")
        stubRefkeyDir(refkeyDir, listOf("LineA", "LineB"))

        val executor = RecordingProcessExecutor(defaultExitCode = 0, onInvoke = convertCoordsSimulator())

        ProcessRunner.withExecutor(executor) {
            ConvertCoordinates().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--assembly-list", assemblyList.toString(),
                    "--chain-dir", chainDir.toString(),
                    "--refkey-dir", refkeyDir.toString()
                )
            )
        }

        assertEquals(1, executor.invocations.size, "convert_coords.py should be invoked exactly once")
        val inv = executor.invocations.single()

        assertEquals("pixi", inv.command[0])
        assertEquals("run", inv.command[1])
        assertEquals("sh", inv.command[2])
        assertEquals("-c", inv.command[3])
        val shellCommand = inv.command[4]

        val expectedPythonPath = workDir.resolve("src/MLImpute/src").toAbsolutePath().toString()
        assertTrue(
            shellCommand.contains("PYTHONPATH='$expectedPythonPath'"),
            "Shell command should export PYTHONPATH=$expectedPythonPath; got: $shellCommand"
        )

        val expectedScript = workDir.resolve("src/MLImpute/src/python/cross/convert_coords.py")
            .toAbsolutePath().toString()
        assertTrue(
            shellCommand.contains("python '$expectedScript'"),
            "Shell command should invoke convert_coords.py; got: $shellCommand"
        )
        assertTrue(
            shellCommand.contains("--assembly-list '${assemblyList.toAbsolutePath()}'"),
            "Shell command should pass --assembly-list; got: $shellCommand"
        )
        assertTrue(
            shellCommand.contains("--chain-dir '${chainDir.toAbsolutePath()}'"),
            "Shell command should pass --chain-dir; got: $shellCommand"
        )

        val expectedOutputDir = workDir.resolve("output/07_coordinates_results").toFile().absoluteFile
        assertEquals(expectedOutputDir, inv.workingDir?.absoluteFile)
    }

    @Test
    fun refkeyBedFilesAreStagedIntoOutputDirBeforeScriptRuns(@TempDir workDir: Path) {
        stubMlimputePythonScript(workDir)
        val assemblyList = workDir.resolve("assembly_list.txt")
        writeAssemblyList(assemblyList, listOf("LineA", "LineB"))
        val chainDir = workDir.resolve("chains").also { it.createDirectories() }
        val refkeyDir = workDir.resolve("refkeys")
        stubRefkeyDir(refkeyDir, listOf("LineA", "LineB"))

        val executor = RecordingProcessExecutor(defaultExitCode = 0, onInvoke = convertCoordsSimulator())

        ProcessRunner.withExecutor(executor) {
            ConvertCoordinates().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--assembly-list", assemblyList.toString(),
                    "--chain-dir", chainDir.toString(),
                    "--refkey-dir", refkeyDir.toString()
                )
            )
        }

        val outputDir = workDir.resolve("output/07_coordinates_results").toFile()
        val stagedRefkeys = outputDir.listFiles { f -> f.name.endsWith("_refkey.bed") }!!.map { it.name }.toSet()
        assertEquals(
            setOf("LineA_refkey.bed", "LineB_refkey.bed"),
            stagedRefkeys,
            "Refkey BEDs should be copied into the output dir before convert_coords.py runs"
        )
    }

    @Test
    fun keyAndFounderKeyPathFilesAreWrittenForGeneratedBeds(@TempDir workDir: Path) {
        stubMlimputePythonScript(workDir)
        val assemblyList = workDir.resolve("assembly_list.txt")
        writeAssemblyList(assemblyList, listOf("LineA", "LineB", "LineC", "LineD"))
        val chainDir = workDir.resolve("chains").also { it.createDirectories() }
        val refkeyDir = workDir.resolve("refkeys")
        stubRefkeyDir(refkeyDir, listOf("LineA", "LineB", "LineC", "LineD"))

        val executor = RecordingProcessExecutor(defaultExitCode = 0, onInvoke = convertCoordsSimulator())

        ProcessRunner.withExecutor(executor) {
            ConvertCoordinates().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--assembly-list", assemblyList.toString(),
                    "--chain-dir", chainDir.toString(),
                    "--refkey-dir", refkeyDir.toString()
                )
            )
        }

        val outputDir = workDir.resolve("output/07_coordinates_results").toFile()

        val keyPathsFile = File(outputDir, "key_file_paths.txt")
        assertTrue(keyPathsFile.exists(), "key_file_paths.txt should be written for assembly key BEDs")
        val keyLines = keyPathsFile.readLines().filter { it.isNotBlank() }
        assertEquals(4, keyLines.size, "One assembly key path per assembly")
        assertTrue(
            keyLines.all { it.endsWith("_key.bed") && !File(it).name.matches(Regex("^\\d+_key\\.bed$")) },
            "Assembly key paths should look like <name>_key.bed; got: $keyLines"
        )

        val founderPathsFile = File(outputDir, "founder_key_file_paths.txt")
        assertTrue(
            founderPathsFile.exists(),
            "founder_key_file_paths.txt should be written for N_key.bed founders"
        )
        val founderLines = founderPathsFile.readLines().filter { it.isNotBlank() }
        assertTrue(founderLines.isNotEmpty(), "At least one founder key should be listed")
        assertTrue(
            founderLines.all { File(it).name.matches(Regex("^\\d+_key\\.bed$")) },
            "Founder keys must match N_key.bed; got: $founderLines"
        )
    }

    @Test
    fun customOutputDirIsHonored(@TempDir workDir: Path) {
        stubMlimputePythonScript(workDir)
        val assemblyList = workDir.resolve("assembly_list.txt")
        writeAssemblyList(assemblyList, listOf("LineA", "LineB"))
        val chainDir = workDir.resolve("chains").also { it.createDirectories() }
        val refkeyDir = workDir.resolve("refkeys")
        stubRefkeyDir(refkeyDir, listOf("LineA", "LineB"))
        val customOutput = workDir.resolve("custom_coords_out")

        val executor = RecordingProcessExecutor(defaultExitCode = 0, onInvoke = convertCoordsSimulator())

        ProcessRunner.withExecutor(executor) {
            ConvertCoordinates().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--assembly-list", assemblyList.toString(),
                    "--chain-dir", chainDir.toString(),
                    "--refkey-dir", refkeyDir.toString(),
                    "--output-dir", customOutput.toString()
                )
            )
        }

        val inv = executor.invocations.single()
        assertEquals(customOutput.toFile().absoluteFile, inv.workingDir?.absoluteFile)
        assertTrue(
            customOutput.resolve("key_file_paths.txt").toFile().exists(),
            "key_file_paths.txt should be written under the custom output dir"
        )
    }
}
