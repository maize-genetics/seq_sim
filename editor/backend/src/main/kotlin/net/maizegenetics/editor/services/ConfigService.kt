package net.maizegenetics.editor.services

import net.maizegenetics.editor.shared.*
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.FileWriter

class ConfigService {
    
    private val yaml = Yaml(DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        isPrettyFlow = true
    })

    /**
     * Load a pipeline configuration from a YAML file
     */
    fun loadConfig(filePath: String?): LoadConfigResponse {
        val file = if (filePath != null) File(filePath) else findDefaultConfig()
        
        return if (file != null && file.exists()) {
            val config = parseYamlFile(file)
            LoadConfigResponse(
                config = config,
                filePath = file.absolutePath,
                isDefault = filePath == null
            )
        } else {
            // Return default empty config
            LoadConfigResponse(
                config = createDefaultConfig(),
                isDefault = true
            )
        }
    }

    /**
     * Save a pipeline configuration to a YAML file
     */
    fun saveConfig(config: PipelineConfig, filePath: String?): SaveConfigResponse {
        val targetPath = filePath ?: "pipeline_config.yaml"
        val file = File(targetPath)
        
        return try {
            val yamlContent = configToYaml(config)
            FileWriter(file).use { writer ->
                writer.write(yamlContent)
            }
            SaveConfigResponse(
                success = true,
                message = "Configuration saved successfully",
                filePath = file.absolutePath
            )
        } catch (e: Exception) {
            SaveConfigResponse(
                success = false,
                message = "Failed to save configuration: ${e.message}"
            )
        }
    }

    /**
     * Validate if a path exists and get its properties
     */
    fun validatePath(path: String): ValidatePathResponse {
        val file = File(path)
        return ValidatePathResponse(
            exists = file.exists(),
            isFile = file.isFile,
            isDirectory = file.isDirectory,
            readable = file.canRead()
        )
    }

    /**
     * Find default config file in common locations
     */
    private fun findDefaultConfig(): File? {
        val candidates = listOf(
            "pipeline_config.yaml",
            "pipeline_config.yml",
            "config/pipeline_config.yaml",
            "../pipeline_config.yaml",
            "../pipeline_config.example.yaml"
        )
        return candidates.map { File(it) }.firstOrNull { it.exists() }
    }

    /**
     * Parse YAML file into PipelineConfig
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseYamlFile(file: File): PipelineConfig {
        val configMap = file.inputStream().use { input ->
            yaml.load<Map<String, Any>>(input)
        } ?: return createDefaultConfig()

        return PipelineConfig(
            workDir = configMap["work_dir"] as? String,
            runSteps = configMap["run_steps"] as? List<String>,
            alignAssemblies = parseAlignAssemblies(configMap["align_assemblies"] as? Map<String, Any>),
            mafToGvcf = parseMafToGvcf(configMap["maf_to_gvcf"] as? Map<String, Any>),
            downsampleGvcf = parseDownsampleGvcf(configMap["downsample_gvcf"] as? Map<String, Any>),
            convertToFasta = parseConvertToFasta(configMap["convert_to_fasta"] as? Map<String, Any>),
            alignMutatedAssemblies = parseAlignMutatedAssemblies(configMap["align_mutated_assemblies"] as? Map<String, Any>),
            pickCrossovers = parsePickCrossovers(configMap["pick_crossovers"] as? Map<String, Any>),
            createChainFiles = parseCreateChainFiles(configMap["create_chain_files"] as? Map<String, Any>),
            convertCoordinates = parseConvertCoordinates(configMap["convert_coordinates"] as? Map<String, Any>),
            generateRecombinedSequences = parseGenerateRecombinedSequences(configMap["generate_recombined_sequences"] as? Map<String, Any>),
            formatRecombinedFastas = parseFormatRecombinedFastas(configMap["format_recombined_fastas"] as? Map<String, Any>)
        )
    }

    private fun parseAlignAssemblies(map: Map<String, Any>?): AlignAssembliesConfig? {
        return map?.let {
            AlignAssembliesConfig(
                refGff = it["ref_gff"] as? String ?: "",
                refFasta = it["ref_fasta"] as? String ?: "",
                queryFasta = it["query_fasta"] as? String ?: "",
                threads = it["threads"] as? Int,
                output = it["output"] as? String
            )
        }
    }

    private fun parseMafToGvcf(map: Map<String, Any>?): MafToGvcfConfig? {
        return map?.let {
            MafToGvcfConfig(
                sampleName = it["sample_name"] as? String,
                input = it["input"] as? String,
                output = it["output"] as? String
            )
        }
    }

    private fun parseDownsampleGvcf(map: Map<String, Any>?): DownsampleGvcfConfig? {
        return map?.let {
            DownsampleGvcfConfig(
                ignoreContig = it["ignore_contig"] as? String,
                rates = it["rates"] as? String,
                seed = it["seed"] as? Int,
                keepRef = it["keep_ref"] as? Boolean,
                minRefBlockSize = it["min_ref_block_size"] as? Int,
                input = it["input"] as? String,
                output = it["output"] as? String
            )
        }
    }

    private fun parseConvertToFasta(map: Map<String, Any>?): ConvertToFastaConfig? {
        return map?.let {
            ConvertToFastaConfig(
                missingRecordsAs = it["missing_records_as"] as? String,
                missingGenotypeAs = it["missing_genotype_as"] as? String,
                input = it["input"] as? String,
                output = it["output"] as? String
            )
        }
    }

    private fun parseAlignMutatedAssemblies(map: Map<String, Any>?): AlignMutatedAssembliesConfig? {
        return map?.let {
            AlignMutatedAssembliesConfig(
                threads = it["threads"] as? Int,
                input = it["input"] as? String,
                output = it["output"] as? String
            )
        }
    }

    private fun parsePickCrossovers(map: Map<String, Any>?): PickCrossoversConfig? {
        return map?.let {
            PickCrossoversConfig(
                assemblyList = it["assembly_list"] as? String ?: "",
                output = it["output"] as? String
            )
        }
    }

    private fun parseCreateChainFiles(map: Map<String, Any>?): CreateChainFilesConfig? {
        return map?.let {
            CreateChainFilesConfig(
                jobs = it["jobs"] as? Int,
                input = it["input"] as? String,
                output = it["output"] as? String
            )
        }
    }

    private fun parseConvertCoordinates(map: Map<String, Any>?): ConvertCoordinatesConfig? {
        return map?.let {
            ConvertCoordinatesConfig(
                assemblyList = it["assembly_list"] as? String ?: "",
                inputChain = it["input_chain"] as? String,
                inputRefkey = it["input_refkey"] as? String,
                output = it["output"] as? String
            )
        }
    }

    private fun parseGenerateRecombinedSequences(map: Map<String, Any>?): GenerateRecombinedSequencesConfig? {
        return map?.let {
            GenerateRecombinedSequencesConfig(
                assemblyList = it["assembly_list"] as? String ?: "",
                chromosomeList = it["chromosome_list"] as? String ?: "",
                assemblyDir = it["assembly_dir"] as? String ?: "",
                input = it["input"] as? String,
                output = it["output"] as? String
            )
        }
    }

    private fun parseFormatRecombinedFastas(map: Map<String, Any>?): FormatRecombinedFastasConfig? {
        return map?.let {
            FormatRecombinedFastasConfig(
                lineWidth = it["line_width"] as? Int,
                threads = it["threads"] as? Int,
                input = it["input"] as? String,
                output = it["output"] as? String
            )
        }
    }

    /**
     * Convert PipelineConfig to YAML string
     */
    private fun configToYaml(config: PipelineConfig): String {
        val sb = StringBuilder()
        sb.appendLine("# Pipeline Configuration for seq_sim orchestrate command")
        sb.appendLine()

        config.workDir?.let {
            sb.appendLine("work_dir: \"$it\"")
            sb.appendLine()
        }

        config.runSteps?.let { steps ->
            sb.appendLine("run_steps:")
            steps.forEach { step ->
                sb.appendLine("  - $step")
            }
            sb.appendLine()
        }

        config.alignAssemblies?.let { writeAlignAssemblies(sb, it) }
        config.mafToGvcf?.let { writeMafToGvcf(sb, it) }
        config.downsampleGvcf?.let { writeDownsampleGvcf(sb, it) }
        config.convertToFasta?.let { writeConvertToFasta(sb, it) }
        config.alignMutatedAssemblies?.let { writeAlignMutatedAssemblies(sb, it) }
        config.pickCrossovers?.let { writePickCrossovers(sb, it) }
        config.createChainFiles?.let { writeCreateChainFiles(sb, it) }
        config.convertCoordinates?.let { writeConvertCoordinates(sb, it) }
        config.generateRecombinedSequences?.let { writeGenerateRecombinedSequences(sb, it) }
        config.formatRecombinedFastas?.let { writeFormatRecombinedFastas(sb, it) }

        return sb.toString()
    }

    private fun writeAlignAssemblies(sb: StringBuilder, config: AlignAssembliesConfig) {
        sb.appendLine("align_assemblies:")
        sb.appendLine("  ref_gff: \"${config.refGff}\"")
        sb.appendLine("  ref_fasta: \"${config.refFasta}\"")
        sb.appendLine("  query_fasta: \"${config.queryFasta}\"")
        config.threads?.let { sb.appendLine("  threads: $it") }
        config.output?.let { sb.appendLine("  output: \"$it\"") }
        sb.appendLine()
    }

    private fun writeMafToGvcf(sb: StringBuilder, config: MafToGvcfConfig) {
        sb.appendLine("maf_to_gvcf:")
        config.sampleName?.let { sb.appendLine("  sample_name: \"$it\"") }
        config.input?.let { sb.appendLine("  input: \"$it\"") }
        config.output?.let { sb.appendLine("  output: \"$it\"") }
        sb.appendLine()
    }

    private fun writeDownsampleGvcf(sb: StringBuilder, config: DownsampleGvcfConfig) {
        sb.appendLine("downsample_gvcf:")
        config.ignoreContig?.let { sb.appendLine("  ignore_contig: \"$it\"") }
        config.rates?.let { sb.appendLine("  rates: \"$it\"") }
        config.seed?.let { sb.appendLine("  seed: $it") }
        config.keepRef?.let { sb.appendLine("  keep_ref: $it") }
        config.minRefBlockSize?.let { sb.appendLine("  min_ref_block_size: $it") }
        config.input?.let { sb.appendLine("  input: \"$it\"") }
        config.output?.let { sb.appendLine("  output: \"$it\"") }
        sb.appendLine()
    }

    private fun writeConvertToFasta(sb: StringBuilder, config: ConvertToFastaConfig) {
        sb.appendLine("convert_to_fasta:")
        config.missingRecordsAs?.let { sb.appendLine("  missing_records_as: \"$it\"") }
        config.missingGenotypeAs?.let { sb.appendLine("  missing_genotype_as: \"$it\"") }
        config.input?.let { sb.appendLine("  input: \"$it\"") }
        config.output?.let { sb.appendLine("  output: \"$it\"") }
        sb.appendLine()
    }

    private fun writeAlignMutatedAssemblies(sb: StringBuilder, config: AlignMutatedAssembliesConfig) {
        sb.appendLine("align_mutated_assemblies:")
        config.threads?.let { sb.appendLine("  threads: $it") }
        config.input?.let { sb.appendLine("  input: \"$it\"") }
        config.output?.let { sb.appendLine("  output: \"$it\"") }
        sb.appendLine()
    }

    private fun writePickCrossovers(sb: StringBuilder, config: PickCrossoversConfig) {
        sb.appendLine("pick_crossovers:")
        sb.appendLine("  assembly_list: \"${config.assemblyList}\"")
        config.output?.let { sb.appendLine("  output: \"$it\"") }
        sb.appendLine()
    }

    private fun writeCreateChainFiles(sb: StringBuilder, config: CreateChainFilesConfig) {
        sb.appendLine("create_chain_files:")
        config.jobs?.let { sb.appendLine("  jobs: $it") }
        config.input?.let { sb.appendLine("  input: \"$it\"") }
        config.output?.let { sb.appendLine("  output: \"$it\"") }
        sb.appendLine()
    }

    private fun writeConvertCoordinates(sb: StringBuilder, config: ConvertCoordinatesConfig) {
        sb.appendLine("convert_coordinates:")
        sb.appendLine("  assembly_list: \"${config.assemblyList}\"")
        config.inputChain?.let { sb.appendLine("  input_chain: \"$it\"") }
        config.inputRefkey?.let { sb.appendLine("  input_refkey: \"$it\"") }
        config.output?.let { sb.appendLine("  output: \"$it\"") }
        sb.appendLine()
    }

    private fun writeGenerateRecombinedSequences(sb: StringBuilder, config: GenerateRecombinedSequencesConfig) {
        sb.appendLine("generate_recombined_sequences:")
        sb.appendLine("  assembly_list: \"${config.assemblyList}\"")
        sb.appendLine("  chromosome_list: \"${config.chromosomeList}\"")
        sb.appendLine("  assembly_dir: \"${config.assemblyDir}\"")
        config.input?.let { sb.appendLine("  input: \"$it\"") }
        config.output?.let { sb.appendLine("  output: \"$it\"") }
        sb.appendLine()
    }

    private fun writeFormatRecombinedFastas(sb: StringBuilder, config: FormatRecombinedFastasConfig) {
        sb.appendLine("format_recombined_fastas:")
        config.lineWidth?.let { sb.appendLine("  line_width: $it") }
        config.threads?.let { sb.appendLine("  threads: $it") }
        config.input?.let { sb.appendLine("  input: \"$it\"") }
        config.output?.let { sb.appendLine("  output: \"$it\"") }
        sb.appendLine()
    }

    private fun createDefaultConfig(): PipelineConfig {
        return PipelineConfig(
            workDir = "seq_sim_work",
            runSteps = listOf(
                "align_assemblies",
                "maf_to_gvcf",
                "downsample_gvcf",
                "convert_to_fasta",
                "align_mutated_assemblies"
            ),
            alignAssemblies = AlignAssembliesConfig(),
            mafToGvcf = MafToGvcfConfig(),
            downsampleGvcf = DownsampleGvcfConfig(rates = "0.01,0.05,0.1,0.15,0.2", keepRef = true, minRefBlockSize = 20),
            convertToFasta = ConvertToFastaConfig(missingRecordsAs = "asRef", missingGenotypeAs = "asN"),
            alignMutatedAssemblies = AlignMutatedAssembliesConfig(threads = 1)
        )
    }
}

