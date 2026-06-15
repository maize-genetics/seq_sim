package net.maizegenetics.commands

import biokotlin.seq.NucSeqRecord
import biokotlin.seqIO.NucSeqIO
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import com.google.common.collect.Range
import htsjdk.variant.variantcontext.Allele
import htsjdk.variant.variantcontext.GenotypeBuilder
import htsjdk.variant.variantcontext.VariantContext
import htsjdk.variant.variantcontext.VariantContextBuilder
import htsjdk.variant.variantcontext.writer.Options
import htsjdk.variant.variantcontext.writer.VariantContextWriter
import htsjdk.variant.variantcontext.writer.VariantContextWriterBuilder
import htsjdk.variant.vcf.VCFFileReader
import htsjdk.variant.vcf.VCFReader
import net.maizegenetics.Constants
import net.maizegenetics.utils.Position
import net.maizegenetics.utils.VariantContextUtils
import java.nio.file.Path
import java.util.AbstractMap
import java.util.TreeMap



fun <V> TreeMap<Position, Pair<Position, V>>.getEntry(key: Position): Map.Entry<Position, Pair<Position, V>>? {
    val entry = floorEntry(key) ?: return null
    return if (key <= entry.value.first) entry else null
}


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



    val pattern = Regex("""^(.+?)\.g(?:\.?vcf)(?:\.gz)?$""")

    override fun run() {
        // Implementation goes here
        recombineGvcfs(inputBedDir, inputGvcfDir, refFile, outputDir)
    }

    fun recombineGvcfs(inputBedDir: Path, inputGvcfDir: Path, refFile: Path, outputDir: Path) {
        println("Loading in the reference Genome from $refFile")
        val refSeq = NucSeqIO(refFile.toFile().path).readAll()

        // Placeholder for the actual recombination logic
        println("Recombining GVCFs from $inputGvcfDir using BED files from $inputBedDir into $outputDir")

        //Build BedFile Map
        val (recombinationMap, sampleNames) = buildRecombinationMap(inputBedDir)

        println("Building the Initial output GVCF Writers.")
        //Build Output writers for each sample name
        val outputWriters = buildOutputWriterMap(sampleNames, outputDir)
        println("Process GVCFs and write them out.")
        //Process GVCFs and write out recombined files
        processGvcfsAndWrite(recombinationMap, inputGvcfDir, outputWriters, refSeq)
        //Close the GVCF writers
        outputWriters.values.forEach { it.close() }
    }

    /**
     * Build a TreeMap of all the recombination blocks for each sample.
     * The key is the start position of the block and the value is a pair of the end position and the sample name.
     * Using a TreeMap as it is much faster.
     */
    fun buildRecombinationMap(inputBedDir: Path): Pair<Map<String, TreeMap<Position, Pair<Position,String>>>, List<String>> {
        //loop through each file in the inputBedDir
        val recombinationMap = mutableMapOf<String, TreeMap<Position, Pair<Position,String>>>()
        val targetNames = mutableSetOf<String>()
        inputBedDir.toFile().listFiles()?.forEach { bedFile ->
            val bedFileSampleName = bedFile.name.substringBeforeLast("_").replace(".bed","")
            bedFile.forEachLine { line ->
                val parts = line.split("\t")
                if (parts.size >= 4) {
                    val chrom = parts[0]
                    val start = parts[1].toInt() + 1 //BED is 0 based, VCF is 1 based
                    val end = parts[2].toInt()
                    val targetSampleName = parts[3]

                    val range = Range.closed(Position(chrom, start), Position(chrom, end))
                    recombinationMap.computeIfAbsent(bedFileSampleName) { TreeMap() }[range.lowerEndpoint()] =
                        Pair(range.upperEndpoint(), targetSampleName)
                    targetNames.add(targetSampleName)
                }
            }
        }
        return Pair(recombinationMap, targetNames.toList())
    }

    /**
     * Build the output writer maps
     */
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

    /**
     * Process each gvcfs and write out the variants to the targets
     */
    fun processGvcfsAndWrite(
        recombinationMap: Map<String, TreeMap<Position,Pair<Position,String>>>,
        inputGvcfDir: Path,
        outputWriters: Map<String, VariantContextWriter>,
        refSeq :Map<String, NucSeqRecord>
    ) {
        var count = 0
        val fileList = inputGvcfDir.toFile().listFiles()
        val totalFiles = fileList?.size?:0
        fileList?.forEach { gvcfFile ->
            count++
            val match = pattern.matchEntire(gvcfFile.name)
            if (match == null) {
                println("Skipping file ${gvcfFile.name} as it does not match expected GVCF naming pattern.")
                return@forEach
            }
            val sampleName = deriveSourceSampleKey(gvcfFile.name)
            val ranges = recombinationMap[sampleName] ?: return@forEach

            VCFFileReader(gvcfFile, false).use { gvcfReader ->
                println("Processing file ${count}/$totalFiles ${gvcfFile.name}")
                processSingleGVCFFile(gvcfReader, ranges, outputWriters, refSeq)
            }
        }
    }

    /**
     * Function to process a single GVCF file
     */
    fun processSingleGVCFFile(
        gvcfReader: VCFReader,
        ranges: TreeMap<Position,Pair<Position,String>>,
        outputWriters: Map<String, VariantContextWriter>,
        refSeq :Map<String, NucSeqRecord>
    ) {
        //Need to loop through each range and each gvcf record.
        //We need to see if the variant falls within the range. If so, write it to the appropriate output writer.
        //There are edge cases where the variant can span multiple ranges(Due to RefBlock) which is valid just need to resize the variant and write out.
        //We do need to make sure that if an indel spans a range boundary that we correctly handle it by assigning it to the left most range but not the right

        //This might actually be very simple, If we convert the List<RecombinationRange> into a Range Map, we only need to check the start position.
        //Then if the variant is an RefBlock we need to resize it to start at the end of the range and move to the next range.  Continue doing this

        var currentEntry: Map.Entry<Position, Pair<Position,String>> = AbstractMap.SimpleEntry(Position("", -1), Pair(Position("", -1), ""))

        val iterator = gvcfReader.iterator()
        while (iterator.hasNext()) {
            val vc = iterator.next()
            val startPos = Position(vc.contig, vc.start)
            val endPos = Position(vc.contig, vc.end)

            //Check to see if startPos is between our current entry
            val startPosEntry = if(startPos in currentEntry.key .. currentEntry.value.first) {
                //use currentEntry
                currentEntry
            } else {
                val newEntry = ranges.getEntry(startPos) ?: continue
                currentEntry = newEntry
                newEntry
            }
            val targetSampleName = startPosEntry.value.second

            val outputWriter = outputWriters[targetSampleName] ?: continue

            if((vc.reference.length() == 1) &&  vc.reference.length() == vc.alternateAlleles[0].length()) {
                // SNP polymorphism
                //can write out directly as it is only 1 position and will not overlap but will need to change the genotype name
                val newVc = changeSampleName(vc, targetSampleName)
                outputWriter.add(newVc)
            }
            else if(vc.reference.length() == 1 && vc.alternateAlleles.first().displayString == "<NON_REF>"){
                //This is a RefBlock
                //We need to 'walk through' the refBlock by splitting it up into multiple refBlocks and write out to the correct output writer
                //Get the current start position
                processRefBlockOverlap(startPos, endPos, ranges, outputWriters, refSeq, vc)
            }
            else if(vc.reference.length() == 1 && vc.alternateAlleles.first().baseString.length > 1) {
                //Insertion
                //we can just write it out as simple insertions only hit one bp of ref
                val newVc = changeSampleName(vc, targetSampleName)
                outputWriter.add(newVc)
            }
            else {
                //zrm22 updating to resize deletions specifically
                processDelOverlap(startPos, endPos, ranges, outputWriter, vc)
            }
        }
    }

    /**
     * Function to process the ref block overlaps
     */
    fun processRefBlockOverlap(
        startPos: Position,
        endPos: Position,
        ranges: TreeMap<Position, Pair<Position, String>>,
        outputWriters: Map<String, VariantContextWriter>,
        refSeq: Map<String, NucSeqRecord>,
        vc: VariantContext
    ) {
        val subRanges = ranges.subMap(startPos, true, endPos, true)
        val floorEntry = ranges.floorEntry(startPos)

        // Build iterator — floor entry first if it spans into our region
        val iterator: Iterator<Map.Entry<Position, Pair<Position, String>>> = sequence {
            if (floorEntry != null &&
                floorEntry.value.first >= startPos &&
                floorEntry.key != startPos) {
                yield(floorEntry)
            }
            yieldAll(subRanges.entries.iterator())
        }.iterator()

        if (!iterator.hasNext()) return

        var currentStartPos = startPos
        var currentEntry = iterator.next()

        while (currentStartPos <= endPos) {
            val (rangeStart, pair) = currentEntry
            val (rangeEnd, targetSampleName) = pair

            if (currentStartPos < rangeStart) {
                currentStartPos = rangeStart
                continue
            }

            val outputWriter = outputWriters[targetSampleName] ?: break
            val refAllele = refSeq[vc.contig]!!.get(currentStartPos.position - 1).char

            if (endPos <= rangeEnd) {
                // Fully contained within the range, write out and break
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
                // Partially contained, need to resize and write out
                val newVc = buildRefBlock(
                    vc.contig,
                    currentStartPos.position,
                    rangeEnd.position,
                    "$refAllele",
                    targetSampleName
                )
                outputWriter.add(newVc)
                currentStartPos = Position(vc.contig, rangeEnd.position + 1)

                if (!iterator.hasNext()) break
                currentEntry = iterator.next()
            }
        }
    }

    /**
     * Function to process if there is a deletion
     */
    fun processDelOverlap(
        startPos: Position,
        endPos: Position,
        ranges: TreeMap<Position, Pair<Position, String>>,
        outputWriter: VariantContextWriter,
        vc: VariantContext
    ) {
        //Get out the region that this vc hits using startPos
        val entry = ranges.getEntry(startPos) ?: return

        if(endPos in entry.key..entry.value.first) {
            //This means its fully contained so we can just write out
            val newVc = changeSampleName(vc, entry.value.second)
            outputWriter.add(newVc)
        }
        else {
            //We need to resize the deletion to be up to the end of the entry
            val altAlleleString = vc.alternateAlleles.first().baseString
            val resizeLength = entry.value.first.position  - vc.start + 1
            val resizedRefSeq = vc.reference.baseString.substring(0 until resizeLength )
            val resizedAltSeq = if(altAlleleString.length < resizeLength) {
                altAlleleString
            }
            else {
                altAlleleString.substring(0 until resizeLength)
            }
            if((entry.value.first.position - vc.start) + 1 != resizedRefSeq.length) {
                println("*******************************")
                println("ERROR With sizes:\n" +
                        "${vc.contig}:${vc.start}-${vc.end} ${vc.reference.baseString.length}->${vc.alternateAlleles.first().baseString.length}\n" +
                        "${startPos} - ${entry.value.first.position}")
                println((entry.value.first.position - vc.start) + 1)
                println(resizedRefSeq.length)
            }
            val newDel = buildDel(vc.contig, vc.start, entry.value.first.position, resizedRefSeq, resizedAltSeq, entry.value.second )
            outputWriter.add(newDel)

        }

    }

    /**
     * Function to change the sample name
     */
    fun changeSampleName(vc: VariantContext, newSampleName: String): VariantContext {
        val builder = VariantContextBuilder(vc)
        val genotypes = vc.genotypes.map { genotype ->
            GenotypeBuilder(genotype)
                .alleles(listOf(genotype.alleles.first()))
                .name(newSampleName)
                .make()
        }
        builder.genotypes(genotypes)
        return builder.make()
    }


    /**
     * Recovers the source-sample key used to look a GVCF file up in the
     * recombination map (which is keyed by the BED source name, e.g. the base
     * assembly name "{base}"). Strips any recognized gVCF extension (longest
     * first so "g.vcf.gz" wins over "gz"/"vcf"), then reduces a mutated GVCF
     * file name back to its base sample: the substring before "__" if present
     * (mutate-assemblies writes "{base}__{donor}_mutated.g.vcf"), otherwise a
     * trailing "_mutated" is removed. Names without those markers (e.g. test
     * fixtures like "sampleC") are returned unchanged.
     */
    fun deriveSourceSampleKey(fileName: String): String {
        var base = fileName
        for (ext in Constants.GVCF_EXTENSIONS.sortedByDescending { it.length }) {
            val suffix = ".$ext"
            if (base.endsWith(suffix)) {
                base = base.removeSuffix(suffix)
                break
            }
        }
        return when {
            base.contains("__") -> base.substringBefore("__")
            base.endsWith("_mutated") -> base.removeSuffix("_mutated")
            else -> base
        }
    }

    /**
     * Function to build a new resized reference block
     */
    fun buildRefBlock(chrom: String, start: Int, end: Int, refAllele: String, sampleName: String): VariantContext {
        return VariantContextBuilder()
            .chr(chrom)
            .start(start.toLong())
            .stop(end.toLong())
            .attribute("END", end)
            .alleles(listOf(refAllele, "<NON_REF>"))
            .genotypes(
                listOf(
                    GenotypeBuilder(sampleName).alleles(listOf(Allele.create(refAllele,true))).make()
                )
            ).make()
    }

    /**
     * Function to build a resized deletion variant
     */
    fun buildDel(chrom: String, start:Int, end:Int, refAllele:String, altAllele: String, sampleName: String): VariantContext {

        return if(refAllele == altAllele) {
            //This is a weird edge case where we remove all but the first allele due to needing to resize the deletion
            //This causes it to effectively be a 1 bp refBlock and we can treat it as one.
            VariantContextBuilder()
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
        else {
            VariantContextBuilder()
                .chr(chrom)
                .start(start.toLong())
                .stop(end.toLong())
                .alleles(listOf(refAllele, altAllele,"<NON_REF>"))
                .genotypes(
                    listOf(
                        GenotypeBuilder(sampleName).alleles(listOf(Allele.create(altAllele,false))).make()
                    )
                ).make()
        }
    }
}