package net.maizegenetics.commands

import biokotlin.seq.NucSeq
import biokotlin.seq.NucSeqRecord
import com.google.common.collect.Range
import com.google.common.collect.RangeMap
import com.google.common.collect.TreeRangeMap
import htsjdk.variant.variantcontext.Allele
import htsjdk.variant.variantcontext.GenotypeBuilder
import htsjdk.variant.variantcontext.VariantContextBuilder
import htsjdk.variant.variantcontext.writer.VariantContextWriter
import htsjdk.variant.vcf.VCFFileReader
import net.maizegenetics.net.maizegenetics.commands.RecombineGvcfs
import net.maizegenetics.utils.Position
import net.maizegenetics.utils.SimpleVariant
import java.io.File
import kotlin.io.path.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.DefaultAsserter.assertEquals
import kotlin.test.DefaultAsserter.assertTrue
import kotlin.test.Test
import kotlin.test.assertFalse

class RecombineGvcfsTest {

    val homeDir = System.getProperty("user.home").replace('\\', '/')

    val outputDir = "$homeDir/temp/seq_sim/recombine_gvcf_test/"

    @BeforeTest
    fun setupTest() {
        // Runs before each test in common code
        File(outputDir).mkdirs()
    }

    @AfterTest
    fun teardownTest() {
        // Runs after each test in common code
        File(outputDir).deleteRecursively()
    }

    @Test
    fun testBuildRecombinationMap() {
        val bedDir = "./data/RecombineGvcfs/bed/"
        val recombineGvcfs = RecombineGvcfs()
        val (recombinationMap, targetNameList) = recombineGvcfs.buildRecombinationMap(Path(bedDir))
        //Check that the map has the correct number of samples
        assertEquals(
            "Recombination map should have 3 samples",
            3,
            recombinationMap.size
        )

        //Check to make sure that the recombination maps are correct
        checkRecombinationMapContents(recombinationMap, "sampleA", listOf("sampleX", "sampleY", "sampleZ"))
        checkRecombinationMapContents(recombinationMap, "sampleB", listOf("sampleY", "sampleZ", "sampleX"))
        checkRecombinationMapContents(recombinationMap, "sampleC", listOf("sampleZ", "sampleX", "sampleY"))


        //targetNameList should also have 3 samples
        assertEquals(
            "Target name list should have 3 samples",
            3,
            targetNameList.size
        )

        //Should have sampleX, sampleY, sampleZ
        assertTrue(
            "Target name list does not contain sampleX",
            targetNameList.contains("sampleX")
        )
        assertTrue(
            "Target name list does not contain sampleY",
            targetNameList.contains("sampleY")
        )
        assertTrue(
            "Target name list does not contain sampleZ",
            targetNameList.contains("sampleZ")
        )
    }

    private fun checkRecombinationMapContents(recombinationMap: Map<String, RangeMap<Position, String>>, sampleName: String, expectedTargetSamples: List<String>) {
        val sampleMap = recombinationMap[sampleName]
        require(sampleMap != null) { "Range map for $sampleName is null" }
        assertEquals(
            "${sampleName} should have 3 ranges",
            3,
            sampleMap.asMapOfRanges().size
        )
        //From 1 -10 should be sampleX, from 10 to 19 should be sampleY, from 20 to 29 should be sampleZ
        val range1 = sampleMap.getEntry(Position("chr1", 5))
        assertEquals(
            "${sampleName} first range not correct",
            Range.closed(Position("chr1", 1), Position("chr1", 10)),
            range1?.key
        )
        assertEquals(
            "${sampleName} first range target not correct",
            expectedTargetSamples[0],
            range1?.value
        )
        val range2 = sampleMap.getEntry(Position("chr1", 15))
        assertEquals(
            "${sampleName} second range not correct",
            Range.closed(Position("chr1", 11), Position("chr1", 20)),
            range2?.key
        )
        assertEquals(
            "${sampleName} second range target not correct",
            expectedTargetSamples[1],
            range2?.value
        )
        val range3 = sampleMap.getEntry(Position("chr1", 25))
        assertEquals(
            "${sampleName} third range not correct",
            Range.closed(Position("chr1", 21), Position("chr1", 30)),
            range3?.key
        )
        assertEquals(
            "${sampleName} third range target not correct",
            expectedTargetSamples[2],
            range3?.value
        )
    }


