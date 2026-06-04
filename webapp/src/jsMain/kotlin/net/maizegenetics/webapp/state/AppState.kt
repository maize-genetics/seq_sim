package net.maizegenetics.webapp.state

import net.maizegenetics.webapp.model.PipelineSpec

/**
 * Immutable snapshot of the builder form state.
 *
 * @property version  Selected pipeline version ("v1" / "v2").
 * @property workDir  The `work_dir` value.
 * @property enabled  Set of step keys to include in `run_steps`.
 * @property values   Per-step field values, keyed by stepKey -> (fieldKey -> value).
 */
data class AppState(
    val version: String,
    val workDir: String,
    val enabled: Set<String>,
    val values: Map<String, Map<String, String>>,
) {
    companion object {
        /**
         * Builds an initial state for [spec]: every step enabled, with each
         * field's schema default pre-seeded so the generated YAML mirrors the
         * example template files.
         */
        fun seed(spec: PipelineSpec, workDir: String = "seq_sim_work"): AppState {
            val seededValues = spec.steps.associate { step ->
                step.key to step.fields.mapNotNull { f -> f.default?.let { f.key to it } }.toMap()
            }
            return AppState(
                version = spec.version,
                workDir = workDir,
                enabled = spec.steps.map { it.key }.toSet(),
                values = seededValues,
            )
        }
    }
}
