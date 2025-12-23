package net.maizegenetics.net.maizegenetics.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import com.google.common.collect.Range
import com.google.common.collect.RangeMap
import com.google.common.collect.TreeRangeMap
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


    override fun run() {
        // Implementation goes here
        recombineGvcfs(inputBedDir, inputGvcfDir, outputDir)
    }

    fun recombineGvcfs(inputBedDir: Path, inputGvcfDir: java.nio.file.Path, outputDir: java.nio.file.Path) {
        // Placeholder for the actual recombination logic
        println("Recombining GVCFs from $inputGvcfDir using BED files from $inputBedDir into $outputDir")

        //Build BedFile Map
        val (recombinationMap, sampleNames) = buildRecombinationMap(inputBedDir)
        //Build Output writers for each sample name
        val outputWriters = buildOutputWriterMap(sampleNames, outputDir)
        //Process GVCFs and write out recombined files
        processGvcfsAndWrite(recombinationMap, inputGvcfDir, outputWriters)
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

//    fun buildRecombinationMap(inputBedDir: Path): Pair<Map<String, List<RecombinationRange>>, List<String>> {
//        //loop through each file in the inputBedDir
//        val recombinationMap = mutableMapOf<String, MutableList<RecombinationRange>>()
//        val targetNames = mutableSetOf<String>()
//        inputBedDir.toFile().listFiles()?.forEach { bedFile ->
//            val bedFileSampleName = bedFile.name.substringBeforeLast("_")
//            bedFile.forEachLine { line ->
//                val parts = line.split("\t")
//                if (parts.size >= 4) {
//                    val chrom = parts[0]
//                    val start = parts[1].toInt()
//                    val end = parts[2].toInt()
//                    val targetSampleName = parts[3]
//
//                    val range = RecombinationRange(chrom, start, end, targetSampleName)
//                    recombinationMap.computeIfAbsent(bedFileSampleName) { mutableListOf() }.add(range)
//                    targetNames.add(targetSampleName)
//                }
//            }
//        }
//        return Pair(recombinationMap, targetNames.toList())
//    }



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
        outputWriters: Map<String, VariantContextWriter>
    ) {
        inputGvcfDir.toFile().listFiles()?.forEach { gvcfFile ->
            val sampleName = gvcfFile.name.substringBeforeLast(".g.vcf")
            val ranges = recombinationMap[sampleName] ?: return@forEach

            VCFFileReader(gvcfFile, false).use { gvcfReader ->
                processSingleGVCFFile(gvcfReader, ranges, outputWriters)
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
        outputWriters: Map<String, VariantContextWriter>
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

            val targetSampleName = ranges.get(startPos) ?: continue

            val outputWriter = outputWriters[targetSampleName] ?: continue

            if((vc.reference.length() == 1) && (vc.alternateAlleles.first().displayString == "NON_REF" || vc.reference.length() == vc.alternateAlleles[0].length())) {
                //RefBlock or SNP polymorphism
                outputWriter.add(vc)
            } else {
                //Indel case, need to check if it spans multiple ranges
                TODO("Handle Indel spanning multiple ranges")
                var currentStartPos = startPos
                var currentEndPos = endPos
                var currentVc = vc

                while (true) {
                    val rangeTargetSampleName = ranges.get(currentStartPos) ?: break
                    val range = ranges.asMapOfRanges().entries.find { it.value == rangeTargetSampleName }?.key ?: break

                    if (range.contains(currentEndPos)) {
                        //Fully contained within the range
                        outputWriter.add(currentVc)
                        break
                    } else {
                        //Partially contained, need to resize and write out
                        val resizedEndPos = Position(range.upperEndpoint().contig, range.upperEndpoint().position)
//                        val resizedVc = VariantContextUtils.resizeVariantContext(currentVc, currentStartPos.position, resizedEndPos.position)

//                        outputWriter.add(resizedVc)

                        //Move to the next range
                        currentStartPos = Position(currentVc.contig, resizedEndPos.position + 1)
//                        currentVc = VariantContextUtils.resizeVariantContext(currentVc, currentStartPos.position, currentEndPos.position)
                    }
                }
            }
        }
    }

}