    @Test
    fun testResizeRecombinationMapsForIndels() {
        //Each of the units are tested in other functions.  Here we just need to test that the overall resizing works as expected
        val bedDir = "./data/RecombineGvcfs/bed/"
        val gvcfFile = "./data/RecombineGvcfs/gvcf/"
        val recombineGvcfs = RecombineGvcfs()
        val (recombinationMap, targetNameList) = recombineGvcfs.buildRecombinationMap(Path(bedDir))
        val resizedMap = recombineGvcfs.resizeRecombinationMapsForIndels(recombinationMap, Path(gvcfFile))

        //resized map should be the same except for the first range of sampleC which should be resized to 1-11 for target sampleZ
        //And the second range of sampleB which should be resized to 12-20 for target sampleZ
        //Check the parts that have not changed first
        //SampleA should be unchanged
        val sampleARangeMap = resizedMap["sampleA"]
        assertEquals(
            "SampleA should have 3 ranges",
            3,
            sampleARangeMap?.asMapOfRanges()?.size
        )
        assertEquals("SampleA matches original", recombinationMap["sampleA"], sampleARangeMap)

        //SamplesB and C should have one resized region each
        val sampleBRangeMap = resizedMap["sampleB"]
        assertEquals(
            "SampleB should have 3 ranges",
            3,
            sampleBRangeMap?.asMapOfRanges()?.size
        )
        val sampleBFirstRange = sampleBRangeMap?.getEntry(Position("chr1",5))
        assertEquals(
            "SampleB first range should be unchanged",
            Range.closed(Position("chr1",1), Position("chr1",10)),
            sampleBFirstRange?.key
        )
        assertEquals(
            "SampleB first range target should be unchanged",
            "sampleY",
            sampleBFirstRange?.value
        )
        val sampleBSecondRange = sampleBRangeMap?.getEntry(Position("chr1",15))
        assertEquals(
            "SampleB second range should be resized to 12-20",
            Range.closed(Position("chr1",12), Position("chr1",20)),
            sampleBSecondRange?.key
        )
        assertEquals(
            "SampleB second range target should be sampleZ",
            "sampleZ",
            sampleBSecondRange?.value
        )
        val sampleBThirdRange = sampleBRangeMap?.getEntry(Position("chr1",25))
        assertEquals(
            "SampleB third range should be unchanged",
            Range.closed(Position("chr1",21), Position("chr1",30)),
            sampleBThirdRange?.key
        )
        assertEquals(
            "SampleB third range target should be unchanged",
            "sampleX",
            sampleBThirdRange?.value
        )

        val sampleCRangeMap = resizedMap["sampleC"]
        assertEquals(
            "SampleC should have 3 ranges",
            3,
            sampleCRangeMap?.asMapOfRanges()?.size
        )
        val sampleCFirstRange = sampleCRangeMap?.getEntry(Position("chr1",5))
        assertEquals(
            "SampleC first range should be resized to 1-11",
            Range.closed(Position("chr1",1), Position("chr1",11)),
            sampleCFirstRange?.key
        )
        assertEquals(
            "SampleC first range target should be sampleZ",
            "sampleZ",
            sampleCFirstRange?.value
        )
        val sampleCSecondRange = sampleCRangeMap?.getEntry(Position("chr1",15))
        assertEquals(
            "SampleC second range has changed",
            Range.closed(Position("chr1",12), Position("chr1",20)),
            sampleCSecondRange?.key
        )
        assertEquals(
            "SampleC second range target should be unchanged",
            "sampleX",
            sampleCSecondRange?.value
        )
        val sampleCThirdRange = sampleCRangeMap?.getEntry(Position("chr1",25))
        assertEquals(
            "SampleC third range should be unchanged",
            Range.closed(Position("chr1",21), Position("chr1",30)),
            sampleCThirdRange?.key
        )
        assertEquals(
            "SampleC third range target should be unchanged",
            "sampleY",
            sampleCThirdRange?.value
        )
    }

