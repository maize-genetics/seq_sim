package net.maizegenetics.commands

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for parsing the v2 `sort_gvcfs` step config out of the orchestrate
 * YAML. Like [RecombineGvcfsConfigUnitTest], these exercise the private
 * [Orchestrate.parseYamlConfig] directly via reflection so they stay fast and
 * hermetic (no environment validation / shelling out to bcftools).
 */
class SortGvcfsConfigUnitTest {

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
    fun sortGvcfsBlockIsFullyParsed(@TempDir workDir: Path) {
        val config = parseConfig(
            writeConfig(
                workDir,
                """
                version: "v2"
                sort_gvcfs:
                  input: "gvcfs/"
                  threads: 8
                  output: "out/"
                """
            )
        )

        val sort = config.sort_gvcfs
        assertNotNull(sort, "sort_gvcfs should be parsed when present")
        assertEquals("gvcfs/", sort.input)
        assertEquals(8, sort.threads)
        assertEquals("out/", sort.output)
    }

    @Test
    fun emptySortGvcfsBlockMeansRunWithDefaults(@TempDir workDir: Path) {
        // Key present but with no values => non-null config with all-null fields,
        // signalling "run this step with defaults".
        val config = parseConfig(
            writeConfig(
                workDir,
                """
                version: "v2"
                sort_gvcfs:
                """
            )
        )

        val sort = config.sort_gvcfs
        assertNotNull(sort, "An empty sort_gvcfs block should still be non-null")
        assertNull(sort.input)
        assertNull(sort.threads)
        assertNull(sort.output)
    }

    @Test
    fun absentSortGvcfsKeyIsNull(@TempDir workDir: Path) {
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
            config.sort_gvcfs,
            "sort_gvcfs should be null when the key is absent (step not configured)"
        )
    }
}
