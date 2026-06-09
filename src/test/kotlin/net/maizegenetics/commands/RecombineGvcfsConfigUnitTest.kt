package net.maizegenetics.commands

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for parsing the v2 `recombine_gvcfs` step config out of the
 * orchestrate YAML. Like [OrchestrateVersionUnitTest], these exercise the
 * private [Orchestrate.parseYamlConfig] directly via reflection so they stay
 * fast and hermetic (no environment validation / shelling out).
 */
class RecombineGvcfsConfigUnitTest {

    private fun parseConfig(configPath: Path): PipelineConfig {
        val method = Orchestrate::class.java
            .getDeclaredMethod("parseYamlConfig", Path::class.java)
            .apply { isAccessible = true }
        return method.invoke(Orchestrate(), configPath) as PipelineConfig
    }

    private fun writeConfig(workDir: Path, contents: String): Path {
        val configPath = workDir.resolve("pipeline.yaml")
        configPath.writeText(contents.trimIndent())
        return configPath
    }

    @Test
    fun recombineGvcfsBlockIsFullyParsed(@TempDir workDir: Path) {
        val config = parseConfig(
            writeConfig(
                workDir,
                """
                version: "v2"
                recombine_gvcfs:
                  ref_file: "ref.fa"
                  input_bed: "beds/"
                  input_gvcf: "gvcfs/"
                  output: "out/"
                """
            )
        )

        val recombine = config.recombine_gvcfs
        assertNotNull(recombine, "recombine_gvcfs should be parsed when present")
        assertEquals("ref.fa", recombine.ref_file)
        assertEquals("beds/", recombine.input_bed)
        assertEquals("gvcfs/", recombine.input_gvcf)
        assertEquals("out/", recombine.output)
    }

    @Test
    fun emptyRecombineGvcfsBlockMeansRunWithDefaults(@TempDir workDir: Path) {
        // Key present but with no values => non-null config with all-null fields,
        // signalling "run this step with defaults".
        val config = parseConfig(
            writeConfig(
                workDir,
                """
                version: "v2"
                recombine_gvcfs:
                """
            )
        )

        val recombine = config.recombine_gvcfs
        assertNotNull(recombine, "An empty recombine_gvcfs block should still be non-null")
        assertNull(recombine.ref_file)
        assertNull(recombine.input_bed)
        assertNull(recombine.input_gvcf)
        assertNull(recombine.output)
    }

    @Test
    fun absentRecombineGvcfsKeyIsNull(@TempDir workDir: Path) {
        val config = parseConfig(
            writeConfig(
                workDir,
                """
                version: "v2"
                work_dir: "seq_sim_work"
                """
            )
        )

        assertNull(
            config.recombine_gvcfs,
            "recombine_gvcfs should be null when the key is absent (step not configured)"
        )
    }
}