    @Test
    fun testFindOverlappingIndelsInGvcf() {
        val bedDir = "./data/RecombineGvcfs/bed/"
        val gvcfFile = "./data/RecombineGvcfs/gvcf/"

        val recombineGvcfs = RecombineGvcfs()
        val (recombinationMap, targetNameList) = recombineGvcfs.buildRecombinationMap(Path(bedDir))

        //Test SampleB and Sample C.  SampleB does not have an overlapping indel so the list should be empty
        val sampleBIndels = recombineGvcfs.findOverlappingIndelsInGvcf("sampleB", File("$gvcfFile/sampleB.gvcf"),recombinationMap["sampleB"]!!)

        assertEquals(
            "SampleB should have 0 overlapping indels",
            0,
            sampleBIndels.size
        )

        //SampleC should have one overlapping indel at chr1:9-11 hitting position 10 in the recombination map
        val sampleCIndels = recombineGvcfs.findOverlappingIndelsInGvcf("sampleC", File("$gvcfFile/sampleC.gvcf"),recombinationMap["sampleC"]!!)
        assertEquals(
            "SampleC should have 1 overlapping indel",
            1,
            sampleCIndels.size
        )
        val indel = sampleCIndels[0]

        assertEquals(
            "Indel contig does not match",
            "chr1",
            indel.third.refStart.contig
        )
        assertEquals(
            "Indel start does not match",
            9,
            indel.third.refStart.position
        )
        assertEquals(
            "Indel end does not match",
            11,
            indel.third.refEnd.position
        )
        //check source and target sample names
        assertEquals(
            "Indel source sample name does not match",
            "sampleC",
            indel.first
        )
        assertEquals(
            "Indel target sample name does not match",
            "sampleZ",
            indel.second
        )
    }

    @Test
    fun testFindAllOverlappingIndels() {
        val bedDir = "./data/RecombineGvcfs/bed/"
        val gvcfDir = "./data/RecombineGvcfs/gvcf/"
        val recombineGvcfs = RecombineGvcfs()
        val (recombinationMap, targetNameList) = recombineGvcfs.buildRecombinationMap(Path(bedDir))
        val allIndels = recombineGvcfs.findAllOverlappingIndels(recombinationMap, Path(gvcfDir))
        //There should be only one indel from sampleC
        assertEquals(
            "There should be 1 overlapping indel in total",
            1,
            allIndels.size
        )
        val indel = allIndels[0]
        assertEquals(
            "Indel source sample name does not match",
            "sampleC",
            indel.first
        )
        assertEquals(
            "Indel target sample name does not match",
            "sampleZ",
            indel.second
        )
        assertEquals(
            "Indel contig does not match",
            "chr1",
            indel.third.refStart.contig
        )
        assertEquals(
            "Indel start does not match",
            9,
            indel.third.refStart.position
        )
        assertEquals(
            "Indel end does not match",
            11,
            indel.third.refEnd.position
        )

    }

