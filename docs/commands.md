# Commands

All commands share the same invocation pattern: `seq_sim <command> [OPTIONS]`.
seqSim ships two pipeline versions, both run through the same
[`orchestrate`](#orchestrate-recommended) command and selected by the `version`
field in the YAML config. Many commands are shared between the two pipelines;
some are specific to one. The tables below map each command to its step in each
pipeline (see the [v1 Pipeline](pipeline-v1.md#pipeline-overview) and
[v2 Pipeline](pipeline-v2.md#pipeline-overview) overviews for the full context).

### v1 Pipeline Commands (default)

The 15-step FASTA-level pipeline (`version: "v1"`, or omitted).

| Step | Command | Description |
|------|---------|-------------|
| 00 | [`setup-environment`](#setup-environment-step-00) | Initialize environment and download tools |
| 01 | [`align-assemblies`](#align-assemblies-step-01) | Align query assemblies to reference |
| 02 | [`maf-to-gvcf`](#maf-to-gvcf-step-02) | Convert MAF alignments to GVCF |
| 03 | [`downsample-gvcf`](#downsample-gvcf-step-03) | Downsample variants per chromosome |
| 04 | [`convert-to-fasta`](#convert-to-fasta-step-04) | Generate mutated FASTAs from variants |
| 05 | [`pick-crossovers`](#pick-crossovers-step-05) | Pick crossover breakpoints (reference coords) |
| 06 | [`create-chain-files`](#create-chain-files-step-06) | Convert MAF alignments to CHAIN format |
| 07 | [`convert-coordinates`](#convert-coordinates-step-07) | Convert reference coords to assembly coords |
| 08 | [`generate-recombined-sequences`](#generate-recombined-sequences-step-08) | Concatenate parent segments into FASTAs |
| 09 | [`format-recombined-fastas`](#format-recombined-fastas-step-09) | Normalize recombined FASTA line widths |
| 10 | [`align-mutated-assemblies`](#align-mutated-assemblies-step-10) | Realign recombined FASTAs to reference |
| 11 | [`mutated-maf-to-gvcf`](#mutated-maf-to-gvcf-step-11-orchestrate-only) | Convert mutated MAFs to GVCF (reuses maf-to-gvcf) |
| 12 | [`rope-bwt-chr-index`](#rope-bwt-chr-index-step-12) | Build PHGv2 ropebwt3 index |
| 13 | [`ropebwt-mem`](#ropebwt-mem-step-13) | Align FASTQ reads to the ropebwt3 index |
| 14 | [`build-spline-knots`](#build-spline-knots-step-14) | Build spline knots for imputation |
| 15 | [`convert-ropebwt2ps4g`](#convert-ropebwt2ps4g-step-15) | Convert ropebwt BED alignments to PS4G |

### v2 Pipeline Commands

The 12-step gVCF-level pipeline (`version: "v2"`).

| Step | Command | Description |
|------|---------|-------------|
| 00 | [`setup-environment`](#setup-environment-step-00) | Initialize environment and download tools |
| 01 | [`align-assemblies`](#align-assemblies-step-01) | Align query assemblies to reference |
| 02 | [`maf-to-gvcf`](#maf-to-gvcf-step-02) | Convert MAF alignments to GVCF |
| 03 | [`split-gvcfs`](#split-gvcfs-v2-step-03) | Split gVCFs into base / mutation-donor sets |
| 04 | [`downsample-gvcf`](#downsample-gvcf-step-03) | Downsample the mutation-donor gVCFs |
| 05 | [`mutate-assemblies`](#mutate-assemblies-v2-step-05) | Mutate base gVCFs with downsampled donors |
| 06 | [`pick-base-crossovers`](#pick-base-crossovers-v2-step-06) | Pick crossovers on the base assemblies |
| 07 | [`recombine-gvcfs`](#recombine-gvcfs-v2-step-07) | Recombine mutated base gVCFs along crossovers |
| 08 | [`sort-gvcfs`](#sort-gvcfs-v2-step-08) | Sort recombined gVCFs with bcftools |
| 09 | [`convert-to-fasta`](#convert-to-fasta-step-04) | Convert sorted gVCFs back to FASTA |
| 10 | [`build-spline-knots`](#build-spline-knots-step-14) | Build spline knots from sorted gVCFs |
| 11 | `ropebwt` = [`rope-bwt-chr-index`](#rope-bwt-chr-index-step-12) + [`ropebwt-mem`](#ropebwt-mem-step-13) | Build index and align FASTQ reads (combined step) |
| 12 | [`convert-ropebwt2ps4g`](#convert-ropebwt2ps4g-step-15) | Convert ropebwt BED alignments to PS4G |

> **Note:** The step numbers in the command headings below follow the **v1**
> pipeline ordering. Where a command is also used by v2 (at a different step
> number), that role is noted in the command's description and in the table
> above. v2-only commands are documented under
> [v2 Pipeline Commands](#v2-pipeline-commands-1).

### Helper Commands

Standalone utilities that are not part of either `orchestrate` pipeline.

| Command | Description |
|---------|-------------|
| [extract-chrom-ids](#extract-chrom-ids) | Extract unique chromosome IDs from GVCF files |

## `orchestrate` (Recommended)

**Runs the entire pipeline from a YAML configuration file with automatic environment setup.**

**Usage:**
```bash
seq_sim orchestrate [OPTIONS]
```

**Options:**
- `--config`, `-c`: Path to YAML configuration file (required)
- `--work-dir`, `-w`: Override the `work_dir` from the YAML (optional)

**What it does:**
1. **Auto-detects environment** - Validates if setup is needed
2. **Automatic setup** - Runs setup-environment only if tools are missing
3. **Sequential execution** - Runs configured steps in order
4. **Output chaining** - Automatically passes outputs between steps
5. **Selective execution** - Skip or rerun specific steps via `run_steps`

The full list of configurable steps, their parameters, and how outputs chain
between steps is documented in [`pipeline_config.example.yaml`](../pipeline_config.example.yaml).
A minimal configuration that runs every step looks like:

```yaml
work_dir: "seq_sim_work"

run_steps:
  # Variant pipeline
  - align_assemblies
  - maf_to_gvcf
  - downsample_gvcf
  - convert_to_fasta
  # Recombination pipeline
  - pick_crossovers
  - create_chain_files
  - convert_coordinates
  - generate_recombined_sequences
  - format_recombined_fastas
  # PS4G creation
  - align_mutated_assemblies
  - mutated_maf_to_gvcf
  - rope_bwt_chr_index
  - ropebwt_mem
  - build_spline_knots
  - convert_ropebwt2ps4g

align_assemblies:
  ref_gff: "reference.gff"
  ref_fasta: "reference.fa"
  query_fasta: "queries.txt"
  threads: 8

# ... each step's options mirror its standalone CLI options;
# see pipeline_config.example.yaml for the full schema.
```

**Example:**
```bash
# Full pipeline (environment setup runs automatically if needed)
seq_sim orchestrate --config pipeline.yaml

# Rerun a subset by editing run_steps in the YAML (e.g. only step 09):
# run_steps: [format_recombined_fastas]
seq_sim orchestrate --config pipeline.yaml
```

---

## `setup-environment` (Step `00`)

Initializes the environment and downloads dependencies. **Note: This runs automatically with orchestrate!**

**Usage:**
```bash
seq_sim setup-environment [OPTIONS]
```

**Options:**
- `--work-dir`, `-w`: Working directory for files and scripts (default: `seq_sim_work`)

**What it does:**
- Copies `pixi.toml` to the working directory
- Installs the pixi environment with all dependencies:
  - Python 3.10, NumPy, Pandas, pysam
  - Java 21 (OpenJDK)
  - minimap2 2.28
  - AnchorWave (Linux only)
  - agc 3.1, ropebwt3 3.8
  - seqkit, CrossMap, GNU parallel
- Downloads and extracts the MLImpute, biokotlin-tools, and PHGv2 repositories to `<work-dir>/src/`

**Output:**
- `<work-dir>/pixi.toml`, `<work-dir>/.pixi/`
- `<work-dir>/src/MLImpute/`, `<work-dir>/src/biokotlin-tools/`, `<work-dir>/src/phg_v2/`
- `<work-dir>/logs/00_setup_environment.log`

**Example:**
```bash
seq_sim setup-environment -w my_workdir
```

---

## `align-assemblies` (Step `01`)

Aligns multiple query assemblies to a reference genome via the PHGv2
[`align-assemblies`](https://phg.maizegenetics.net/build_and_load/#align-assemblies-parameters)
command, which itself drives AnchorWave + minimap2 under the hood. This wrapper
keeps seq_sim's CLI surface (`--ref-gff`, `--ref-fasta`, `--query-fasta`, ...)
and the `maf_file_paths.txt` output contract that downstream steps depend on.

**Usage:**
```bash
seq_sim align-assemblies [OPTIONS]
```

**Options:**
- `--work-dir`, `-w`: Working directory (default: `seq_sim_work`)
- `--ref-gff`, `-g`: Reference GFF file (required, forwarded as PHGv2 `--gff`)
- `--ref-fasta`, `-r`: Reference FASTA file (required, forwarded as PHGv2 `--reference-file`). For best results this should be the output of `phg prepare-assemblies`.
- `--query-fasta`, `-q`: Query input (required) - can be a single FASTA (`.fa`, `.fasta`, `.fna`), a directory of FASTAs, or a text file listing one path per line. Translated to a PHGv2 `--assembly-file-list` internally.
- `--threads`, `-t`: Total number of threads available to PHGv2 (`--total-threads`, default: 1)
- `--in-parallel`: How many alignments to run in parallel (PHGv2 `--in-parallel`). If omitted, PHGv2 picks a value from system memory and thread count.
- `--ref-max-align-cov`: Maximum reference genome alignment coverage for AnchorWave `proali` (PHGv2 `--ref-max-align-cov`, default: 1)
- `--query-max-align-cov`: Maximum query genome alignment coverage for AnchorWave `proali` (PHGv2 `--query-max-align-cov`, default: 1)
- `--conda-env-prefix`: Path to a Conda env containing PHGv2's runtime deps (anchorwave, minimap2, samtools, ...). Defaults to the `phgv2-conda` env in its standard location.
- `--just-ref-prep`: Only run PHGv2's reference-prep phase and stop. Useful for SLURM array workflows; no per-query MAFs and no `maf_file_paths.txt` are produced.
- `--output-dir`, `-o`: Custom output directory (default: `<work-dir>/output/01_anchorwave_results`)

**What it does:**
1. Collects the query FASTA list from `--query-fasta` and writes it as
   `<output-dir>/assemblies_list.txt` (the PHGv2 `--assembly-file-list`).
2. Invokes `phg align-assemblies` from `<work-dir>/src/phg_v2/bin/phg`. PHGv2
   then runs `anchorwave gff2seq`, `minimap2`, and `anchorwave proali`
   internally.
3. Collects the resulting `.maf` files PHGv2 wrote to the output directory and
   produces `maf_file_paths.txt` so downstream steps (`maf-to-gvcf`,
   `create-chain-files`) continue to work unchanged.

**Output:**
- `<work-dir>/output/01_anchorwave_results/assemblies_list.txt` (PHGv2 assembly-file-list, generated by this wrapper)
- `<work-dir>/output/01_anchorwave_results/{queryName}.maf` (per-query alignment, one file each)
- `<work-dir>/output/01_anchorwave_results/{queryName}.sam`
- `<work-dir>/output/01_anchorwave_results/{queryName}_{refBase}.anchorspro`
- `<work-dir>/output/01_anchorwave_results/{queryName}.svg` (dot plot)
- `<work-dir>/output/01_anchorwave_results/ref.cds.fasta`, `{refBase}.sam` (reference-prep outputs)
- `<work-dir>/output/01_anchorwave_results/maf_file_paths.txt`
- `<work-dir>/logs/01_align_assemblies.log`

**Examples:**
```bash
# Directory of queries, 8 threads
seq_sim align-assemblies -g ref.gff -r ref.fa -q queries/ -t 8

# Text list of query paths, 4 threads, run 2 alignments in parallel
seq_sim align-assemblies -g ref.gff -r ref.fa -q queries.txt -t 4 --in-parallel 2

# Reference-prep only (for SLURM array workflows)
seq_sim align-assemblies -g ref.gff -r ref.fa -q queries.txt --just-ref-prep
```

---

## `maf-to-gvcf` (Step `02`)

Converts MAF alignment files to compressed GVCF format using biokotlin-tools.

This command is also reused internally by `orchestrate` as the **mutated_maf_to_gvcf**
step (step 11) to convert MAF files produced by `align-mutated-assemblies`.

**Usage:**
```bash
seq_sim maf-to-gvcf [OPTIONS]
```

**Options:**
- `--work-dir`, `-w`: Working directory (default: `seq_sim_work`)
- `--reference-file`, `-r`: Reference FASTA file (required)
- `--maf-file`, `-m`: MAF input (required) - single file, directory, or text list
- `--output-file`, `-o`: Output GVCF file name (auto-generated for multiple inputs)
- `--output-dir`: Override the output directory (useful when reusing this command as step 11)
- `--sample-name`, `-s`: Sample name for GVCF (defaults to MAF base name)

**Output:**
- `<work-dir>/output/02_gvcf_results/*.g.vcf.gz` (or `11_mutated_gvcf_results/` when run as step 11)
- `<work-dir>/output/02_gvcf_results/gvcf_file_paths.txt`
- `<work-dir>/logs/02_maf_to_gvcf.log`

**Examples:**
```bash
# Using path list from align-assemblies (recommended)
seq_sim maf-to-gvcf -r ref.fa -m seq_sim_work/output/01_anchorwave_results/maf_file_paths.txt

# Running as step 11 (mutated MAFs from align-mutated-assemblies)
seq_sim maf-to-gvcf -r ref.fa \
    -m seq_sim_work/output/10_mutated_alignment_results/maf_file_paths.txt \
    --output-dir seq_sim_work/output/11_mutated_gvcf_results/
```

---

## `downsample-gvcf` (Step `03`)

Downsamples GVCF files at specified rates using MLImpute's `DownsampleGvcf`.

**Usage:**
```bash
seq_sim downsample-gvcf [OPTIONS]
```

**Options:**
- `--work-dir`, `-w`: Working directory (default: `seq_sim_work`)
- `--gvcf-dir`, `-g`: Input directory containing GVCF files (required)
- `--ignore-contig`: Comma-separated contig patterns to ignore
- `--rates`: Comma-separated downsampling rates per chromosome (default: `0.01,0.05,0.1,0.15,0.2,0.3,0.35,0.4,0.45,0.49`)
- `--seed`: Random seed for reproducibility
- `--keep-ref`: Keep reference blocks (default: true)
- `--min-ref-block-size`: Minimum ref block size (default: 20)
- `--keep-uncompressed`: Keep temporary uncompressed files (default: false)

**Output:**
- `<work-dir>/output/03_downsample_results/*_subsampled.gvcf`
- `<work-dir>/output/03_downsample_results/*_subsampled_block_sizes.tsv`
- `<work-dir>/logs/03_downsample_gvcf.log`

**Example:**
```bash
seq_sim downsample-gvcf -g seq_sim_work/output/02_gvcf_results/ --rates 0.1,0.2,0.3 --seed 42
```

---

## `convert-to-fasta` (Step `04`)

Generates FASTA files from downsampled GVCF files using MLImpute's `ConvertToFasta`.

**Usage:**
```bash
seq_sim convert-to-fasta [OPTIONS]
```

**Options:**
- `--work-dir`, `-w`: Working directory (default: `seq_sim_work`)
- `--gvcf-file`, `-g`: GVCF input (required) - single file, directory, or text list
- `--ref-fasta`, `-r`: Reference FASTA file (required)
- `--missing-records-as`: How to handle missing records: `asN`, `asRef`, `asNone` (default: `asRef`)
- `--missing-genotype-as`: How to handle missing genotypes: `asN`, `asRef`, `asNone` (default: `asN`)

**Output:**
- `<work-dir>/output/04_fasta_results/*.fasta`
- `<work-dir>/output/04_fasta_results/fasta_file_paths.txt`
- `<work-dir>/logs/04_convert_to_fasta.log`

**Example:**
```bash
seq_sim convert-to-fasta -r ref.fa -g seq_sim_work/output/03_downsample_results/
```

---

## `pick-crossovers` (Step `05`)

Simulates crossover events in reference coordinates and writes refkey BED files
that track which parent each genomic region comes from.

**Usage:**
```bash
seq_sim pick-crossovers [OPTIONS]
```

**Options:**
- `--work-dir`, `-w`: Working directory (default: `seq_sim_work`)
- `--ref-fasta`, `-r`: Reference FASTA file (required)
- `--assembly-list`, `-a`: Tab-separated file with `path<TAB>name` (required) - **must contain an even number of assemblies** (they are paired for crossover simulation)

**Output:**
- `<work-dir>/output/05_crossovers_results/{founder}_refkey.bed`
- `<work-dir>/output/05_crossovers_results/refkey_file_paths.txt`
- `<work-dir>/logs/05_pick_crossovers.log`

**Example:**
```bash
seq_sim pick-crossovers -r reference.fa -a assembly_list.txt
```

**Assembly list format (`assembly_list.txt`):**
```
/path/to/assembly1.fa	parent1
/path/to/assembly2.fa	parent2
/path/to/assembly3.fa	parent3
/path/to/assembly4.fa	parent4
```

---

## `create-chain-files` (Step `06`)

Converts MAF alignment files to UCSC CHAIN format for coordinate conversion.

**Usage:**
```bash
seq_sim create-chain-files [OPTIONS]
```

**Options:**
- `--work-dir`, `-w`: Working directory (default: `seq_sim_work`)
- `--maf-input`, `-m`: MAF input (required) - single `.maf`/`.maf.gz`, directory, or text list
- `--jobs`, `-j`: Number of parallel jobs (default: 8)

**Output:**
- `<work-dir>/output/06_chain_results/*.chain`
- `<work-dir>/output/06_chain_results/chain_file_paths.txt`
- `<work-dir>/logs/06_create_chain_files.log`

**Examples:**
```bash
# Using MAF paths from align-assemblies (recommended)
seq_sim create-chain-files -m seq_sim_work/output/01_anchorwave_results/maf_file_paths.txt -j 12

# Directory of MAF files
seq_sim create-chain-files -m mafs/ -j 8
```

---

## `convert-coordinates` (Step `07`)

Converts reference-coordinate refkey BED files to assembly coordinates using
chain files (via CrossMap).

**Usage:**
```bash
seq_sim convert-coordinates [OPTIONS]
```

**Options:**
- `--work-dir`, `-w`: Working directory (default: `seq_sim_work`)
- `--assembly-list`, `-a`: Tab-separated file with assembly paths and names (required)
- `--chain-dir`, `-c`: Directory containing chain files (required)
- `--refkey-dir`, `-r`: Directory containing refkey BED files (optional, auto-detected from step 05)

**Output:**
- `<work-dir>/output/07_coordinates_results/{assembly}_key.bed` (assembly coordinates)
- `<work-dir>/output/07_coordinates_results/{founder}_key.bed` (FASTA coordinates)
- `<work-dir>/output/07_coordinates_results/key_file_paths.txt`
- `<work-dir>/output/07_coordinates_results/founder_key_file_paths.txt`
- `<work-dir>/logs/07_convert_coordinates.log`

**Example:**
```bash
seq_sim convert-coordinates -a assembly_list.txt -c seq_sim_work/output/06_chain_results/
```

---

## `generate-recombined-sequences` (Step `08`)

Generates recombined FASTA sequences by concatenating segments from parent
assemblies based on the founder key files from step 07.

**Usage:**
```bash
seq_sim generate-recombined-sequences [OPTIONS]
```

**Options:**
- `--work-dir`, `-w`: Working directory (default: `seq_sim_work`)
- `--assembly-list`, `-a`: Tab-separated file with assembly paths and names (required)
- `--chromosome-list`, `-c`: Text file with chromosome names, one per line (optional; auto-derived from the first assembly if omitted)
- `--assembly-dir`, `-d`: Directory containing parent assembly FASTA files (required)
- `--founder-key-dir`, `-k`: Directory containing founder key BED files (optional, auto-detected from step 07)

**Output:**
- `<work-dir>/output/08_recombined_sequences/recombinate_fastas/{founder}.fa`
- `<work-dir>/output/08_recombined_sequences/recombined_fasta_paths.txt`
- `<work-dir>/logs/08_generate_recombined_sequences.log`

**Example:**
```bash
seq_sim generate-recombined-sequences \
    -a assembly_list.txt -c chromosomes.txt -d data/assemblies/
```

**Chromosome list format (`chromosomes.txt`):**
```
chr1
chr2
chr3
```

---

## `format-recombined-fastas` (Step `09`)

Reformats recombined FASTA files to a consistent line width using seqkit.

**Usage:**
```bash
seq_sim format-recombined-fastas [OPTIONS]
```

**Options:**
- `--work-dir`, `-w`: Working directory (default: `seq_sim_work`)
- `--fasta-input`, `-f`: FASTA file, directory, or text list (optional, auto-detected from step 08)
- `--line-width`, `-l`: Characters per line (default: 60)
- `--threads`, `-t`: Number of threads for seqkit (default: 8)
- `--output-dir`, `-o`: Custom output directory (default: `work_dir/output/09_formatted_fastas`)

**Output:**
- `<work-dir>/output/09_formatted_fastas/{founder}.fa`
- `<work-dir>/output/09_formatted_fastas/formatted_fasta_paths.txt`
- `<work-dir>/logs/09_format_recombined_fastas.log`

**Example:**
```bash
seq_sim format-recombined-fastas \
    -f seq_sim_work/output/08_recombined_sequences/recombinate_fastas/ -l 60 -t 8
```

---

## `align-mutated-assemblies` (Step `10`)

Realigns the formatted recombined (or otherwise mutated) FASTA files back to
the reference genome via the PHGv2
[`align-assemblies`](https://phg.maizegenetics.net/build_and_load/#align-assemblies-parameters)
command, which itself drives AnchorWave + minimap2 under the hood. This is the
first step of the PS4G creation workflow. The wrapper keeps seq_sim's existing
CLI surface and the `maf_file_paths.txt` output contract that step 11
(`mutated-maf-to-gvcf`) depends on.

**Usage:**
```bash
seq_sim align-mutated-assemblies [OPTIONS]
```

**Options:**
- `--work-dir`, `-w`: Working directory (default: `seq_sim_work`)
- `--ref-gff`, `-g`: Reference GFF file (required, forwarded as PHGv2 `--gff`)
- `--ref-fasta`, `-r`: Reference FASTA file (required, forwarded as PHGv2 `--reference-file`). For best results this should be the output of `phg prepare-assemblies`.
- `--fasta-input`, `-f`: FASTA input (required) - single file, directory, or text list. Translated to a PHGv2 `--assembly-file-list` internally.
- `--threads`, `-t`: Total number of threads available to PHGv2 (`--total-threads`, default: 1)
- `--in-parallel`: How many alignments to run in parallel (PHGv2 `--in-parallel`). If omitted, PHGv2 picks a value from system memory and thread count.
- `--ref-max-align-cov`: Maximum reference genome alignment coverage for AnchorWave `proali` (PHGv2 `--ref-max-align-cov`, default: 1)
- `--query-max-align-cov`: Maximum query genome alignment coverage for AnchorWave `proali` (PHGv2 `--query-max-align-cov`, default: 1)
- `--conda-env-prefix`: Path to a Conda env containing PHGv2's runtime deps. Defaults to the `phgv2-conda` env in its standard location.
- `--just-ref-prep`: Only run PHGv2's reference-prep phase and stop. No per-query MAFs and no `maf_file_paths.txt` are produced.
- `--output-dir`, `-o`: Custom output directory (default: `<work-dir>/output/10_mutated_alignment_results`)

**Output:**
- `<work-dir>/output/10_mutated_alignment_results/assemblies_list.txt` (PHGv2 assembly-file-list, generated by this wrapper)
- `<work-dir>/output/10_mutated_alignment_results/{fastaName}.maf` (per-FASTA alignment, one file each)
- `<work-dir>/output/10_mutated_alignment_results/{fastaName}.sam`
- `<work-dir>/output/10_mutated_alignment_results/{fastaName}_{refBase}.anchorspro`
- `<work-dir>/output/10_mutated_alignment_results/{fastaName}.svg` (dot plot)
- `<work-dir>/output/10_mutated_alignment_results/ref.cds.fasta`, `{refBase}.sam` (reference-prep outputs)
- `<work-dir>/output/10_mutated_alignment_results/maf_file_paths.txt`
- `<work-dir>/logs/10_align_mutated_assemblies.log`

**Example:**
```bash
seq_sim align-mutated-assemblies \
    -g ref.gff -r ref.fa -f seq_sim_work/output/09_formatted_fastas/ -t 8

# Run 2 alignments in parallel with 4 total threads
seq_sim align-mutated-assemblies \
    -g ref.gff -r ref.fa -f seq_sim_work/output/09_formatted_fastas/ \
    -t 4 --in-parallel 2
```

---

## `mutated-maf-to-gvcf` (Step `11`, orchestrate only)

Converts the mutated MAF files from `align-mutated-assemblies` into GVCFs. This
step has no dedicated clikt subcommand - `orchestrate` runs the
[`maf-to-gvcf`](#maf-to-gvcf-step-02) command under the hood with different
inputs and directs output to `<work-dir>/output/11_mutated_gvcf_results/`.

To reproduce it manually, run `maf-to-gvcf` with the MAF paths from step 10:

```bash
seq_sim maf-to-gvcf -r ref.fa \
    -m seq_sim_work/output/10_mutated_alignment_results/maf_file_paths.txt \
    --output-dir seq_sim_work/output/11_mutated_gvcf_results/
```

**Output:**
- `<work-dir>/output/11_mutated_gvcf_results/*.g.vcf.gz`
- `<work-dir>/output/11_mutated_gvcf_results/gvcf_file_paths.txt`

---

## `rope-bwt-chr-index` (Step `12`)

Builds a PHGv2 ropebwt3 index from FASTA files for downstream genotype imputation.

In the v2 `orchestrate` pipeline this runs as the first half of the combined
Step `11` (`ropebwt`), where the orchestrator invokes it on the recombined FASTAs
from `convert-to-fasta` (Step `09`) and writes the index into the `index/`
subdirectory of `11_ropebwt_results/`.

**Usage:**
```bash
seq_sim rope-bwt-chr-index [OPTIONS]
```

**Options:**
- `--work-dir`, `-w`: Working directory (default: `seq_sim_work`)
- `--fasta-input`, `-f`: FASTA file, directory, or text list (mutually exclusive with `--keyfile`)
- `--keyfile`, `-k`: Pre-made keyfile, tab-delimited `fasta_path<TAB>sample_name` (mutually exclusive with `--fasta-input`)
- `--output-dir`, `-o`: Output directory for index files (default: `work_dir/output/12_rope_bwt_index_results`)
- `--index-file-prefix`, `-p`: Prefix for generated index files (default: `phgIndex`)
- `--threads`, `-t`: Threads for index creation (default: 20)
- `--delete-fmr-index`: Delete `.fmr` files after converting to `.fmd` (flag)

> **Note:** PHGv2 uses underscores internally as contig separators
> (`samplename_contig`). If your sample names contain underscores they are
> converted to hyphens and a warning is logged.

**Output:**
- `<output-dir>/{index_file_prefix}.fmd`
- `<output-dir>/phg_keyfile.txt` (auto-generated when using `--fasta-input`)
- `<work-dir>/logs/12_rope_bwt_chr_index.log`

**Examples:**
```bash
# Auto-generate keyfile from a FASTA directory
seq_sim rope-bwt-chr-index -f seq_sim_work/output/09_formatted_fastas/ -t 20

# Use a pre-made keyfile
seq_sim rope-bwt-chr-index -k my_keyfile.txt -p myIndex -t 40
```

---

## `ropebwt-mem` (Step `13`)

Aligns FASTQ reads to the ropebwt3 index from step 12 and writes per-sample BED
alignment files.

In the v2 `orchestrate` pipeline this runs as the second half of the combined
Step `11` (`ropebwt`): after building the index from the recombined FASTAs, the
orchestrator aligns the user-provided FASTQ reads to it, passing the generated
`.fmd` and `-l` explicitly and writing the BED alignments to
`11_ropebwt_results/`.

**Usage:**
```bash
seq_sim ropebwt-mem [OPTIONS]
```

**Options:**
- `--work-dir`, `-w`: Working directory (default: `seq_sim_work`)
- `--fastq-input`, `-f`: FASTQ file, directory, or text list (required; supports `.fq`, `.fastq`, `.fq.gz`, `.fastq.gz`)
- `--index-file`, `-i`: Path to `.fmd` index (auto-detected from step 12)
- `--l-value`, `-l`: `-l` parameter for `ropebwt3 mem` (auto-calculated as `2 × FASTA count` from the step 12 keyfile)
- `--p-value`, `-p`: `-p` parameter for `ropebwt3 mem` (default: 168)
- `--threads`, `-t`: Threads for `ropebwt3 mem` (default: 1)
- `--output-dir`, `-o`: Custom output directory (default: `work_dir/output/12_ropebwt_mem_results`)

**Output:**
- `<output-dir>/{sample}_ropebwt.bed` (one per FASTQ input)
- `<output-dir>/bed_file_paths.txt`
- `<work-dir>/logs/12_ropebwt_mem.log`

**Examples:**
```bash
# Auto-detect index and -l value from step 12
seq_sim ropebwt-mem -f fastq_samples/ -t 40

# Explicit parameters
seq_sim ropebwt-mem -f samples.txt -i my_index.fmd -l 100 -p 200 -t 40
```

---

## `build-spline-knots` (Step `14`)

Builds spline knots from hVCF or gVCF files for PHGv2 ML-based imputation. This
step is independent of the earlier steps and only requires a directory of VCFs.

**Usage:**
```bash
seq_sim build-spline-knots [OPTIONS]
```

**Options:**
- `--work-dir`, `-w`: Working directory (default: `seq_sim_work`)
- `--vcf-dir`, `-v`: Directory containing hVCF or gVCF files (required)
- `--vcf-type`, `-t`: `hvcf` or `gvcf` (default: `hvcf`)
- `--output-dir`, `-o`: Output directory (default: `work_dir/output/13_spline_knots_results`)
- `--min-indel-length`, `-m`: Minimum indel length (gVCF only, default: 10)
- `--num-bps-per-knot`, `-n`: Max base pairs per knot (default: 50000)
- `--contig-list`, `-c`: Comma-separated chromosomes to include (default: all)
- `--random-seed`, `-r`: Random seed (default: 12345)

**Output:**
- `<output-dir>/` (spline knot files)
- `<work-dir>/logs/13_build_spline_knots.log`

**Examples:**
```bash
# Basic gVCF run
seq_sim build-spline-knots -v vcf_files/ -t gvcf

# Restrict to specific chromosomes with a larger knot spacing
seq_sim build-spline-knots -v vcf_files/ -t hvcf -n 100000 -c chr1,chr2,chr3
```

---

## `convert-ropebwt2ps4g` (Step `15`)

Converts the RopeBWT3 BED alignments from step 13 into PS4G files, using the
spline knots from step 14 for assembly-to-reference coordinate mapping.

**Usage:**
```bash
seq_sim convert-ropebwt2ps4g [OPTIONS]
```

**Options:**
- `--work-dir`, `-w`: Working directory (default: `seq_sim_work`)
- `--bed-input`, `-b`: BED file, directory, or text list (optional, auto-detected from step 13)
- `--output-dir`, `-o`: Custom output directory (default: `work_dir/output/14_convert_ropebwt2ps4g_results`)
- `--spline-knot-dir`, `-s`: Directory of spline knots (optional, auto-detected from step 14)
- `--min-mem-length`, `-m`: Minimum MEM length in bp (default: 135)
- `--max-num-hits`, `-x`: Maximum allowable haplotype hits per alignment (default: 16)

**Output:**
- `<output-dir>/{sample}.ps4g`
- `<output-dir>/ps4g_file_paths.txt`
- `<work-dir>/logs/14_convert_ropebwt2ps4g.log`

**Examples:**
```bash
# Auto-detect BED files from step 13 and spline knots from step 14
seq_sim convert-ropebwt2ps4g

# Explicit inputs
seq_sim convert-ropebwt2ps4g -b bed_files/ -s spline_knots/ -m 148 -x 50
```

---

## v2 Pipeline Commands

These commands implement the v2-specific steps of the `orchestrate` pipeline
(see [v2 Pipeline](pipeline-v2.md)). They are run automatically by
`orchestrate` when `version: "v2"`, and several can also be run standalone. The
v2 pipeline also reuses several v1 commands at different step numbers
([align-assemblies](#align-assemblies-step-01), [maf-to-gvcf](#maf-to-gvcf-step-02),
[downsample-gvcf](#downsample-gvcf-step-03), [convert-to-fasta](#convert-to-fasta-step-04),
[build-spline-knots](#build-spline-knots-step-14), [rope-bwt-chr-index](#rope-bwt-chr-index-step-12),
[ropebwt-mem](#ropebwt-mem-step-13), and [convert-ropebwt2ps4g](#convert-ropebwt2ps4g-step-15)).

### `split-gvcfs` (v2 Step `03`)

Splits the [maf-to-gvcf](#maf-to-gvcf-step-02) output gVCFs into a "base" set and
a "mutation donor" set, driven by a required tab-delimited keyfile. Each "full"
row (both columns present and resolvable to a gVCF) defines a
(base, mutation-donor) pair that flows downstream into
[mutate-assemblies](#mutate-assemblies-v2-step-05); rows that are not full are
logged and excluded.

**Usage:**
```bash
seq_sim split-gvcfs [OPTIONS]
```

**Options:**
- `--work-dir`, `-w`: Working directory (default: `seq_sim_work`)
- `--keyfile`, `-k`: Tab-delimited keyfile with header columns `Base` and `MutationDonor` (required)
- `--gvcf-dir`, `-g`: GVCF file, directory, or text list (optional, auto-detected from step 02)
- `--output-dir`, `-o`: Custom output directory (default: `work_dir/output/03_split_gvcfs_results`)

**Keyfile format:**
- Tab-delimited with a header row containing `Base` and `MutationDonor`.
- Values are sample names matching the step-02 gVCF base names (`{sample}.g.vcf.gz`).
- If the header lacks those columns, the first two columns are assumed to be
  `Base` and `MutationDonor` (the first row is treated as a header and skipped).

```text
Base	MutationDonor
B73	Mo17
B73	W22
Ki3	Mo17
```

**Output:**
- `<output-dir>/base/` (base gVCFs + `base_gvcf_paths.txt`)
- `<output-dir>/mutation_donor/` (deduped mutation-donor gVCFs + `mutation_donor_gvcf_paths.txt`)
- `<output-dir>/pairs.tsv` (normalized, full/resolved rows only; consumed by step 05)
- `<work-dir>/logs/03_split_gvcfs.log`

**Example:**
```bash
seq_sim split-gvcfs -k split_keyfile.txt -g seq_sim_work/output/02_gvcf_results/
```

### `mutate-assemblies` (v2 Step `05`)

Injects the variants from a donor GVCF into a base GVCF to produce a new mutated
GVCF.

In the v2 `orchestrate` pipeline this runs as Step `05`, mutating each base gVCF
from [split-gvcfs](#split-gvcfs-v2-step-03) with every downsampled variant of its
paired mutation donor from [downsample-gvcf](#downsample-gvcf-step-03), writing
one mutated base gVCF per pairing (`{base}__{donorVariant}_mutated.g.vcf`). It is
also useful standalone for quickly building synthetic mutation test cases without
re-running the full variant pipeline.

```bash
seq_sim mutate-assemblies [OPTIONS]
```

- `--base-gvcf`: Base GVCF to mutate (required; `.gvcf` or `.g.vcf.gz`)
- `--mutation-donor-gvcf`: GVCF whose variants will be injected into the base (required)
- `--output-dir`: Output directory for the mutated GVCF (required)

**Example:**
```bash
seq_sim mutate-assemblies \
    --base-gvcf base.g.vcf.gz \
    --mutation-donor-gvcf donor.g.vcf.gz \
    --output-dir mutated/
```

### `pick-base-crossovers` (v2 Step `06`)

Runs `pick-crossovers` on the **base** assemblies. The base samples designated by
[split-gvcfs](#split-gvcfs-v2-step-03) (the `base/` directory) are resolved and
mapped to their assembly FASTA counterparts from the original
[align-assemblies](#align-assemblies-step-01) query input (matched by base name,
e.g. base sample `B73` ↔ `B73.fa`). It then writes a `pick-crossovers` assembly
list and delegates to the same shared crossover logic used by the v1 pipeline.

A base sample without a matching assembly FASTA is a hard error, and the matched
assembly count must be even (assemblies are paired for crossover simulation).

**Usage:**
```bash
seq_sim pick-base-crossovers [OPTIONS]
```

**Options:**
- `--work-dir`, `-w`: Working directory (default: `seq_sim_work`)
- `--ref-fasta`, `-r`: Reference FASTA file (required)
- `--query-fasta`, `-q`: Original assembly FASTA file, directory, or text list - the align-assemblies query input (required)
- `--base-input`, `-b`: Base gVCF file, directory, or text list (optional, auto-detected from step 03 `base/` output)
- `--output-dir`, `-o`: Custom output directory (default: `work_dir/output/06_crossovers_results`)

**Output:**
- `<output-dir>/base_assembly_list.txt`
- `<output-dir>/{assemblyName}_refkey.bed`
- `<work-dir>/logs/06_pick_base_crossovers.log`

**Example:**
```bash
seq_sim pick-base-crossovers -r reference.fa -q assemblies/ \
    -b seq_sim_work/output/03_split_gvcfs_results/base/
```

### `recombine-gvcfs` (v2 Step `07`)

Build recombined per-sample GVCFs from a directory of ancestry BED files and
matching per-parent GVCFs. Acts as a GVCF-level counterpart to
[generate-recombined-sequences](#generate-recombined-sequences-step-08).

In the v2 `orchestrate` pipeline this runs as Step `07` (`recombine_gvcfs`), fed
by the mutated base gVCFs from [mutate-assemblies](#mutate-assemblies-v2-step-05)
(Step `05`) and the crossover BEDs from
[pick-base-crossovers](#pick-base-crossovers-v2-step-06) (Step `06`). A mutated gVCF
named `{base}__{donor}_mutated.g.vcf` is matched to its `{base}_refkey.bed` by its
base sample name.

```bash
seq_sim recombine-gvcfs [OPTIONS]
```

- `--input-bed-dir`: Directory of per-sample ancestry BED files (required)
- `--input-gvcf-dir`: Directory of parent GVCF files (required)
- `--ref-file`: Reference FASTA (required)
- `--output-dir`: Output directory for the recombined GVCFs (required)

**Example:**
```bash
seq_sim recombine-gvcfs \
    --input-bed-dir ancestry_beds/ \
    --input-gvcf-dir parent_gvcfs/ \
    --ref-file ref.fa \
    --output-dir recombined_gvcfs/ 
```

### `sort-gvcfs` (v2 Step `08`)

Sort the recombined GVCFs from [recombine-gvcfs](#recombine-gvcfs-v2-step-07) into
coordinate order using `bcftools sort` (run through `pixi` so the bioconda
`bcftools` is used), then index each output. Recombination stitches segments from
multiple parent gVCFs together, which can leave records out of position order;
this step produces bgzip-compressed, indexed gVCFs
(`{sample}.g.vcf.gz` + `{sample}.g.vcf.gz.csi`) ready for downstream tools.

This step requires the third-party tool [bcftools](https://github.com/samtools/bcftools),
which is provided by the pixi environment (`setup-environment`).

In the v2 `orchestrate` pipeline this runs as Step `08` (`sort_gvcfs`), fed by the
recombined gVCFs from `recombine-gvcfs` (Step `07`). When `--gvcf-input` is omitted
it auto-detects the step 07 output directory (`07_recombine_gvcfs_results`).

```bash
seq_sim sort-gvcfs [OPTIONS]
```

- `--work-dir`, `-w`: Working directory for files and logs (default: `seq_sim_work`)
- `--gvcf-input`, `-g`: GVCF file, directory, or text list (default: step 07 recombine output)
- `--threads`, `-t`: Number of threads for bcftools (default: 4)
- `--output-dir`, `-o`: Custom output directory (default: `work_dir/output/08_sort_gvcfs_results`)

**Output:**
- `output/08_sort_gvcfs_results/{sample}.g.vcf.gz`
- `output/08_sort_gvcfs_results/{sample}.g.vcf.gz.csi`
- `output/08_sort_gvcfs_results/sorted_gvcf_paths.txt`
- `logs/08_sort_gvcfs.log`

**Example:**
```bash
seq_sim sort-gvcfs \
    --gvcf-input recombined_gvcfs/ \
    --output-dir sorted_gvcfs/ \
    --threads 8
```

---

## Helpers

These commands are standalone utilities; they are not part of `orchestrate`.

### `extract-chrom-ids`

Extract unique chromosome IDs from one or more GVCF files.

```bash
seq_sim extract-chrom-ids [OPTIONS]
```

- `--gvcf-file`, `-g`: GVCF input (required) - single file, directory, or text list
- `--output-file`, `-o`: Output file path (default: `chromosome_ids.txt`)

**Example:**
```bash
seq_sim extract-chrom-ids -g gvcf_files/ -o chroms.txt
```
