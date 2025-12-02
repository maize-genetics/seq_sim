package net.maizegenetics.editor.frontend

import kotlinx.browser.window
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.maizegenetics.editor.shared.*
import org.w3c.fetch.RequestInit
import kotlin.js.json

object ApiClient {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private const val BASE_URL = "/api"

    fun loadConfig(
        filePath: String? = null,
        callback: (Result<LoadConfigResponse>) -> Unit
    ) {
        val url = if (filePath != null) {
            "$BASE_URL/config?path=$filePath"
        } else {
            "$BASE_URL/config"
        }

        window.fetch(url)
            .then { response ->
                if (response.ok) {
                    response.text().then { text ->
                        val result = json.decodeFromString<LoadConfigResponse>(text)
                        callback(Result.success(result))
                    }
                } else {
                    callback(Result.failure(Exception("Failed to load config: ${response.status}")))
                }
            }
            .catch { error ->
                callback(Result.failure(Exception(error.toString())))
            }
    }

    fun saveConfig(
        config: PipelineConfig,
        filePath: String? = null,
        callback: (Result<SaveConfigResponse>) -> Unit
    ) {
        val request = SaveConfigRequest(config = config, filePath = filePath)
        val body = json.encodeToString(request)

        window.fetch(
            "$BASE_URL/config",
            RequestInit(
                method = "POST",
                headers = json("Content-Type" to "application/json"),
                body = body
            )
        ).then { response ->
            response.text().then { text ->
                val result = json.decodeFromString<SaveConfigResponse>(text)
                callback(Result.success(result))
            }
        }.catch { error ->
            callback(Result.failure(Exception(error.toString())))
        }
    }

    fun getStatus(callback: (Result<PipelineStatus>) -> Unit) {
        window.fetch("$BASE_URL/pipeline/status")
            .then { response ->
                if (response.ok) {
                    response.text().then { text ->
                        val result = json.decodeFromString<PipelineStatus>(text)
                        callback(Result.success(result))
                    }
                } else {
                    callback(Result.failure(Exception("Failed to get status")))
                }
            }
            .catch { error ->
                callback(Result.failure(Exception(error.toString())))
            }
    }

    fun runPipeline(
        config: PipelineConfig,
        callback: (Result<Unit>) -> Unit
    ) {
        val request = RunPipelineRequest(config = config)
        val body = json.encodeToString(request)

        window.fetch(
            "$BASE_URL/pipeline/run",
            RequestInit(
                method = "POST",
                headers = json("Content-Type" to "application/json"),
                body = body
            )
        ).then { response ->
            if (response.ok) {
                callback(Result.success(Unit))
            } else {
                response.text().then { text ->
                    callback(Result.failure(Exception(text)))
                }
            }
        }.catch { error ->
            callback(Result.failure(Exception(error.toString())))
        }
    }

    fun stopPipeline(callback: (Result<Unit>) -> Unit) {
        window.fetch(
            "$BASE_URL/pipeline/stop",
            RequestInit(method = "POST")
        ).then { response ->
            if (response.ok) {
                callback(Result.success(Unit))
            } else {
                callback(Result.failure(Exception("Failed to stop pipeline")))
            }
        }.catch { error ->
            callback(Result.failure(Exception(error.toString())))
        }
    }

    fun validatePath(
        path: String,
        callback: (Result<ValidatePathResponse>) -> Unit
    ) {
        val request = ValidatePathRequest(path = path)
        val body = json.encodeToString(request)

        window.fetch(
            "$BASE_URL/config/validate-path",
            RequestInit(
                method = "POST",
                headers = json("Content-Type" to "application/json"),
                body = body
            )
        ).then { response ->
            if (response.ok) {
                response.text().then { text ->
                    val result = json.decodeFromString<ValidatePathResponse>(text)
                    callback(Result.success(result))
                }
            } else {
                callback(Result.failure(Exception("Failed to validate path")))
            }
        }.catch { error ->
            callback(Result.failure(Exception(error.toString())))
        }
    }
}