    @Test
    fun testFlipRecombinationMap() {
        //This needs to take a recombination map Map<sampleName, RangeMap<Position,targetSampleName>> and flip it to Map<targetSampleName, RangeMap<Position,sampleName>>
        val recombinationMap = buildSimpleRecombinationMap()

        val recombineGvcfs = RecombineGvcfs()
        val flippedMap = recombineGvcfs.flipRecombinationMap(recombinationMap)
        //Now check that the flipped map is correct
        //Check TargetSampleA
        val targetSampleARangeMap = flippedMap["TargetSampleA"]
        assertEquals(
            "TargetSampleA should have 2 ranges",
            2,
            targetSampleARangeMap?.asMapOfRanges()?.size
        )
        val targetSampleARanges = targetSampleARangeMap?.asMapOfRanges()
        //Check that the ranges are correct
        val range1 = targetSampleARanges?.keys?.find { it.contains(Position("chr1", 150)) }
        assertEquals(
            "TargetSampleA should have range from Sample1",
            Range.closed(Position("chr1", 100), Position("chr1", 200)),
            range1
        )
        val range2 = targetSampleARanges?.keys?.find { it.contains(Position("chr1", 250)) }
        assertEquals(
            "TargetSampleA should have range from Sample2",
            Range.closed(Position("chr1", 201), Position("chr1", 300)),
            range2
        )
        //Check TargetSampleB
        val targetSampleBRangeMap = flippedMap["TargetSampleB"]
        assertEquals(
            "TargetSampleB should have 2 ranges",
            2,
            targetSampleBRangeMap?.asMapOfRanges()?.size
        )
        val targetSampleBRanges = targetSampleBRangeMap?.asMapOfRanges()
        //Check that the ranges are correct
        val range3 = targetSampleBRanges?.keys?.find { it.contains(Position("chr1", 150)) }
        assertEquals(
            "TargetSampleB should have range from Sample2",
            Range.closed(Position("chr1", 100), Position("chr1", 200)),
            range3
        )
        val range4 = targetSampleBRanges?.keys?.find { it.contains(Position("chr1", 250)) }
        assertEquals(
            "TargetSampleB should have range from Sample1",
            Range.closed(Position("chr1", 201), Position("chr1", 300)),
            range4
        )

        //Flip it back and see if the original map is recovered
        val reflippedMap = recombineGvcfs.flipRecombinationMap(flippedMap)
        assertEquals(
            "Reflipped map should be equal to original map",
            recombinationMap,
            reflippedMap
        )

    }


    @Test
    fun testResizeMaps() {
        val bedDir = "./data/RecombineGvcfs/bed/"
        val recombineGvcfs = RecombineGvcfs()
        val (recombinationMap, targetNameList) = recombineGvcfs.buildRecombinationMap(Path(bedDir))

        //Need to flip the map
        val flippedMap = recombineGvcfs.flipRecombinationMap(recombinationMap)
        val indelsForResizingEmpty = emptyList<Triple<String, String, SimpleVariant>>()

        val nonResizedMap = recombineGvcfs.resizeMaps(indelsForResizingEmpty, recombinationMap,flippedMap)
        //Check that the map is the same as the flipped map
        assertEquals(
            "No-resize map should be equal to flipped map",
            flippedMap,
            nonResizedMap
        )

        //Make an overlapping indel SampleC is the donor for TargetSampleZ at chr1:9-11
        //chr1	9	.	AAA	A	.	.	GT	1
        val indelToResize = Triple(
            "sampleC",
            "sampleZ",
            SimpleVariant(
                Position("chr1",9),
                Position("chr1",11),
                refAllele =  "AAA",
                altAllele = "A"
            )
        )

        val indelsForResizing = listOf(indelToResize)
        val zIndel1ResizedMap = recombineGvcfs.resizeMaps(indelsForResizing, recombinationMap,flippedMap)
        //Now check that the map has been resized correctly

        val targetSampleZRangeMap = zIndel1ResizedMap["sampleZ"]
        assertEquals(
            "sampleZ should have 3 ranges",
            3,
            targetSampleZRangeMap?.asMapOfRanges()?.size
        )
        val firstRegion = targetSampleZRangeMap?.getEntry(Position("chr1",5))
        assertEquals(
            "First region should be from 1-11",
            Range.closed(Position("chr1",1), Position("chr1",11)),
            firstRegion?.key
        )
        assertEquals(
            "First region should be from sampleC",
            "sampleC",
            firstRegion?.value
        )
        val secondRegion = targetSampleZRangeMap?.getEntry(Position("chr1",15))
        assertEquals(
            "Second region should be from 12-20",
            Range.closed(Position("chr1",12), Position("chr1",20)),
            secondRegion?.key
        )
        assertEquals(
            "Second region should be from sampleB",
            "sampleB",
            secondRegion?.value
        )
        val thirdRegion = targetSampleZRangeMap?.getEntry(Position("chr1",25))
        assertEquals(
            "Third region should be from 21-30",
            Range.closed(Position("chr1",21), Position("chr1",30)),
            thirdRegion?.key
        )
        assertEquals(
            "Third region should be from sampleA",
            "sampleA",
            thirdRegion?.value
        )
    }



