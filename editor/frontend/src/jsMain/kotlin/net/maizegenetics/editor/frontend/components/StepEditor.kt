package net.maizegenetics.editor.frontend.components

import emotion.react.css
import net.maizegenetics.editor.frontend.M3Colors
import net.maizegenetics.editor.shared.*
import react.*
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.option
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.select
import react.dom.html.ReactHTML.span
import web.cssom.*
import web.html.InputType

external interface StepEditorProps : Props {
    var stepId: String
    var config: PipelineConfig
    var onConfigChange: (PipelineConfig) -> Unit
}

val StepEditor = FC<StepEditorProps> { props ->
    val stepMetadata = getStepMetadata(props.stepId)
    var selectedField by useState<FieldMetadata?>(null)

    if (stepMetadata == null) {
        div { +"Step not found" }
        return@FC
    }

    div {
        css {
            display = Display.flex
            height = 100.pct
            overflow = Overflow.hidden
        }

        // Editor form
        div {
            css {
                flexGrow = number(1.0)
                padding = 24.px
                overflowY = Auto.auto
                background = M3Colors.surfaceContainerLow
            }

            // Card container for the form
            div {
                css {
                    background = M3Colors.surfaceContainerLowest
                    borderRadius = 16.px
                    padding = 24.px
                    boxShadow = BoxShadow(0.px, 1.px, 2.px, 0.px, Color("rgba(0, 0, 0, 0.3)"))
                }

                // Step header
                div {
                    css {
                        marginBottom = 24.px
                    }

                    div {
                        css {
                            display = Display.flex
                            alignItems = AlignItems.center
                            gap = 12.px
                            marginBottom = 8.px
                        }

                        span {
                            css {
                                fontSize = 12.px
                                fontWeight = integer(500)
                                color = M3Colors.primary
                                fontFamily = string("'Roboto Mono', monospace")
                                background = M3Colors.primaryContainer
                                padding = 4.px
                                paddingLeft = 8.px
                                paddingRight = 8.px
                                borderRadius = 4.px
                            }
                            +"Step ${stepMetadata.stepNumber}"
                        }
                    }

                    h2 {
                        css {
                            fontSize = 24.px
                            fontWeight = integer(400)
                            color = M3Colors.onSurface
                            marginBottom = 8.px
                        }
                        +stepMetadata.name
                    }

                    p {
                        css {
                            fontSize = 14.px
                            color = M3Colors.onSurfaceVariant
                            lineHeight = number(1.5)
                        }
                        +stepMetadata.description
                    }
                }

                // Divider
                div {
                    css {
                        height = 1.px
                        background = M3Colors.outlineVariant
                        marginBottom = 24.px
                    }
                }

                // Form fields
                div {
                    css {
                        display = Display.flex
                        flexDirection = FlexDirection.column
                        gap = 24.px
                    }

                    stepMetadata.fields.forEach { field ->
                        FieldInput {
                            this.field = field
                            this.value = getFieldValue(props.stepId, field.name, props.config)
                            this.onChange = { newValue ->
                                val newConfig = setFieldValue(props.stepId, field.name, newValue, props.config)
                                props.onConfigChange(newConfig)
                            }
                            this.onFocus = { selectedField = field }
                        }
                    }
                }
            }
        }

        // Help panel - Supporting pane
        div {
            css {
                width = 320.px
                background = M3Colors.surfaceContainerLowest
                borderLeft = Border(1.px, LineStyle.solid, M3Colors.outlineVariant)
                padding = 24.px
                overflowY = Auto.auto
            }

            div {
                css {
                    fontSize = 11.px
                    fontWeight = integer(500)
                    color = M3Colors.onSurfaceVariant
                    textTransform = TextTransform.uppercase
                    letterSpacing = 0.5.px
                    marginBottom = 16.px
                }
                +"Field Help"
            }

            if (selectedField != null) {
                // Help card
                div {
                    css {
                        background = M3Colors.surfaceContainerHigh
                        borderRadius = 12.px
                        padding = 16.px
                    }

                    div {
                        css {
                            fontSize = 16.px
                            fontWeight = integer(500)
                            color = M3Colors.onSurface
                            marginBottom = 8.px
                        }
                        +selectedField!!.label
                    }

                    p {
                        css {
                            fontSize = 14.px
                            color = M3Colors.onSurfaceVariant
                            lineHeight = number(1.6)
                            marginBottom = 16.px
                        }
                        +selectedField!!.description
                    }

                    if (selectedField!!.required) {
                        div {
                            css {
                                display = Display.inlineBlock
                                padding = 4.px
                                paddingLeft = 12.px
                                paddingRight = 12.px
                                background = M3Colors.errorContainer
                                borderRadius = 8.px
                                fontSize = 12.px
                                fontWeight = integer(500)
                                color = M3Colors.onErrorContainer
                                marginBottom = 8.px
                            }
                            +"Required"
                        }
                    }

                    selectedField!!.defaultValue?.let { default ->
                        div {
                            css {
                                marginTop = 12.px
                                fontSize = 13.px
                                color = M3Colors.onSurfaceVariant
                            }
                            +"Default: "
                            span {
                                css {
                                    fontFamily = string("'Roboto Mono', monospace")
                                    color = M3Colors.onSurface
                                    background = M3Colors.surfaceVariant
                                    padding = 2.px
                                    paddingLeft = 6.px
                                    paddingRight = 6.px
                                    borderRadius = 4.px
                                }
                                +default
                            }
                        }
                    }
                }
            } else {
                div {
                    css {
                        color = M3Colors.onSurfaceVariant
                        fontSize = 14.px
                        textAlign = TextAlign.center
                        padding = 32.px
                        background = M3Colors.surfaceContainerHigh
                        borderRadius = 12.px
                    }
                    +"Click on a field to see help"
                }
            }
        }
    }
}

