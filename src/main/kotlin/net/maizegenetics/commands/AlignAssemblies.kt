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
 * Wraps the PHGv2 `align-assemblies` command, which itself drives AnchorWave +
 * minimap2 to align query assemblies against a reference. This wrapper keeps
 * seq_sim's existing inputs (`--ref-gff`, `--ref-fasta`, `--query-fasta`, ...)
 * and existing output contract (`output/01_anchorwave_results/maf_file_paths.txt`)
 * so downstream pipeline steps continue to work unchanged.
 *
 * All the heavy lifting (validating PHG, materializing the assembly file list,
 * invoking PHGv2, writing `maf_file_paths.txt`) lives in [PhgAlignRunner] and
 * is shared with every other align iteration; the only thing this wrapper
 * declares is the step-specific input flag and per-step metadata (log
 * filename and output subdirectory).
 *
 * See: https://phg.maizegenetics.net/build_and_load/#align-assemblies-parameters
 */
class AlignAssemblies : CliktCommand(name = "align-assemblies") {
    companion object {
        private const val LOG_FILE_NAME = "01_align_assemblies.log"
        private const val ANCHORWAVE_RESULTS_DIR = "01_anchorwave_results"
    }

    private val logger: Logger = LogManager.getLogger(AlignAssemblies::class.java)

    private val shared by PhgAlignSharedOptions()

    private val queryInput by option(
        "--query-fasta", "-q",
        help = "Query FASTA file, directory of FASTA files, or text file with paths to FASTA files (one per line). " +
            "Translated to a PHGv2 --assembly-file-list internally."
    ).path(mustExist = true)
        .required()

    override fun run() {
        PhgAlignRunner.run(
            PhgAlignParams(
                workDir = shared.workDir,
                refGff = shared.refGff,
                refFasta = shared.refFasta,
                queryInput = queryInput,
                threads = shared.threads,
                inParallel = shared.inParallel,
                refMaxAlignCov = shared.refMaxAlignCov,
                queryMaxAlignCov = shared.queryMaxAlignCov,
                condaEnvPrefix = shared.condaEnvPrefix,
                justRefPrep = shared.justRefPrep,
                customOutputDir = shared.outputDir,
                logFileName = LOG_FILE_NAME,
                outputSubdir = ANCHORWAVE_RESULTS_DIR,
                inputKind = "query",
            ),
            logger
        )
    }
}
