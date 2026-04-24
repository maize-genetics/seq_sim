package net.maizegenetics.integration

import org.junit.jupiter.api.Assumptions

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
}
