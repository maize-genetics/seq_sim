package net.maizegenetics.commands

import com.github.ajalt.clikt.core.parse
import net.maizegenetics.utils.ProcessRunner
import net.maizegenetics.utils.testing.RecordingProcessExecutor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [GenerateRecombinedSequences] (step 08 of the
 * recombination pipeline). The command shells out to MLImpute's
 * `write_fastas.py` via `pixi run sh -c "..."`. The tests verify the
 * constructed command line, that founder key BEDs are staged into the
 * output dir, that `.fasta`/`.fna` parents get `.fa` symlinks created
 * for them automatically, and the `recombined_fasta_paths.txt` output
 * contract.
 */
class GenerateRecombinedSequencesUnitTest {

    // GenerateRecombinedSequences uses `pixi run sh -c "..."`. Snapshot
    // skipPixiPrefix and force it off so we can assert the full command.
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

    private fun stubMlimputePythonScript(workDir: Path): Path {
        val scriptDir = workDir.resolve("src/MLImpute/src/python/cross")
        scriptDir.createDirectories()
        val script = scriptDir.resolve("write_fastas.py")
        script.writeText("#!/usr/bin/env python\n")
        return script
    }

    /**
     * Write a minimal assembly list at [path] referencing the given
     * assembly names with `<path>\t<name>` formatting.
     */
    private fun writeAssemblyList(path: Path, assemblyDir: Path, names: List<String>) {
        path.writeText(
            names.joinToString("\n") { name ->
                "${assemblyDir.resolve("$name.fa").toAbsolutePath()}\t$name"
            }
        )
    }

    /**
     * Drop `<N>_key.bed` files (founder keys) into [dir] so the command's
     * existence check passes. The command requires AT LEAST one file
     * matching the regex `^\d+_key\.bed$`.
     */
    private fun stubFounderKeyDir(dir: Path, founderIds: List<Int>) {
        dir.createDirectories()
        founderIds.forEach { id ->
            dir.resolve("${id}_key.bed").writeText("# founder key $id\n")
        }
    }

    /**
     * Simulate write_fastas.py by producing one recombined FASTA per
     * founder key file inside the `recombinate_fastas/` subdirectory of
     * the script's working directory (which the command sets to the
     * step's output dir).
     */
    private fun writeFastasSimulator(): (RecordingProcessExecutor.Invocation) -> Int = { inv ->
        val outDir = inv.workingDir
        outDir?.mkdirs()
        val recombinedDir = File(outDir, "recombinate_fastas").also { it.mkdirs() }
        val founderKeys = outDir?.listFiles { f -> f.name.matches(Regex("^\\d+_key\\.bed$")) }
            ?.toList().orEmpty()
        founderKeys.forEach { fk ->
            val founderId = fk.name.removeSuffix("_key.bed")
            File(recombinedDir, "$founderId.fa").writeText(">$founderId\nACGT\n")
        }
        0
    }

