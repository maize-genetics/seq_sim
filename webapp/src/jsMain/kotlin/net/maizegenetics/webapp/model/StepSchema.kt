package net.maizegenetics.webapp.model

/**
 * The kind of form control + YAML serialization rule for a field.
 *  - TEXT / ENUM are emitted as quoted YAML strings.
 *  - INT / BOOL are emitted bare (unquoted).
 */
enum class FieldType { TEXT, INT, BOOL, ENUM }

/**
 * Declarative description of a single configuration field within a step.
 *
 * @property key      YAML key (matches the corresponding *Config data class field).
 * @property label    Human-readable label shown in the form.
 * @property type     Form control + serialization rule.
 * @property required Whether the orchestrate command requires this field for the step.
 * @property default  Default value seeded into the form (also emitted to YAML).
 * @property help     Short explanatory text shown beneath the control.
 * @property enumValues Allowed values for [FieldType.ENUM] fields.
 */
data class FieldSpec(
    val key: String,
    val label: String,
    val type: FieldType,
    val required: Boolean = false,
    val default: String? = null,
    val help: String = "",
    val enumValues: List<String> = emptyList(),
)

/** A single orchestrate pipeline step and the fields it accepts. */
data class StepSpec(
    val key: String,
    val title: String,
    val description: String,
    val fields: List<FieldSpec>,
)

/** A complete pipeline definition (v1 or v2) and its ordered steps. */
data class PipelineSpec(
    val version: String,
    val label: String,
    val steps: List<StepSpec>,
)
