package net.maizegenetics.commands

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.InvocationTargetException
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the orchestrate `version` field. The dispatch between the
 * v1 (full) and v2 (variant) pipelines is driven entirely by what
 * [Orchestrate.parseYamlConfig] resolves the version to, so we exercise that
 * parsing directly via reflection. It runs before any environment
 * validation/setup, so these tests stay fast and hermetic (no shelling out).
 */
class OrchestrateVersionUnitTest {

    /**
     * Invoke the private `parseYamlConfig(Path)` on a fresh [Orchestrate] and
     * return the resulting [PipelineConfig].
     */
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
    fun versionDefaultsToV1WhenOmitted(@TempDir workDir: Path) {
        val config = parseConfig(
            writeConfig(
                workDir,
                """
                work_dir: "seq_sim_work"
                """
            )
        )
        assertEquals("v1", config.version, "Omitting version must fall back to v1 for backward compatibility")
    }

    @Test
    fun versionV1IsParsed(@TempDir workDir: Path) {
        val config = parseConfig(
            writeConfig(
                workDir,
                """
                version: "v1"
                work_dir: "seq_sim_work"
                """
            )
        )
        assertEquals("v1", config.version)
    }

    @Test
    fun versionV2IsParsed(@TempDir workDir: Path) {
        val config = parseConfig(
            writeConfig(
                workDir,
                """
                version: "v2"
                work_dir: "seq_sim_work"
                """
            )
        )
        assertEquals("v2", config.version)
    }

    @Test
    fun versionIsNormalizedToLowercaseAndTrimmed(@TempDir workDir: Path) {
        val config = parseConfig(
            writeConfig(
                workDir,
                """
                version: "  V2  "
                """
            )
        )
        assertEquals("v2", config.version, "Version should be trimmed and lowercased before validation")
    }

    @Test
    fun unsupportedVersionThrows(@TempDir workDir: Path) {
        val configPath = writeConfig(
            workDir,
            """
            version: "v3"
            """
        )

        val thrown = runCatching { parseConfig(configPath) }.exceptionOrNull()
        // Reflection wraps the thrown exception in InvocationTargetException.
        val cause = (thrown as? InvocationTargetException)?.targetException ?: thrown
        assertTrue(
            cause is IllegalArgumentException,
            "An unsupported version must raise IllegalArgumentException (got: $cause)"
        )
        assertTrue(
            cause!!.message!!.contains("Unsupported pipeline version"),
            "Error message should call out the unsupported version (got: ${cause.message})"
        )
    }
}
