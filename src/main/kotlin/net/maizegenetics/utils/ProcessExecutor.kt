package net.maizegenetics.utils

import org.apache.logging.log4j.Logger
import java.io.File

/**
 * Abstraction for running external processes. Introduced so that pipeline
 * commands can be unit-tested without actually shelling out to
 * AnchorWave / PHGv2 / minimap2 / etc.
 *
 * The production implementation is [RealProcessExecutor]. Tests typically
 * swap in a [net.maizegenetics.utils.testing.RecordingProcessExecutor]
 * via [ProcessRunner.withExecutor].
 */
interface ProcessExecutor {
    fun runCommand(
        command: Array<out String>,
        workingDir: File?,
        outputFile: File?,
        logger: Logger
    ): Int
}
