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
import net.maizegenetics.utils.Position
import net.maizegenetics.utils.SimpleVariant
import java.io.File
import java.util.TreeMap
import kotlin.io.path.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.DefaultAsserter.assertEquals
import kotlin.test.DefaultAsserter.assertTrue
import kotlin.test.Test
import kotlin.test.assertFalse

class RecombineGvcfsTest {

    val homeDir = System.getProperty("user.home").replace('\\', '/')

    val outputGvcfDir = "$homeDir/temp/seq_sim/recombine_gvcf_test/gvcf/"
    val outputBedDir = "$homeDir/temp/seq_sim/recombine_gvcf_test/bed/"

    @BeforeTest
    fun setupTest() {
        // Runs before each test in common code
        File(outputGvcfDir).mkdirs()
        File(outputBedDir).mkdirs()
    }

    @AfterTest
    fun teardownTest() {
        // Runs after each test in common code
        File(outputGvcfDir).deleteRecursively()
        File(outputBedDir).deleteRecursively()
    }

    @Test
    fun testRecombineGvcfs() {
        //recombineGvcfs(inputBedDir: Path, inputGvcfDir: Path, refFile: Path, outputDir: Path)
        val bedDir = "./data/RecombineGvcfs/bed/"
        val gvcfFile = "./data/RecombineGvcfs/gvcf/"
        val refFile = "./data/RecombineGvcfs/ref.fa"
        val recombineGvcfs = RecombineGvcfs()
        recombineGvcfs.recombineGvcfs(
            Path(bedDir),
            Path(gvcfFile),
            Path(refFile),
            Path(outputGvcfDir)
        )
        //Check that the output files were created
        val outputFiles = File(outputGvcfDir).listFiles().filter { it.name.endsWith(".gvcf") }
        assertEquals(
            "There should be 3 output gvcf files",
            3,
            outputFiles.size
        )
        checkAllResults(outputGvcfDir)
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

    private fun checkRecombinationMapContents(recombinationMap: Map<String, TreeMap<Position, Pair<Position, String>>>, sampleName: String, expectedTargetSamples: List<String>) {
        val sampleMap = recombinationMap[sampleName]
        require(sampleMap != null) { "Range map for $sampleName is null" }
        assertEquals(
            "${sampleName} should have 3 ranges",
            3,
            sampleMap.size
        )
        //From 1 -10 should be sampleX, from 10 to 19 should be sampleY, from 20 to 29 should be sampleZ
        val range1 = sampleMap.getEntry(Position("chr1", 5))
        assertEquals(
            "${sampleName} first range start not correct",
            Position("chr1", 1),
            range1?.key
        )
        assertEquals(
            "${sampleName} first range end and target not correct",
            Pair(Position("chr1", 10),expectedTargetSamples[0]),
            range1?.value
        )
        val range2 = sampleMap.getEntry(Position("chr1", 15))
        assertEquals(
            "${sampleName} second range start not correct",
            Position("chr1", 11),
            range2?.key
        )
        assertEquals(
            "${sampleName} second range end and target not correct",
            Pair( Position("chr1", 20), expectedTargetSamples[1]),
            range2?.value
        )
        val range3 = sampleMap.getEntry(Position("chr1", 25))
        assertEquals(
            "${sampleName} third range start not correct",
            Position("chr1", 21),
            range3?.key
        )
        assertEquals(
            "${sampleName} third range end and target not correct",
            Pair(Position("chr1", 30), expectedTargetSamples[2]),
            range3?.value
        )
    }


    @Test
    fun testBuildOutputWriterMap() {
        val sampleNames = listOf("Sample1", "Sample2", "Sample3")
        val recombineGvcfs = RecombineGvcfs()
        val writerMap = recombineGvcfs.buildOutputWriterMap(sampleNames, Path(outputGvcfDir))
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
        val outputFile = File("$outputGvcfDir/${sampleName}-recombined.gvcf")
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
            "${sampleName}-recombined",
            headerSampleNames.first()
        )
        vcfReader.close()
    }

