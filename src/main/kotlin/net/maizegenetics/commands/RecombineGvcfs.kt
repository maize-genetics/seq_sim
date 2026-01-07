package net.maizegenetics.net.maizegenetics.commands

import biokotlin.seq.NucSeq
import biokotlin.seq.NucSeqRecord
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
import htsjdk.variant.variantcontext.VariantContext
import htsjdk.variant.variantcontext.VariantContextBuilder
import htsjdk.variant.variantcontext.writer.Options
import htsjdk.variant.variantcontext.writer.VariantContextWriter
import htsjdk.variant.variantcontext.writer.VariantContextWriterBuilder
import htsjdk.variant.vcf.VCFFileReader
import htsjdk.variant.vcf.VCFReader
import net.maizegenetics.net.maizegenetics.utils.Position
import net.maizegenetics.net.maizegenetics.utils.VariantContextUtils
import java.io.File
import java.nio.file.Path

data class RecombinationRange(val chrom: String, val start: Int, val end: Int, val targetSampleName: String)

class RecombineGvcfs : CliktCommand(name = "recombine-gvcfs") {

    private val inputBedDir by option(help = "Input Bed dir")
        .path(canBeFile = false, canBeDir = true)
        .required()

    private val inputGvcfDir by option(help = "Input GVCF dir")
        .path(canBeFile = false, canBeDir = true)
        .required()

    private val outputDir by option(help = "Output dir")
        .path(canBeFile = false, canBeDir = true)
        .required()

    private val refFile by option(help = "Ref file")
        .path(canBeFile = true, canBeDir = false)
        .required()


    override fun run() {
        // Implementation goes here
        recombineGvcfs(inputBedDir, inputGvcfDir, outputDir,refFile)
    }

    fun recombineGvcfs(inputBedDir: Path, inputGvcfDir: Path, outputDir: Path, refFile: Path) {
        println("Loading in the reference Genome from $refFile")
        val refSeq = NucSeqIO(refFile.toFile().path).readAll()

        // Placeholder for the actual recombination logic
        println("Recombining GVCFs from $inputGvcfDir using BED files from $inputBedDir into $outputDir")

        //Build BedFile Map
        val (recombinationMap, sampleNames) = buildRecombinationMap(inputBedDir)
        //Build Output writers for each sample name
        val outputWriters = buildOutputWriterMap(sampleNames, outputDir)
        //Process GVCFs and write out recombined files
        processGvcfsAndWrite(recombinationMap, inputGvcfDir, outputWriters, refSeq)
        //Close the GVCF writers
        outputWriters.values.forEach { it.close() }

        //Sort the gvcfs
    }

    fun buildRecombinationMap(inputBedDir: Path): Pair<Map<String, RangeMap<Position, String>>, List<String>> {
        //loop through each file in the inputBedDir
        val recombinationMap = mutableMapOf<String, RangeMap<Position, String>>()
        val targetNames = mutableSetOf<String>()
        inputBedDir.toFile().listFiles()?.forEach { bedFile ->
            val bedFileSampleName = bedFile.name.substringBeforeLast("_")
            bedFile.forEachLine { line ->
                val parts = line.split("\t")
                if (parts.size >= 4) {
                    val chrom = parts[0]
                    val start = parts[1].toInt() + 1 //BED is 0 based, VCF is 1 based
                    val end = parts[2].toInt()
                    val targetSampleName = parts[3]

                    val range = Range.closed(Position(chrom, start), Position(chrom, end))
                    recombinationMap.computeIfAbsent(bedFileSampleName) { TreeRangeMap.create() }.put(range, targetSampleName)
                    targetNames.add(targetSampleName)
                }
            }
        }
        return Pair(recombinationMap, targetNames.toList())
    }
    

    fun buildOutputWriterMap(sampleNames: List<String>, outputDir: Path): Map<String, VariantContextWriter> {
        return sampleNames.associateWith { sampleName ->
            val outputFile = outputDir.resolve("${sampleName}_recombined.gvcf")
            val writer = VariantContextWriterBuilder()
                .unsetOption(Options.INDEX_ON_THE_FLY)
                .setOutputFile(outputFile.toFile())
                .setOutputFileType(VariantContextWriterBuilder.OutputType.VCF)
                .setOption(Options.ALLOW_MISSING_FIELDS_IN_HEADER)
                .build()

            writer.writeHeader(VariantContextUtils.createGenericHeader(listOf(sampleName), emptySet()))
            writer
        }
    }

