package net.maizegenetics.webapp.model

/**
 * Concrete schema definitions for the v1 (full, 15-step) and v2 (variant,
 * 8-step) orchestrate pipelines.
 *
 * Field names, defaults, required flags and help text mirror the *Config
 * data classes in `src/main/kotlin/net/maizegenetics/commands/Orchestrate.kt`
 * and the two example YAML files. The defaults seeded here match the example
 * files so a freshly generated YAML looks like the templates users expect.
 */

private fun text(key: String, label: String, required: Boolean = false, default: String? = null, help: String = "") =
    FieldSpec(key, label, FieldType.TEXT, required = required, default = default, help = help)

private fun int(key: String, label: String, default: String? = null, help: String = "") =
    FieldSpec(key, label, FieldType.INT, default = default, help = help)

private fun bool(key: String, label: String, default: String? = null, help: String = "") =
    FieldSpec(key, label, FieldType.BOOL, default = default, help = help)

private fun enum(key: String, label: String, values: List<String>, default: String? = null, help: String = "") =
    FieldSpec(key, label, FieldType.ENUM, default = default, help = help, enumValues = values)

// --- Shared field groups ----------------------------------------------------

/** PHGv2 align-assemblies knobs shared by align_assemblies / align_mutated_assemblies. */
private fun alignTuningFields() = listOf(
    int("threads", "Threads", default = "1", help = "PHGv2 --total-threads"),
    int("in_parallel", "In parallel", help = "PHGv2 --in-parallel (omit for auto-tuning)"),
    int("ref_max_align_cov", "Ref max align cov", help = "PHGv2 --ref-max-align-cov (proali -R)"),
    int("query_max_align_cov", "Query max align cov", help = "PHGv2 --query-max-align-cov (proali -Q)"),
    text("conda_env_prefix", "Conda env prefix", help = "PHGv2 --conda-env-prefix (overrides phgv2-conda)"),
    bool("just_ref_prep", "Just ref prep", help = "PHGv2 --just-ref-prep (ref-prep only, no MAFs)"),
    text("output", "Output directory", help = "Optional custom output directory"),
)

private fun mafToGvcfFields() = listOf(
    text("reference_file", "Reference FASTA", help = "Uses align_assemblies.ref_fasta if omitted"),
    text("maf_file", "MAF input", help = "MAF file, directory, or text list (uses previous align step if omitted)"),
    text("output_file", "Output filename", help = "Output GVCF filename (auto-generated if omitted)"),
    text("sample_name", "Sample name", help = "Override the sample name written into the GVCF"),
    text("output_dir", "Output directory", help = "Optional custom GVCF output directory"),
)

private fun downsampleFields() = listOf(
    text("ignore_contig", "Ignore contig", default = "__NO_MATCH__", help = "Comma-separated patterns to ignore (currently needed)"),
    text("rates", "Rates", default = "0.01,0.05,0.1,0.15,0.2", help = "Comma-separated downsampling rates per chromosome"),
    int("seed", "Seed", default = "42", help = "Random seed for reproducibility"),
    bool("keep_ref", "Keep reference blocks", default = "true", help = "Keep reference blocks (default: true)"),
    int("min_ref_block_size", "Min ref block size", default = "20", help = "Minimum reference block size to sample"),
    text("input", "Input directory", help = "Custom GVCF input directory/list"),
    text("output", "Output directory", help = "Optional custom output directory"),
)

private fun alignAssembliesStep(key: String, title: String, description: String, includeFastaInput: Boolean) =
    StepSpec(
        key = key,
        title = title,
        description = description,
        fields = buildList {
            add(text("ref_gff", "Reference GFF", required = !includeFastaInput, help = "Reference GFF annotation file"))
            add(text("ref_fasta", "Reference FASTA", required = !includeFastaInput, help = "Reference FASTA file"))
            if (includeFastaInput) {
                add(text("fasta_input", "FASTA input", help = "Query FASTA file/dir/list (uses format_recombined_fastas output if omitted)"))
            } else {
                add(text("query_fasta", "Query FASTA", required = true, help = "Single query file, directory, or text list"))
            }
            addAll(alignTuningFields())
        },
    )

// --- v1 (full) pipeline ------------------------------------------------------

