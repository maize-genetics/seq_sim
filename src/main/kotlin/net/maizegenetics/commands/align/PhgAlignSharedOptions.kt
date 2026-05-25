package net.maizegenetics.commands.align

import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import net.maizegenetics.Constants
import java.nio.file.Path

/**
 * Shared Clikt option group for every command that wraps PHGv2
 * `align-assemblies`. Owns the options that are identical across align
 * iterations (working dir, reference files, threading, AnchorWave proali
 * knobs, conda env prefix, ref-prep-only flag, and the custom output dir
 * override) so option names, help text, and defaults live in exactly one
 * place. The per-step unique input flag (e.g. `--query-fasta` /
 * `--fasta-input`) is declared on each wrapper itself.
 */
class PhgAlignSharedOptions : OptionGroup(name = "PHGv2 align-assemblies options") {

    val workDir by option(
        "--work-dir", "-w",
        help = "Working directory for files and scripts"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)
        .default(Path.of(Constants.DEFAULT_WORK_DIR))

    val refGff by option(
        "--ref-gff", "-g",
        help = "Reference GFF file (passed to PHGv2 as --gff)"
    ).path(mustExist = true, canBeFile = true, canBeDir = false)
        .required()

    val refFasta by option(
        "--ref-fasta", "-r",
        help = "Reference FASTA file (passed to PHGv2 as --reference-file). For best results " +
            "this should be the output of `phg prepare-assemblies`."
    ).path(mustExist = true, canBeFile = true, canBeDir = false)
        .required()

    val threads by option(
        "--threads", "-t",
        help = "Total number of threads available to PHGv2 (--total-threads)"
    ).int()
        .default(1)

    val inParallel by option(
        "--in-parallel",
        help = "Number of alignments to run in parallel (PHGv2 --in-parallel). " +
            "If omitted, PHGv2 picks a value from system memory + thread count."
    ).int()

    val refMaxAlignCov by option(
        "--ref-max-align-cov",
        help = "Maximum reference genome alignment coverage for AnchorWave proali (PHGv2 --ref-max-align-cov, " +
            "passed through as proali's `-R`). PHGv2 defaults this to 1."
    ).int()

    val queryMaxAlignCov by option(
        "--query-max-align-cov",
        help = "Maximum query genome alignment coverage for AnchorWave proali (PHGv2 --query-max-align-cov, " +
            "passed through as proali's `-Q`). PHGv2 defaults this to 1."
    ).int()

    val condaEnvPrefix by option(
        "--conda-env-prefix",
        help = "Path to a Conda environment that contains PHGv2's runtime dependencies " +
            "(anchorwave, minimap2, samtools, ...). Defaults to the `phgv2-conda` env in its standard location."
    ).path(mustExist = false, canBeFile = false, canBeDir = true)

    val justRefPrep by option(
        "--just-ref-prep",
        help = "Only run PHGv2's reference-prep phase (writes ref.cds.fasta + Ref.sam) and stop. " +
            "Useful when feeding a SLURM array; skips writing maf_file_paths.txt because no MAFs are produced."
    ).flag()

    val outputDir by option(
        "--output-dir", "-o",
        help = "Custom output directory (default: work_dir/output/<step-default>)"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)
}
