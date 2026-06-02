package net.maizegenetics.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import com.google.common.collect.Range
import com.google.common.collect.RangeMap
import com.google.common.collect.TreeRangeMap
import htsjdk.variant.variantcontext.Allele
import htsjdk.variant.variantcontext.GenotypeBuilder
import htsjdk.variant.variantcontext.VariantContext
import htsjdk.variant.variantcontext.VariantContextBuilder
import htsjdk.variant.variantcontext.writer.Options
import htsjdk.variant.variantcontext.writer.VariantContextWriterBuilder
import htsjdk.variant.vcf.VCFFileReader
import net.maizegenetics.utils.Position
import net.maizegenetics.utils.SimpleVariant
import net.maizegenetics.utils.VariantContextUtils
import com.github.ajalt.clikt.parameters.options.default
import net.maizegenetics.Constants
import net.maizegenetics.utils.FileUtils
import net.maizegenetics.utils.LoggingUtils
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.io.File
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.system.exitProcess




@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class MutateAssemblies : CliktCommand(name = "mutate-assemblies") {

    companion object {
        private const val LOG_FILE_NAME = "05_mutate_assemblies.log"
        private const val MUTATED_GVCF_PATHS_FILE = "mutated_gvcf_file_paths.txt"

        private const val BASE_COLUMN = "Base"
        private const val MUTATION_DONOR_COLUMN = "MutationDonor"

        // Boundary characters that may follow a donor sample name in a
        // downsampled output file name (e.g. "donor_subsampled.gvcf").
        private val DONOR_NAME_BOUNDARY = charArrayOf('_', '.', '-')
    }

    private val logger: Logger = LogManager.getLogger(MutateAssemblies::class.java)

    private val workDir by option(
        "--work-dir", "-w",
        help = "Working directory for logs"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)
        .default(Path.of(Constants.DEFAULT_WORK_DIR))

    // --- Single-pair mode options ---
    private val baseGvcf by option(
        "--base-gvcf",
        help = "Single-pair mode: base GVCF to mutate (.gvcf or .g.vcf.gz)"
    ).path(mustExist = true, canBeFile = true, canBeDir = false)

    private val mutationDonorGvcf by option(
        "--mutation-donor-gvcf",
        help = "Single-pair mode: GVCF to pull variants from (.gvcf or .g.vcf.gz)"
    ).path(mustExist = true, canBeFile = true, canBeDir = false)

    // --- Batch mode options ---
    private val keyfile by option(
        "--keyfile", "-k", "--pairs",
        help = "Batch mode: tab-delimited pairs file with 'Base' and 'MutationDonor' columns"
    ).path(mustExist = true, canBeFile = true, canBeDir = false)

    private val baseDir by option(
        "--base-dir",
        help = "Batch mode: directory containing base gVCFs"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)

    private val mutationDonorDir by option(
        "--mutation-donor-dir",
        help = "Batch mode: directory containing (downsampled) mutation-donor gVCFs"
    ).path(mustExist = false, canBeFile = false, canBeDir = true)

    private val outputDir by option(
        "--output-dir", "-o",
        help = "Output directory for mutated gVCFs"
    ).path(canBeFile = false, canBeDir = true)
        .required()

    override fun run() {
        if (!outputDir.exists()) {
            outputDir.createDirectories()
        }

        if (workDir.exists()) {
            LoggingUtils.setupFileLogging(workDir, LOG_FILE_NAME, logger)
        }

        if (keyfile != null) {
            runBatch()
        } else {
            val base = baseGvcf
                ?: throw IllegalArgumentException("Single-pair mode requires --base-gvcf (or use --keyfile for batch mode)")
            val donor = mutationDonorGvcf
                ?: throw IllegalArgumentException("Single-pair mode requires --mutation-donor-gvcf (or use --keyfile for batch mode)")
            introduceMutations(base.toFile(), donor.toFile(), outputDir.toFile())
        }
    }

    /**
     * Batch mode: for each full (base, mutation-donor) pair in the keyfile,
     * pair the base gVCF with every downsampled variant of its donor and
     * write one mutated base gVCF per pairing. Emits a path file listing all
     * outputs.
     */
    private fun runBatch() {
        val keyfilePath = keyfile!!
        val baseDirPath = baseDir
            ?: throw IllegalArgumentException("Batch mode requires --base-dir")
        val donorDirPath = mutationDonorDir
            ?: throw IllegalArgumentException("Batch mode requires --mutation-donor-dir")

        logger.info("Starting mutate-assemblies (batch mode)")
        logger.info("Keyfile: $keyfilePath")
        logger.info("Base dir: $baseDirPath")
        logger.info("Mutation-donor dir: $donorDirPath")
        logger.info("Output dir: $outputDir")

        val pairs = parsePairs(keyfilePath)
        if (pairs.isEmpty()) {
            logger.error("No full (base, mutation-donor) pairs found in keyfile: $keyfilePath")
            exitProcess(1)
        }

        val baseIndex = indexGvcfsBySample(baseDirPath, "base")
        val donorFiles = listGvcfFiles(donorDirPath)

        val mutatedOutputs = mutableListOf<Path>()
        var successCount = 0
        var failureCount = 0

        pairs.forEach { (baseSample, donorSample) ->
            val baseFile = baseIndex[baseSample]
            if (baseFile == null) {
                logger.warn("Skipping pair: base sample '$baseSample' has no gVCF in $baseDirPath")
                return@forEach
            }

            val donorVariants = donorVariantsFor(donorSample, donorFiles)
            if (donorVariants.isEmpty()) {
                logger.warn("Skipping pair: no downsampled gVCFs for mutation-donor '$donorSample' in $donorDirPath")
                return@forEach
            }

            donorVariants.forEach { donorFile ->
                val donorVariantName = stripGvcfExtension(donorFile.fileName.toString())
                val outputFile = outputDir.resolve("${baseSample}__${donorVariantName}_mutated.g.vcf")
                try {
                    introduceMutationsToFile(baseFile.toFile(), donorFile.toFile(), outputFile.toFile())
                    mutatedOutputs.add(outputFile)
                    successCount++
                    logger.info("Mutated base '$baseSample' with donor '$donorVariantName' -> ${outputFile.fileName}")
                } catch (e: Exception) {
                    failureCount++
                    logger.error("Failed to mutate base '$baseSample' with donor '$donorVariantName': ${e.message}", e)
                }
            }
        }

        FileUtils.writeFilePaths(
            mutatedOutputs,
            outputDir.resolve(MUTATED_GVCF_PATHS_FILE),
            logger,
            "Mutated gVCF file"
        )

        logger.info("mutate-assemblies (batch) completed. Success: $successCount, Failures: $failureCount")

        if (failureCount > 0) {
            exitProcess(1)
        }
    }

    /**
     * Parses a tab-delimited pairs file (header with `Base` and
     * `MutationDonor`, falling back to the first two columns) into full
     * (base, mutation-donor) pairs. Rows missing either value are skipped.
     */
    fun parsePairs(pairsFile: Path): List<Pair<String, String>> {
        val lines = pairsFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

        if (lines.isEmpty()) return emptyList()

        val header = lines.first().split("\t").map { it.trim() }
        var baseIdx = header.indexOfFirst { it.equals(BASE_COLUMN, ignoreCase = true) }
        var donorIdx = header.indexOfFirst { it.equals(MUTATION_DONOR_COLUMN, ignoreCase = true) }
        if (baseIdx < 0 || donorIdx < 0) {
            baseIdx = 0
            donorIdx = 1
        }

        return lines.drop(1).mapNotNull { line ->
            val cols = line.split("\t").map { it.trim() }
            val base = cols.getOrNull(baseIdx).orEmpty()
            val donor = cols.getOrNull(donorIdx).orEmpty()
            if (base.isEmpty() || donor.isEmpty()) {
                logger.warn("Excluding non-full pairs row: '$line'")
                null
            } else {
                base to donor
            }
        }
    }

    private fun listGvcfFiles(dir: Path): List<Path> {
        if (!dir.exists()) return emptyList()
        return dir.listDirectoryEntries()
            .filter { it.isRegularFile() && FileUtils.hasValidExtension(it, Constants.GVCF_EXTENSIONS) }
    }

    private fun indexGvcfsBySample(dir: Path, label: String): Map<String, Path> {
        val index = mutableMapOf<String, Path>()
        listGvcfFiles(dir).forEach { file ->
            val sample = stripGvcfExtension(file.fileName.toString())
            if (index.put(sample, file) != null) {
                logger.warn("Multiple $label gVCFs resolve to sample '$sample'; using ${file.fileName}")
            }
        }
        return index
    }

    /**
     * Selects the downsampled donor files derived from [donorSample]. A file
     * matches when its name equals the donor name (any gVCF extension) or
     * begins with the donor name followed by a boundary character, so that
     * "donor" matches "donor_subsampled.gvcf" but not "donorX.gvcf".
     */
    fun donorVariantsFor(donorSample: String, donorFiles: List<Path>): List<Path> {
        return donorFiles.filter { file ->
            val name = file.fileName.toString()
            name.startsWith(donorSample) &&
                (name.length == donorSample.length || name[donorSample.length] in DONOR_NAME_BOUNDARY)
        }
    }

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

    fun introduceMutations(baseGvcf: File, mutationDonorGvcf: File, outputDir: File) {
        //walk through the two gvcf files
        //From one  pull the mutations left

        //Loop through the founderGVCF and build a RangeMap of Position to Variant
        val (sampleName, baseVariantMap) = buildBaseVariantMap(baseGvcf)

        addNewVariants(mutationDonorGvcf, baseVariantMap)

        writeMutatedGVCF(outputDir, sampleName, baseVariantMap)
    }

    /**
     * Like [introduceMutations] but writes to an explicit output file (used
     * by batch mode where multiple donors map to a single base and the
     * default `{sample}_mutated.g.vcf` name would collide). The genotype
     * sample name remains the base gVCF's sample name.
     */
    fun introduceMutationsToFile(baseGvcf: File, mutationDonorGvcf: File, outputFile: File) {
        val (sampleName, baseVariantMap) = buildBaseVariantMap(baseGvcf)

        addNewVariants(mutationDonorGvcf, baseVariantMap)

        writeMutatedGVCFToFile(outputFile, sampleName, baseVariantMap)
    }

    fun buildBaseVariantMap(baseGvcf: File) : Pair<String,RangeMap<Position, SimpleVariant>> {
        //Loop through the founderGVCF and build a RangeMap of Position to Variant
        val variantReader = VCFFileReader(baseGvcf, false)

        val iterator = variantReader.iterator()

        val sampleName = variantReader.header.sampleNamesInOrder.first()
        val rangeMap = TreeRangeMap.create<Position,SimpleVariant>()

        while(iterator.hasNext()) {
            val vc = iterator.next()

            val refChr = vc.contig
            val refStart = vc.start
            val refEnd = vc.end

            val refAllele = vc.reference.displayString
            val altAlleles = vc.alternateAlleles.map { it.displayString }

            rangeMap.put(Range.closed(Position(refChr, refStart), Position(refChr, refEnd)),
                SimpleVariant(Position(refChr, refStart), Position(refChr, refEnd), refAllele, altAlleles.joinToString(",")))

        }

        return Pair(sampleName,rangeMap)
    }

    fun addNewVariants(mutationDonorGvcf: File, baseVariantMap: RangeMap<Position, SimpleVariant>) {
        val variantReader = VCFFileReader(mutationDonorGvcf, false)

        val iterator = variantReader.iterator()

        while(iterator.hasNext()) {
            val vc = iterator.next()

            extractVCAndAddToRangeMap(vc, baseVariantMap)
        }
    }

    fun extractVCAndAddToRangeMap(
        vc: VariantContext,
        baseVariantMap: RangeMap<Position, SimpleVariant>
    ) {
        val refChr = vc.contig
        val refStart = vc.start
        val refEnd = vc.end

        val refAllele = vc.reference.displayString
        val altAllele = vc.alternateAlleles.map { it.displayString }.first()

        val variantPosition = Position(refChr, refStart)

        val currentSimpleVariant =
            SimpleVariant(Position(refChr, refStart), Position(refChr, refEnd), refAllele, altAllele, true)

        val overlappingVariant = baseVariantMap.get(variantPosition)

        //Skip if refBlock
        if(currentSimpleVariant.refAllele.length == 1 && currentSimpleVariant.altAllele == "<NON_REF>") {
            return
        }

        if (VariantContextUtils.isIndel(currentSimpleVariant) && VariantContextUtils.isIndel(overlappingVariant)) {
            //skip as it will be tricky/slow to handle
            return //TODO figure out a way to handle this well
        }

        if (overlappingVariant == null) {
            //This is a new variant we can add as it does not overlap with an existing variant
            baseVariantMap.put(
                Range.closed(Position(refChr, refStart), Position(refChr, refEnd)),
                currentSimpleVariant
            )
        } else {
            //we need to split out the existing and add the new variant
            updateOverlappingVariant(baseVariantMap, currentSimpleVariant)
        }
    }

    fun updateOverlappingVariant(baseVariantMap: RangeMap<Position, SimpleVariant>, variant: SimpleVariant) {
        //Get out the overlapping entry
        val overlappingEntry = baseVariantMap.getEntry(variant.refStart)?: return

        //split it up based on the new variant
        val existingVariant = overlappingEntry.value

        if(existingVariant == variant) {
            //same variant, nothing to do
            return
        }

        //Check to see if the existing Variant is a SNP
        if(existingVariant.refStart == existingVariant.refEnd) {
            //SNP case, we can just replace it
            baseVariantMap.put(Range.closed(variant.refStart, variant.refEnd), variant)
        }
        else if( existingVariant.refAllele.length == 1 && existingVariant.altAllele == "<NON_REF>"  //This checks that the existing variant is a refBlock
            && variant.refAllele.length == 1 ) { //This means current variant is a SNP or single BP insertion which can be treated like a normal SNP as it only covers 1 ref BP
            //This is a refBlock case that fully covers the new variant
            val splitVariants = splitRefBlock(existingVariant, variant)

            baseVariantMap.remove(overlappingEntry.key)
            for(sv in splitVariants) {
                baseVariantMap.put(Range.closed(sv.refStart, sv.refEnd), sv)
            }
        }
        //We also need to handle a complex edge case where we have an indel overlapping another indel.
    // If the new indel is fully covered we can remove the existing, add potentially 2 refBlocks surrounding
        //TODO make this work with indel edge cases
//        else if( variant.refStart >= existingVariant.refStart && variant.refEnd <= existingVariant.refEnd ) {
//            //The new variant is fully contained within the existing variant
//            val splitVariants = splitRefBlock(existingVariant, variant)
//
//            founderVariantMap.remove(overlappingEntry.key)
//            for(sv in splitVariants) {
//                founderVariantMap.put(Range.closed(sv.refStart, sv.refEnd), sv)
//            }
//        }


    }

    fun splitRefBlock(variantToSplit: SimpleVariant, variantToAdd: SimpleVariant): List<SimpleVariant> {
        //check to make sure that the variantToAdd is fully contained within the variantToSplit
        require(variantToAdd.refStart >= variantToSplit.refStart && variantToAdd.refEnd <= variantToSplit.refEnd) {
            "Variant to add must be fully contained within the variant to split"
        }

        val splitVariants = mutableListOf<SimpleVariant>()
        //Check to see if the variantToAdd starts at the same position.
        if(variantToSplit.refStart != variantToAdd.refStart) {
            //We have a left side to create
            val leftVariant = SimpleVariant(
                refStart = variantToSplit.refStart,
                refEnd = Position(variantToAdd.refStart.contig, variantToAdd.refStart.position -1),
                refAllele = variantToSplit.refAllele,
                altAllele = "<NON_REF>",
                isAddedMutation = false
            )
            splitVariants.add(leftVariant)
        }
        //Add the new variant
        splitVariants.add(variantToAdd)
        //Check to see if we have a right side to create
        if(variantToSplit.refEnd != variantToAdd.refEnd) {
            val rightVariant = SimpleVariant(
                refStart = Position(variantToAdd.refEnd.contig, variantToAdd.refEnd.position +1),
                refEnd = variantToSplit.refEnd,
                refAllele = variantToSplit.refAllele,
                altAllele = "<NON_REF>",
                isAddedMutation = false
            )
            splitVariants.add(rightVariant)
        }
        return splitVariants
    }

    fun writeMutatedGVCF(outputDir: File, sampleName: String, baseVariantMap: RangeMap<Position, SimpleVariant>) {
        writeMutatedGVCFToFile(File(outputDir, "${sampleName}_mutated.g.vcf"), sampleName, baseVariantMap)
    }

    fun writeMutatedGVCFToFile(outputGvcfFile: File, sampleName: String, baseVariantMap: RangeMap<Position, SimpleVariant>) {
        VariantContextWriterBuilder()
            .unsetOption(Options.INDEX_ON_THE_FLY)
            .setOutputFile(outputGvcfFile)
            .setOutputFileType(VariantContextWriterBuilder.OutputType.VCF)
            .setOption(Options.ALLOW_MISSING_FIELDS_IN_HEADER)
            .build().use { writer ->

                writer.writeHeader(VariantContextUtils.createGenericHeader(listOf(sampleName), emptySet()))

                val sortedVariants = baseVariantMap.asMapOfRanges().toList().sortedBy { it.first.lowerEndpoint() }

                for ((range, variant) in sortedVariants) {
                    val vcBuilder = VariantContextBuilder()
                    vcBuilder.chr(variant.refStart.contig)
                    vcBuilder.start(variant.refStart.position.toLong())
                    vcBuilder.stop(variant.refEnd.position.toLong())
                    vcBuilder.id(".")
                    vcBuilder.alleles(
                        listOf(
                            Allele.create(variant.refAllele, true),
                            Allele.create(variant.altAllele, false)
                        )
                    )
                    vcBuilder.attribute("END", variant.refEnd.position)
                    val genotypeBuilder = GenotypeBuilder(sampleName)
                    val alleles = if (variant.altAllele == "<NON_REF>") {
                        listOf(Allele.create(variant.refAllele, true), Allele.create(variant.refAllele, true))
                    } else {
                        listOf(Allele.create(variant.altAllele, false), Allele.create(variant.altAllele, false))
                    }
                    genotypeBuilder.alleles(
                        alleles
                    )
                    vcBuilder.genotypes(genotypeBuilder.make())

                    writer.add(vcBuilder.make())
                }

            }
    }



}