    @Test
    fun testWriteResizedBedFiles() {
        val recombinationMap = buildSimpleRecombinationMap()

        val recombineGvcfs = RecombineGvcfs()

        recombineGvcfs.writeResizedBedFiles(recombinationMap, Path(outputDir))

        //Check that the files were created and have the correct content
        val targetSampleABedFile = File("$outputDir/Sample1_resized.bed")
        assertEquals("TargetSampleA.bed file was not created", true, targetSampleABedFile.isFile)
        val targetSampleAContent = targetSampleABedFile.readText().trim()
        val expectedTargetSampleAContent = "chr1\t99\t200\tTargetSampleA\nchr1\t200\t300\tTargetSampleB"
        assertEquals("TargetSampleA.bed content is incorrect", expectedTargetSampleAContent, targetSampleAContent)
        val targetSampleBBedFile = File("$outputDir/Sample2_resized.bed")
        assertEquals("TargetSampleB.bed file was not created", true, targetSampleBBedFile.isFile)
        val targetSampleBContent = targetSampleBBedFile.readText().trim()
        val expectedTargetSampleBContent = "chr1\t99\t200\tTargetSampleB\nchr1\t200\t300\tTargetSampleA"
        assertEquals("TargetSampleB.bed content is incorrect", expectedTargetSampleBContent, targetSampleBContent)
    }

    private fun buildSimpleRecombinationMap(): Map<String, RangeMap<Position, String>> {
        val recombinationMap = mutableMapOf<String, RangeMap<Position, String>>()
        //Build the recombination map here
        val sample1RangeMap = TreeRangeMap.create<Position, String>()
        sample1RangeMap.put(Range.closed(Position("chr1", 100), Position("chr1", 200)), "TargetSampleA")
        sample1RangeMap.put(Range.closed(Position("chr1", 201), Position("chr1", 300)), "TargetSampleB")
        recombinationMap["Sample1"] = sample1RangeMap
        val sample2RangeMap = TreeRangeMap.create<Position, String>()
        sample2RangeMap.put(Range.closed(Position("chr1", 100), Position("chr1", 200)), "TargetSampleB")
        sample2RangeMap.put(Range.closed(Position("chr1", 201), Position("chr1", 300)), "TargetSampleA")
        recombinationMap["Sample2"] = sample2RangeMap
        return recombinationMap
    }


    @Test
    fun testBuildOutputWriterMap() {
        val sampleNames = listOf("Sample1", "Sample2", "Sample3")
        val recombineGvcfs = RecombineGvcfs()
        val writerMap = recombineGvcfs.buildOutputWriterMap(sampleNames, Path(outputDir))
        //Check that the map has the correct number of writers
        assertEquals(
            "Writer map should have 3 writers",
            3,
            writerMap.size
        )
        //Check that the writers are correctly named
        sampleNames.forEach { sampleName ->
            checkSampleNameToOutputFile(sampleName, writerMap)
        }
    }

    private fun checkSampleNameToOutputFile(
        sampleName: String,
        writerMap: Map<String, VariantContextWriter>
    ) {
        assertTrue(
            "Writer map should contain writer for $sampleName",
            writerMap.containsKey(sampleName)
        )
        //close out the writers
        writerMap[sampleName]?.close()
        //Open up the file and check that the sample name is right
        val outputFile = File("$outputDir/${sampleName}_recombined.gvcf")
        assertEquals(
            "Output file for $sampleName was not created",
            true,
            outputFile.isFile
        )

        val vcfReader = VCFFileReader(outputFile,false)
        val headerSampleNames = vcfReader.fileHeader.sampleNamesInOrder
        assertEquals(
            "Output VCF for $sampleName should have 1 sample",
            1,
            headerSampleNames.size
        )
        assertEquals(
            "Output VCF for $sampleName has incorrect sample name",
            sampleName,
            headerSampleNames.first()
        )
        vcfReader.close()
    }