external interface FieldInputProps : Props {
    var field: FieldMetadata
    var value: String?
    var onChange: (String?) -> Unit
    var onFocus: () -> Unit
}

val FieldInput = FC<FieldInputProps> { props ->
    div {
        css {
            display = Display.flex
            flexDirection = FlexDirection.column
            gap = 8.px
        }

        // Label - Material 3 style
        label {
            css {
                display = Display.flex
                alignItems = AlignItems.center
                gap = 4.px
                fontSize = 12.px
                fontWeight = integer(500)
                color = M3Colors.onSurfaceVariant
            }

            +props.field.label

            if (props.field.required) {
                span {
                    css {
                        color = M3Colors.error
                        fontSize = 12.px
                    }
                    +"*"
                }
            }
        }

        when (props.field.type) {
            FieldType.SELECT -> {
                select {
                    css {
                        padding = 16.px
                        paddingLeft = 16.px
                        paddingRight = 16.px
                        background = M3Colors.surfaceContainerHighest
                        border = Border(1.px, LineStyle.solid, M3Colors.outline)
                        borderRadius = 4.px
                        color = M3Colors.onSurface
                        fontSize = 16.px
                        outline = None.none
                        focus {
                            borderColor = M3Colors.primary
                            borderWidth = 2.px
                        }
                    }
                    value = props.value ?: props.field.defaultValue ?: ""
                    onChange = { e -> props.onChange(e.target.value.ifEmpty { null }) }
                    onFocus = { props.onFocus() }

                    option {
                        value = ""
                        +"Select..."
                    }
                    props.field.options?.forEach { opt ->
                        option {
                            value = opt
                            +opt
                        }
                    }
                }
            }

            FieldType.BOOLEAN -> {
                div {
                    css {
                        display = Display.flex
                        alignItems = AlignItems.center
                        gap = 12.px
                        padding = 8.px
                    }

                    input {
                        type = InputType.checkbox
                        checked = props.value?.toBooleanStrictOrNull() ?: props.field.defaultValue?.toBooleanStrictOrNull() ?: false
                        onChange = { e ->
                            props.onChange(e.target.checked.toString())
                        }
                        onFocus = { props.onFocus() }
                        css {
                            width = 20.px
                            height = 20.px
                            accentColor = M3Colors.primary
                        }
                    }

                    span {
                        css {
                            fontSize = 16.px
                            color = M3Colors.onSurface
                        }
                        +"Enabled"
                    }
                }
            }

            FieldType.NUMBER -> {
                input {
                    type = InputType.number
                    value = props.value ?: props.field.defaultValue ?: ""
                    placeholder = props.field.defaultValue ?: "Enter number..."
                    onChange = { e -> props.onChange(e.target.value.ifEmpty { null }) }
                    onFocus = { props.onFocus() }
                    css {
                        padding = 16.px
                        paddingLeft = 16.px
                        paddingRight = 16.px
                        background = M3Colors.surfaceContainerHighest
                        border = Border(1.px, LineStyle.solid, M3Colors.outline)
                        borderRadius = 4.px
                        color = M3Colors.onSurface
                        fontSize = 16.px
                        outline = None.none
                        width = 100.pct
                        focus {
                            borderColor = M3Colors.primary
                            borderWidth = 2.px
                        }
                    }
                }
            }

            else -> {
                input {
                    type = InputType.text
                    value = props.value ?: ""
                    placeholder = if (props.field.type == FieldType.PATH) "Enter path..." else "Enter value..."
                    onChange = { e -> props.onChange(e.target.value.ifEmpty { null }) }
                    onFocus = { props.onFocus() }
                    css {
                        padding = 16.px
                        paddingLeft = 16.px
                        paddingRight = 16.px
                        background = M3Colors.surfaceContainerHighest
                        border = Border(1.px, LineStyle.solid, M3Colors.outline)
                        borderRadius = 4.px
                        color = M3Colors.onSurface
                        fontSize = 16.px
                        fontFamily = if (props.field.type == FieldType.PATH) {
                            string("'Roboto Mono', monospace")
                        } else {
                            FontFamily.sansSerif
                        }
                        outline = None.none
                        width = 100.pct
                        focus {
                            borderColor = M3Colors.primary
                            borderWidth = 2.px
                        }
                    }
                }
            }
        }
    }
}

