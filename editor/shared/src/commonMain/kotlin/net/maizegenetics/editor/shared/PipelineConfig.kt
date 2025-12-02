package net.maizegenetics.editor.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Root pipeline configuration matching the YAML structure
 */
@Serializable
data class PipelineConfig(
    @SerialName("work_dir")
    val workDir: String? = null,
    @SerialName("run_steps")
    val runSteps: List<String>? = null,
    @SerialName("align_assemblies")
    val alignAssemblies: AlignAssembliesConfig? = null,
    @SerialName("maf_to_gvcf")
    val mafToGvcf: MafToGvcfConfig? = null,
    @SerialName("downsample_gvcf")
    val downsampleGvcf: DownsampleGvcfConfig? = null,
    @SerialName("convert_to_fasta")
    val convertToFasta: ConvertToFastaConfig? = null,
    @SerialName("align_mutated_assemblies")
    val alignMutatedAssemblies: AlignMutatedAssembliesConfig? = null,
    @SerialName("pick_crossovers")
    val pickCrossovers: PickCrossoversConfig? = null,
    @SerialName("create_chain_files")
    val createChainFiles: CreateChainFilesConfig? = null,
    @SerialName("convert_coordinates")
    val convertCoordinates: ConvertCoordinatesConfig? = null,
    @SerialName("generate_recombined_sequences")
    val generateRecombinedSequences: GenerateRecombinedSequencesConfig? = null,
    @SerialName("format_recombined_fastas")
    val formatRecombinedFastas: FormatRecombinedFastasConfig? = null
)

@Serializable
data class AlignAssembliesConfig(
    @SerialName("ref_gff")
    val refGff: String = "",
    @SerialName("ref_fasta")
    val refFasta: String = "",
    @SerialName("query_fasta")
    val queryFasta: String = "",
    val threads: Int? = null,
    val output: String? = null
)

@Serializable
data class MafToGvcfConfig(
    @SerialName("sample_name")
    val sampleName: String? = null,
    val input: String? = null,
    val output: String? = null
)

@Serializable
data class DownsampleGvcfConfig(
    @SerialName("ignore_contig")
    val ignoreContig: String? = null,
    val rates: String? = null,
    val seed: Int? = null,
    @SerialName("keep_ref")
    val keepRef: Boolean? = null,
    @SerialName("min_ref_block_size")
    val minRefBlockSize: Int? = null,
    val input: String? = null,
    val output: String? = null
)

@Serializable
data class ConvertToFastaConfig(
    @SerialName("missing_records_as")
    val missingRecordsAs: String? = null,
    @SerialName("missing_genotype_as")
    val missingGenotypeAs: String? = null,
    val input: String? = null,
    val output: String? = null
)

@Serializable
data class AlignMutatedAssembliesConfig(
    val threads: Int? = null,
    val input: String? = null,
    val output: String? = null
)

@Serializable
data class PickCrossoversConfig(
    @SerialName("assembly_list")
    val assemblyList: String = "",
    val output: String? = null
)

@Serializable
data class CreateChainFilesConfig(
    val jobs: Int? = null,
    val input: String? = null,
    val output: String? = null
)

@Serializable
data class ConvertCoordinatesConfig(
    @SerialName("assembly_list")
    val assemblyList: String = "",
    @SerialName("input_chain")
    val inputChain: String? = null,
    @SerialName("input_refkey")
    val inputRefkey: String? = null,
    val output: String? = null
)

@Serializable
data class GenerateRecombinedSequencesConfig(
    @SerialName("assembly_list")
    val assemblyList: String = "",
    @SerialName("chromosome_list")
    val chromosomeList: String = "",
    @SerialName("assembly_dir")
    val assemblyDir: String = "",
    val input: String? = null,
    val output: String? = null
)

@Serializable
data class FormatRecombinedFastasConfig(
    @SerialName("line_width")
    val lineWidth: Int? = null,
    val threads: Int? = null,
    val input: String? = null,
    val output: String? = null
)