    @Test
    fun testProcessGvcfsAndWrite(){
        val gvcfDir = "./data/RecombineGvcfs/gvcf/"
        val bedDir = "./data/RecombineGvcfs/bed/"
        val recombineGvcfs = RecombineGvcfs()
        val (recombinationMap, targetNameList) = recombineGvcfs.buildRecombinationMap(Path(bedDir))
        //Need to resize the recombination map first
        val outputWriters = recombineGvcfs.buildOutputWriterMap(targetNameList, Path(outputGvcfDir))
        val refString = "A".repeat(30) //Length should be 30
        val refSeq = mapOf(Pair("chr1",NucSeqRecord(NucSeq(refString), "chr1")))
        recombineGvcfs.processGvcfsAndWrite(
            recombinationMap,
            Path(gvcfDir),
            outputWriters,
            refSeq
        )
        //Close out the writers
        outputWriters.values.forEach { it.close() }
        //Now check that the output files were created
        checkAllResults(outputGvcfDir)
    }

    fun checkAllResults(outputDir: String) {
        val expectedSamples = listOf("sampleX", "sampleY", "sampleZ")
        //Check sampleX's output
        //load in sampleX-recombined.gvcf and check that it has the expected variants but the variants are not sorted so we need to sort by start position
        //It will have A refBlock from 1-10, A RefBlock from 12-16, a Deletion at 17-19 and a SNP at 20 then A refBlock from 21-25 a SNP at 15 and a refBlock from 26-30
        val sampleXFile = File("$outputDir/sampleX-recombined.gvcf")
        assertEquals("sampleX output file was not created", true, sampleXFile.isFile)
        val sampleXReader = VCFFileReader(sampleXFile,false)
        val sampleXVariants = sampleXReader.iterator().toList().sortedBy { it.start }
        assertEquals("sampleX should have 7 variants", 7, sampleXVariants.size)
        //Check first variant
        val firstVariantX = sampleXVariants[0]
        assertEquals("sampleX first variant contig does not match", "chr1", firstVariantX.contig)
        assertEquals("sampleX first variant start does not match", 1, firstVariantX.start)
        assertEquals("sampleX first variant end does not match", 10, firstVariantX.end)
        //Check second variant
        val secondVariantX = sampleXVariants[1]
        assertEquals("sampleX second variant contig does not match", "chr1", secondVariantX.contig)
        assertEquals("sampleX second variant start does not match", 12, secondVariantX.start)
        assertEquals("sampleX second variant end does not match",  16, secondVariantX.end)
        //Check third variant
        val thirdVariantX = sampleXVariants[2]
        assertEquals("sampleX third variant contig does not match", "chr1", thirdVariantX.contig)
        assertEquals("sampleX third variant start does not match", 17 , thirdVariantX.start)
        assertEquals("sampleX third variant end does not match", 19 , thirdVariantX.end)
        //Check fourth variant
        val fourthVariantX = sampleXVariants[3]
        assertEquals("sampleX fourth variant contig does not match", "chr1", fourthVariantX.contig)
        assertEquals("sampleX fourth variant start does not match", 20 , fourthVariantX.start)
        assertEquals("sampleX fourth variant end does not match", 20 , fourthVariantX.end)
        //Check fifth variant
        val fifthVariantX = sampleXVariants[4]
        assertEquals("sampleX fifth variant contig does not match", "chr1", fifthVariantX.contig)
        assertEquals("sampleX fifth variant start does not match", 21 , fifthVariantX.start)
        assertEquals("sampleX fifth variant end does not match", 24 , fifthVariantX.end)
        //Check sixth variant
        val sixthVariantX = sampleXVariants[5]
        assertEquals("sampleX sixth variant contig does not match", "chr1", sixthVariantX.contig)
        assertEquals("sampleX sixth variant start does not match", 25 , sixthVariantX.start)
        assertEquals("sampleX sixth variant end does not match", 25 , sixthVariantX.end)
        //Check seventh variant
        val seventhVariantX = sampleXVariants[6]
        assertEquals("sampleX sixth variant contig does not match", "chr1", seventhVariantX.contig)
        assertEquals("sampleX sixth variant start does not match", 26 , seventhVariantX.start)
        assertEquals("sampleX sixth variant end does not match", 30 , seventhVariantX.end)
        sampleXReader.close()


        //Check sampleY's output
        //There is a Refblock from 1-4, a SNP at 5, A ref block from 6-10, then ref block from 11-20 and ref block from 21-30
        val sampleYFile = File("$outputDir/sampleY-recombined.gvcf")
        assertEquals("sampleY output file was not created", true, sampleYFile.isFile)
        val sampleYReader = VCFFileReader(sampleYFile,false)
        val sampleYVariants = sampleYReader.iterator().toList().sortedBy { it.start }
        assertEquals("sampleY should have 5 variants", 5, sampleYVariants.size)
        //Check first variant
        val firstVariantY = sampleYVariants[0]
        assertEquals("sampleY first variant contig does not match", "chr1", firstVariantY.contig)
        assertEquals("sampleY first variant start does not match", 1, firstVariantY.start)
        assertEquals("sampleY first variant end does not match", 4, firstVariantY.end)
        //Check second variant
        val secondVariantY = sampleYVariants[1]
        assertEquals("sampleY second variant contig does not match", "chr1", secondVariantY.contig)
        assertEquals("sampleY second variant start does not match", 5, secondVariantY.start)
        assertEquals("sampleY second variant end does not match", 5, secondVariantY.end)
        //Check third variant
        val thirdVariantY = sampleYVariants[2]
        assertEquals("sampleY third variant contig does not match", "chr1", thirdVariantY.contig)
        assertEquals("sampleY third variant start does not match", 6 , thirdVariantY.start)
        assertEquals("sampleY third variant end does not match", 10 , thirdVariantY.end)
        //Check fourth variant
        val fourthVariantY = sampleYVariants[3]
        assertEquals("sampleY fourth variant contig does not match", "chr1", fourthVariantY.contig)
        assertEquals("sampleY fourth variant start does not match", 11 , fourthVariantY.start)
        assertEquals("sampleY fourth variant end does not match", 20 , fourthVariantY.end)
        //Check fifth variant
        val fifthVariantY = sampleYVariants[4]
        assertEquals("sampleY fifth variant contig does not match", "chr1", fifthVariantY.contig)
        assertEquals("sampleY fifth variant start does not match", 21 , fifthVariantY.start)
        assertEquals("sampleY fifth variant end does not match", 30 , fifthVariantY.end)
        sampleYReader.close()

        //Check sampleZ's output
        //There is a ref block from 1-8, an indel at 9-11, ref block from 12-14, SNP at 15, ref block from 15-20, ref block from 21-30
        val sampleZFile = File("$outputDir/sampleZ-recombined.gvcf")
        assertEquals("sampleZ output file was not created", true, sampleZFile.isFile)
        val sampleZReader = VCFFileReader(sampleZFile,false)
        val sampleZVariants = sampleZReader.iterator().toList().sortedBy { it.start }
        assertEquals("sampleZ should have 6 variants", 6, sampleZVariants.size)
        //Check first variant
        val firstVariantZ = sampleZVariants[0]
        assertEquals("sampleZ first variant contig does not match", "chr1", firstVariantZ.contig)
        assertEquals("sampleZ first variant start does not match", 1, firstVariantZ.start)
        assertEquals("sampleZ first variant end does not match", 8, firstVariantZ.end)
        //Check second variant
        val secondVariantZ = sampleZVariants[1]
        assertEquals("sampleZ second variant contig does not match", "chr1", secondVariantZ.contig)
        assertEquals("sampleZ second variant start does not match", 9, secondVariantZ.start)
        assertEquals("sampleZ second variant end does not match", 10, secondVariantZ.end) //Resized down due to del
        //Check ref and alt alleles for the indel
        assertEquals("sampleZ second variant ref allele does not match", "AA", secondVariantZ.reference.baseString) //Resized down due to del
        assertEquals("sampleZ second variant alt allele does not match", "A", secondVariantZ.alternateAlleles[0].baseString)
        //Check third variant
        val thirdVariantZ = sampleZVariants[2]
        assertEquals("sampleZ third variant contig does not match", "chr1", thirdVariantZ.contig)
        assertEquals("sampleZ third variant start does not match", 11 , thirdVariantZ.start) //Due to resize in sampleC this can start at original spot
        assertEquals("sampleZ third variant end does not match", 14 , thirdVariantZ.end)
        //Check fourth variant
        val fourthVariantZ = sampleZVariants[3]
        assertEquals("sampleZ fourth variant contig does not match", "chr1", fourthVariantZ.contig)
        assertEquals("sampleZ fourth variant start does not match", 15 , fourthVariantZ.start)
        assertEquals("sampleZ fourth variant end does not match", 15 , fourthVariantZ.end)
        //Check fifth variant
        val fifthVariantZ = sampleZVariants[4]
        assertEquals("sampleZ fifth variant contig does not match", "chr1", fifthVariantZ.contig)
        assertEquals("sampleZ fifth variant start does not match", 16 , fifthVariantZ.start)
        assertEquals("sampleZ fifth variant end does not match", 20 , fifthVariantZ.end)
        //Check sixth variant
        val sixthVariantZ = sampleZVariants[5]
        assertEquals("sampleZ sixth variant contig does not match", "chr1", sixthVariantZ.contig)
        assertEquals("sampleZ sixth variant start does not match", 21 , sixthVariantZ.start)
        assertEquals("sampleZ sixth variant end does not match", 30 , sixthVariantZ.end)
        sampleZReader.close()
    }


