package net.maizegenetics.editor

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.serialization.json.Json
import net.maizegenetics.editor.routes.configRoutes
import net.maizegenetics.editor.routes.pipelineRoutes
import net.maizegenetics.editor.services.ConfigService
import net.maizegenetics.editor.services.PipelineService
import net.maizegenetics.editor.shared.ApiError
import java.time.Duration

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    // Initialize services
    val configService = ConfigService()
    val pipelineService = PipelineService()

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    install(CORS) {
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        anyHost()
    }

    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiError(
                    error = "Internal Server Error",
                    details = cause.message
                )
            )
        }
    }

    routing {
        // API routes
        route("/api") {
            configRoutes(configService)
            pipelineRoutes(pipelineService, configService)
        }

        // Serve static files (frontend)
        staticResources("/", "static") {
            default("index.html")
        }

        // Health check
        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }
    }
}

