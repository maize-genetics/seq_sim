package net.maizegenetics.commands.align

import java.nio.file.Path

/**
 * All inputs the [PhgAlignRunner] needs to wrap PHGv2 `align-assemblies`.
 *
 * Combines:
 *  - the user-facing PHGv2 knobs that are common to every align step (see
 *    [PhgAlignSharedOptions]),
 *  - per-caller metadata (log filename, output subdirectory, label used in
 *    log strings) that lets each thin wrapper place its outputs and logs
 *    in step-specific locations, and
 *  - the caller's unique input (collected as a single [Path] -- single file,
 *    directory, or .txt list -- just like the previous standalone commands).
 *
 * Adding a new align iteration is a new [PhgAlignParams] instance from a
 * new Clikt wrapper; the runner itself does not change.
 */
data class PhgAlignParams(
    val workDir: Path,
    val refGff: Path,
    val refFasta: Path,
    val queryInput: Path,
    val threads: Int,
    val inParallel: Int? = null,
    val refMaxAlignCov: Int? = null,
    val queryMaxAlignCov: Int? = null,
    val condaEnvPrefix: Path? = null,
    val justRefPrep: Boolean = false,
    val customOutputDir: Path? = null,
    val logFileName: String,
    val outputSubdir: String,
    val inputKind: String,
)
