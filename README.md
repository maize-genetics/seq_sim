# seqSim

Sequence simulator pipeline and orchestrator for [GRITS](https://github.com/maize-genetics/grits).
seqSim is a comprehensive bioinformatics pipeline for assembly alignment, variant
simulation, recombinant genome construction, and comparative analysis. It wraps a
collection of tools (AnchorWave, minimap2, PHGv2, GRITS utilities, ropebwt3, and others)
behind a single `seq_sim` command driven by a YAML configuration file.

## Pipelines

seqSim ships two pipeline versions, both run through the same `orchestrate`
command and selected by the `version` field in your YAML config:

- **[v1 pipeline](docs/pipeline-v1.md)** - a 15-step workflow that
  simulates variants and recombinant genomes at the **FASTA level** and produces
  PS4G files for genotype imputation.
- **[v2 pipeline](docs/pipeline-v2.md)** - a 12-step workflow
  that performs recombination at the **gVCF level** by mixing variants from a
  mutation-donor gVCF into a base gVCF before producing PS4G files.

## Requirements

- Java 21
- [pixi](https://pixi.sh/) for managing the virtual environment
- [conda](https://anaconda.org) for managing PHGv2's virtual environment

## Installation

Download the [latest release](https://github.com/maize-genetics/seq_sim/releases/latest) and extract the archive (pick `seq_sim-<version>.tar` for Unix/Linux/macOS or `seq_sim-<version>.zip` for Windows):

```bash
tar -xf seq_sim-<version>.tar
# or on Windows:
# unzip seq_sim-<version>.zip

# Add the launcher to your PATH for convenience:
export PATH="$PWD/seq_sim-<version>/bin:$PATH"

# Verify the install:
seq_sim --help
```

## Quick Start

```bash
# 1. Install seq_sim (see Installation above) and make sure it's on your PATH

# 2. Create your pipeline configuration from one of the example files:
cp pipeline_config.example.yaml my_pipeline.yaml        # v1 pipeline
# cp pipeline_config_v2.example.yaml my_pipeline.yaml    # v2 pipeline
# Edit my_pipeline.yaml with your file paths

# 3. Run the entire pipeline (environment setup runs automatically!)
seq_sim orchestrate --config my_pipeline.yaml
```

The `orchestrate` command detects whether environment setup is needed, downloads
and installs the required tools, runs the configured steps in sequence, and chains
outputs between steps. See the pipeline docs below for full configuration and
manual step-by-step instructions.

## Documentation

- **[v1 Pipeline](docs/pipeline-v1.md)** - overview, workflow examples, and output layout for the default 15-step pipeline.
- **[v2 Pipeline](docs/pipeline-v2.md)** - overview, workflow examples, and output layout for the gVCF-level 12-step pipeline.
- **[Configuration](docs/configuration.md)** - how to structure and set up the YAML pipeline configuration file.
- **[Command Reference](docs/commands.md)** - every command's options, behavior, outputs, and examples.
- **[Development](docs/development.md)** - tech stack, build commands, and running tests (including the Docker dev container).

Example configurations live at
[`pipeline_config.example.yaml`](pipeline_config.example.yaml) (v1) and
[`pipeline_config_v2.example.yaml`](pipeline_config_v2.example.yaml) (v2).
