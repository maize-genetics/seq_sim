package net.maizegenetics.commands

import com.google.common.collect.Range
import com.google.common.collect.RangeMap
import com.google.common.collect.TreeRangeMap
import htsjdk.variant.variantcontext.Allele
import htsjdk.variant.variantcontext.GenotypeBuilder
import htsjdk.variant.variantcontext.VariantContextBuilder
import net.maizegenetics.net.maizegenetics.commands.RecombineGvcfs
import net.maizegenetics.utils.Position
import kotlin.test.DefaultAsserter.assertEquals
import kotlin.test.Test
import kotlin.test.assertFalse

class RecombineGvcfsTest {

    @Test
    fun testFlipRecombinationMap() {
        //This needs to take a recombination map Map<sampleName, RangeMap<Position,targetSampleName>> and flip it to Map<targetSampleName, RangeMap<Position,sampleName>>

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

        val recombineGvcfs = RecombineGvcfs()
        val flippedMap = recombineGvcfs.flipRecombinationMap(recombinationMap)
        //Now check that the flipped map is correct


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
}