val V1_SPEC = PipelineSpec(
    version = "v1",
    label = "v1 · full pipeline (15 steps)",
    steps = listOf(
        alignAssembliesStep(
            "align_assemblies", "01 · Align assemblies",
            "Align query assemblies to a reference using AnchorWave and minimap2.",
            includeFastaInput = false,
        ),
        StepSpec(
            "maf_to_gvcf", "02 · MAF to GVCF",
            "Convert MAF files from align_assemblies into compressed GVCFs.",
            mafToGvcfFields(),
        ),
        StepSpec(
            "downsample_gvcf", "03 · Downsample GVCF",
            "Downsample GVCF files at the specified rates per chromosome.",
            downsampleFields(),
        ),
        StepSpec(
            "convert_to_fasta", "04 · Convert to FASTA",
            "Convert downsampled GVCF files to mutated FASTA sequences.",
            listOf(
                enum("missing_records_as", "Missing records as", listOf("asN", "asRef", "asNone"), default = "asRef", help = "How to handle missing records"),
                enum("missing_genotype_as", "Missing genotype as", listOf("asN", "asRef", "asNone"), default = "asN", help = "How to handle missing genotypes"),
                text("ignore_contig", "Ignore contig", default = "__NO_MATCH__", help = "Comma-separated patterns to skip (currently needed)"),
                text("input", "Input", help = "Custom GVCF input (file, directory, or text list)"),
                text("output", "Output directory", help = "Optional custom FASTA output directory"),
            ),
        ),
        StepSpec(
            "pick_crossovers", "05 · Pick crossovers",
            "Simulate crossover events. Requires an EVEN number of assemblies.",
            listOf(
                text("assembly_list", "Assembly list", help = "Tab-separated <path><TAB><name>; auto-generated from step 04 if omitted"),
                text("ref_fasta", "Reference FASTA", help = "Uses align_assemblies.ref_fasta if omitted"),
                text("output", "Output directory", help = "Optional custom output directory"),
            ),
        ),
        StepSpec(
            "create_chain_files", "06 · Create chain files",
            "Convert MAF alignment files to UCSC chain format for coordinate conversion.",
            listOf(
                int("jobs", "Jobs", default = "8", help = "Number of parallel jobs"),
                text("maf_file_input", "MAF input", help = "Custom MAF input (defaults to step 01 MAF outputs)"),
                text("output", "Output directory", help = "Optional custom output directory"),
            ),
        ),
        StepSpec(
            "convert_coordinates", "07 · Convert coordinates",
            "Convert crossover breakpoints from reference to assembly coordinates.",
            listOf(
                text("assembly_list", "Assembly list", help = "Defaults to the assembly list from pick_crossovers"),
                text("input_chain", "Chain directory", help = "Custom chain directory"),
                text("input_refkey", "Refkey directory", help = "Custom refkey directory"),
                text("output", "Output directory", help = "Optional custom output directory"),
            ),
        ),
        StepSpec(
            "generate_recombined_sequences", "08 · Generate recombined sequences",
            "Concatenate parent assembly segments into recombined FASTA sequences.",
            listOf(
                text("assembly_list", "Assembly list", help = "Defaults to the assembly list from pick_crossovers"),
                text("chromosome_list", "Chromosome list", help = "Text file of chromosome names (auto-derived if omitted)"),
                text("assembly_dir", "Assembly directory", help = "Directory of parent assembly FASTA files"),
            ),
        ),
        StepSpec(
            "format_recombined_fastas", "09 · Format recombined FASTAs",
            "Reformat recombined FASTA files to a consistent line width with seqkit.",
            listOf(
                int("line_width", "Line width", default = "60", help = "Characters per line"),
                int("threads", "Threads", default = "8", help = "Number of threads"),
                text("input", "Input", help = "Custom FASTA input (file, directory, or text list)"),
                text("output", "Output directory", help = "Optional custom output directory"),
            ),
        ),
        alignAssembliesStep(
            "align_mutated_assemblies", "10 · Align mutated assemblies",
            "Realign formatted recombined FASTA files back to the reference.",
            includeFastaInput = true,
        ),
        StepSpec(
            "mutated_maf_to_gvcf", "11 · Mutated MAF to GVCF",
            "Convert MAF files from align_mutated_assemblies into compressed GVCFs.",
            mafToGvcfFields(),
        ),
        StepSpec(
            "rope_bwt_chr_index", "12 · Rope-BWT chr index",
            "Create a PHGv2 ropebwt3 index from the recombined FASTA files.",
            listOf(
                text("index_file_prefix", "Index file prefix", default = "phgIndex", help = "Prefix for generated index files"),
                int("threads", "Threads", default = "20", help = "Threads for index creation"),
                bool("delete_fmr_index", "Delete .fmr index", default = "true", help = "Delete .fmr files after converting to .fmd"),
                text("keyfile", "Keyfile", help = "Pre-made keyfile (overrides auto-generation). No underscores in sample names"),
                text("output", "Output directory", help = "Optional custom output directory"),
            ),
        ),
        StepSpec(
            "ropebwt_mem", "13 · Ropebwt3 mem",
            "Align FASTQ reads to the ropebwt3 index and produce BED alignment files.",
            listOf(
                text("fastq_input", "FASTQ input", required = true, help = "FASTQ file, directory, or text list (.fq/.fastq[.gz])"),
                int("threads", "Threads", default = "40", help = "Number of threads"),
                int("p_value", "p value", default = "168", help = "The ropebwt3 -p parameter"),
                text("index_file", "Index file", help = "Custom .fmd index (auto-detected from step 12)"),
                int("l_value", "l value", help = "The -l parameter (auto-calculated as 2 x FASTA count)"),
                text("output", "Output directory", help = "Optional custom output directory"),
            ),
        ),
        StepSpec(
            "build_spline_knots", "14 · Build spline knots",
            "Build spline knots from hVCF or gVCF files for imputation (independent step).",
            listOf(
                text("vcf_dir", "VCF directory", required = true, help = "Directory containing hVCF or gVCF files"),
                enum("vcf_type", "VCF type", listOf("gvcf", "hvcf"), default = "gvcf", help = "Type of VCF files"),
                int("min_indel_length", "Min indel length", default = "10", help = "Minimum indel length for gVCF"),
                int("num_bps_per_knot", "Bps per knot", default = "50000", help = "Maximum base pairs per knot"),
                int("random_seed", "Random seed", default = "12345", help = "Seed for downsampling points per chromosome"),
                text("contig_list", "Contig list", help = "Comma-separated chromosome list (default: all)"),
                text("output", "Output directory", help = "Optional custom output directory"),
            ),
        ),
        StepSpec(
            "convert_ropebwt2ps4g", "15 · Convert ropebwt to PS4G",
            "Convert RopeBWT3 BED alignment files to PS4G format.",
            listOf(
                int("min_mem_length", "Min MEM length", default = "135", help = "Minimum MEM length threshold in bp"),
                int("max_num_hits", "Max num hits", default = "16", help = "Maximum allowable haplotype hits per alignment"),
                text("bed_input", "BED input", help = "Custom BED input (auto-detected from step 13)"),
                text("spline_knot_dir", "Spline knot directory", help = "Custom spline knot directory (auto-detected from step 14)"),
                text("output", "Output directory", help = "Optional custom output directory"),
            ),
        ),
    ),
)

