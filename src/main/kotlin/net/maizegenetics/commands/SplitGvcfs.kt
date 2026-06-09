package net.maizegenetics.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import net.maizegenetics.Constants
import net.maizegenetics.utils.FileUtils
import net.maizegenetics.utils.LoggingUtils
import net.maizegenetics.utils.ValidationUtils
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.system.exitProcess

/**
 * v2 pipeline step 03: splits the maf-to-gvcf output gVCFs into a "base"
 * set and a "mutation donor" set, driven entirely by a required
 * tab-delimited keyfile.
 *
 * The keyfile has a header row and two columns, `Base` and `MutationDonor`,
 * naming sample names that match the gVCF base names produced by
 * [MafToGvcf] (file `{sample}.g.vcf.gz`). Each "full" row (both columns
 * present and resolvable to a gVCF) defines a (base, mutation-donor) pair
 * that flows downstream into [MutateAssemblies]. Rows that are not full
 * are logged and excluded.
 *
 * Outputs:
 *  - `base/` directory with the base gVCFs
 *  - `mutation_donor/` directory with the (deduped) mutation-donor gVCFs
 *  - `base_gvcf_paths.txt`, `mutation_donor_gvcf_paths.txt`
 *  - `pairs.tsv` (normalized, full rows only) consumed by step 05
 */
class SplitGvcfs : CliktCommand(name = "split-gvcfs") {
    companion object {
        private const val LOG_FILE_NAME = "03_split_gvcfs.log"
        private const val SPLIT_RESULTS_DIR = "03_split_gvcfs_results"
        private const val BASE_SUBDIR = "base"
        private const val MUTATION_DONOR_SUBDIR = "mutation_donor"
        private const val BASE_PATHS_FILE = "base_gvcf_paths.txt"
        private const val MUTATION_DONOR_PATHS_FILE = "mutation_donor_gvcf_paths.txt"
        private const val PAIRS_FILE = "pairs.tsv"
        private const val DEFAULT_INPUT_DIR = "02_gvcf_results"

        private const val BASE_COLUMN = "Base"
        private const val MUTATION_DONOR_COLUMN = "MutationDonor"
    }

    private val logger: Logger = LogManager.getLogger(SplitGvcfs::class.java)

    private val workDir by option(
        "--work-dir", "-w",
        help = "Working directory for files and logs"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)
        .default(Path.of(Constants.DEFAULT_WORK_DIR))

    private val keyfile by option(
        "--keyfile", "-k",
        help = "Tab-delimited keyfile with header columns 'Base' and 'MutationDonor'"
    ).path(mustExist = true, canBeFile = true, canBeDir = false)
        .required()

    private val gvcfInput by option(
        "--gvcf-dir", "-g",
        help = "GVCF file, directory, or text file with paths (default: step 02 output)"
    ).path(mustExist = false)

    private val outputDirOption by option(
        "--output-dir", "-o",
        help = "Custom output directory (default: work_dir/output/03_split_gvcfs_results)"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)

    /**
     * A single keyfile row that resolved to both a base and a mutation-donor
     * sample name. File resolution happens later against the gVCF index.
     */
    data class KeyfilePair(val base: String, val mutationDonor: String)

    /**
     * Parses the keyfile into the list of full (base, mutation-donor) pairs.
     * The header row determines the column indices for `Base` and
     * `MutationDonor`; if neither header token is present the first two
     * columns are assumed to be base and mutation-donor (and the first row
     * is treated as a header and skipped). Rows missing either value are
     * skipped and counted as excluded.
     */
    fun parseKeyfile(keyfile: Path): List<KeyfilePair> {
        val lines = keyfile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

        if (lines.isEmpty()) {
            logger.error("Keyfile is empty: $keyfile")
            exitProcess(1)
        }

        val header = lines.first().split("\t").map { it.trim() }
        var baseIdx = header.indexOfFirst { it.equals(BASE_COLUMN, ignoreCase = true) }
        var donorIdx = header.indexOfFirst { it.equals(MUTATION_DONOR_COLUMN, ignoreCase = true) }

        if (baseIdx < 0 || donorIdx < 0) {
            logger.warn(
                "Keyfile header did not contain '$BASE_COLUMN' and '$MUTATION_DONOR_COLUMN' columns; " +
                    "assuming column 1 = $BASE_COLUMN and column 2 = $MUTATION_DONOR_COLUMN"
            )
            baseIdx = 0
            donorIdx = 1
        }

        val pairs = mutableListOf<KeyfilePair>()
        var excludedCount = 0

        lines.drop(1).forEach { line ->
            val cols = line.split("\t").map { it.trim() }
            val base = cols.getOrNull(baseIdx).orEmpty()
            val donor = cols.getOrNull(donorIdx).orEmpty()

            if (base.isEmpty() || donor.isEmpty()) {
                logger.warn("Excluding non-full keyfile row: '$line'")
                excludedCount++
            } else {
                pairs.add(KeyfilePair(base, donor))
            }
        }

        logger.info("Parsed ${pairs.size} full pair(s) from keyfile ($excludedCount excluded)")
        return pairs
    }

