# Pipeline Configuration

seqSim is driven entirely by a single YAML configuration file passed to the
[`orchestrate`](commands.md#orchestrate-recommended) command:

```bash
seq_sim orchestrate --config my_pipeline.yaml
```

This document explains how the configuration file is structured and how to set
one up from scratch. For the complete, annotated schemas, copy one of the
example files and edit it in place:

- [`pipeline_config.example.yaml`](../pipeline_config.example.yaml) - the
  **v1** (FASTA-level) pipeline.
- [`pipeline_config_v2.example.yaml`](../pipeline_config_v2.example.yaml) - the
  **v2** (gVCF-level) pipeline.

For the per-command option reference, see [`commands.md`](commands.md); for the
end-to-end workflow walkthroughs, see [`pipeline-v1.md`](pipeline-v1.md) and
[`pipeline-v2.md`](pipeline-v2.md).

## Quick Start

```bash
# 1. Copy the example that matches the pipeline you want to run
cp pipeline_config.example.yaml my_pipeline.yaml        # v1
# cp pipeline_config_v2.example.yaml my_pipeline.yaml    # v2

# 2. Edit the required paths (reference files, query assemblies, etc.)

# 3. Run it - environment setup happens automatically on first run
seq_sim orchestrate --config my_pipeline.yaml
```

> **Tip:** Only edit the *values* in the example files. The keys map directly to
> command-line options, so renaming them will break parsing.

## File Anatomy

A configuration file has two layers:

1. **Top-level fields** that control the whole run (`version`, `work_dir`,
   `run_steps`).
2. **Per-step blocks**, one per pipeline step, each holding that step's
   parameters.

```yaml
version: "v1"               # which pipeline to run
work_dir: "seq_sim_work"    # where everything is written

run_steps:                  # which steps to execute (in order)
  - align_assemblies
  - maf_to_gvcf
  # ...

align_assemblies:           # a per-step block
  ref_gff: "reference.gff"
  ref_fasta: "reference.fa"
  query_fasta: "queries/"
  threads: 8

maf_to_gvcf:                # another per-step block
  sample_name: "sample1"
```

## Top-Level Fields

| Field | Required | Default | Description |
|-------|----------|---------|-------------|
| `version` | No | `"v1"` | Which pipeline to run: `"v1"` (FASTA-level, 15 steps) or `"v2"` (gVCF-level, 12 steps). The same `orchestrate` command runs both; the value is logged at the start of the run. |
| `work_dir` | No | `"seq_sim_work"` | Directory where the pixi environment, downloaded tools, logs, and all step outputs are written. Created automatically if missing. |
| `run_steps` | No | all configured steps | An ordered list of the steps to execute. Omit it to run every step that has a configuration block. |

The `--work-dir` command-line flag overrides the YAML `work_dir` if both are
supplied.

## Selecting Steps with `run_steps`

`run_steps` controls which steps execute and in what order. The step names are
the snake_case keys (e.g. `align_assemblies`, `maf_to_gvcf`), not the
hyphenated command names.

- **Run everything:** omit `run_steps` entirely, and every step that has a
  configuration block runs.
- **Skip a step:** comment it out with a leading `#`.
- **Run a subset:** list only the steps you want.

```yaml
run_steps:
  - align_assemblies
  - maf_to_gvcf
  # - downsample_gvcf      # skipped: convert_to_fasta will use maf_to_gvcf output
  - convert_to_fasta
```

When a step is skipped, `orchestrate` looks for the previous step's output on
disk so the next step can still find its input. This makes it possible to rerun
just the tail of a pipeline, as long as the earlier outputs already exist in the
working directory.

## Per-Step Blocks

Each step has its own block whose keys mirror that command's options. Keys fall
into three categories:

- **Required inputs** - values the step cannot run without (e.g.
  `align_assemblies.ref_fasta`). These are always uncommented in the examples.
- **Tuning parameters** - options with sensible defaults (e.g.
  `downsample_gvcf.rates`, `format_recombined_fastas.threads`). Override them as
  needed.
- **`input` / `output` overrides** - optional paths to break out of automatic
  chaining (see below). Commented out by default.

An empty block (`step_name: {}`) runs the step entirely with defaults and
auto-chained inputs.

## Output Chaining

By default, `orchestrate` automatically feeds each step's output into the next
step that needs it - you usually only configure the very first step's inputs.
For example, in v1 the MAF files from `align_assemblies` flow into
`maf_to_gvcf`, whose gVCFs flow into `downsample_gvcf`, and so on.

To break out of automatic chaining, most steps accept optional `input` and
`output` keys (a few use more specific names like `maf_file`, `vcf_dir`, or
`input_chain` - see the example files for the exact key per step):

- **`input`** - point a step at your own files instead of the previous step's
  output. This lets you start the pipeline in the middle.
- **`output`** - write a step's results to a custom location. The next step
  picks up the custom output automatically.

```yaml
# Start at downsample_gvcf with your own gVCFs, write to a custom location
run_steps:
  - downsample_gvcf
  - convert_to_fasta

downsample_gvcf:
  input: "/data/my_existing_gvcfs/"
  output: "/results/downsampled/"
  rates: "0.1,0.2"

convert_to_fasta:
  # no input needed - automatically uses downsample_gvcf.output
  ref_fasta: "reference.fa"
```

## Keyfile and List Formats

Some steps reference companion files whose paths you set in the YAML. The most
common ones:

- **Assembly list** (v1 `pick_crossovers`, `convert_coordinates`,
  `generate_recombined_sequences`): tab-separated, one assembly per line as
  `<path><TAB><name>`. Must contain an **even** number of assemblies, since they
  are paired for crossover simulation.

  ```text
  /data/assemblies/B73.fa	B73
  /data/assemblies/Mo17.fa	Mo17
  ```

- **Chromosome list** (v1 `generate_recombined_sequences`): plain text, one
  chromosome name per line. Auto-derived from the first assembly if omitted.

  ```text
  chr1
  chr2
  ```

- **Split keyfile** (v2 `split_gvcfs` / `mutate_assemblies`): tab-delimited with
  a header row containing `Base` and `MutationDonor` columns, where values are
  sample names matching the step-02 gVCF base names.

  ```text
  Base	MutationDonor
  B73	Mo17
  B73	W22
  ```

- **PHG keyfile** (v1 `rope_bwt_chr_index`): tab-delimited with `Fasta` and
  `SampleName` columns. Sample names should **not** contain underscores (PHG
  uses them internally). Auto-generated from the recombined FASTAs if omitted.

See the bottom of each example YAML for the authoritative format notes.

## Environment Setup

You do **not** need to configure or run environment setup manually. On the first
`orchestrate` run, if the working directory or required tools (MLImpute,
biokotlin-tools, PHGv2) are missing, `setup-environment` runs automatically to
install the pixi environment and download the tools into `work_dir`.


## Toy Examples

### v1 Pipeline

A minimal v1 variant-only run:

```yaml
version: "v1"
work_dir: "my_analysis"

run_steps:
  - align_assemblies
  - maf_to_gvcf
  - downsample_gvcf
  - convert_to_fasta

align_assemblies:
  ref_gff: "data/reference.gff"
  ref_fasta: "data/reference.fa"
  query_fasta: "data/queries/"
  threads: 8

maf_to_gvcf:
  sample_name: "sample1"

downsample_gvcf:
  rates: "0.1,0.2,0.3"
  seed: 42

convert_to_fasta:
  missing_records_as: "asRef"
```

Run it with:

```bash
seq_sim orchestrate --config my_analysis.yaml
```

### v2 Pipeline

A minimal v2 run. The v2 pipeline mixes variants from a downsampled "mutation
donor" gVCF into a "base" gVCF, so it additionally needs a split keyfile (which
samples are bases vs. mutation donors) and FASTQ reads to align in the final
PS4G step. Everything else chains automatically from `align_assemblies`.

