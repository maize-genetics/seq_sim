# seqSim

Sequence simulator pipeline and orchestrator for MLImpute - A comprehensive bioinformatics pipeline for assembly alignment, variant simulation, and comparative analysis.

## Requirements

- Java 21
- A `seq_sim` executable available on your `PATH` (from a release distribution, or built from source)
- [pixi](https://pixi.sh/) for managing the virtual environment
- [conda](https://anaconda.org) for managing PHGv2's virtual environment

## Quick Start

### One-Command Pipeline Execution (Recommended)

```bash
# 1. Create your pipeline configuration
cp pipeline_config.example.yaml my_pipeline.yaml
# Edit my_pipeline.yaml with your file paths

# 2. Run the entire pipeline (automatic environment setup!)
seq_sim orchestrate --config my_pipeline.yaml
```

The orchestrate command automatically:
- Detects if environment setup is needed
- Downloads and installs required tools
- Runs all configured pipeline steps in sequence
- Tracks outputs between steps

### Manual Step-by-Step Execution

```bash
# 1. Set up environment (automatic with orchestrate, or run manually)
seq_sim setup-environment

# 2. Align assemblies
seq_sim align-assemblies --ref-gff ref.gff --ref-fasta ref.fa --query-fasta queries/

# 3. Convert to GVCF
seq_sim maf-to-gvcf --reference-file ref.fa --maf-file seq_sim_work/output/01_anchorwave_results/maf_file_paths.txt

# 4. Downsample variants
seq_sim downsample-gvcf --gvcf-dir seq_sim_work/output/02_gvcf_results/

# 5. Generate mutated FASTA files
seq_sim convert-to-fasta --ref-fasta ref.fa --gvcf-file seq_sim_work/output/03_downsample_results/

# 6. Realign mutated assemblies
seq_sim align-mutated-assemblies --ref-gff ref.gff --ref-fasta ref.fa --fasta-input seq_sim_work/output/04_fasta_results/
```

## Pipeline Overview

The pipeline consists of two main workflows:

### Variant Simulation Workflow (Steps 00-04)

| Step | Command | Description |
|------|---------|-------------|
| 00 | **setup-environment** | Initialize pixi environment and download tools (auto-runs with orchestrate) |
| 01 | **align-assemblies** | Align query assemblies to reference using AnchorWave and minimap2 |
| 02 | **maf-to-gvcf** | Convert MAF alignments to compressed GVCF format |
| 03 | **downsample-gvcf** | Downsample variants at specified rates per chromosome |
| 04 | **convert-to-fasta** | Generate FASTA files from downsampled variants |

### Recombination Workflow (Steps 05-09)

| Step | Command | Description |
|------|---------|-------------|
| 05 | **pick-crossovers** | Generate crossover points and create ancestry tracking BED files |
| 06 | **create-chain-files** | Convert MAF alignments to CHAIN format for coordinate transformations |
| 07 | **convert-coordinates** | Convert reference coordinates to assembly coordinates using chain files |
| 08 | **generate-recombined-sequences** | Generate recombined FASTA sequences from parent assemblies |
| 09 | **format-recombined-fastas** | Format FASTA output (line wrapping, threading) |

### Optional / Downstream Commands

| Command | Description |
|---------|-------------|
| **align-mutated-assemblies** | Realign FASTA sequences back to reference for comparison |
| **rope-bwt-chr-index** | Build RopeBWT index from FASTA/keyfile inputs |

### Helper Commands

| Command | Description |
|---------|-------------|
| **extract-chrom-ids** | Extract unique chromosome IDs from GVCF files |

Each command generates logs in `<work-dir>/logs/` and outputs in `<work-dir>/output/`.

## Commands

### 0. orchestrate (Recommended)

**Runs the entire pipeline from a YAML configuration file with automatic environment setup.**

**Usage:**
```bash
seq_sim orchestrate [OPTIONS]
```

**Options:**
- `--config`, `-c`: Path to YAML configuration file (required)

**What it does:**
1. **Auto-detects environment** - Validates if setup is needed
2. **Automatic setup** - Runs setup-environment only if tools are missing
3. **Sequential execution** - Runs configured steps in order
4. **Output chaining** - Automatically passes outputs between steps
5. **Selective execution** - Skip or rerun specific steps via `run_steps`

**YAML Configuration Structure:**

*Variant Simulation Workflow:*
```yaml
work_dir: "seq_sim_work"

# Optional: Specify which steps to run (comment out to skip)
run_steps:
  - align_assemblies
  - maf_to_gvcf
  - downsample_gvcf
  - convert_to_fasta
  - align_mutated_assemblies

align_assemblies:
  ref_gff: "reference.gff"
  ref_fasta: "reference.fa"
  query_fasta: "queries/"
  threads: 4

maf_to_gvcf:
  sample_name: "sample1"

downsample_gvcf:
  rates: "0.1,0.2,0.3"
  seed: 42

convert_to_fasta:
  missing_records_as: "asRef"

align_mutated_assemblies:
  threads: 4
```

*Recombination Workflow:*
```yaml
work_dir: "seq_sim_work"

run_steps:
  - align_assemblies
  - pick_crossovers
  - create_chain_files
  - convert_coordinates
  - generate_recombined_sequences

align_assemblies:
  ref_gff: "reference.gff"
  ref_fasta: "reference.fa"
  query_fasta: "assemblies/"
  threads: 8

pick_crossovers:
  assembly_list: "assembly_list.txt"

create_chain_files:
  jobs: 12

convert_coordinates:
  assembly_list: "assembly_list.txt"

generate_recombined_sequences:
  assembly_list: "assembly_list.txt"
  chromosome_list: "chromosomes.txt"
  assembly_dir: "assemblies/"
```

**Example:**
```bash
# Full pipeline (environment setup runs automatically if needed)
seq_sim orchestrate --config pipeline.yaml

# Rerun only last two steps (uses previous outputs)
# Edit yaml: run_steps: [convert_to_fasta, align_mutated_assemblies]
seq_sim orchestrate --config pipeline.yaml
```

---

### 1. setup-environment

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
  - Python 3.10, NumPy, Pandas
  - Java 21 (OpenJDK)
  - minimap2 2.28
  - AnchorWave (Linux only)
  - agc 3.1, ropebwt3 3.8
- Downloads and extracts MLImpute repository to `<work-dir>/src/MLImpute/`
- Downloads and extracts biokotlin-tools to `<work-dir>/src/biokotlin-tools/`

**Output:**
- `<work-dir>/pixi.toml`
- `<work-dir>/.pixi/` (pixi environment)
- `<work-dir>/src/MLImpute/`
- `<work-dir>/src/biokotlin-tools/`
- `<work-dir>/logs/00_setup_environment.log`

**Example:**
```bash
seq_sim setup-environment -w my_workdir
```

---

### 2. align-assemblies

Aligns multiple query assemblies to a reference genome using AnchorWave and minimap2.

**Usage:**
```bash
seq_sim align-assemblies [OPTIONS]
```

**Options:**
- `--work-dir`, `-w`: Working directory (default: `seq_sim_work`)
- `--ref-gff`, `-g`: Reference GFF file (required)
- `--ref-fasta`, `-r`: Reference FASTA file (required)
- `--query-fasta`, `-q`: Query input (required) - can be:
  - A single FASTA file (`.fa`, `.fasta`, `.fna`)
  - A directory containing FASTA files
  - A text file (`.txt`) with one FASTA file path per line
- `--threads`, `-t`: Number of threads to use (default: 1)

**What it does:**
1. Extracts CDS sequences from reference GFF using `anchorwave gff2seq`
2. Aligns reference to CDS with `minimap2` (once for all queries)
3. For each query:
   - Aligns query to CDS with `minimap2`
   - Runs `anchorwave proali` to generate alignments
   - Creates query-specific subdirectory with outputs
4. Generates `maf_file_paths.txt` listing all produced MAF files

**Output:**
- `<work-dir>/output/01_anchorwave_results/{refBase}_cds.fa`
- `<work-dir>/output/01_anchorwave_results/{refBase}.sam`
- `<work-dir>/output/01_anchorwave_results/{queryName}/` containing:
  - `{queryName}.sam`
  - `*.anchors`, `*.maf`, `*.f.maf`
- `<work-dir>/output/01_anchorwave_results/maf_file_paths.txt`
- `<work-dir>/logs/01_align_assemblies.log`

**Examples:**
```bash
# Single query with threads
seq_sim align-assemblies -g ref.gff -r ref.fa -q query1.fa -t 8

# Directory of queries
seq_sim align-assemblies -g ref.gff -r ref.fa -q queries/

# Text list of query paths
seq_sim align-assemblies -g ref.gff -r ref.fa -q queries.txt -t 4
```

---

### 3. maf-to-gvcf

Converts MAF alignment files to compressed GVCF format using biokotlin-tools.

**Usage:**
```bash
seq_sim maf-to-gvcf [OPTIONS]
```

**Options:**
- `--work-dir`, `-w`: Working directory (default: `seq_sim_work`)
- `--reference-file`, `-r`: Reference FASTA file (required)
- `--maf-file`, `-m`: MAF input (required) - can be:
  - A single MAF file (`.maf`)
  - A directory containing MAF files
  - A text file (`.txt`) with one MAF file path per line
- `--output-file`, `-o`: Output GVCF file name (optional, auto-generated for multiple files)
- `--sample-name`, `-s`: Sample name for GVCF (optional, defaults to MAF base name)

**What it does:**
1. Collects MAF files based on input pattern
2. For each MAF file:
   - Runs `biokotlin-tools maf-to-gvcf-converter` through pixi (ensures Java 21)
   - Generates compressed GVCF file (`.g.vcf.gz`)
3. Generates `gvcf_file_paths.txt` listing all produced GVCF files

**Output:**
- `<work-dir>/output/02_gvcf_results/*.g.vcf.gz`
- `<work-dir>/output/02_gvcf_results/gvcf_file_paths.txt`
- `<work-dir>/logs/02_maf_to_gvcf.log`

**Examples:**
```bash
# Using path list from align-assemblies (recommended)
seq_sim maf-to-gvcf -r ref.fa -m seq_sim_work/output/01_anchorwave_results/maf_file_paths.txt

# Directory of MAF files
seq_sim maf-to-gvcf -r ref.fa -m mafs/

# Single MAF with custom name
seq_sim maf-to-gvcf -r ref.fa -m sample.maf -s Sample1
```

---

### 4. downsample-gvcf

Downsamples GVCF files at specified rates using MLImpute's DownsampleGvcf tool.

**Usage:**
```bash
seq_sim downsample-gvcf [OPTIONS]
```

**Options:**
- `--work-dir`, `-w`: Working directory (default: `seq_sim_work`)
- `--gvcf-dir`, `-g`: Input directory containing GVCF files (required)
- `--ignore-contig`: Comma-separated contig patterns to ignore (optional)
- `--rates`: Comma-separated downsampling rates per chromosome (default: "0.01,0.05,0.1,0.15,0.2,0.3,0.35,0.4,0.45,0.49")
- `--seed`: Random seed for reproducibility (optional)
- `--keep-ref`: Keep reference blocks (default: true)
- `--min-ref-block-size`: Minimum ref block size to sample (default: 20)
- `--keep-uncompressed`: Keep temporary uncompressed files (default: false)

**What it does:**
- Automatically handles compressed (`.g.vcf.gz`) and uncompressed formats
- Runs MLImpute DownsampleGvcf to downsample variants
- Generates block size information
- Cleans up temporary files automatically

**Output:**
- `<work-dir>/output/03_downsample_results/*_subsampled.gvcf`
- `<work-dir>/output/03_downsample_results/*_subsampled_block_sizes.tsv`
- `<work-dir>/logs/03_downsample_gvcf.log`

**Example:**
```bash
seq_sim downsample-gvcf -g seq_sim_work/output/02_gvcf_results/ --rates 0.1,0.2,0.3 --seed 42
```

---

### 5. convert-to-fasta

Generates FASTA files from downsampled GVCF files using MLImpute's ConvertToFasta tool.

**Usage:**
```bash
seq_sim convert-to-fasta [OPTIONS]
```

**Options:**
- `--work-dir`, `-w`: Working directory (default: `seq_sim_work`)
- `--gvcf-file`, `-g`: GVCF input (required) - can be:
  - A single GVCF file
  - A directory containing GVCF files
  - A text file with GVCF file paths
- `--ref-fasta`, `-r`: Reference FASTA file (required)
- `--missing-records-as`: How to handle missing records: `asN`, `asRef`, `asNone` (default: `asRef`)
- `--missing-genotype-as`: How to handle missing genotypes: `asN`, `asRef`, `asNone` (default: `asN`)

**What it does:**
- Automatically handles various GVCF formats
- Generates FASTA sequences based on variants
- Handles missing data according to specified strategy
- Generates `fasta_file_paths.txt` with all output paths

**Output:**
- `<work-dir>/output/04_fasta_results/*.fasta`
- `<work-dir>/output/04_fasta_results/fasta_file_paths.txt`
- `<work-dir>/logs/04_convert_to_fasta.log`

**Example:**
```bash
seq_sim convert-to-fasta -r ref.fa -g seq_sim_work/output/03_downsample_results/
```

---

### 6. align-mutated-assemblies

Realigns mutated FASTA files (from convert-to-fasta) back to the reference genome for comparison.

**Usage:**
```bash
seq_sim align-mutated-assemblies [OPTIONS]
```

**Options:**
- `--work-dir`, `-w`: Working directory (default: `seq_sim_work`)
- `--ref-gff`, `-g`: Reference GFF file (required)
- `--ref-fasta`, `-r`: Reference FASTA file (required)
- `--fasta-input`, `-f`: FASTA input (required) - can be:
  - A single FASTA file
  - A directory containing FASTA files
  - A text file with FASTA file paths
- `--threads`, `-t`: Number of threads to use (default: 1)

**What it does:**
- Uses same AnchorWave + minimap2 workflow as align-assemblies
- Aligns mutated sequences back to reference
- Enables comparison of original vs. mutated alignments
- Generates `maf_file_paths.txt` with all output paths

**Output:**
- `<work-dir>/output/10_mutated_alignment_results/{refBase}_cds.fa`
- `<work-dir>/output/10_mutated_alignment_results/{refBase}.sam`
- `<work-dir>/output/10_mutated_alignment_results/{fastaName}/` containing alignments
- `<work-dir>/output/10_mutated_alignment_results/maf_file_paths.txt`
- `<work-dir>/logs/10_align_mutated_assemblies.log`

**Example:**
```bash
seq_sim align-mutated-assemblies -g ref.gff -r ref.fa -f seq_sim_work/output/04_fasta_results/ -t 8
```

---

### 7. pick-crossovers

Generates crossover points in reference coordinates and creates refkey BED files that track genomic region ancestry.

**Usage:**
```bash
seq_sim pick-crossovers [OPTIONS]
```

**Options:**
- `--work-dir`, `-w`: Working directory (default: `seq_sim_work`)
- `--ref-fasta`, `-r`: Reference FASTA file (required)
- `--assembly-list`, `-a`: Text file containing assembly paths and names, tab-separated: `path<TAB>name` (required)

**What it does:**
- Simulates crossover events between parent assemblies
- Generates crossover positions using uniform random spacing (1-9 Mbp between crossovers)
- Creates refkey BED files tracking which founder/parent each genomic region comes from
- Supports multiple rounds of breeding simulation

**Output:**
- `<work-dir>/output/05_crossovers_results/{founder}_refkey.bed` (reference coordinates)
- `<work-dir>/output/05_crossovers_results/refkey_file_paths.txt`
- `<work-dir>/logs/05_pick_crossovers.log`

**Example:**
```bash
seq_sim pick-crossovers -r reference.fa -a assembly_list.txt
```

**Assembly list format (assembly_list.txt):**
```
/path/to/assembly1.fa	assembly1
/path/to/assembly2.fa	assembly2
```

---

### 8. create-chain-files

Converts MAF alignment files to CHAIN format, which describes coordinate transformations between sequences.

**Usage:**
```bash
seq_sim create-chain-files [OPTIONS]
```

**Options:**
- `--work-dir`, `-w`: Working directory (default: `seq_sim_work`)
- `--maf-input`, `-m`: MAF input (required) - can be:
  - A single MAF file (`.maf`, `.maf.gz`)
  - A directory containing MAF files
  - A text file (`.txt`) with one MAF file path per line
- `--jobs`, `-j`: Number of parallel jobs (default: 8)

**What it does:**
- Downloads `maf-convert` tool from LAST package if missing
- Converts each MAF file to CHAIN format using parallel processing
- Supports both compressed (`.maf.gz`) and uncompressed (`.maf`) files
- Chain format describes coordinate mapping between reference and query sequences

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

### 9. convert-coordinates

Converts reference coordinates from refkey BED files to assembly coordinates using chain files.

**Usage:**
```bash
seq_sim convert-coordinates [OPTIONS]
```

**Options:**
- `--work-dir`, `-w`: Working directory (default: `seq_sim_work`)
- `--assembly-list`, `-a`: Text file containing assembly paths and names, tab-separated (required)
- `--chain-dir`, `-c`: Directory containing chain files (required)
- `--refkey-dir`, `-r`: Directory containing refkey BED files (optional, auto-detected from step 06)

**What it does:**
- Reads refkey BED files with reference coordinates
- Uses CrossMap tool with chain files to convert coordinates
- Adjusts per-chromosome to ensure complete coverage (no gaps or overlaps)
- Generates both assembly-specific and founder-specific key files

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

### 10. generate-recombined-sequences

Generates recombined FASTA sequences by concatenating segments from parent assemblies based on founder keyfiles.

**Usage:**
```bash
seq_sim generate-recombined-sequences [OPTIONS]
```

**Options:**
- `--work-dir`, `-w`: Working directory (default: `seq_sim_work`)
- `--assembly-list`, `-a`: Text file containing assembly paths and names, tab-separated (required)
- `--chromosome-list`, `-c`: Text file containing chromosome names, one per line (required)
- `--assembly-dir`, `-d`: Directory containing parent assembly FASTA files (required)
- `--founder-key-dir`, `-k`: Directory containing founder key BED files (optional, auto-detected from step 08)

**What it does:**
- Reads founder key BED files that map FASTA coordinates to parent assemblies
- For each founder and chromosome, fetches sequences from appropriate parent assemblies
- Concatenates segments in genomic order to create recombined sequences
- Uses pysam for efficient sequence retrieval and multiprocessing for parallelization

**Output:**
- `<work-dir>/output/08_recombined_sequences/recombinate_fastas/{founder}.fa`
- `<work-dir>/output/08_recombined_sequences/recombined_fasta_paths.txt`
- `<work-dir>/logs/08_generate_recombined_sequences.log`

**Example:**
```bash
seq_sim generate-recombined-sequences -a assembly_list.txt -c chromosomes.txt -d data/assemblies/
```

**Chromosome list format (chromosomes.txt):**
```
chr1
chr2
chr3
```

---

### 11. format-recombined-fastas

Formats recombined FASTA files (line wrapping) using `seqkit` (runs via pixi).

**Usage:**
```bash
seq_sim format-recombined-fastas [OPTIONS]
```

**Options:**
- `--work-dir`, `-w`: Working directory (default: `seq_sim_work`)
- `--fasta-input`, `-f`: FASTA input (optional; auto-detects from `output/08_recombined_sequences/recombinate_fastas/`)
- `--line-width`, `-l`: FASTA line width (default: 60)
- `--threads`, `-t`: Threads for seqkit (default: 8)

**Output:**
- `<work-dir>/output/09_formatted_fastas/*.fa`
- `<work-dir>/output/09_formatted_fastas/formatted_fasta_paths.txt`
- `<work-dir>/logs/09_format_recombined_fastas.log`

**Example:**
```bash
seq_sim format-recombined-fastas -w seq_sim_work -f seq_sim_work/output/08_recombined_sequences/recombinate_fastas/ -l 60 -t 8
```

---

### 12. rope-bwt-chr-index

Builds a PHG RopeBWT chromosome index from FASTA inputs (requires PHGv2 to be installed via `setup-environment`).

**Usage:**
```bash
seq_sim rope-bwt-chr-index [OPTIONS]
```

**Options:**
- `--work-dir`, `-w`: Working directory (default: `seq_sim_work`)
- `--fasta-input`, `-f`: FASTA input (mutually exclusive with `--keyfile`)
- `--keyfile`, `-k`: Pre-made keyfile (`path<TAB>sample_name`, no header; mutually exclusive with `--fasta-input`)
- `--index-file-prefix`, `-p`: Prefix for generated index files (default: `phgIndex`)
- `--threads`, `-t`: Threads for index creation (default: 20)
- `--delete-fmr-index`: Delete intermediate `.fmr` files after converting to `.fmd` (flag)

**Output:**
- `<work-dir>/output/12_rope_bwt_index_results/` (PHG index outputs)
- `<work-dir>/output/12_rope_bwt_index_results/phg_keyfile.txt` (if `--fasta-input` is used)
- `<work-dir>/logs/11_rope_bwt_chr_index.log`

**Example:**
```bash
seq_sim rope-bwt-chr-index -w seq_sim_work -f seq_sim_work/output/09_formatted_fastas/ -p phgIndex -t 20 --delete-fmr-index
```

---

### 13. ropebwt-mem

Aligns FASTQ reads against a RopeBWT index using `ropebwt3 mem` (runs via pixi).

**Usage:**
```bash
seq_sim ropebwt-mem [OPTIONS]
```

**Options:**
- `--work-dir`, `-w`: Working directory (default: `seq_sim_work`)
- `--fastq-input`, `-f`: FASTQ input (file/dir/list)
- `--index-file`, `-i`: Path to the `.fmd` index file (optional; auto-detects from `output/12_rope_bwt_index_results/`)
- `--l-value`, `-l`: Value for `ropebwt3 mem -l` (optional; auto-calculated from the keyfile if available)
- `--p-value`, `-p`: Value for `ropebwt3 mem -p` (default: 168)
- `--threads`, `-t`: Threads for ropebwt3 mem (default: 1)

**Output:**
- `<work-dir>/output/12_ropebwt_mem_results/*.bed`
- `<work-dir>/output/12_ropebwt_mem_results/bed_file_paths.txt`
- `<work-dir>/logs/12_ropebwt_mem.log`

**Example:**
```bash
seq_sim ropebwt-mem -w seq_sim_work -f reads/ -i seq_sim_work/output/12_rope_bwt_index_results/phgIndex.fmd -t 8
```

---

### 14. build-spline-knots

Runs PHG `build-spline-knots` to generate spline knot files (requires PHGv2 to be installed via `setup-environment`).

**Usage:**
```bash
seq_sim build-spline-knots [OPTIONS]
```

**Options:**
- `--work-dir`, `-w`: Working directory (default: `seq_sim_work`)
- `--vcf-dir`, `-v`: Directory containing hVCF/gVCF files (required)
- `--vcf-type`, `-t`: `hvcf` or `gvcf` (default: `hvcf`)

**Output:**
- `<work-dir>/output/13_spline_knots_results/`
- `<work-dir>/logs/13_build_spline_knots.log`

**Example:**
```bash
seq_sim build-spline-knots -w seq_sim_work -v path/to/vcfs -t hvcf
```

---

### 15. convert-ropebwt2ps4g

Converts RopeBWT BED outputs to PS4G using PHG (requires outputs from `ropebwt-mem` and `build-spline-knots`).

**Usage:**
```bash
seq_sim convert-ropebwt2ps4g [OPTIONS]
```

**Options:**
- `--work-dir`, `-w`: Working directory (default: `seq_sim_work`)
- `--bed-input`, `-b`: BED input (optional; auto-detects from `output/12_ropebwt_mem_results/`)
- `--spline-knot-dir`, `-s`: Spline knot directory (optional; auto-detects from `output/13_spline_knots_results/`)

**Output:**
- `<work-dir>/output/14_convert_ropebwt2ps4g_results/*.ps4g`
- `<work-dir>/output/14_convert_ropebwt2ps4g_results/ps4g_file_paths.txt`
- `<work-dir>/logs/14_convert_ropebwt2ps4g.log`

**Example:**
```bash
seq_sim convert-ropebwt2ps4g -w seq_sim_work -b seq_sim_work/output/12_ropebwt_mem_results/ -s seq_sim_work/output/13_spline_knots_results/
```

---

### Helper: extract-chrom-ids

Extracts unique chromosome IDs from GVCF files. Standalone utility, not part of main pipeline.

**Usage:**
```bash
seq_sim extract-chrom-ids [OPTIONS]
```

**Options:**
- `--gvcf-file`, `-g`: GVCF input (required) - single file, directory, or text list
- `--output-file`, `-o`: Output file path (default: `chromosome_ids.txt`)

**Output:**
- Plain text file with one chromosome ID per line (sorted)

**Example:**
```bash
seq_sim extract-chrom-ids -g gvcf_files/ -o chroms.txt
```

## Complete Workflow Examples

### Variant Simulation Workflow

#### Option 1: Using Orchestrate (Recommended)

```bash
# Create configuration
cat > pipeline.yaml <<EOF
work_dir: "my_analysis"

run_steps:
  - align_assemblies
  - maf_to_gvcf
  - downsample_gvcf
  - convert_to_fasta
  - align_mutated_assemblies

align_assemblies:
  ref_gff: "reference.gff"
  ref_fasta: "reference.fa"
  query_fasta: "queries/"
  threads: 8

maf_to_gvcf:
  sample_name: "sample1"

downsample_gvcf:
  rates: "0.1,0.2,0.3"
  seed: 42

convert_to_fasta: {}

align_mutated_assemblies:
  threads: 8
EOF

# Run entire pipeline (automatic setup!)
seq_sim orchestrate --config pipeline.yaml
```

#### Option 2: Manual Execution

```bash
# Setup (only once)
seq_sim setup-environment -w my_work

# Run each step manually (see individual command examples above)
```

---

### Recombination Workflow

The recombination workflow generates recombined sequences by simulating crossover events and combining segments from multiple parent assemblies.

#### Using Orchestrate (Recommended)

```bash
# Prepare input files
# - assembly_list.txt (tab-separated: /path/to/assembly.fa<TAB>name)
# - chromosomes.txt (one chromosome name per line)

# Create recombination configuration
cat > recombination.yaml <<EOF
work_dir: "recomb_analysis"

run_steps:
  - align_assemblies
  - pick_crossovers
  - create_chain_files
  - convert_coordinates
  - generate_recombined_sequences

align_assemblies:
  ref_gff: "data/reference.gff"
  ref_fasta: "data/reference.fa"
  query_fasta: "data/assemblies.txt"
  threads: 8

pick_crossovers:
  assembly_list: "data/assembly_list.txt"

create_chain_files:
  jobs: 12

convert_coordinates:
  assembly_list: "data/assembly_list.txt"

generate_recombined_sequences:
  assembly_list: "data/assembly_list.txt"
  chromosome_list: "data/chromosomes.txt"
  assembly_dir: "data/assemblies/"
EOF

# Run entire recombination pipeline
seq_sim orchestrate --config recombination.yaml
```

#### Manual Execution

```bash
# Setup (only once)
seq_sim setup-environment

# Align assemblies to reference
seq_sim align-assemblies -g ref.gff -r ref.fa -q assemblies.txt -t 8

# Pick crossover points
seq_sim pick-crossovers -r ref.fa -a assembly_list.txt

# Create chain files from MAF alignments
seq_sim create-chain-files -m seq_sim_work/output/01_anchorwave_results/maf_file_paths.txt -j 12

# Convert coordinates
seq_sim convert-coordinates -a assembly_list.txt -c seq_sim_work/output/06_chain_results/

# Generate recombined sequences
seq_sim generate-recombined-sequences -a assembly_list.txt -c chromosomes.txt -d data/assemblies/
```

**Data Flow:**
```
align-assemblies -> MAF files
                    |
              pick-crossovers -> refkey BED files (reference coords)
                    |
              create-chain-files -> CHAIN files
                    |
              convert-coordinates (uses refkey + chain)
                    |
              founder key BED files (FASTA coords)
                    |
        generate-recombined-sequences → Recombined FASTA files
```


## Important Notes

1. **Automatic Environment Setup**: When using `orchestrate`, environment setup runs automatically if tools are missing. No manual setup needed!

2. **Java 21 Requirement**: biokotlin-tools requires Java 21. Commands automatically run it through pixi to ensure the correct version.

3. **AnchorWave on macOS**: AnchorWave is not available on macOS via conda. Use Linux or Docker for alignment steps.

4. **Multi-File Input**: Commands support three input patterns:
   - **Single file**: Direct path to a file
   - **Directory**: All matching files in directory
   - **Text list**: One file path per line (recommended for large batches)

5. **Path Files**: Commands generate text files (`*_file_paths.txt`) listing outputs. Use these for downstream processing.

6. **Selective Execution**: With orchestrate, comment out steps in `run_steps` to skip them. Useful for reruns.


