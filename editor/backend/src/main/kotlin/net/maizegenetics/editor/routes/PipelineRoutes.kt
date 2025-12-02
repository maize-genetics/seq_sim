package net.maizegenetics.editor.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.maizegenetics.editor.services.ConfigService
import net.maizegenetics.editor.services.PipelineService
import net.maizegenetics.editor.shared.ApiError
import net.maizegenetics.editor.shared.RunPipelineRequest

fun Route.pipelineRoutes(pipelineService: PipelineService, configService: ConfigService) {
    route("/pipeline") {
        
        /**
         * GET /api/pipeline/status - Get current pipeline status
         */
        get("/status") {
            call.respond(pipelineService.getStatus())
        }

        /**
         * POST /api/pipeline/run - Start the pipeline
         */
        post("/run") {
            val request = call.receive<RunPipelineRequest>()
            
            // Save config to a temporary file if no path provided
            val configPath = request.configFilePath ?: run {
                val tempFile = kotlin.io.path.createTempFile("pipeline_config_", ".yaml").toFile()
                val saveResult = configService.saveConfig(request.config, tempFile.absolutePath)
                if (!saveResult.success) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ApiError("Failed to save configuration", saveResult.message)
                    )
                    return@post
                }
                saveResult.filePath!!
            }
            
            val started = pipelineService.startPipeline(configPath)
            
            if (started) {
                call.respond(HttpStatusCode.OK, mapOf(
                    "message" to "Pipeline started",
                    "configPath" to configPath
                ))
            } else {
                call.respond(
                    HttpStatusCode.Conflict,
                    ApiError("Pipeline is already running")
                )
            }
        }

        /**
         * POST /api/pipeline/stop - Stop the pipeline
         */
        post("/stop") {
            val stopped = pipelineService.stopPipeline()
            
            if (stopped) {
                call.respond(HttpStatusCode.OK, mapOf("message" to "Pipeline stopped"))
            } else {
                call.respond(
                    HttpStatusCode.Conflict,
                    ApiError("No pipeline is currently running")
                )
            }
        }

        /**
         * WebSocket /api/pipeline/logs - Stream pipeline logs
         */
        webSocket("/logs") {
            val json = Json { prettyPrint = false }
            
            // Launch a coroutine to collect logs and send them
            launch {
                pipelineService.logFlow.collect { logMessage ->
                    try {
                        val jsonMessage = json.encodeToString(logMessage)
                        send(Frame.Text(jsonMessage))
                    } catch (e: Exception) {
                        // Client disconnected
                    }
                }
            }
            
            // Keep the connection open until client disconnects
            for (frame in incoming) {
                // Handle any client messages if needed
                when (frame) {
                    is Frame.Close -> break
                    else -> { /* ignore other frames */ }
                }
            }
        }
    }
}