```yaml
version: "v2"
work_dir: "my_analysis_v2"

run_steps:
  - align_assemblies
  - maf_to_gvcf
  - split_gvcfs
  - downsample_gvcf
  - mutate_assemblies
  - pick_crossovers
  - recombine_gvcfs
  - sort_gvcfs
  - convert_to_fasta
  - build_spline_knots
  - ropebwt
  - convert_ropebwt2ps4g

align_assemblies:
  ref_gff: "data/reference.gff"
  ref_fasta: "data/reference.fa"
  query_fasta: "data/queries/"
  threads: 8

split_gvcfs:
  keyfile: "data/split_keyfile.txt"   # tab-delimited; 'Base' and 'MutationDonor' columns

downsample_gvcf:
  rates: "0.1,0.2,0.3"
  seed: 42

ropebwt:
  fastq_input: "data/fastq_samples/"
  threads: 40
```

The `data/split_keyfile.txt` referenced above pairs base samples with mutation
donors (sample names match the step-02 gVCF base names):

```text
Base	MutationDonor
B73	Mo17
B73	W22
```

Run it with:

```bash
seq_sim orchestrate --config my_analysis_v2.yaml
```

For more complete configurations, including the recombination and PS4G-creation
workflows, see the [v1 Pipeline](pipeline-v1.md) and [v2 Pipeline](pipeline-v2.md)
walkthroughs and the annotated example files.



