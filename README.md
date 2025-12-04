# seqSim

Sequence simulator pipeline and orchestrator for MLImpute - A comprehensive bioinformatics pipeline for assembly alignment, variant simulation, and comparative analysis.

## Requirements

- Java 21
- Gradle (included via wrapper)
- [pixi](https://pixi.sh/) for managing the virtual environment
- [conda](https://anaconda.org) for managing PHGv2's virtual environment

## Quick Start

### One-Command Pipeline Execution (Recommended)

```bash
# 1. Build the application
./gradlew build

# 2. Create your pipeline configuration
cp pipeline_config.example.yaml my_pipeline.yaml
# Edit my_pipeline.yaml with your file paths

# 3. Run the entire pipeline (automatic environment setup!)
./gradlew run --args="orchestrate --config my_pipeline.yaml"
```

The orchestrate command automatically:
- Detects if environment setup is needed
- Downloads and installs required tools
- Runs all configured pipeline steps in sequence
- Tracks outputs between steps

### Manual Step-by-Step Execution

```bash
# 1. Build the application
./gradlew build

# 2. Set up environment (automatic with orchestrate, or run manually)
./gradlew run --args="setup-environment"

# 3. Align assemblies
./gradlew run --args="align-assemblies --ref-gff ref.gff --ref-fasta ref.fa --query-fasta queries/"

# 4. Convert to GVCF
./gradlew run --args="maf-to-gvcf --reference-file ref.fa --maf-file seq_sim_work/output/01_anchorwave_results/maf_file_paths.txt"

# 5. Downsample variants
./gradlew run --args="downsample-gvcf --gvcf-dir seq_sim_work/output/02_gvcf_results/"

# 6. Generate mutated FASTA files
./gradlew run --args="convert-to-fasta --ref-fasta ref.fa --gvcf-file seq_sim_work/output/03_downsample_results/"

# 7. Realign mutated assemblies
./gradlew run --args="align-mutated-assemblies --ref-gff ref.gff --ref-fasta ref.fa --fasta-input seq_sim_work/output/04_fasta_results/"
```

## Pipeline Overview

The pipeline consists of two main workflows:

### Variant Simulation Workflow (Steps 01-05)

| Step | Command | Description |
|------|---------|-------------|
| 00 | **setup-environment** | Initialize pixi environment and download tools (auto-runs with orchestrate) |
| 01 | **align-assemblies** | Align query assemblies to reference using AnchorWave and minimap2 |
| 02 | **maf-to-gvcf** | Convert MAF alignments to compressed GVCF format |
| 03 | **downsample-gvcf** | Downsample variants at specified rates per chromosome |
| 04 | **convert-to-fasta** | Generate FASTA files from downsampled variants |
| 05 | **align-mutated-assemblies** | Realign mutated sequences back to reference for comparison |

### Recombination Workflow (Steps 06-09)

| Step | Command | Description |
|------|---------|-------------|
| 06 | **pick-crossovers** | Generate crossover points and create ancestry tracking BED files |
| 07 | **create-chain-files** | Convert MAF alignments to CHAIN format for coordinate transformations |
| 08 | **convert-coordinates** | Convert reference coordinates to assembly coordinates using chain files |
| 09 | **generate-recombined-sequences** | Generate recombined FASTA sequences from parent assemblies |

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
./gradlew run --args="orchestrate [OPTIONS]"
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
./gradlew run --args="orchestrate --config pipeline.yaml"

# Rerun only last two steps (uses previous outputs)
# Edit yaml: run_steps: [convert_to_fasta, align_mutated_assemblies]
./gradlew run --args="orchestrate --config pipeline.yaml"
```

---

### 1. setup-environment

Initializes the environment and downloads dependencies. **Note: This runs automatically with orchestrate!**

**Usage:**
```bash
./gradlew run --args="setup-environment [OPTIONS]"
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
./gradlew run --args="setup-environment -w my_workdir"
```

---

### 2. align-assemblies

Aligns multiple query assemblies to a reference genome using AnchorWave and minimap2.

**Usage:**
```bash
./gradlew run --args="align-assemblies [OPTIONS]"
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
./gradlew run --args="align-assemblies -g ref.gff -r ref.fa -q query1.fa -t 8"

# Directory of queries
./gradlew run --args="align-assemblies -g ref.gff -r ref.fa -q queries/"

# Text list of query paths
./gradlew run --args="align-assemblies -g ref.gff -r ref.fa -q queries.txt -t 4"
```

---

### 3. maf-to-gvcf

Converts MAF alignment files to compressed GVCF format using biokotlin-tools.

**Usage:**
```bash
./gradlew run --args="maf-to-gvcf [OPTIONS]"
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
./gradlew run --args="maf-to-gvcf -r ref.fa -m seq_sim_work/output/01_anchorwave_results/maf_file_paths.txt"

# Directory of MAF files
./gradlew run --args="maf-to-gvcf -r ref.fa -m mafs/"

# Single MAF with custom name
./gradlew run --args="maf-to-gvcf -r ref.fa -m sample.maf -s Sample1"
```

---

### 4. downsample-gvcf

Downsamples GVCF files at specified rates using MLImpute's DownsampleGvcf tool.

**Usage:**
```bash
./gradlew run --args="downsample-gvcf [OPTIONS]"
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
./gradlew run --args="downsample-gvcf -g seq_sim_work/output/02_gvcf_results/ --rates 0.1,0.2,0.3 --seed 42"
```

---

### 5. convert-to-fasta

Generates FASTA files from downsampled GVCF files using MLImpute's ConvertToFasta tool.

**Usage:**
```bash
./gradlew run --args="convert-to-fasta [OPTIONS]"
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
./gradlew run --args="convert-to-fasta -r ref.fa -g seq_sim_work/output/03_downsample_results/"
```

---

### 6. align-mutated-assemblies

Realigns mutated FASTA files (from convert-to-fasta) back to the reference genome for comparison.

**Usage:**
```bash
./gradlew run --args="align-mutated-assemblies [OPTIONS]"
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
- `<work-dir>/output/05_mutated_alignment_results/{refBase}_cds.fa`
- `<work-dir>/output/05_mutated_alignment_results/{refBase}.sam`
- `<work-dir>/output/05_mutated_alignment_results/{fastaName}/` containing alignments
- `<work-dir>/output/05_mutated_alignment_results/maf_file_paths.txt`
- `<work-dir>/logs/05_align_mutated_assemblies.log`

**Example:**
```bash
./gradlew run --args="align-mutated-assemblies -g ref.gff -r ref.fa -f seq_sim_work/output/04_fasta_results/ -t 8"
```

---

### 7. pick-crossovers

Generates crossover points in reference coordinates and creates refkey BED files that track genomic region ancestry.

**Usage:**
```bash
./gradlew run --args="pick-crossovers [OPTIONS]"
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
- `<work-dir>/output/06_crossovers_results/{founder}_refkey.bed` (reference coordinates)
- `<work-dir>/output/06_crossovers_results/refkey_file_paths.txt`
- `<work-dir>/logs/06_pick_crossovers.log`

**Example:**
```bash
./gradlew run --args="pick-crossovers -r reference.fa -a assembly_list.txt"
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
./gradlew run --args="create-chain-files [OPTIONS]"
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
- `<work-dir>/output/07_chain_results/*.chain`
- `<work-dir>/output/07_chain_results/chain_file_paths.txt`
- `<work-dir>/logs/07_create_chain_files.log`

**Examples:**
```bash
# Using MAF paths from align-assemblies (recommended)
./gradlew run --args="create-chain-files -m seq_sim_work/output/01_anchorwave_results/maf_file_paths.txt -j 12"

# Directory of MAF files
./gradlew run --args="create-chain-files -m mafs/ -j 8"
```

---

### 9. convert-coordinates

Converts reference coordinates from refkey BED files to assembly coordinates using chain files.

**Usage:**
```bash
./gradlew run --args="convert-coordinates [OPTIONS]"
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
- `<work-dir>/output/08_coordinates_results/{assembly}_key.bed` (assembly coordinates)
- `<work-dir>/output/08_coordinates_results/{founder}_key.bed` (FASTA coordinates)
- `<work-dir>/output/08_coordinates_results/key_file_paths.txt`
- `<work-dir>/output/08_coordinates_results/founder_key_file_paths.txt`
- `<work-dir>/logs/08_convert_coordinates.log`

**Example:**
```bash
./gradlew run --args="convert-coordinates -a assembly_list.txt -c seq_sim_work/output/07_chain_results/"
```

---

### 10. generate-recombined-sequences

Generates recombined FASTA sequences by concatenating segments from parent assemblies based on founder keyfiles.

**Usage:**
```bash
./gradlew run --args="generate-recombined-sequences [OPTIONS]"
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
- `<work-dir>/output/09_recombined_sequences/recombinate_fastas/{founder}.fa`
- `<work-dir>/output/09_recombined_sequences/recombined_fasta_paths.txt`
- `<work-dir>/logs/09_generate_recombined_sequences.log`

**Example:**
```bash
./gradlew run --args="generate-recombined-sequences -a assembly_list.txt -c chromosomes.txt -d data/assemblies/"
```

**Chromosome list format (chromosomes.txt):**
```
chr1
chr2
chr3
```

---

### Helper: extract-chrom-ids

Extracts unique chromosome IDs from GVCF files. Standalone utility, not part of main pipeline.

**Usage:**
```bash
./gradlew run --args="extract-chrom-ids [OPTIONS]"
```

**Options:**
- `--gvcf-file`, `-g`: GVCF input (required) - single file, directory, or text list
- `--output-file`, `-o`: Output file path (default: `chromosome_ids.txt`)

**Output:**
- Plain text file with one chromosome ID per line (sorted)

**Example:**
```bash
./gradlew run --args="extract-chrom-ids -g gvcf_files/ -o chroms.txt"
```

## Complete Workflow Examples

### Variant Simulation Workflow

#### Option 1: Using Orchestrate (Recommended)

```bash
# 1. Build
./gradlew build

# 2. Create configuration
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

# 3. Run entire pipeline (automatic setup!)
./gradlew run --args="orchestrate --config pipeline.yaml"
```

#### Option 2: Manual Execution

```bash
# 1. Build
./gradlew build

# 2. Setup (only once)
./gradlew run --args="setup-environment -w my_work"

# 3-7. Run each step manually (see individual command examples above)
```

---

### Recombination Workflow

The recombination workflow generates recombined sequences by simulating crossover events and combining segments from multiple parent assemblies.

#### Using Orchestrate (Recommended)

```bash
# 1. Build
./gradlew build

# 2. Prepare input files
# - assembly_list.txt (tab-separated: /path/to/assembly.fa<TAB>name)
# - chromosomes.txt (one chromosome name per line)

# 3. Create recombination configuration
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

# 4. Run entire recombination pipeline
./gradlew run --args="orchestrate --config recombination.yaml"
```

#### Manual Execution

```bash
# 1. Build
./gradlew build

# 2. Setup (only once)
./gradlew run --args="setup-environment"

# 3. Align assemblies to reference
./gradlew run --args="align-assemblies -g ref.gff -r ref.fa -q assemblies.txt -t 8"

# 4. Pick crossover points
./gradlew run --args="pick-crossovers -r ref.fa -a assembly_list.txt"

# 5. Create chain files from MAF alignments
./gradlew run --args="create-chain-files -m seq_sim_work/output/01_anchorwave_results/maf_file_paths.txt -j 12"

# 6. Convert coordinates
./gradlew run --args="convert-coordinates -a assembly_list.txt -c seq_sim_work/output/07_chain_results/"

# 7. Generate recombined sequences
./gradlew run --args="generate-recombined-sequences -a assembly_list.txt -c chromosomes.txt -d data/assemblies/"
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

## Working Directory Structure

After running the complete pipeline:

```
seq_sim_work/                                    # Working directory
├── pixi.toml                                    # Pixi configuration
├── pixi.lock                                    # Pixi lock file
├── .pixi/                                       # Pixi environment
├── src/                                         # Downloaded tools
│   ├── MLImpute/                                # MLImpute repository
│   └── biokotlin-tools/                         # biokotlin-tools binary
├── logs/                                        # Log files
│   ├── 00_orchestrate.log                       # Orchestrate log (if used)
│   ├── 00_setup_environment.log
│   ├── 01_align_assemblies.log
│   ├── 02_maf_to_gvcf.log
│   ├── 03_downsample_gvcf.log
│   ├── 04_convert_to_fasta.log
│   ├── 05_align_mutated_assemblies.log
│   ├── 06_pick_crossovers.log                   # Recombination workflow logs
│   ├── 07_create_chain_files.log
│   ├── 08_convert_coordinates.log
│   └── 09_generate_recombined_sequences.log
└── output/                                      # Pipeline outputs
    ├── 01_anchorwave_results/                   # Original alignments
    │   ├── {refBase}_cds.fa
    │   ├── {refBase}.sam
    │   ├── {queryName}/
    │   └── maf_file_paths.txt
    ├── 02_gvcf_results/                         # GVCF files
    │   ├── *.g.vcf.gz
    │   └── gvcf_file_paths.txt
    ├── 03_downsample_results/                   # Downsampled variants
    │   ├── *_subsampled.gvcf
    │   └── *_subsampled_block_sizes.tsv
    ├── 04_fasta_results/                        # Mutated FASTA files
    │   ├── *.fasta
    │   └── fasta_file_paths.txt
    ├── 05_mutated_alignment_results/            # Realignments
    │   ├── {refBase}_cds.fa
    │   ├── {refBase}.sam
    │   ├── {fastaName}/
    │   └── maf_file_paths.txt
    ├── 06_crossovers_results/                   # Recombination workflow outputs
    │   ├── {founder}_refkey.bed                 # Reference coordinates
    │   └── refkey_file_paths.txt
    ├── 07_chain_results/                        # Coordinate transformation files
    │   ├── *.chain
    │   └── chain_file_paths.txt
    ├── 08_coordinates_results/                  # Converted coordinates
    │   ├── {assembly}_key.bed                   # Assembly coordinates
    │   ├── {founder}_key.bed                    # FASTA coordinates
    │   ├── key_file_paths.txt
    │   └── founder_key_file_paths.txt
    └── 09_recombined_sequences/                 # Recombined sequences
        ├── recombinate_fastas/
        │   └── {founder}.fa                     # 0.fa, 1.fa, 2.fa, etc.
        └── recombined_fasta_paths.txt
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

7. **Circular Workflow**: Step 5 (align-mutated-assemblies) creates a comparison loop - compare original vs. mutated alignments.

## Troubleshooting

**Issue**: `UnsupportedClassVersionError` when running maf-to-gvcf

**Solution**: Ensure Java 21 is in your pixi environment. Commands run biokotlin-tools through `pixi run` automatically.

---

**Issue**: No MAF files generated by align-assemblies

**Solution**:
1. AnchorWave is Linux-only
2. Check input file extensions (`.fa`, `.fasta`, `.fna`)
3. Validate reference GFF and FASTA
4. Check `logs/01_align_assemblies.log`

---

**Issue**: Orchestrate fails with "environment validation failed"

**Solution**: Ensure pixi is installed and accessible. Check logs for specific missing tools.

---

**Issue**: Permission denied errors

**Solution**: Ensure working directory is writable and you have execute permissions.

## Development

- **Language:** Kotlin 2.2.20 (JVM target: Java 21)
- **CLI Framework:** Clikt 5.0.3
- **Logging:** Log4j2 2.24.3
- **YAML Parser:** SnakeYAML 2.3
- **Build Tool:** Gradle with Kotlin DSL
- **Environment Manager:** pixi (conda-based)

### Build Commands

```bash
./gradlew build          # Build project
./gradlew test           # Run tests
./gradlew clean build    # Clean build
./gradlew jar            # Generate JAR
```

