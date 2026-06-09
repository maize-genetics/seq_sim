package net.maizegenetics.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import net.maizegenetics.commands.align.PhgAlignParams
import net.maizegenetics.commands.align.PhgAlignRunner
import net.maizegenetics.commands.align.PhgAlignSharedOptions
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Wraps the PHGv2 `align-assemblies` command for the "circular" mutated /
 * recombined FASTA realignment step (step 10). PHGv2 internally drives
 * AnchorWave + minimap2; this wrapper keeps seq_sim's existing inputs
 * (`--ref-gff`, `--ref-fasta`, `--fasta-input`, ...) and existing output
 * contract (`output/10_mutated_alignment_results/maf_file_paths.txt`) so
 * downstream pipeline steps continue to work unchanged.
 *
 * All the heavy lifting (validating PHG, materializing the assembly file list,
 * invoking PHGv2, writing `maf_file_paths.txt`) lives in [PhgAlignRunner] and
 * is shared with [AlignAssemblies]; the only thing this wrapper declares is
 * the step-specific input flag and per-step metadata (log filename and
 * output subdirectory).
 *
 * See: https://phg.maizegenetics.net/build_and_load/#align-assemblies-parameters
 */
class AlignMutatedAssemblies : CliktCommand(name = "align-mutated-assemblies") {
    companion object {
        private const val LOG_FILE_NAME = "10_align_mutated_assemblies.log"
        private const val MUTATED_ALIGNMENT_RESULTS_DIR = "10_mutated_alignment_results"
    }

    private val logger: Logger = LogManager.getLogger(AlignMutatedAssemblies::class.java)

    private val shared by PhgAlignSharedOptions()

    private val fastaInput by option(
        "--fasta-input", "-f",
        help = "FASTA file, directory of FASTA files, or text file with paths to FASTA files (one per line). " +
            "Translated to a PHGv2 --assembly-file-list internally."
    ).path(mustExist = true)
        .required()

    override fun run() {
        PhgAlignRunner.run(
            PhgAlignParams(
                workDir = shared.workDir,
                refGff = shared.refGff,
                refFasta = shared.refFasta,
                queryInput = fastaInput,
                threads = shared.threads,
                inParallel = shared.inParallel,
                refMaxAlignCov = shared.refMaxAlignCov,
                queryMaxAlignCov = shared.queryMaxAlignCov,
                condaEnvPrefix = shared.condaEnvPrefix,
                justRefPrep = shared.justRefPrep,
                customOutputDir = shared.outputDir,
                logFileName = LOG_FILE_NAME,
                outputSubdir = MUTATED_ALIGNMENT_RESULTS_DIR,
                inputKind = "FASTA",
            ),
            logger
        )
    }
}
