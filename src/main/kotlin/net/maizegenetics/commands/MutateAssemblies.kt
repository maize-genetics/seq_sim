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
import java.io.File
import kotlin.io.path.createDirectories
import kotlin.io.path.exists




@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class MutateAssemblies : CliktCommand(name = "mutate-assemblies") {


    private val baseGvcf by option(
        help = "Base GVCF to mutate (.gvcf or .g.vcf.gz)"
    ).path(mustExist = true, canBeFile = true, canBeDir = false)
        .required()

    private val mutationDonorGvcf by option(
        help = "GVCF to pull variants from (.gvcf or .g.vcf.gz)"
    ).path(mustExist = true, canBeFile = true, canBeDir = false)
        .required()



    private val outputDir by option(help = "Output dir")
        .path(canBeFile = false, canBeDir = true)
        .required()

    override fun run() {
        if(!outputDir.exists()) {
            outputDir.createDirectories()
        }

        introduceMutations(baseGvcf.toFile(), mutationDonorGvcf.toFile(), outputDir.toFile())

    }

    fun introduceMutations(baseGvcf: File, mutationDonorGvcf: File, outputDir: File) {
        //walk through the two gvcf files
        //From one  pull the mutations left

        //Loop through the founderGVCF and build a RangeMap of Position to Variant
        val (sampleName, baseVariantMap) = buildBaseVariantMap(baseGvcf)

        addNewVariants(mutationDonorGvcf, baseVariantMap)

        writeMutatedGVCF(outputDir, sampleName, baseVariantMap)
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
        val outputGvcfFile = File(outputDir, "${sampleName}_mutated.g.vcf")
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