package net.maizegenetics.integration

import net.maizegenetics.utils.ProcessRunner
import org.apache.logging.log4j.LogManager
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * Sanity check that the PHGv2 binary baked into the container is executable
 * and that seq-sim can find/invoke it. This catches container drift (wrong
 * JDK, broken download, renamed binary, etc.) before heavier PHG-based
 * integration tests try to run.
 */
@Tag("integration")
class PhgAvailabilityIntegrationTest {

    private val logger = LogManager.getLogger(PhgAvailabilityIntegrationTest::class.java)

    @Test
    fun phgBinaryIsExecutable() {
        IntegrationGuard.requirePhg()

        val phg = File("${IntegrationGuard.phgDir}/bin/phg")
        // `phg --version` exits 0 if the binary works.
        val exit = ProcessRunner.runCommand(
            phg.absolutePath, "--version",
            logger = logger
        )
        assertEquals(0, exit, "PHG binary should respond to --version")
    }
}
