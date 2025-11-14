package net.maizegenetics.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import net.maizegenetics.utils.ProcessRunner
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.nameWithoutExtension
import kotlin.system.exitProcess

class AlignAssemblies : CliktCommand(name = "align-assemblies") {
    private val logger: Logger = LogManager.getLogger(AlignAssemblies::class.java)

    private val workDir by option(
        "--work-dir", "-w",
        help = "Working directory for files and scripts"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)
        .default(Path.of("seq_sim_work"))

    private val refGff by option(
        "--ref-gff", "-g",
        help = "Reference GFF file"
    ).path(mustExist = true, canBeFile = true, canBeDir = false)
        .required()

    private val refFasta by option(
        "--ref-fasta", "-r",
        help = "Reference FASTA file"
    ).path(mustExist = true, canBeFile = true, canBeDir = false)
        .required()

    private val queryFasta by option(
        "--query-fasta", "-q",
        help = "Query FASTA file"
    ).path(mustExist = true, canBeFile = true, canBeDir = false)
        .required()

    private val threads by option(
        "--threads", "-t",
        help = "Number of threads to use"
    ).int()
        .default(1)

    override fun run() {
        logger.info("Starting assembly alignment")
        logger.info("Working directory: $workDir")
        logger.info("Reference GFF: $refGff")
        logger.info("Reference FASTA: $refFasta")
        logger.info("Query FASTA: $queryFasta")
        logger.info("Threads: $threads")

        // Validate working directory exists
        if (!workDir.exists()) {
            logger.error("Working directory does not exist: $workDir")
            logger.error("Please run 'setup-environment' command first")
            exitProcess(1)
        }

        // Create output directory
        val outputDir = workDir.resolve("output").resolve("anchorwave_results")
        if (!outputDir.exists()) {
            logger.debug("Creating output directory: $outputDir")
            outputDir.createDirectories()
            logger.info("Output directory created: $outputDir")
        }

        // Derive base names
        val refBase = refFasta.nameWithoutExtension
        val queryName = queryFasta.nameWithoutExtension

        logger.info("Reference base name: $refBase")
        logger.info("Query base name: $queryName")

        // Step 1: Run anchorwave gff2seq
        logger.info("Step 1: Extracting CDS sequences with anchorwave gff2seq")
        val cdsFile = outputDir.resolve("${refBase}_cds.fa")
        val gff2seqExitCode = ProcessRunner.runCommand(
            "pixi", "run", "anchorwave", "gff2seq",
            "-i", refGff.toString(),
            "-r", refFasta.toString(),
            "-o", cdsFile.toString(),
            workingDir = workDir.toFile(),
            logger = logger
        )
        if (gff2seqExitCode != 0) {
            logger.error("anchorwave gff2seq failed with exit code $gff2seqExitCode")
            exitProcess(gff2seqExitCode)
        }
        logger.info("CDS file created: $cdsFile")

        // Step 2: Run minimap2 for query
        logger.info("Step 2: Running minimap2 alignment for query")
        val querySam = outputDir.resolve("${queryName}.sam")
        val minimap2QueryExitCode = ProcessRunner.runCommand(
            "pixi", "run", "minimap2",
            "-x", "splice",
            "-t", threads.toString(),
            "-k", "12",
            "-a",
            "-p", "0.4",
            "-N", "20",
            queryFasta.toString(),
            cdsFile.toString(),
            workingDir = workDir.toFile(),
            outputFile = querySam.toFile(),
            logger = logger
        )
        if (minimap2QueryExitCode != 0) {
            logger.error("minimap2 (query) failed with exit code $minimap2QueryExitCode")
            exitProcess(minimap2QueryExitCode)
        }
        logger.info("Query SAM file created: $querySam")

        // Step 3: Run minimap2 for reference
        logger.info("Step 3: Running minimap2 alignment for reference")
        val refSam = outputDir.resolve("${refBase}.sam")
        val minimap2RefExitCode = ProcessRunner.runCommand(
            "pixi", "run", "minimap2",
            "-x", "splice",
            "-t", threads.toString(),
            "-k", "12",
            "-a",
            "-p", "0.4",
            "-N", "20",
            refFasta.toString(),
            cdsFile.toString(),
            workingDir = workDir.toFile(),
            outputFile = refSam.toFile(),
            logger = logger
        )
        if (minimap2RefExitCode != 0) {
            logger.error("minimap2 (reference) failed with exit code $minimap2RefExitCode")
            exitProcess(minimap2RefExitCode)
        }
        logger.info("Reference SAM file created: $refSam")

        // Step 4: Run anchorwave proali
        logger.info("Step 4: Running anchorwave proali")
        val anchorsFile = outputDir.resolve("${refBase}_R1_${queryName}_Q1.anchors")
        val mafFile = outputDir.resolve("${refBase}_R1_${queryName}_Q1.maf")
        val fMafFile = outputDir.resolve("${refBase}_R1_${queryName}_Q1.f.maf")

        val proaliExitCode = ProcessRunner.runCommand(
            "pixi", "run", "anchorwave", "proali",
            "-i", refGff.toString(),
            "-as", cdsFile.toString(),
            "-r", refFasta.toString(),
            "-a", querySam.toString(),
            "-ar", refSam.toString(),
            "-s", queryFasta.toString(),
            "-n", anchorsFile.toString(),
            "-R", "1",
            "-Q", "1",
            "-o", mafFile.toString(),
            "-f", fMafFile.toString(),
            "-t", threads.toString(),
            workingDir = workDir.toFile(),
            logger = logger
        )
        if (proaliExitCode != 0) {
            logger.error("anchorwave proali failed with exit code $proaliExitCode")
            exitProcess(proaliExitCode)
        }

        logger.info("Alignment completed successfully!")
        logger.info("Output directory: $outputDir")
        logger.info("Output files:")
        logger.info("  CDS: $cdsFile")
        logger.info("  Query SAM: $querySam")
        logger.info("  Reference SAM: $refSam")
        logger.info("  Anchors: $anchorsFile")
        logger.info("  MAF: $mafFile")
        logger.info("  Filtered MAF: $fMafFile")
    }
}