    @Test
    fun testProcessSingleGVCFFile() {
        //Lets test SampleC as it has an overlapping index, a standard indel and a SNP so we should hit all the edge cases we handle
        val gvcfFile = "./data/RecombineGvcfs/gvcf/sampleC.gvcf"
        val bedDir = "./data/RecombineGvcfs/bed/"
        val recombineGvcfs = RecombineGvcfs()
        val (recombinationMap, targetNameList) = recombineGvcfs.buildRecombinationMap(Path(bedDir))

        val sampleCRanges = recombinationMap["sampleC"]!!
        val outputWriters = recombineGvcfs.buildOutputWriterMap(targetNameList, Path(outputGvcfDir))
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
        val targetSampleZFile = File("$outputGvcfDir/sampleZ-recombined.gvcf")
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
        assertEquals("sampleZ second variant end does not match", 10, secondVariantZ.end)
        //check ref and alt alleles for the indel
        assertEquals("sampleZ second variant ref allele does not match", "AA", secondVariantZ.reference.baseString)
        assertEquals("sampleZ second variant alt allele does not match", "A", secondVariantZ.alternateAlleles[0].baseString)
        targetSampleZReader.close()
        //targetSampleX should have 3 variant: Deletion at 17-19 and SNP at 20 and a refBlock from 12-16
        val targetSampleXFile = File("$outputGvcfDir/sampleX-recombined.gvcf")
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
        val targetSampleYFile = File("$outputGvcfDir/sampleY-recombined.gvcf")
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
    fun testProcessDelOverlap() {
        val recombineGvcfs = RecombineGvcfs()
        val outputWriters = recombineGvcfs.buildOutputWriterMap(listOf("sampleX", "sampleY", "sampleZ"), Path(outputGvcfDir))

        //Build a rangeMap -> Target map
        val rangeMap = TreeMap<Position, Pair<Position,String>>()
        rangeMap[Position("chr1", 1)] = Pair(Position("chr1", 10), "sampleX")
        rangeMap[Position("chr1", 11)] = Pair(Position("chr1", 20), "sampleY")
        rangeMap[Position("chr1", 21)] = Pair(Position("chr1", 30), "sampleZ")

        //Make a deletion spanning positions 9-14 then feed it in.  We should get a new variant context that goes in sampleX but not sampleY
        val outputVariantContext = recombineGvcfs.buildDel("chr1", 9, 14, "AAAAAA", "A", "inputA")

        recombineGvcfs.processDelOverlap(Position("chr1", 9), Position("chr1", 14), rangeMap, outputWriters["sampleX"]!!, outputVariantContext)

        //Loop through the writers and close them out
        for(writer in outputWriters.values) {
            writer.close()
        }

        //Check the output files
        //Check that we have one record in sampleX
        val sampleXVcfReader = VCFFileReader(File("${outputGvcfDir}/sampleX-recombined.gvcf"),false)
        val sampleXVariants = sampleXVcfReader.iterator().toList()
        assertEquals("sampleX should have 1 variant", 1, sampleXVariants.size)
        val sampleXVariant = sampleXVariants[0]
        assertEquals("sampleX variant contig does not match", "chr1", sampleXVariant.contig)
        assertEquals("sampleX variant start does not match", 9, sampleXVariant.start)
        assertEquals("sampleX variant end does not match", 10, sampleXVariant.end)
        assertEquals("sampleX variant ref allele does not match", "AA", sampleXVariant.reference.baseString)
        assertEquals("sampleX variant alt allele does not match", "A", sampleXVariant.alternateAlleles[0].baseString)
        sampleXVcfReader.close()

        //Check sampleY has nothing
        val sampleYVcfReader = VCFFileReader(File("${outputGvcfDir}/sampleY-recombined.gvcf"), false)
        val sampleYVariants = sampleYVcfReader.iterator().toList()
        assertEquals("sampleY should have 0 variant", 0, sampleYVariants.size)
        sampleYVcfReader.close()

    }
    
    @Test
    fun testProcessRefBlockOverlap() {
        val recombineGvcfs = RecombineGvcfs()
        val outputWriters = recombineGvcfs.buildOutputWriterMap(listOf("sampleX", "sampleY", "sampleZ"), Path(outputGvcfDir))

        //Build a rangeMap -> Target map
        val rangeMap = TreeMap<Position, Pair<Position,String>>()
        rangeMap[Position("chr1", 1)] = Pair(Position("chr1", 10), "sampleX")
        rangeMap[Position("chr1", 11)] = Pair(Position("chr1", 20), "sampleY")
        rangeMap[Position("chr1", 21)] = Pair(Position("chr1", 30), "sampleZ")

        //Build a refBlock from 5-25
        val refBlock = recombineGvcfs.buildRefBlock("chr1", 5, 25, "A", "sampleA")

        val refString = "A".repeat(30) //Length should be 30
        val refSeq = mapOf(Pair("chr1",NucSeqRecord(NucSeq(refString), "chr1")))

        recombineGvcfs.processRefBlockOverlap(Position("chr1",5),Position("chr1",25),rangeMap,outputWriters, refSeq , refBlock )

        //Close out the writers
        outputWriters.values.forEach { it.close() }

        //Now check that the output files have the correct refBlocks
        //We will have 3 output files each with one variant in them.
        //sampleX should have a refBlock from 5-10
        val sampleXFile = File("$outputGvcfDir/sampleX-recombined.gvcf")
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
        val sampleYFile = File("$outputGvcfDir/sampleY-recombined.gvcf")
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
        val sampleZFile = File("$outputGvcfDir/sampleZ-recombined.gvcf")
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
                    GenotypeBuilder("Sample1").alleles(listOf(Allele.REF_A)).make()
                )
            )
            .make()

