package net.maizegenetics.editor.services

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import net.maizegenetics.editor.shared.LogMessage
import net.maizegenetics.editor.shared.PipelineStatus
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

class PipelineService {
    
    private var currentProcess: Process? = null
    private var currentJob: Job? = null
    private val isRunning = AtomicBoolean(false)
    private var currentStatus = PipelineStatus(running = false)
    
    private val logChannel = Channel<LogMessage>(Channel.BUFFERED)
    
    val logFlow: Flow<LogMessage> = logChannel.receiveAsFlow()

    fun getStatus(): PipelineStatus = currentStatus

    /**
     * Start the pipeline execution
     */
    suspend fun startPipeline(configFilePath: String, projectRoot: String = "."): Boolean {
        if (isRunning.get()) {
            return false
        }

        isRunning.set(true)
        currentStatus = PipelineStatus(
            running = true,
            currentStep = "Starting...",
            progress = 0,
            totalSteps = 10,
            startTime = System.currentTimeMillis()
        )

        currentJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                runPipelineProcess(configFilePath, projectRoot)
            } catch (e: Exception) {
                currentStatus = currentStatus.copy(
                    running = false,
                    error = e.message
                )
                sendLog("ERROR", "Pipeline failed: ${e.message}")
            } finally {
                isRunning.set(false)
                currentStatus = currentStatus.copy(running = false)
            }
        }

        return true
    }

    /**
     * Stop the currently running pipeline
     */
    fun stopPipeline(): Boolean {
        if (!isRunning.get()) {
            return false
        }

        currentProcess?.destroyForcibly()
        currentJob?.cancel()
        isRunning.set(false)
        currentStatus = currentStatus.copy(
            running = false,
            error = "Pipeline stopped by user"
        )
        sendLog("WARN", "Pipeline stopped by user")
        return true
    }

    private suspend fun runPipelineProcess(configFilePath: String, projectRoot: String) {
        val workDir = File(projectRoot)
        
        // Build the command to run the orchestrator
        val command = listOf(
            "./gradlew", "run",
            "--args=orchestrate --config $configFilePath"
        )

        sendLog("INFO", "Starting pipeline with config: $configFilePath")
        sendLog("INFO", "Command: ${command.joinToString(" ")}")

        val processBuilder = ProcessBuilder(command)
            .directory(workDir)
            .redirectErrorStream(true)

        currentProcess = processBuilder.start()
        
        val reader = BufferedReader(InputStreamReader(currentProcess!!.inputStream))
        var line: String?
        var stepCount = 0

        while (reader.readLine().also { line = it } != null) {
            val logLine = line ?: continue
            
            // Parse log level and detect step progress
            val level = when {
                logLine.contains("ERROR") -> "ERROR"
                logLine.contains("WARN") -> "WARN"
                logLine.contains("STEP") -> {
                    stepCount++
                    currentStatus = currentStatus.copy(
                        currentStep = extractStepName(logLine),
                        progress = stepCount
                    )
                    "INFO"
                }
                logLine.contains("completed successfully") -> "INFO"
                else -> "DEBUG"
            }
            
            sendLog(level, logLine)
        }

        val exitCode = currentProcess!!.waitFor()
        
        if (exitCode == 0) {
            currentStatus = currentStatus.copy(
                running = false,
                progress = currentStatus.totalSteps
            )
            sendLog("INFO", "Pipeline completed successfully!")
        } else {
            currentStatus = currentStatus.copy(
                running = false,
                error = "Pipeline exited with code $exitCode"
            )
            sendLog("ERROR", "Pipeline failed with exit code $exitCode")
        }
    }

    private fun extractStepName(line: String): String {
        // Extract step name from log line like "STEP 1: Align Assemblies"
        val regex = Regex("STEP \\d+: (.+)")
        return regex.find(line)?.groupValues?.get(1) ?: "Unknown step"
    }

    private fun sendLog(level: String, message: String, step: String? = null) {
        val logMessage = LogMessage(
            timestamp = System.currentTimeMillis(),
            level = level,
            message = message,
            step = step ?: currentStatus.currentStep
        )
        logChannel.trySend(logMessage)
    }
}