// Helper functions to get/set field values in config
private fun getFieldValue(stepId: String, fieldName: String, config: PipelineConfig): String? {
    return when (stepId) {
        "align_assemblies" -> config.alignAssemblies?.let { cfg ->
            when (fieldName) {
                "refGff" -> cfg.refGff.takeIf { it.isNotEmpty() }
                "refFasta" -> cfg.refFasta.takeIf { it.isNotEmpty() }
                "queryFasta" -> cfg.queryFasta.takeIf { it.isNotEmpty() }
                "threads" -> cfg.threads?.toString()
                "output" -> cfg.output
                else -> null
            }
        }
        "maf_to_gvcf" -> config.mafToGvcf?.let { cfg ->
            when (fieldName) {
                "sampleName" -> cfg.sampleName
                "input" -> cfg.input
                "output" -> cfg.output
                else -> null
            }
        }
        "downsample_gvcf" -> config.downsampleGvcf?.let { cfg ->
            when (fieldName) {
                "ignoreContig" -> cfg.ignoreContig
                "rates" -> cfg.rates
                "seed" -> cfg.seed?.toString()
                "keepRef" -> cfg.keepRef?.toString()
                "minRefBlockSize" -> cfg.minRefBlockSize?.toString()
                "input" -> cfg.input
                "output" -> cfg.output
                else -> null
            }
        }
        "convert_to_fasta" -> config.convertToFasta?.let { cfg ->
            when (fieldName) {
                "missingRecordsAs" -> cfg.missingRecordsAs
                "missingGenotypeAs" -> cfg.missingGenotypeAs
                "input" -> cfg.input
                "output" -> cfg.output
                else -> null
            }
        }
        "align_mutated_assemblies" -> config.alignMutatedAssemblies?.let { cfg ->
            when (fieldName) {
                "threads" -> cfg.threads?.toString()
                "input" -> cfg.input
                "output" -> cfg.output
                else -> null
            }
        }
        "pick_crossovers" -> config.pickCrossovers?.let { cfg ->
            when (fieldName) {
                "assemblyList" -> cfg.assemblyList.takeIf { it.isNotEmpty() }
                "output" -> cfg.output
                else -> null
            }
        }
        "create_chain_files" -> config.createChainFiles?.let { cfg ->
            when (fieldName) {
                "jobs" -> cfg.jobs?.toString()
                "input" -> cfg.input
                "output" -> cfg.output
                else -> null
            }
        }
        "convert_coordinates" -> config.convertCoordinates?.let { cfg ->
            when (fieldName) {
                "assemblyList" -> cfg.assemblyList.takeIf { it.isNotEmpty() }
                "inputChain" -> cfg.inputChain
                "inputRefkey" -> cfg.inputRefkey
                "output" -> cfg.output
                else -> null
            }
        }
        "generate_recombined_sequences" -> config.generateRecombinedSequences?.let { cfg ->
            when (fieldName) {
                "assemblyList" -> cfg.assemblyList.takeIf { it.isNotEmpty() }
                "chromosomeList" -> cfg.chromosomeList.takeIf { it.isNotEmpty() }
                "assemblyDir" -> cfg.assemblyDir.takeIf { it.isNotEmpty() }
                "input" -> cfg.input
                "output" -> cfg.output
                else -> null
            }
        }
        "format_recombined_fastas" -> config.formatRecombinedFastas?.let { cfg ->
            when (fieldName) {
                "lineWidth" -> cfg.lineWidth?.toString()
                "threads" -> cfg.threads?.toString()
                "input" -> cfg.input
                "output" -> cfg.output
                else -> null
            }
        }
        else -> null
    }
}