    @Test
    fun testProcessSingleGVCFFile() {
        //gvcfReader: VCFReader,
        //        ranges: RangeMap<Position,String>,
        //        outputWriters: Map<String, VariantContextWriter>,
        //        refSeq :Map<String, NucSeqRecord>

        //Lets test SampleC as it has an overlapping index, a standard indel and a SNP so we should hit all the edge cases we handle
        val gvcfFile = "./data/RecombineGvcfs/gvcf/sampleC.gvcf"
        val bedDir = "./data/RecombineGvcfs/bed/"
        val recombineGvcfs = RecombineGvcfs()
        val (recombinationMap, targetNameList) = recombineGvcfs.buildRecombinationMap(Path(bedDir))
        //Need to resize the recombination map first
        val resizedRecombinationMap = recombineGvcfs.resizeRecombinationMapsForIndels(recombinationMap, Path("./data/RecombineGvcfs/gvcf/"))
        val sampleCRanges = resizedRecombinationMap["sampleC"]!!
        val outputWriters = recombineGvcfs.buildOutputWriterMap(targetNameList, Path(outputDir))
        val gvcfReader = VCFFileReader(File(gvcfFile), false)
        val refString = "A".repeat(30) //Length should be 30
        val refSeq = mapOf(Pair("chr1",NucSeqRecord(NucSeq(refString), "chr1")))
        recombineGvcfs.processSingleGVCFFile(
            gvcfReader,
            sampleCRanges,
            outputWriters,
            refSeq
        )
        //Close out the writers
        outputWriters.values.forEach { it.close() }
        //Now check that the output files have the correct variants
        //We will have 3 output files
        //targetSampleZ whould have 2 variants: refBlock from 1-8 and Indel at 9 - 11
        val targetSampleZFile = File("$outputDir/sampleZ_recombined.gvcf")
        assertEquals("sampleZ output file was not created", true, targetSampleZFile.isFile)
        val targetSampleZReader = VCFFileReader(targetSampleZFile,false)
        val targetSampleZVariants = targetSampleZReader.iterator().toList()
        assertEquals("sampleZ should have 2 variants", 2, targetSampleZVariants.size)
        val firstVariantZ = targetSampleZVariants[0]
        assertEquals("sampleZ first variant contig does not match", "chr1", firstVariantZ.contig)
        assertEquals("sampleZ first variant start does not match", 1, firstVariantZ.start)
        assertEquals("sampleZ first variant end does not match", 8, firstVariantZ.end)
        val secondVariantZ = targetSampleZVariants[1]
        assertEquals("sampleZ second variant contig does not match", "chr1", secondVariantZ.contig)
        assertEquals("sampleZ second variant start does not match", 9, secondVariantZ.start)
        assertEquals("sampleZ second variant end does not match", 11, secondVariantZ.end)
        //check ref and alt alleles for the indel
        assertEquals("sampleZ second variant ref allele does not match", "AAA", secondVariantZ.reference.baseString)
        assertEquals("sampleZ second variant alt allele does not match", "A", secondVariantZ.alternateAlleles[0].baseString)
        targetSampleZReader.close()
        //targetSampleX should have 3 variant: Deletion at 17-19 and SNP at 20 and a refBlock from 12-16
        val targetSampleXFile = File("$outputDir/sampleX_recombined.gvcf")
        assertEquals("sampleX output file was not created", true, targetSampleXFile.isFile)
        val targetSampleXReader = VCFFileReader(targetSampleXFile,false)
        val targetSampleXVariants = targetSampleXReader.iterator().toList()
        assertEquals("sampleX should have 3 variants", 3, targetSampleXVariants.size)
        val firstVariantX = targetSampleXVariants[0]
        assertEquals("sampleX first variant contig does not match", "chr1", firstVariantX.contig)
        assertEquals("sampleX first variant start does not match", 12, firstVariantX.start)
        assertEquals("sampleX first variant end does not match", 16, firstVariantX.end)
        val secondVariantX = targetSampleXVariants[1]
        assertEquals("sampleX second variant contig does not match", "chr1", secondVariantX.contig)
        assertEquals("sampleX second variant start does not match", 17, secondVariantX.start)
        assertEquals("sampleX second variant end does not match", 19, secondVariantX.end)
        //check ref and alt alleles for the deletion
        assertEquals("sampleX second variant ref allele does not match", "AAA", secondVariantX.reference.baseString)
        assertEquals("sampleX second variant alt allele does not match", "A", secondVariantX.alternateAlleles[0].baseString)
        val thirdVariantX = targetSampleXVariants[2]
        assertEquals("sampleX third variant contig does not match", "chr1", thirdVariantX.contig)
        assertEquals("sampleX third variant start does not match", 20, thirdVariantX.start)
        assertEquals("sampleX third variant end does not match", 20, thirdVariantX.end)
        //check ref and alt alleles for the SNP
        assertEquals("sampleX third variant ref allele does not match", "A", thirdVariantX.reference.baseString)
        assertEquals("sampleX third variant alt allele does not match", "T", thirdVariantX.alternateAlleles[0].baseString)
        targetSampleXReader.close()
        //targetSampleY should have 1 variant: refBlock from 21-30
        val targetSampleYFile = File("$outputDir/sampleY_recombined.gvcf")
        assertEquals("sampleY output file was not created", true, targetSampleYFile.isFile)
        val targetSampleYReader = VCFFileReader(targetSampleYFile,false)
        val targetSampleYVariants = targetSampleYReader.iterator().toList()
        assertEquals("sampleY should have 1 variant", 1, targetSampleYVariants.size)
        val firstVariantY = targetSampleYVariants[0]
        assertEquals("sampleY variant contig does not match", "chr1", firstVariantY.contig)
        assertEquals("sampleY variant start does not match", 21, firstVariantY.start)
        assertEquals("sampleY variant end does not match", 30, firstVariantY.end)
        targetSampleYReader.close()
    }