    @Test
    fun writeFastasInvokesPixiShellOnceWithExpectedArgs(@TempDir workDir: Path) {
        stubMlimputePythonScript(workDir)
        val assemblyDir = workDir.resolve("assemblies").also { it.createDirectories() }
        listOf("LineA", "LineB").forEach { name ->
            assemblyDir.resolve("$name.fa").writeText(">1\nACGT\n")
        }
        val assemblyList = workDir.resolve("assembly_list.txt")
        writeAssemblyList(assemblyList, assemblyDir, listOf("LineA", "LineB"))
        val chromosomeList = workDir.resolve("chromosomes.txt").also { it.writeText("1\n") }
        val founderKeyDir = workDir.resolve("founder_keys")
        stubFounderKeyDir(founderKeyDir, listOf(0, 1))

        val executor = RecordingProcessExecutor(defaultExitCode = 0, onInvoke = writeFastasSimulator())

        ProcessRunner.withExecutor(executor) {
            GenerateRecombinedSequences().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--assembly-list", assemblyList.toString(),
                    "--chromosome-list", chromosomeList.toString(),
                    "--assembly-dir", assemblyDir.toString(),
                    "--founder-key-dir", founderKeyDir.toString()
                )
            )
        }

        assertEquals(1, executor.invocations.size, "write_fastas.py should be invoked exactly once")
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

        val expectedScript = workDir.resolve("src/MLImpute/src/python/cross/write_fastas.py")
            .toAbsolutePath().toString()
        assertTrue(
            shellCommand.contains("python '$expectedScript'"),
            "Shell command should invoke write_fastas.py; got: $shellCommand"
        )
        assertTrue(
            shellCommand.contains("--assembly-list '${assemblyList.toAbsolutePath()}'"),
            "Shell command should pass --assembly-list; got: $shellCommand"
        )
        assertTrue(
            shellCommand.contains("--chromosome-list '${chromosomeList.toAbsolutePath()}'"),
            "Shell command should pass --chromosome-list; got: $shellCommand"
        )
        assertTrue(
            shellCommand.contains("--assembly-dir '${assemblyDir.toAbsolutePath()}'"),
            "Shell command should pass --assembly-dir; got: $shellCommand"
        )

        val expectedOutputDir = workDir.resolve("output/08_recombined_sequences").toFile().absoluteFile
        assertEquals(expectedOutputDir, inv.workingDir?.absoluteFile)
    }

    @Test
    fun founderKeyFilesAreStagedIntoOutputDirBeforeScriptRuns(@TempDir workDir: Path) {
        stubMlimputePythonScript(workDir)
        val assemblyDir = workDir.resolve("assemblies").also { it.createDirectories() }
        assemblyDir.resolve("LineA.fa").writeText(">1\nACGT\n")
        assemblyDir.resolve("LineB.fa").writeText(">1\nACGT\n")
        val assemblyList = workDir.resolve("assembly_list.txt")
        writeAssemblyList(assemblyList, assemblyDir, listOf("LineA", "LineB"))
        val chromosomeList = workDir.resolve("chromosomes.txt").also { it.writeText("1\n") }
        val founderKeyDir = workDir.resolve("founder_keys")
        stubFounderKeyDir(founderKeyDir, listOf(0, 1, 2))

        val executor = RecordingProcessExecutor(defaultExitCode = 0, onInvoke = writeFastasSimulator())

        ProcessRunner.withExecutor(executor) {
            GenerateRecombinedSequences().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--assembly-list", assemblyList.toString(),
                    "--chromosome-list", chromosomeList.toString(),
                    "--assembly-dir", assemblyDir.toString(),
                    "--founder-key-dir", founderKeyDir.toString()
                )
            )
        }

        val outputDir = workDir.resolve("output/08_recombined_sequences").toFile()
        val stagedKeys = outputDir.listFiles { f -> f.name.matches(Regex("^\\d+_key\\.bed$")) }!!
            .map { it.name }.toSet()
        assertEquals(
            setOf("0_key.bed", "1_key.bed", "2_key.bed"),
            stagedKeys,
            "Founder key BEDs should be copied into the output dir before write_fastas.py runs"
        )
    }

    @Test
    fun fastaSymlinksAreCreatedForFastaAndFnaParents(@TempDir workDir: Path) {
        stubMlimputePythonScript(workDir)
        val assemblyDir = workDir.resolve("assemblies").also { it.createDirectories() }
        // The Python script wants .fa, but the parent assemblies live with
        // .fasta / .fna extensions. The command must create symlinks so the
        // script can find them.
        assemblyDir.resolve("LineA.fasta").writeText(">1\nACGT\n")
        assemblyDir.resolve("LineB.fna").writeText(">1\nACGT\n")
        // LineA also has a .fai sidecar; the symlink for it should also be created.
        assemblyDir.resolve("LineA.fasta.fai").writeText("1\t4\t3\t60\t61\n")

        val assemblyList = workDir.resolve("assembly_list.txt")
        writeAssemblyList(assemblyList, assemblyDir, listOf("LineA", "LineB"))
        val chromosomeList = workDir.resolve("chromosomes.txt").also { it.writeText("1\n") }
        val founderKeyDir = workDir.resolve("founder_keys")
        stubFounderKeyDir(founderKeyDir, listOf(0))

        val executor = RecordingProcessExecutor(defaultExitCode = 0, onInvoke = writeFastasSimulator())

        ProcessRunner.withExecutor(executor) {
            GenerateRecombinedSequences().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--assembly-list", assemblyList.toString(),
                    "--chromosome-list", chromosomeList.toString(),
                    "--assembly-dir", assemblyDir.toString(),
                    "--founder-key-dir", founderKeyDir.toString()
                )
            )
        }

        val lineAFa = assemblyDir.resolve("LineA.fa")
        val lineBFa = assemblyDir.resolve("LineB.fa")
        assertTrue(lineAFa.exists(), "LineA.fa symlink should be created from LineA.fasta")
        assertTrue(lineAFa.isSymbolicLink(), "LineA.fa should be a symlink")
        assertTrue(lineBFa.exists(), "LineB.fa symlink should be created from LineB.fna")
        assertTrue(lineBFa.isSymbolicLink(), "LineB.fa should be a symlink")

        val lineAFai = assemblyDir.resolve("LineA.fa.fai")
        assertTrue(lineAFai.exists(), "LineA.fa.fai sidecar symlink should also be created")
        assertTrue(
            Files.isSymbolicLink(lineAFai),
            "LineA.fa.fai should be a symlink to LineA.fasta.fai"
        )
    }

    @Test
    fun recombinedFastaPathsTextFileListsGeneratedFastas(@TempDir workDir: Path) {
        stubMlimputePythonScript(workDir)
        val assemblyDir = workDir.resolve("assemblies").also { it.createDirectories() }
        listOf("LineA", "LineB").forEach { name ->
            assemblyDir.resolve("$name.fa").writeText(">1\nACGT\n")
        }
        val assemblyList = workDir.resolve("assembly_list.txt")
        writeAssemblyList(assemblyList, assemblyDir, listOf("LineA", "LineB"))
        val chromosomeList = workDir.resolve("chromosomes.txt").also { it.writeText("1\n") }
        val founderKeyDir = workDir.resolve("founder_keys")
        stubFounderKeyDir(founderKeyDir, listOf(0, 1, 2, 3))

        val executor = RecordingProcessExecutor(defaultExitCode = 0, onInvoke = writeFastasSimulator())

        ProcessRunner.withExecutor(executor) {
            GenerateRecombinedSequences().parse(
                listOf(
                    "--work-dir", workDir.toString(),
                    "--assembly-list", assemblyList.toString(),
                    "--chromosome-list", chromosomeList.toString(),
                    "--assembly-dir", assemblyDir.toString(),
                    "--founder-key-dir", founderKeyDir.toString()
                )
            )
        }

        val pathsFile = workDir.resolve("output/08_recombined_sequences/recombined_fasta_paths.txt").toFile()
        assertTrue(pathsFile.exists(), "recombined_fasta_paths.txt should be written")
        val lines = pathsFile.readLines().filter { it.isNotBlank() }
        assertEquals(4, lines.size, "One recombined FASTA per founder key should be listed")
        assertTrue(
            lines.all { it.endsWith(".fa") && File(it).exists() && File(it).length() > 0 },
            "Every listed recombined FASTA must exist on disk and be non-empty; got: $lines"
        )
        assertTrue(
            lines.all { File(it).parentFile.name == "recombinate_fastas" },
            "Recombined FASTAs should live under recombinate_fastas/; got: $lines"
        )
    }
}
