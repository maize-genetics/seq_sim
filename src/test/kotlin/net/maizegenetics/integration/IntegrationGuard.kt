package net.maizegenetics.integration

import org.junit.jupiter.api.Assumptions
import java.io.File

/**
 * Shared precondition helpers for integration-tier tests.
 *
 * Integration tests invoke real external binaries (AnchorWave, minimap2,
 * PHGv2, ...) which are only available inside the seq-sim-dev Docker
 * image. We skip rather than fail when run outside that container so that
 * `./gradlew integrationTest` is harmless on a laptop without Docker.
 *
 * The container sets `SEQ_SIM_IN_CONTAINER=1` and `SEQ_SIM_PHG_DIR=/opt/phg_v2`
 * so those two markers together indicate "real binaries are available".
 */
object IntegrationGuard {

    val inContainer: Boolean = System.getenv("SEQ_SIM_IN_CONTAINER") == "1"
    val phgDir: String? = System.getenv("SEQ_SIM_PHG_DIR")

    /** Skip the current test unless we are running inside the dev container. */
    fun requireContainer() {
        Assumptions.assumeTrue(
            inContainer,
            "Integration tests require the seq-sim-dev Docker container. " +
                "Run via: scripts/dev.sh integration"
        )
    }

    /** Skip the current test unless the PHGv2 binary is available at SEQ_SIM_PHG_DIR. */
    fun requirePhg() {
        requireContainer()
        Assumptions.assumeTrue(
            phgDir != null && java.io.File("$phgDir/bin/phg").canExecute(),
            "PHGv2 binary not available at \$SEQ_SIM_PHG_DIR/bin/phg"
        )
    }

    /** Skip unless `anchorwave` is on PATH (i.e. the phgv2-conda env is active). */
    fun requireAnchorwave() {
        requireContainer()
        val found = System.getenv("PATH")
            ?.split(java.io.File.pathSeparator)
            ?.any { dir -> java.io.File(dir, "anchorwave").canExecute() }
            ?: false
        Assumptions.assumeTrue(found, "anchorwave binary not found on PATH")
    }

    /**
     * Print the container's actual memory budget (cgroup v2 / v1) and the
     * test JVM's heap settings so we can diagnose `exit 137` (kernel OOM
     * kills) without guessing. Should be called once per E2E test.
     *
     * E2E tests run the full pipeline which forks Gradle daemons,
     * MLImpute application JVMs, and native tools (AnchorWave/minimap2/
     * pysam). Their combined RSS can comfortably exceed 4 GB. If the
     * container memory limit is below that, the OOM killer fires.
     */
    fun logContainerMemoryBudget() {
        val runtime = Runtime.getRuntime()
        val mb = 1024L * 1024L
        println(">>> [MEMORY] JVM Xmx (maxMemory): ${runtime.maxMemory() / mb} MB")
        println(">>> [MEMORY] JVM available processors: ${runtime.availableProcessors()}")

        // cgroup v2 (modern Docker / Linux >= 4.5)
        val cgroupV2 = File("/sys/fs/cgroup/memory.max")
        if (cgroupV2.exists()) {
            val raw = runCatching { cgroupV2.readText().trim() }.getOrNull() ?: "<unreadable>"
            val pretty = raw.toLongOrNull()?.let { "$raw bytes (${it / mb} MB)" } ?: raw
            println(">>> [MEMORY] cgroup v2 memory.max: $pretty")
        }

        // cgroup v1 fallback
        val cgroupV1 = File("/sys/fs/cgroup/memory/memory.limit_in_bytes")
        if (cgroupV1.exists()) {
            val raw = runCatching { cgroupV1.readText().trim() }.getOrNull() ?: "<unreadable>"
            val pretty = raw.toLongOrNull()?.let { "$raw bytes (${it / mb} MB)" } ?: raw
            println(">>> [MEMORY] cgroup v1 memory.limit_in_bytes: $pretty")
        }

        // /proc/meminfo for the host kernel's view
        val meminfo = File("/proc/meminfo")
        if (meminfo.exists()) {
            val lines = runCatching {
                meminfo.readLines().filter { line ->
                    line.startsWith("MemTotal:") ||
                        line.startsWith("MemAvailable:") ||
                        line.startsWith("MemFree:")
                }
            }.getOrNull().orEmpty()
            lines.forEach { println(">>> [MEMORY] /proc/meminfo $it") }
        }

        println(
            ">>> [MEMORY] If subsequent steps fail with exit 137, the container memory " +
                "budget shown above is too small. End-to-end tests typically need at least 6 GB."
        )
    }
}
