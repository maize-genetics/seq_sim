package net.maizegenetics.editor.shared

import kotlinx.serialization.Serializable

/**
 * API request/response models for the editor
 */

@Serializable
data class SaveConfigRequest(
    val config: PipelineConfig,
    val filePath: String? = null
)

@Serializable
data class SaveConfigResponse(
    val success: Boolean,
    val message: String,
    val filePath: String? = null
)

@Serializable
data class LoadConfigResponse(
    val config: PipelineConfig,
    val filePath: String? = null,
    val isDefault: Boolean = false
)

@Serializable
data class RunPipelineRequest(
    val config: PipelineConfig,
    val configFilePath: String? = null
)

@Serializable
data class PipelineStatus(
    val running: Boolean,
    val currentStep: String? = null,
    val progress: Int = 0,
    val totalSteps: Int = 0,
    val startTime: Long? = null,
    val error: String? = null
)

@Serializable
data class LogMessage(
    val timestamp: Long,
    val level: String,
    val message: String,
    val step: String? = null
)

@Serializable
data class ApiError(
    val error: String,
    val details: String? = null
)

@Serializable
data class ValidatePathRequest(
    val path: String
)

@Serializable
data class ValidatePathResponse(
    val exists: Boolean,
    val isFile: Boolean,
    val isDirectory: Boolean,
    val readable: Boolean
)

