package net.maizegenetics.net.maizegenetics.commands

import apple.laf.JRSUIConstants
import biokotlin.seqIO.NucSeqIO
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import com.google.common.collect.Range
import com.google.common.collect.RangeMap
import com.google.common.collect.TreeRangeMap
import htsjdk.variant.variantcontext.Allele
import htsjdk.variant.variantcontext.GenotypeBuilder
import htsjdk.variant.variantcontext.VariantContextBuilder
import htsjdk.variant.variantcontext.writer.Options
import htsjdk.variant.variantcontext.writer.VariantContextWriter
import htsjdk.variant.variantcontext.writer.VariantContextWriterBuilder
import htsjdk.variant.vcf.VCFFileReader
import htsjdk.variant.vcf.VCFFormatHeaderLine
import htsjdk.variant.vcf.VCFHeader
import htsjdk.variant.vcf.VCFHeaderLine
import htsjdk.variant.vcf.VCFHeaderLineCount
import htsjdk.variant.vcf.VCFHeaderLineType
import htsjdk.variant.vcf.VCFInfoHeaderLine
import java.io.File
import java.util.HashSet
import kotlin.io.path.createDirectories
import kotlin.io.path.exists


data class Position(val contig: String, val position: Int) : Comparable<Position> {
    override fun compareTo(other: Position): Int {

        if (this.contig == other.contig) {
            return this.position.compareTo(other.position)
        }

        val thisContig = this.contig.replace("chr", "", ignoreCase = true).trim()
        val otherContig = other.contig.replace("chr", "", ignoreCase = true).trim()

        return try {
            thisContig.toInt() - otherContig.toInt()
        } catch (e: NumberFormatException) {
            // If we can't convert contigs to an int, then compare the strings
            contig.compareTo(other.contig)
        }

    }