// --- v2 (variant) pipeline ---------------------------------------------------

val V2_SPEC = PipelineSpec(
    version = "v2",
    label = "v2 · variant pipeline (8 steps)",
    steps = listOf(
        alignAssembliesStep(
            "align_assemblies", "01 · Align assemblies",
            "Align query assemblies to a reference using AnchorWave and minimap2.",
            includeFastaInput = false,
        ),
        StepSpec(
            "maf_to_gvcf", "02 · MAF to GVCF",
            "Convert MAF files into compressed GVCFs named {sample}.g.vcf.gz.",
            mafToGvcfFields(),
        ),
        StepSpec(
            "split_gvcfs", "03 · Split GVCFs",
            "Split the step-02 gVCFs into base / mutation-donor sets via a keyfile.",
            listOf(
                text("keyfile", "Keyfile", required = true, help = "Tab-delimited; header columns 'Base' and 'MutationDonor'"),
                text("input", "Input directory", help = "GVCF input dir/list (defaults to maf_to_gvcf output)"),
                text("output", "Output directory", help = "Optional custom output directory"),
            ),
        ),
        StepSpec(
            "downsample_gvcf", "04 · Downsample GVCF",
            "Downsample the mutation-donor gVCFs from split_gvcfs at the given rates.",
            downsampleFields(),
        ),
        StepSpec(
            "mutate_assemblies", "05 · Mutate assemblies",
            "Mutate each base gVCF with its downsampled mutation donor(s).",
            listOf(
                text("keyfile", "Keyfile", help = "Defaults to split_gvcfs pairs.tsv"),
                text("base_input", "Base input", help = "Defaults to split_gvcfs base/ output"),
                text("mutation_donor_input", "Mutation donor input", help = "Defaults to downsample_gvcf output"),
                text("output", "Output directory", help = "Optional custom output directory"),
            ),
        ),
        StepSpec(
            "pick_crossovers", "06 · Pick crossovers",
            "Pick crossover breakpoints on the BASE assemblies (matched count must be even).",
            listOf(
                text("ref_fasta", "Reference FASTA", help = "Uses align_assemblies.ref_fasta if omitted"),
                text("query_fasta", "Query FASTA", help = "Uses align_assemblies.query_fasta if omitted"),
                text("base_input", "Base input", help = "Defaults to split_gvcfs base/ output"),
                text("output", "Output directory", help = "Optional custom output directory"),
            ),
        ),
        StepSpec(
            "recombine_gvcfs", "07 · Recombine GVCFs",
            "Recombine mutated base gVCFs along the crossover breakpoints.",
            listOf(
                text("ref_file", "Reference FASTA", help = "Uses align_assemblies.ref_fasta if omitted"),
                text("input_bed", "Input BED", help = "Defaults to pick_crossovers output (step 06)"),
                text("input_gvcf", "Input GVCF", help = "Defaults to mutate_assemblies output (step 05)"),
                text("output", "Output directory", help = "Custom output directory for recombined gVCFs"),
                text("output_bed", "Output BED directory", help = "Custom output directory for resized BEDs"),
            ),
        ),
        StepSpec(
            "sort_gvcfs", "08 · Sort GVCFs",
            "Sort the recombined gVCFs into coordinate order with bcftools and index them.",
            listOf(
                text("input", "Input directory", help = "Defaults to recombine_gvcfs output (step 07)"),
                int("threads", "Threads", default = "4", help = "Threads for bcftools"),
                text("output", "Output directory", help = "Optional custom output directory"),
            ),
        ),
    ),
)

/** Returns the schema for the given version string ("v2" -> v2, anything else -> v1). */
fun specFor(version: String): PipelineSpec = if (version == "v2") V2_SPEC else V1_SPEC
