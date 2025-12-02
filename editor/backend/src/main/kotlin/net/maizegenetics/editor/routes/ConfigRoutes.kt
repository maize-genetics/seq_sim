package net.maizegenetics.editor.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import net.maizegenetics.editor.services.ConfigService
import net.maizegenetics.editor.shared.*

fun Route.configRoutes(configService: ConfigService) {
    route("/config") {
        
        /**
         * GET /api/config - Load configuration
         * Query params:
         *   - path: optional file path to load
         */
        get {
            val filePath = call.request.queryParameters["path"]
            val response = configService.loadConfig(filePath)
            call.respond(response)
        }

        /**
         * POST /api/config - Save configuration
         */
        post {
            val request = call.receive<SaveConfigRequest>()
            val response = configService.saveConfig(request.config, request.filePath)
            
            if (response.success) {
                call.respond(HttpStatusCode.OK, response)
            } else {
                call.respond(HttpStatusCode.InternalServerError, response)
            }
        }

        /**
         * POST /api/config/validate-path - Validate a file path
         */
        post("/validate-path") {
            val request = call.receive<ValidatePathRequest>()
            val response = configService.validatePath(request.path)
            call.respond(response)
        }

        /**
         * GET /api/config/steps - Get step metadata
         */
        get("/steps") {
            call.respond(PIPELINE_STEPS)
        }

        /**
         * GET /api/config/steps/{id} - Get specific step metadata
         */
        get("/steps/{id}") {
            val stepId = call.parameters["id"]
            if (stepId == null) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Missing step ID"))
                return@get
            }
            val step = getStepMetadata(stepId)
            if (step != null) {
                call.respond(step)
            } else {
                call.respond(HttpStatusCode.NotFound, ApiError("Step not found: $stepId"))
            }
        }
    }
}
