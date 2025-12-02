package net.maizegenetics.editor.shared

import kotlinx.serialization.Serializable

/**
 * Metadata about pipeline steps including help text and field descriptions
 */
@Serializable
data class StepMetadata(
    val id: String,
    val name: String,
    val description: String,
    val stepNumber: Int,
    val fields: List<FieldMetadata>
)

@Serializable
data class FieldMetadata(
    val name: String,
    val label: String,
    val description: String,
    val type: FieldType,
    val required: Boolean = false,
    val defaultValue: String? = null,
    val options: List<String>? = null
)

@Serializable
enum class FieldType {
    TEXT, NUMBER, BOOLEAN, PATH, SELECT
}

/**
 * All available pipeline steps
 */
val PIPELINE_STEPS = listOf(
    StepMetadata(
        id = "align_assemblies",
        name = "Align Assemblies",
        description = "Aligns query assemblies to a reference using AnchorWave and minimap2",
        stepNumber = 1,
        fields = listOf(
            FieldMetadata("refGff", "Reference GFF", "Reference GFF annotation file used for extracting CDS sequences with AnchorWave", FieldType.PATH, required = true),
            FieldMetadata("refFasta", "Reference FASTA", "Reference FASTA file", FieldType.PATH, required = true),
            FieldMetadata("queryFasta", "Query FASTA", "Single query file, directory of FASTA files, or text list of file paths", FieldType.PATH, required = true),
            FieldMetadata("threads", "Threads", "Number of threads to use for alignment", FieldType.NUMBER, defaultValue = "1"),
            FieldMetadata("output", "Output Directory", "Custom output directory (optional)", FieldType.PATH)
        )
    ),
    StepMetadata(
        id = "maf_to_gvcf",
        name = "MAF to GVCF",
        description = "Converts MAF alignment files to compressed GVCF format using biokotlin-tools",
        stepNumber = 2,
        fields = listOf(
            FieldMetadata("sampleName", "Sample Name", "Sample name to use in the GVCF file (defaults to MAF file base name)", FieldType.TEXT),
            FieldMetadata("input", "Input", "Custom MAF input file, directory, or text list (overrides automatic chaining)", FieldType.PATH),
            FieldMetadata("output", "Output Directory", "Custom output directory (optional)", FieldType.PATH)
        )
    ),
    StepMetadata(
        id = "downsample_gvcf",
        name = "Downsample GVCF",
        description = "Downsamples GVCF files at specified rates per chromosome using MLImpute",
        stepNumber = 3,
        fields = listOf(
            FieldMetadata("ignoreContig", "Ignore Contig", "Comma-separated list of contig patterns to ignore", FieldType.TEXT),
            FieldMetadata("rates", "Rates", "Comma-separated downsampling rates for each chromosome", FieldType.TEXT, defaultValue = "0.01,0.05,0.1,0.15,0.2"),
            FieldMetadata("seed", "Seed", "Random seed for reproducibility", FieldType.NUMBER),
            FieldMetadata("keepRef", "Keep Reference", "Keep reference blocks in output", FieldType.BOOLEAN, defaultValue = "true"),
            FieldMetadata("minRefBlockSize", "Min Ref Block Size", "Minimum reference block size to sample", FieldType.NUMBER, defaultValue = "20"),
            FieldMetadata("input", "Input", "Custom GVCF input directory (overrides automatic chaining)", FieldType.PATH),
            FieldMetadata("output", "Output Directory", "Custom output directory (optional)", FieldType.PATH)
        )
    ),
    StepMetadata(
        id = "convert_to_fasta",
        name = "Convert to FASTA",
        description = "Converts downsampled GVCF files to FASTA format using MLImpute",
        stepNumber = 4,
        fields = listOf(
            FieldMetadata("missingRecordsAs", "Missing Records As", "How to handle missing GVCF records", FieldType.SELECT, defaultValue = "asRef", options = listOf("asN", "asRef", "asNone")),
            FieldMetadata("missingGenotypeAs", "Missing Genotype As", "How to handle missing genotypes", FieldType.SELECT, defaultValue = "asN", options = listOf("asN", "asRef", "asNone")),
            FieldMetadata("input", "Input", "Custom GVCF input (overrides automatic chaining)", FieldType.PATH),
            FieldMetadata("output", "Output Directory", "Custom output directory (optional)", FieldType.PATH)
        )
    ),
    StepMetadata(
        id = "align_mutated_assemblies",
        name = "Align Mutated Assemblies",
        description = "Realigns mutated FASTA files back to the reference for comparison",
        stepNumber = 5,
        fields = listOf(
            FieldMetadata("threads", "Threads", "Number of threads to use for alignment", FieldType.NUMBER, defaultValue = "1"),
            FieldMetadata("input", "Input", "Custom FASTA input (overrides automatic chaining)", FieldType.PATH),
            FieldMetadata("output", "Output Directory", "Custom output directory (optional)", FieldType.PATH)
        )
    ),
    StepMetadata(
        id = "pick_crossovers",
        name = "Pick Crossovers",
        description = "Simulates crossover events to generate recombination breakpoints in reference coordinates",
        stepNumber = 6,
        fields = listOf(
            FieldMetadata("assemblyList", "Assembly List", "Tab-separated file with assembly paths and names (path<TAB>name)", FieldType.PATH, required = true),
            FieldMetadata("output", "Output Directory", "Custom output directory (optional)", FieldType.PATH)
        )
    ),
    StepMetadata(
        id = "create_chain_files",
        name = "Create Chain Files",
        description = "Converts MAF alignment files to UCSC chain format for coordinate conversion",
        stepNumber = 7,
        fields = listOf(
            FieldMetadata("jobs", "Parallel Jobs", "Number of parallel jobs for processing", FieldType.NUMBER, defaultValue = "8"),
            FieldMetadata("input", "Input", "Custom MAF input (overrides automatic chaining)", FieldType.PATH),
            FieldMetadata("output", "Output Directory", "Custom output directory (optional)", FieldType.PATH)
        )
    ),
    StepMetadata(
        id = "convert_coordinates",
        name = "Convert Coordinates",
        description = "Converts crossover breakpoints from reference to assembly coordinates using CrossMap",
        stepNumber = 8,
        fields = listOf(
            FieldMetadata("assemblyList", "Assembly List", "Tab-separated file with assembly paths and names", FieldType.PATH, required = true),
            FieldMetadata("inputChain", "Chain Directory", "Custom chain directory (overrides automatic chaining)", FieldType.PATH),
            FieldMetadata("inputRefkey", "Refkey Directory", "Custom refkey directory (overrides automatic chaining)", FieldType.PATH),
            FieldMetadata("output", "Output Directory", "Custom output directory (optional)", FieldType.PATH)
        )
    ),
    StepMetadata(
        id = "generate_recombined_sequences",
        name = "Generate Recombined Sequences",
        description = "Creates recombined FASTA sequences by concatenating segments from parent assemblies",
        stepNumber = 9,
        fields = listOf(
            FieldMetadata("assemblyList", "Assembly List", "Tab-separated file with assembly paths and names", FieldType.PATH, required = true),
            FieldMetadata("chromosomeList", "Chromosome List", "Text file with chromosome names (one per line)", FieldType.PATH, required = true),
            FieldMetadata("assemblyDir", "Assembly Directory", "Directory containing parent assembly FASTA files", FieldType.PATH, required = true),
            FieldMetadata("input", "Founder Key Directory", "Custom founder key directory (overrides automatic chaining)", FieldType.PATH),
            FieldMetadata("output", "Output Directory", "Custom output directory (optional)", FieldType.PATH)
        )
    ),
    StepMetadata(
        id = "format_recombined_fastas",
        name = "Format Recombined FASTAs",
        description = "Reformats recombined FASTA files with consistent line widths using seqkit",
        stepNumber = 10,
        fields = listOf(
            FieldMetadata("lineWidth", "Line Width", "Characters per line in formatted FASTA", FieldType.NUMBER, defaultValue = "60"),
            FieldMetadata("threads", "Threads", "Number of threads for seqkit", FieldType.NUMBER, defaultValue = "8"),
            FieldMetadata("input", "Input", "Custom FASTA input (overrides automatic chaining)", FieldType.PATH),
            FieldMetadata("output", "Output Directory", "Custom output directory (optional)", FieldType.PATH)
        )
    )
)

/**
 * Helper to get step metadata by ID
 */
fun getStepMetadata(stepId: String): StepMetadata? = PIPELINE_STEPS.find { it.id == stepId }

