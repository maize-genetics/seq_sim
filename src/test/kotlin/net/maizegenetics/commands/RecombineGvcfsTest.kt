package net.maizegenetics.commands

import com.google.common.collect.Range
import com.google.common.collect.RangeMap
import com.google.common.collect.TreeRangeMap
import htsjdk.variant.variantcontext.Allele
import htsjdk.variant.variantcontext.GenotypeBuilder
import htsjdk.variant.variantcontext.VariantContextBuilder
import net.maizegenetics.net.maizegenetics.commands.RecombineGvcfs
import net.maizegenetics.utils.Position
import java.io.File
import kotlin.io.path.Path
import kotlin.test.DefaultAsserter.assertEquals
import kotlin.test.Test
import kotlin.test.assertFalse

class RecombineGvcfsTest {

    val homeDir = System.getProperty("user.home").replace('\\', '/')

    val outputDir = "$homeDir/temp/seq_sim/recombine_gvcf_test/"

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


    }



    @Test
    fun testWriteResizedBedFiles() {
        val recombinationMap = buildSimpleRecombinationMap()

        val recombineGvcfs = RecombineGvcfs()

        File(outputDir).mkdirs()

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

        //Delete the output directory
        File(outputDir).deleteRecursively()


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