    override fun toString(): String {
        return "$contig:$position"
    }
}
data class SimpleVariant(val refStart: Position, val refEnd: Position,
                         val refAllele: String, val altAllele: String,
                         val isAddedMutation: Boolean = false)

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class MutateAssemblies : CliktCommand(name = "mutate-assemblies") {


    private val founderGvcf by option(
        help = "Founder GVCF to mutate (.gvcf or .g.vcf.gz)"
    ).path(mustExist = true, canBeFile = true, canBeDir = false)
        .required()

    private val nonFounderGvcf by option(
        help = "Non-founder GVCF to pull variants from (.gvcf or .g.vcf.gz)"
    ).path(mustExist = true, canBeFile = true, canBeDir = false)
        .required()



    private val outputDir by option(help = "Output dir")
        .path(canBeFile = false, canBeDir = true)
        .required()

    override fun run() {
        if(!outputDir.exists()) {
            outputDir.createDirectories()
        }

        introduceMutations(founderGvcf.toFile(), nonFounderGvcf.toFile(), outputDir.toFile())

    }

    fun introduceMutations(founderGvcf: File, mutationGvcf: File, outputDir: File) {
        //walk through the two gvcf files
        //From one  pull the mutations left

        //Loop through the founderGVCF and build a RangeMap of Position to Variant
        val (sampleName, founderVariantMap) = buildFounderVariantMap(founderGvcf)

        addNewVariants(mutationGvcf, founderVariantMap)

        writeMutatedGVCF(outputDir, sampleName, founderVariantMap)
    }

    fun buildFounderVariantMap(founderGvcf: File) : Pair<String,RangeMap<Position, SimpleVariant>> {
        //Loop through the founderGVCF and build a RangeMap of Position to Variant
        val variantReader = VCFFileReader(founderGvcf)

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

    fun addNewVariants(mutationGvcf: File, founderVariantMap: RangeMap<Position, SimpleVariant>) {
        val variantReader = VCFFileReader(mutationGvcf)

        val iterator = variantReader.iterator()

        while(iterator.hasNext()) {
            val vc = iterator.next()

            val refChr = vc.contig
            val refStart = vc.start
            val refEnd = vc.end

            val refAllele = vc.reference.displayString
            val altAllele = vc.alternateAlleles.map { it.displayString }.first()

            val variantPosition = Position(refChr, refStart)

            val currentSimpleVariant = SimpleVariant(Position(refChr, refStart), Position(refChr, refEnd), refAllele, altAllele, true)

            val overlappingVariantSt = founderVariantMap.get(variantPosition)
            val overlappingVariantEnd = founderVariantMap.get(Position(refChr, refEnd))

            if(overlappingVariantSt == overlappingVariantEnd) {
                //skip as it will be tricky/slow to handle
                continue //TODO figure out a way to handle this well
            }

            if(overlappingVariantSt == null) {
                //This is a new variant we can add as it does not overlap with an existing variant
                founderVariantMap.put(Range.closed(Position(refChr, refStart), Position(refChr, refEnd)), currentSimpleVariant)
            }
            else {
                //we need to split out the existing and add the new variant
                updateOverlappingVariant(founderVariantMap, currentSimpleVariant)
            }

        }
    }

    fun updateOverlappingVariant(founderVariantMap: RangeMap<Position, SimpleVariant>, variant: SimpleVariant) {
        //Get out the overlapping entry
        val overlappingEntry = founderVariantMap.getEntry(variant.refStart)?: return

        //split it up based on the new variant
        val existingVariant = overlappingEntry.value

        if(existingVariant == variant) {
            //same variant, nothing to do
            return
        }

        //Check to see if the existing Variant is a SNP
        if(existingVariant.refStart == existingVariant.refEnd) {
            //SNP case, we can just replace it
            founderVariantMap.put(Range.closed(variant.refStart, variant.refEnd), variant)
        }
        else if( existingVariant.refAllele.length == 1 && existingVariant.altAllele == "<NON_REF>"  //This checks that the existing variant is a refBlock
            && variant.refAllele.length == 1 && variant.altAllele.length == 1 ) { //This means current variant is a SNP
            //This is a refBlock case that fully covers the new variant
            val splitVariants = splitRefBlock(existingVariant, variant)

            founderVariantMap.remove(overlappingEntry.key)
            for(sv in splitVariants) {
                founderVariantMap.put(Range.closed(sv.refStart, sv.refEnd), sv)
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

    fun writeMutatedGVCF(outputDir: File, sampleName: String, founderVariantMap: RangeMap<Position, SimpleVariant>) {
        val outputGvcfFile = File(outputDir, "${sampleName}_mutated.g.vcf")
        VariantContextWriterBuilder()
            .unsetOption(Options.INDEX_ON_THE_FLY)
            .setOutputFile(outputGvcfFile)
            .setOutputFileType(VariantContextWriterBuilder.OutputType.VCF)
            .setOption(Options.ALLOW_MISSING_FIELDS_IN_HEADER)
            .build().use { writer ->

                writer.writeHeader(createGenericHeader(listOf(sampleName), emptySet()))

                val sortedVariants = founderVariantMap.asMapOfRanges().toList().sortedBy { it.first.lowerEndpoint() }

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

    fun createGenericHeader(taxaNames: List<String>, altLines:Set<VCFHeaderLine>): VCFHeader {
        val headerLines = createGenericHeaderLineSet() as MutableSet<VCFHeaderLine>
        headerLines.addAll(altLines)
        return VCFHeader(headerLines, taxaNames)
    }

    fun createGenericHeaderLineSet(): Set<VCFHeaderLine> {
        val headerLines: MutableSet<VCFHeaderLine> = HashSet()
        headerLines.add(VCFFormatHeaderLine("AD", 3, VCFHeaderLineType.Integer, "Allelic depths for the ref and alt alleles in the order listed"))
        headerLines.add(
            VCFFormatHeaderLine("DP", 1, VCFHeaderLineType.Integer, "Read Depth (only filtered reads used for calling)")
        )
        headerLines.add(VCFFormatHeaderLine("GQ", 1, VCFHeaderLineType.Integer, "Genotype Quality"))
        headerLines.add(VCFFormatHeaderLine("GT", 1, VCFHeaderLineType.String, "Genotype"))
        headerLines.add(
            VCFFormatHeaderLine("PL", VCFHeaderLineCount.G, VCFHeaderLineType.Integer, "Normalized, Phred-scaled likelihoods for genotypes as defined in the VCF specification")
        )
        headerLines.add(VCFInfoHeaderLine("DP", 1, VCFHeaderLineType.Integer, "Total Depth"))
        headerLines.add(VCFInfoHeaderLine("NS", 1, VCFHeaderLineType.Integer, "Number of Samples With Data"))
        headerLines.add(VCFInfoHeaderLine("AF", 3, VCFHeaderLineType.Integer, "Allele Frequency"))
        headerLines.add(VCFInfoHeaderLine("END", 1, VCFHeaderLineType.Integer, "Stop position of the interval"))
        // These last 4 are needed for assembly g/hvcfs, but not for reference.  I am keeping them in as header
        // lines but they will only be added to the data lines if they are present in the VariantContext.
        headerLines.add(VCFInfoHeaderLine("ASM_Chr", 1, VCFHeaderLineType.String, "Assembly chromosome"))
        headerLines.add(VCFInfoHeaderLine("ASM_Start", 1, VCFHeaderLineType.Integer, "Assembly start position"))
        headerLines.add(VCFInfoHeaderLine("ASM_End", 1, VCFHeaderLineType.Integer, "Assembly end position"))
        headerLines.add(VCFInfoHeaderLine("ASM_Strand", 1, VCFHeaderLineType.String, "Assembly strand"))
        return headerLines
    }

}