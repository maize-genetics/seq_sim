package net.maizegenetics.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import net.maizegenetics.Constants
import net.maizegenetics.utils.FileUtils
import net.maizegenetics.utils.LoggingUtils
import net.maizegenetics.utils.ProcessRunner
import net.maizegenetics.utils.ValidationUtils
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.system.exitProcess

/**
 * v2 pipeline step 08: sorts the recombined gVCFs produced by
 * [RecombineGvcfs] (step 07) so their records are in coordinate order.
 *
 * Recombination stitches together segments from multiple parent gVCFs, which
 * can leave records out of position order. This step runs `bcftools sort` on
 * each input gVCF (through `pixi run` so the bioconda `bcftools` is used) and
 * then `bcftools index` to produce a bgzip-compressed, indexed output
 * (`{sample}.g.vcf.gz` + `{sample}.g.vcf.gz.csi`).
 *
 * Outputs (under work_dir/output/08_sort_gvcfs_results by default):
 *  - `{sample}.g.vcf.gz` sorted, compressed gVCFs
 *  - `{sample}.g.vcf.gz.csi` index files
 *  - `sorted_gvcf_paths.txt` paths to the sorted gVCFs
 */
class SortGvcfs : CliktCommand(name = "sort-gvcfs") {
    companion object {
        private const val LOG_FILE_NAME = "08_sort_gvcfs.log"
        private const val SORT_RESULTS_DIR = "08_sort_gvcfs_results"
        private const val SORTED_GVCF_PATHS_FILE = "sorted_gvcf_paths.txt"
        private const val DEFAULT_INPUT_DIR = "07_recombine_gvcfs_results"
        private const val TMP_SUBDIR = "tmp"
        private const val DEFAULT_THREADS = 4
        private const val SORTED_EXTENSION = "g.vcf.gz"
    }

    private val logger: Logger = LogManager.getLogger(SortGvcfs::class.java)

    private val workDir by option(
        "--work-dir", "-w",
        help = "Working directory for files and logs"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)
        .default(Path.of(Constants.DEFAULT_WORK_DIR))

    private val gvcfInput by option(
        "--gvcf-input", "-g",
        help = "GVCF file, directory, or text file with paths (default: step 07 recombine output)"
    ).path(mustExist = false)

    private val threads by option(
        "--threads", "-t",
        help = "Number of threads for bcftools"
    ).int()
        .default(DEFAULT_THREADS)

    private val outputDirOption by option(
        "--output-dir", "-o",
        help = "Custom output directory (default: work_dir/output/08_sort_gvcfs_results)"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)

    private fun collectGvcfFiles(): List<Path> {
        val actualInput = gvcfInput ?: run {
            logger.info("No --gvcf-input specified, attempting to auto-detect from step 07")
            FileUtils.autoDetectStepOutput(
                workDir,
                DEFAULT_INPUT_DIR,
                logger,
                "Please specify --gvcf-input or run 'recombine-gvcfs' first"
            )
        }

        return FileUtils.collectFiles(
            actualInput,
            Constants.GVCF_EXTENSIONS,
            "GVCF",
            logger
        )
    }

    /**
     * Strips any recognized GVCF extension from a file name, longest first so
     * "g.vcf.gz" wins over "gz"/"vcf".
     */
    private fun stripGvcfExtension(fileName: String): String {
        val sorted = Constants.GVCF_EXTENSIONS.sortedByDescending { it.length }
        for (ext in sorted) {
            val suffix = ".$ext"
            if (fileName.endsWith(suffix)) {
                return fileName.removeSuffix(suffix)
            }
        }
        return fileName.substringBeforeLast(".")
    }

    override fun run() {
        ValidationUtils.validateWorkingDirectory(workDir, logger)
        LoggingUtils.setupFileLogging(workDir, LOG_FILE_NAME, logger)

        logger.info("Starting sort-gvcfs")
        logger.info("Working directory: $workDir")
        logger.info("Threads: $threads")

        val gvcfFiles = collectGvcfFiles()
        logger.info("Processing ${gvcfFiles.size} GVCF file(s)")

        val outputDir = FileUtils.resolveOutputDirectory(workDir, outputDirOption, SORT_RESULTS_DIR)
        FileUtils.createOutputDirectory(outputDir, logger)

        // bcftools sort needs a temp directory for spilling large inputs; keep
        // it inside the output dir to avoid filling /tmp on big gVCFs.
        val tmpDir = outputDir.resolve(TMP_SUBDIR)
        FileUtils.createOutputDirectory(tmpDir, logger)

        var successCount = 0
        var failureCount = 0
        val sortedGvcfs = mutableListOf<Path>()

        gvcfFiles.forEach { gvcfFile ->
            val sample = stripGvcfExtension(gvcfFile.fileName.toString())
            val outputFile = outputDir.resolve("$sample.$SORTED_EXTENSION")
            logger.info("Sorting: ${gvcfFile.fileName} -> ${outputFile.fileName}")

            val sortExitCode = ProcessRunner.runCommand(
                "pixi", "run",
                "bcftools", "sort",
                "-O", "z",
                "-T", tmpDir.toString(),
                "-o", outputFile.toString(),
                gvcfFile.toString(),
                workingDir = workDir.toFile(),
                logger = logger
            )

            if (sortExitCode != 0) {
                failureCount++
                logger.error("Failed to sort ${gvcfFile.fileName} with exit code $sortExitCode")
                return@forEach
            }

            val indexExitCode = ProcessRunner.runCommand(
                "pixi", "run",
                "bcftools", "index",
                "--threads", threads.toString(),
                outputFile.toString(),
                workingDir = workDir.toFile(),
                logger = logger
            )

            if (indexExitCode != 0) {
                failureCount++
                logger.error("Failed to index ${outputFile.fileName} with exit code $indexExitCode")
                return@forEach
            }

            successCount++
            sortedGvcfs.add(outputFile)
            logger.info("Successfully sorted and indexed: ${outputFile.fileName}")
        }

        logger.info("sort-gvcfs completed")
        logger.info("Success: $successCount, Failures: $failureCount")

        FileUtils.writeFilePaths(
            sortedGvcfs,
            outputDir.resolve(SORTED_GVCF_PATHS_FILE),
            logger,
            "Sorted GVCF file"
        )

        logger.info("Output directory: $outputDir")

        if (failureCount > 0) {
            exitProcess(1)
        }
    }
}
