package net.maizegenetics.utils

import net.maizegenetics.utils.testing.RecordingProcessExecutor
import org.apache.logging.log4j.LogManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Unit tests for the [ProcessRunner] facade, including its `pixi run`
 * stripping behavior that lets the containerized dev environment bypass
 * the pixi wrapper.
 */
class ProcessRunnerTest {

    private val logger = LogManager.getLogger(ProcessRunnerTest::class.java)
    private val originalSkipPixi = ProcessRunner.skipPixiPrefix

    @AfterEach
    fun cleanup() {
        ProcessRunner.resetExecutor()
        ProcessRunner.skipPixiPrefix = originalSkipPixi
    }

    @Test
    fun recordingExecutorIsUsedWhenInstalled() {
        val executor = RecordingProcessExecutor(defaultExitCode = 0)
        ProcessRunner.withExecutor(executor) {
            val exit = ProcessRunner.runCommand(
                "echo", "hello",
                logger = logger
            )
            assertEquals(0, exit)
        }
        assertEquals(1, executor.invocations.size)
        assertEquals(listOf("echo", "hello"), executor.invocations[0].command)
    }

    @Test
    fun pixiPrefixIsStrippedWhenSkipFlagIsSet() {
        ProcessRunner.skipPixiPrefix = true
        val executor = RecordingProcessExecutor(defaultExitCode = 0)
        ProcessRunner.withExecutor(executor) {
            ProcessRunner.runCommand(
                "pixi", "run", "minimap2", "--version",
                logger = logger
            )
        }
        assertEquals(
            listOf("minimap2", "--version"),
            executor.invocations.single().command
        )
    }

    @Test
    fun pixiPrefixIsPreservedByDefault() {
        ProcessRunner.skipPixiPrefix = false
        val executor = RecordingProcessExecutor(defaultExitCode = 0)
        ProcessRunner.withExecutor(executor) {
            ProcessRunner.runCommand(
                "pixi", "run", "minimap2", "--version",
                logger = logger
            )
        }
        assertEquals(
            listOf("pixi", "run", "minimap2", "--version"),
            executor.invocations.single().command
        )
    }

    @Test
    fun nonPixiCommandsPassThroughEvenWhenSkipIsSet() {
        ProcessRunner.skipPixiPrefix = true
        val executor = RecordingProcessExecutor(defaultExitCode = 0)
        ProcessRunner.withExecutor(executor) {
            ProcessRunner.runCommand(
                "/opt/phg_v2/bin/phg", "build-spline-knots",
                logger = logger
            )
        }
        assertEquals(
            listOf("/opt/phg_v2/bin/phg", "build-spline-knots"),
            executor.invocations.single().command
        )
    }

    @Test
    fun scriptedExitCodesArePropagated() {
        val executor = RecordingProcessExecutor(
            defaultExitCode = 0,
            onInvoke = { inv -> if (inv.command.firstOrNull() == "bad") 7 else 0 }
        )
        ProcessRunner.withExecutor(executor) {
            assertEquals(0, ProcessRunner.runCommand("ok", logger = logger))
            assertEquals(7, ProcessRunner.runCommand("bad", logger = logger))
        }
    }
}