        val newSampleName = "NewSample"
        val renamed = recombineGvcfs.changeSampleName(originalVariantContext, newSampleName)

        assertEquals("SampleName was not updated", newSampleName, renamed.genotypes.sampleNames.first())
        assertEquals(
            "Alleles were not preserved",
            originalVariantContext.genotypes.first().alleles, //Doing haploid outputs now
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
    fun testDeriveSourceSampleKey() {
        val recombineGvcfs = RecombineGvcfs()

        // Batch mutate-assemblies output: "{base}__{donor}_mutated.g.vcf"
        // (and its compressed variant) should reduce to the base sample name.
        assertEquals(
            "Batch mutated name should reduce to base sample",
            "B73",
            recombineGvcfs.deriveSourceSampleKey("B73__Mo17_subsampled_mutated.g.vcf")
        )
        assertEquals(
            "Compressed batch mutated name should reduce to base sample",
            "B73",
            recombineGvcfs.deriveSourceSampleKey("B73__Mo17_subsampled_mutated.g.vcf.gz")
        )
        // A '__' marker takes precedence regardless of the trailing token.
        assertEquals(
            "Name with '__' should take substring before it",
            "B73",
            recombineGvcfs.deriveSourceSampleKey("B73__W22.gvcf")
        )

        // Single-pair mutate-assemblies output: "{sample}_mutated.g.vcf".
        assertEquals(
            "Trailing _mutated should be stripped",
            "B73",
            recombineGvcfs.deriveSourceSampleKey("B73_mutated.g.vcf")
        )
        assertEquals(
            "Trailing _mutated should be stripped (.gvcf)",
            "Ki3",
            recombineGvcfs.deriveSourceSampleKey("Ki3_mutated.gvcf")
        )

        // Plain fixture-style names (no markers) are returned unchanged for
        // every recognized gVCF extension.
        assertEquals(
            "Plain .gvcf name unchanged",
            "sampleC",
            recombineGvcfs.deriveSourceSampleKey("sampleC.gvcf")
        )
        assertEquals(
            "Plain .g.vcf.gz name unchanged",
            "sampleC",
            recombineGvcfs.deriveSourceSampleKey("sampleC.g.vcf.gz")
        )
        assertEquals(
            "Plain .gvcf.gz name unchanged",
            "Ki3",
            recombineGvcfs.deriveSourceSampleKey("Ki3.gvcf.gz")
        )
        assertEquals(
            "Plain .g.vcf name unchanged",
            "Mo17",
            recombineGvcfs.deriveSourceSampleKey("Mo17.g.vcf")
        )
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

    @Test
    fun testBuildDel() {
        //buildDel(chrom: String, start:Int, end:Int, refAllele:String, altAllele: String, sampleName: String): VariantContext
        val recombineGvcfs = RecombineGvcfs()
        val simpleDel = recombineGvcfs.buildDel("chr1", 100, 110, "AAAAAAAAAAA","A","SampleA")

        //check the deletion
        assertEquals("Contig does not match","chr1", simpleDel.contig)
        assertEquals("Start position does not match",100, simpleDel.start)
        assertEquals("End position does not match", 110, simpleDel.end)
        assertEquals("Del should have 2 alleles", 3, simpleDel.alleles.size)
        assertEquals("Ref allele does not match", Allele.create("AAAAAAAAAAA", true), simpleDel.alleles[0])
        assertEquals("Alt allele1 does not match", Allele.create("A", false), simpleDel.alleles[1])
        assertEquals("Alt allele2 does not match", Allele.NON_REF_ALLELE, simpleDel.alleles[2])
        assertEquals("There should be one sample in the del", 1, simpleDel.genotypes.sampleNames.size)
        assertEquals("Sample name does not match","SampleA", simpleDel.genotypes.sampleNames.first())
        val genotype = simpleDel.genotypes.first()
        assertEquals("Genotype should have 1 alleles", 1, genotype.alleles.size)
        assertEquals("Genotype ref allele does not match", Allele.create("A", false), genotype.alleles[0])

        //Build 'fake' deletion
        val fakeDel = recombineGvcfs.buildDel("chr1", 100, 100, "A","A","SampleA")
        assertEquals("Contig does not match","chr1", fakeDel.contig)
        assertEquals("Start position does not match",100, fakeDel.start)
        assertEquals("End position does not match", 100, fakeDel.end)
        assertEquals("Fake del should have 2 alleles", 2, fakeDel.alleles.size)
        assertEquals("Ref allele does not match", Allele.create("A", true), fakeDel.alleles[0])
        assertEquals("Alt allele does not match", Allele.NON_REF_ALLELE, fakeDel.alleles[1])
        assertEquals("There should be one sample in the fake del", 1, fakeDel.genotypes.sampleNames.size)
        assertEquals("Sample name does not match","SampleA", fakeDel.genotypes.sampleNames.first())
        val fakeGenotype = fakeDel.genotypes.first()
        assertEquals("Genotype should have 1 alleles", 1, fakeGenotype.alleles.size)
        assertEquals("Genotype ref allele does not match", Allele.create("A", true), fakeGenotype.alleles[0])
    }
}