    /**
     * Builds a map of gVCF sample name (file name with any GVCF extension
     * stripped) to its file path, scanning [gvcfInput] (or the step-02
     * default when not provided).
     */
    fun indexGvcfsBySample(): Map<String, Path> {
        val actualInput = gvcfInput ?: run {
            logger.info("No --gvcf-dir specified, attempting to auto-detect from step 02")
            FileUtils.autoDetectStepOutput(
                workDir,
                DEFAULT_INPUT_DIR,
                logger,
                "Please specify --gvcf-dir or run 'maf-to-gvcf' first"
            )
        }

        val gvcfFiles = FileUtils.collectFiles(
            actualInput,
            Constants.GVCF_EXTENSIONS,
            "GVCF",
            logger
        )

        val index = mutableMapOf<String, Path>()
        gvcfFiles.forEach { file ->
            val sample = stripGvcfExtension(file.fileName.toString())
            val existing = index.put(sample, file)
            if (existing != null) {
                logger.warn("Multiple gVCFs resolve to sample '$sample'; using ${file.fileName}")
            }
        }
        logger.info("Indexed ${index.size} gVCF sample(s) from input")
        return index
    }

    private fun stripGvcfExtension(fileName: String): String {
        // Longest extensions first so "g.vcf.gz" wins over "gz"/"vcf".
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

        logger.info("Starting split-gvcfs")
        logger.info("Working directory: $workDir")
        logger.info("Keyfile: $keyfile")

        val pairs = parseKeyfile(keyfile)
        if (pairs.isEmpty()) {
            logger.error("No full (base, mutation-donor) pairs found in keyfile: $keyfile")
            exitProcess(1)
        }

        val gvcfIndex = indexGvcfsBySample()

        // Output directories
        val outputDir = FileUtils.resolveOutputDirectory(workDir, outputDirOption, SPLIT_RESULTS_DIR)
        FileUtils.createOutputDirectory(outputDir, logger)
        val baseDir = outputDir.resolve(BASE_SUBDIR)
        val donorDir = outputDir.resolve(MUTATION_DONOR_SUBDIR)
        FileUtils.createOutputDirectory(baseDir, logger)
        FileUtils.createOutputDirectory(donorDir, logger)

        // Resolve pairs against the gVCF index. A pair is kept only when both
        // sample names resolve to an existing gVCF file.
        val resolvedPairs = mutableListOf<KeyfilePair>()
        val baseSamples = linkedSetOf<String>()
        val donorSamples = linkedSetOf<String>()

        pairs.forEach { pair ->
            val baseFile = gvcfIndex[pair.base]
            val donorFile = gvcfIndex[pair.mutationDonor]
            when {
                baseFile == null -> logger.warn("Excluding pair: base sample '${pair.base}' has no gVCF in input")
                donorFile == null -> logger.warn("Excluding pair: mutation-donor sample '${pair.mutationDonor}' has no gVCF in input")
                else -> {
                    resolvedPairs.add(pair)
                    baseSamples.add(pair.base)
                    donorSamples.add(pair.mutationDonor)
                }
            }
        }

        if (resolvedPairs.isEmpty()) {
            logger.error("No keyfile pairs could be resolved to gVCF files in the input")
            exitProcess(1)
        }

        // Copy the base and mutation-donor gVCFs into their subdirectories.
        val baseFiles = copySamples(baseSamples, gvcfIndex, baseDir, "base")
        val donorFiles = copySamples(donorSamples, gvcfIndex, donorDir, "mutation-donor")

        // Write path files for downstream steps.
        FileUtils.writeFilePaths(baseFiles, baseDir.resolve(BASE_PATHS_FILE), logger, "Base gVCF file")
        FileUtils.writeFilePaths(
            donorFiles,
            donorDir.resolve(MUTATION_DONOR_PATHS_FILE),
            logger,
            "Mutation-donor gVCF file"
        )

        // Write the normalized pairs file (full, resolved rows only).
        writePairs(resolvedPairs, outputDir.resolve(PAIRS_FILE))

        logger.info("split-gvcfs completed")
        logger.info("Base samples: ${baseSamples.size}, mutation-donor samples: ${donorSamples.size}")
        logger.info("Resolved pairs: ${resolvedPairs.size}")
        logger.info("Output directory: $outputDir")
    }

    private fun copySamples(
        samples: Collection<String>,
        gvcfIndex: Map<String, Path>,
        destDir: Path,
        label: String
    ): List<Path> {
        val copied = mutableListOf<Path>()
        samples.forEach { sample ->
            val source = gvcfIndex[sample] ?: return@forEach
            val dest = destDir.resolve(source.fileName.toString())
            try {
                source.copyTo(dest, overwrite = true)
                copied.add(dest)
                logger.info("Copied $label gVCF: ${source.fileName} -> $dest")
            } catch (e: Exception) {
                logger.error("Failed to copy $label gVCF ${source.fileName}: ${e.message}", e)
            }
        }
        return copied
    }

    private fun writePairs(pairs: List<KeyfilePair>, pairsFile: Path) {
        val lines = buildList {
            add("$BASE_COLUMN\t$MUTATION_DONOR_COLUMN")
            pairs.forEach { add("${it.base}\t${it.mutationDonor}") }
        }
        pairsFile.writeLines(lines)
        logger.info("Pairs written to: $pairsFile")
    }
}
