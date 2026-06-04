package net.maizegenetics.webapp.components

import net.maizegenetics.webapp.model.V1_SPEC
import net.maizegenetics.webapp.model.specFor
import net.maizegenetics.webapp.state.AppState
import net.maizegenetics.webapp.yaml.YamlBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.aside
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.header
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.main
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.section
import react.useState
import web.cssom.ClassName
import web.html.InputType
import web.html.text

/** Root component: version toggle, work_dir input, step cards, and live YAML preview. */
val App = FC<Props> {
    val (state, setState) = useState { AppState.seed(V1_SPEC) }
    val spec = specFor(state.version)
    val yamlText = YamlBuilder.build(spec, state)

    div {
        className = ClassName("app")

        header {
            className = ClassName("app-header")
            div {
                className = ClassName("app-header-inner")
                h1 {
                    className = ClassName("app-title")
                    +"seq_sim Orchestrate Builder"
                }
                p {
                    className = ClassName("app-subtitle")
                    +"Configure a pipeline and download the orchestrate YAML configuration."
                }
            }
        }

        main {
            className = ClassName("layout")

            section {
                className = ClassName("form-column")

                div {
                    className = ClassName("card setup-card")

                    div {
                        className = ClassName("field")
                        label {
                            className = ClassName("field-label")
                            +"Pipeline version"
                        }
                        div {
                            className = ClassName("segmented")
                            listOf("v1", "v2").forEach { v ->
                                button {
                                    className = ClassName(if (state.version == v) "seg-btn active" else "seg-btn")
                                    onClick = {
                                        if (state.version != v) {
                                            setState(AppState.seed(specFor(v), state.workDir))
                                        }
                                    }
                                    +specFor(v).label
                                }
                            }
                        }
                    }

                    div {
                        className = ClassName("field")
                        label {
                            className = ClassName("field-label")
                            +"Working directory"
                        }
                        input {
                            className = ClassName("text-input")
                            type = InputType.text
                            value = state.workDir
                            placeholder = "seq_sim_work"
                            onChange = { event -> setState(state.copy(workDir = event.target.value)) }
                        }
                        p {
                            className = ClassName("field-help")
                            +"Where the pipeline stores tools, logs, and outputs (default: seq_sim_work)."
                        }
                    }
                }

                spec.steps.forEach { step ->
                    StepCard {
                        key = step.key
                        this.spec = step
                        enabled = step.key in state.enabled
                        values = state.values[step.key] ?: emptyMap()
                        onToggle = { on ->
                            val newEnabled = if (on) state.enabled + step.key else state.enabled - step.key
                            setState(state.copy(enabled = newEnabled))
                        }
                        onFieldChange = { fieldKey, value ->
                            val stepValues = (state.values[step.key] ?: emptyMap()).toMutableMap()
                            stepValues[fieldKey] = value
                            setState(state.copy(values = state.values + (step.key to stepValues)))
                        }
                    }
                }
            }

            aside {
                className = ClassName("preview-column")
                YamlPreview {
                    yaml = yamlText
                    fileName = if (state.version == "v2") "pipeline_config_v2.yaml" else "pipeline_config.yaml"
                }
            }
        }
    }
}