    fun processGvcfsAndWrite(
        recombinationMap: Map<String, RangeMap<Position,String>>,
        inputGvcfDir: Path,
        outputWriters: Map<String, VariantContextWriter>,
        refSeq :Map<String, NucSeqRecord>
    ) {
        inputGvcfDir.toFile().listFiles()?.forEach { gvcfFile ->
            val sampleName = gvcfFile.name.substringBeforeLast(".g.vcf")
            val ranges = recombinationMap[sampleName] ?: return@forEach

            VCFFileReader(gvcfFile, false).use { gvcfReader ->
                processSingleGVCFFile(gvcfReader, ranges, outputWriters, refSeq)
            }
        }
    }

//    fun processGvcfsAndWrite(
//        recombinationMap: Map<String, List<RecombinationRange>>,
//        inputGvcfDir: Path,
//        outputWriters: Map<String, VariantContextWriter>
//    ) {
//        inputGvcfDir.toFile().listFiles()?.forEach { gvcfFile ->
//            val sampleName = gvcfFile.name.substringBeforeLast(".g.vcf")
//            val ranges = recombinationMap[sampleName] ?: return@forEach
//
//            VCFFileReader(gvcfFile, false).use { gvcfReader ->
//                processSingleGVCFFile(gvcfReader, ranges, outputWriters)
//            }
//        }
//    }

    fun processSingleGVCFFile(
        gvcfReader: VCFReader,
        ranges: RangeMap<Position,String>,
        outputWriters: Map<String, VariantContextWriter>,
        refSeq :Map<String, NucSeqRecord>
    ) {
        //Need to loop through each range and each gvcf record.
        //We need to see if the variant falls within the range. If so, write it to the appropriate output writer.
        //There are edge cases where the variant can span multiple ranges(Due to RefBlock) which is valid just need to resize the variant and write out.
        //We do need to make sure that if an indel spans a range boundary that we correctly handle it by assigning it to the left most range but not the right

        //This might actually be very simple, If we convert the List<RecombinationRange> into a Range Map, we only need to check the start position.
        //Then if the variant is an RefBlock we need to resize it to start at the end of the range and move to the next range.  Continue doing this
        val iterator = gvcfReader.iterator()
        while (iterator.hasNext()) {
            val vc = iterator.next()
            val startPos = Position(vc.contig, vc.start)
            val endPos = Position(vc.contig, vc.end)

            var targetSampleName = ranges.get(startPos) ?: continue

            var outputWriter = outputWriters[targetSampleName] ?: continue

            if((vc.reference.length() == 1) &&  vc.reference.length() == vc.alternateAlleles[0].length()) {
                // SNP polymorphism
                //can write out directly as it is only 1 position and will not overlap but will need to change the genotype name
                val newVc = changeSampleName(vc, targetSampleName)
                outputWriter.add(newVc)
            }
            else if(vc.reference.length() == 1 && vc.alternateAlleles.first().displayString == "NON_REF"){
                //This is a RefBlock
                //We need to 'walk through' the refBlock by splitting it up into multiple refBlocks and write out to the correct output writer
                //Get the current start position
                processRefBlockOverlap(startPos, endPos, ranges, outputWriters, refSeq, vc)
            }
        }
    }

    fun processRefBlockOverlap(
        startPos: Position,
        endPos: Position,
        ranges: RangeMap<Position, String>,
        outputWriters: Map<String, VariantContextWriter>,
        refSeq: Map<String, NucSeq>,
        vc: VariantContext
    ){
        var currentStartPos = startPos
        while (currentStartPos <= endPos) {
            //Get the target sample name for the current start position
            val rangesEntry = ranges.getEntry(currentStartPos) ?: break
            val range = rangesEntry.key
            val targetSampleName = rangesEntry.value

            val outputWriter = outputWriters[targetSampleName] ?: break

            val refAllele = refSeq[vc.contig]!!.get(currentStartPos.position - 1).char

            //Check to see if the refBlock is fully contained within the range
            if (range.contains(endPos)) {
                //Fully contained within the range, write out and break
                val newVc = buildRefBlock(
                    vc.contig,
                    currentStartPos.position,
                    endPos.position,
                    "$refAllele",
                    targetSampleName
                )
                outputWriter.add(newVc)
                break
            } else {
                //Partially contained, need to resize and write out
                val resizedEndPos = Position(range.upperEndpoint().contig, range.upperEndpoint().position)
                val newVc = buildRefBlock(
                    vc.contig,
                    currentStartPos.position,
                    range.upperEndpoint().position,
                    "$refAllele",
                    targetSampleName
                )
                outputWriter.add(newVc)
                //move up the start
                currentStartPos = Position(vc.contig, resizedEndPos.position + 1)
            }
        }
    }

    fun changeSampleName(vc: VariantContext, newSampleName: String): VariantContext {
        val builder = VariantContextBuilder(vc)
        val genotypes = vc.genotypes.map { genotype ->
            GenotypeBuilder(genotype)
                .name(newSampleName)
                .make()
        }
        builder.genotypes(genotypes)
        return builder.make()
    }

    fun buildRefBlock(chrom: String, start: Int, end: Int, refAllele: String, sampleName: String): VariantContext {
        return VariantContextBuilder()
            .chr(chrom)
            .start(start.toLong())
            .stop(end.toLong())
            .alleles(listOf(refAllele, "<NON_REF>"))
            .genotypes(
                listOf(
                    GenotypeBuilder(sampleName).alleles(listOf(Allele.create(refAllele,true))).make()
                )
            ).make()
    }

}