    @Test
    fun testProcessRefBlockOverlap() {
        val recombineGvcfs = RecombineGvcfs()
        val outputWriters = recombineGvcfs.buildOutputWriterMap(listOf("sampleX", "sampleY", "sampleZ"), Path(outputDir))

        //Build a rangeMap -> Target map
        val rangeMap = TreeRangeMap.create<Position, String>()
        rangeMap.put(Range.closed(Position("chr1", 1), Position("chr1", 10)), "sampleX")
        rangeMap.put(Range.closed(Position("chr1",11), Position("chr1", 20)), "sampleY")
        rangeMap.put(Range.closed(Position("chr1",21), Position("chr1", 30)), "sampleZ")

        //Build a refBlock from 5-25
        val refBlock = recombineGvcfs.buildRefBlock("chr1", 5, 25, "A", "sampleA")

        val refString = "A".repeat(30) //Length should be 30
        val refSeq = mapOf(Pair("chr1",NucSeq(refString)))

        recombineGvcfs.processRefBlockOverlap(Position("chr1",5),Position("chr1",25),rangeMap,outputWriters, refSeq , refBlock )

        //Close out the writers
        outputWriters.values.forEach { it.close() }

        //Now check that the output files have the correct refBlocks
        //We will have 3 output files each with one variant in them.
        //sampleX should have a refBlock from 5-10
        val sampleXFile = File("$outputDir/sampleX_recombined.gvcf")
        assertEquals("sampleX output file was not created", true, sampleXFile.isFile)
        val sampleXReader = VCFFileReader(sampleXFile,false)
        val sampleXVariants = sampleXReader.iterator().toList()
        assertEquals("sampleX should have 1 variant", 1, sampleXVariants.size)
        val sampleXVariant = sampleXVariants[0]
        assertEquals("sampleX variant contig does not match", "chr1", sampleXVariant.contig)
        assertEquals("sampleX variant start does not match", 5, sampleXVariant.start)
        assertEquals("sampleX variant end does not match", 10, sampleXVariant.end)
        sampleXReader.close()
        //sampleY should have a refBlock from 11-20
        val sampleYFile = File("$outputDir/sampleY_recombined.gvcf")
        assertEquals("sampleY output file was not created", true, sampleYFile.isFile)
        val sampleYReader = VCFFileReader(sampleYFile,false)
        val sampleYVariants = sampleYReader.iterator().toList()
        assertEquals("sampleY should have 1 variant", 1, sampleYVariants.size)
        val sampleYVariant = sampleYVariants[0]
        assertEquals("sampleY variant contig does not match", "chr1", sampleYVariant.contig)
        assertEquals("sampleY variant start does not match", 11, sampleYVariant.start)
        assertEquals("sampleY variant end does not match", 20, sampleYVariant.end)
        sampleYReader.close()
        //sampleZ should have a refBlock from 21-25
        val sampleZFile = File("$outputDir/sampleZ_recombined.gvcf")
        assertEquals("sampleZ output file was not created", true, sampleZFile.isFile)
        val sampleZReader = VCFFileReader(sampleZFile,false)
        val sampleZVariants = sampleZReader.iterator().toList()
        assertEquals("sampleZ should have 1 variant", 1, sampleZVariants.size)
        val sampleZVariant = sampleZVariants[0]
        assertEquals("sampleZ variant contig does not match", "chr1", sampleZVariant.contig)
        assertEquals("sampleZ variant start does not match", 21, sampleZVariant.start)
        assertEquals("sampleZ variant end does not match", 25, sampleZVariant.end)
        sampleZReader.close()
    }