private fun setFieldValue(stepId: String, fieldName: String, value: String?, config: PipelineConfig): PipelineConfig {
    return when (stepId) {
        "align_assemblies" -> {
            val current = config.alignAssemblies ?: AlignAssembliesConfig()
            config.copy(alignAssemblies = when (fieldName) {
                "refGff" -> current.copy(refGff = value ?: "")
                "refFasta" -> current.copy(refFasta = value ?: "")
                "queryFasta" -> current.copy(queryFasta = value ?: "")
                "threads" -> current.copy(threads = value?.toIntOrNull())
                "output" -> current.copy(output = value)
                else -> current
            })
        }
        "maf_to_gvcf" -> {
            val current = config.mafToGvcf ?: MafToGvcfConfig()
            config.copy(mafToGvcf = when (fieldName) {
                "sampleName" -> current.copy(sampleName = value)
                "input" -> current.copy(input = value)
                "output" -> current.copy(output = value)
                else -> current
            })
        }
        "downsample_gvcf" -> {
            val current = config.downsampleGvcf ?: DownsampleGvcfConfig()
            config.copy(downsampleGvcf = when (fieldName) {
                "ignoreContig" -> current.copy(ignoreContig = value)
                "rates" -> current.copy(rates = value)
                "seed" -> current.copy(seed = value?.toIntOrNull())
                "keepRef" -> current.copy(keepRef = value?.toBooleanStrictOrNull())
                "minRefBlockSize" -> current.copy(minRefBlockSize = value?.toIntOrNull())
                "input" -> current.copy(input = value)
                "output" -> current.copy(output = value)
                else -> current
            })
        }
        "convert_to_fasta" -> {
            val current = config.convertToFasta ?: ConvertToFastaConfig()
            config.copy(convertToFasta = when (fieldName) {
                "missingRecordsAs" -> current.copy(missingRecordsAs = value)
                "missingGenotypeAs" -> current.copy(missingGenotypeAs = value)
                "input" -> current.copy(input = value)
                "output" -> current.copy(output = value)
                else -> current
            })
        }
        "align_mutated_assemblies" -> {
            val current = config.alignMutatedAssemblies ?: AlignMutatedAssembliesConfig()
            config.copy(alignMutatedAssemblies = when (fieldName) {
                "threads" -> current.copy(threads = value?.toIntOrNull())
                "input" -> current.copy(input = value)
                "output" -> current.copy(output = value)
                else -> current
            })
        }
        "pick_crossovers" -> {
            val current = config.pickCrossovers ?: PickCrossoversConfig()
            config.copy(pickCrossovers = when (fieldName) {
                "assemblyList" -> current.copy(assemblyList = value ?: "")
                "output" -> current.copy(output = value)
                else -> current
            })
        }
        "create_chain_files" -> {
            val current = config.createChainFiles ?: CreateChainFilesConfig()
            config.copy(createChainFiles = when (fieldName) {
                "jobs" -> current.copy(jobs = value?.toIntOrNull())
                "input" -> current.copy(input = value)
                "output" -> current.copy(output = value)
                else -> current
            })
        }
        "convert_coordinates" -> {
            val current = config.convertCoordinates ?: ConvertCoordinatesConfig()
            config.copy(convertCoordinates = when (fieldName) {
                "assemblyList" -> current.copy(assemblyList = value ?: "")
                "inputChain" -> current.copy(inputChain = value)
                "inputRefkey" -> current.copy(inputRefkey = value)
                "output" -> current.copy(output = value)
                else -> current
            })
        }
        "generate_recombined_sequences" -> {
            val current = config.generateRecombinedSequences ?: GenerateRecombinedSequencesConfig()
            config.copy(generateRecombinedSequences = when (fieldName) {
                "assemblyList" -> current.copy(assemblyList = value ?: "")
                "chromosomeList" -> current.copy(chromosomeList = value ?: "")
                "assemblyDir" -> current.copy(assemblyDir = value ?: "")
                "input" -> current.copy(input = value)
                "output" -> current.copy(output = value)
                else -> current
            })
        }
        "format_recombined_fastas" -> {
            val current = config.formatRecombinedFastas ?: FormatRecombinedFastasConfig()
            config.copy(formatRecombinedFastas = when (fieldName) {
                "lineWidth" -> current.copy(lineWidth = value?.toIntOrNull())
                "threads" -> current.copy(threads = value?.toIntOrNull())
                "input" -> current.copy(input = value)
                "output" -> current.copy(output = value)
                else -> current
            })
        }
        else -> config
    }
}