    @Test
    fun testChangeSampleName() {

        val recombineGvcfs = RecombineGvcfs()

        val originalVariantContext = VariantContextBuilder()
            .chr("1")
            .start(100)
            .stop(100)
            .id("rs123")
            .alleles(listOf(Allele.REF_A, Allele.ALT_C))
            .genotypes(
                listOf(
                    GenotypeBuilder("Sample1").alleles(listOf(Allele.REF_A, Allele.REF_A)).make()
                )
            )
            .make()

        val newSampleName = "NewSample"
        val renamed = recombineGvcfs.changeSampleName(originalVariantContext, newSampleName)

        assertEquals("SampleName was not updated", newSampleName, renamed.genotypes.sampleNames.first())
        assertEquals(
            "Alleles were not preserved",
            originalVariantContext.genotypes.first().alleles,
            renamed.genotypes.first().alleles
        )
        //check that the rest of the variant matches and that Sample1 does not exist
        assertEquals("Contigs do not match", originalVariantContext.contig, renamed.contig)
        assertEquals("Start positions do not match", originalVariantContext.start, renamed.start)
        assertEquals("Stop positions do not match", originalVariantContext.end, renamed.end)
        assertEquals("IDs do not match", originalVariantContext.id, renamed.id)
        assertEquals("Alleles do not match", originalVariantContext.alleles, renamed.alleles)
        assertFalse(renamed.genotypes.sampleNames.contains("Sample1"), "Old sample name still exists")
    }

    @Test
    fun testBuildRefBlock() {
        //Build a sample refBlock
        val recombineGvcfs = RecombineGvcfs()
        val refBlock = recombineGvcfs.buildRefBlock("chr1", 100, 200, "A","SampleA")
        assertEquals("Contig does not match","chr1", refBlock.contig)
        assertEquals("Start position does not match",100, refBlock.start)
        assertEquals("End position does not match", 200, refBlock.end)
        assertEquals("RefBlock should have 2 alleles", 2, refBlock.alleles.size)
        assertEquals("Ref allele does not match", Allele.REF_A, refBlock.alleles[0])
        assertEquals("Alt allele does not match",Allele.create("<NON_REF>",false), refBlock.alleles[1])
        assertEquals("There should be one sample in the refBlock", 1, refBlock.genotypes.sampleNames.size)
        assertEquals("Sample name does not match","SampleA", refBlock.genotypes.sampleNames.first())
        val genotype = refBlock.genotypes.first()
        assertEquals("Genotype should have 1 alleles", 1, genotype.alleles.size)
        assertEquals("Genotype ref allele does not match", Allele.REF_A, genotype.alleles[0])
    }
}