package net.maizegenetics.commands

import com.google.common.collect.Range
import com.google.common.collect.RangeMap
import com.google.common.collect.TreeRangeMap
import htsjdk.variant.variantcontext.Allele
import htsjdk.variant.variantcontext.VariantContextBuilder
import htsjdk.variant.vcf.VCFFileReader
import net.maizegenetics.utils.Position
import net.maizegenetics.utils.SimpleVariant
import net.maizegenetics.utils.VariantContextUtils
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MutateAssembliesTest {

    val homeDir = System.getProperty("user.home").replace('\\', '/')

    val outputDir = "$homeDir/temp/seq_sim/mutated_gvcf_test/"

    @Test
    fun testBuildBaseVariantMap() {
        val mutateAssemblies = MutateAssemblies()

        val baseVariantMap = createSimpleBaseVariantMap()

        //load back in with
        val loadedMap = mutateAssemblies.buildBaseVariantMap(File("data/MutateAssemblies/base.g.vcf"))

        //Should match the original map
        assertEquals(baseVariantMap.asMapOfRanges().size, loadedMap.second.asMapOfRanges().size)
        for(entry in baseVariantMap.asMapOfRanges().entries) {
            val loadedVariant = loadedMap.second.get(entry.key.lowerEndpoint())
            assertEquals(entry.value, loadedVariant)
        }
    }

    @Test
    fun testAddNewVariants() {
        val mutateAssemblies = MutateAssemblies()

        val (sampleName,baseVariantMap) = mutateAssemblies.buildBaseVariantMap(File("data/MutateAssemblies/base.g.vcf"))

        mutateAssemblies.addNewVariants(File("data/MutateAssemblies/mutationDonor.g.vcf"), baseVariantMap)

        //This should match the BaseMap but we have new variants
        assertEquals(11, baseVariantMap.asMapOfRanges().size)

        //Here are the three additions:
        //chr1    125    .	T	A	.	.	END=125	GT	1/1
        //chr1    175 .  C   TTTT   .   .   END=175 GT  1/1
        //chr1    500 .  A   T   .   .   END=500 GT  1/1
        val variant125 = baseVariantMap.get(Position("chr1", 125))
        assertEquals(SimpleVariant(Position("chr1", 125), Position("chr1",
    125), "T", "A",true), variant125)

        val variant175 = baseVariantMap.get(Position("chr1", 175))
        assertEquals(SimpleVariant(Position("chr1", 175), Position("chr1", 175), "C", "TTTT", true), variant175)

        val variant500 = baseVariantMap.get(Position("chr1", 500))
        assertEquals(SimpleVariant(Position("chr1", 500), Position("chr1", 500), "A", "T", true), variant500)



    }

    @Test
    fun splitRefBlockTest() {
        val mutateAssemblies = MutateAssemblies()

        //Test non overlapping variants
        val variantToSplit = SimpleVariant(Position("chr1", 100), Position("chr1", 200), "A", "<NON_REF>")

        val nonOverlappingVariant = SimpleVariant(Position("chr1", 250), Position("chr1", 250), "C", "G")

        assertFailsWith<IllegalArgumentException> {
            mutateAssemblies.splitRefBlock(variantToSplit, nonOverlappingVariant)
        }

        //check partially overlapping variants
        val partialOverlapLeft = SimpleVariant(Position("chr1", 50), Position("chr1", 150), "T", "G")
        assertFailsWith<IllegalArgumentException> {
            mutateAssemblies.splitRefBlock(variantToSplit, partialOverlapLeft)
        }

        val partialOverlapRight = SimpleVariant(Position("chr1", 150), Position("chr1", 250), "T", "G")
        assertFailsWith<IllegalArgumentException> {
            mutateAssemblies.splitRefBlock(variantToSplit, partialOverlapRight)
        }

        val variantToAdd = SimpleVariant(Position("chr1", 150), Position("chr1", 150), "T", "G")
        val splitVariants = mutateAssemblies.splitRefBlock(variantToSplit, variantToAdd)
        assert(splitVariants.size == 3)
        assert(splitVariants[0].refStart == Position("chr1", 100))
        assert(splitVariants[0].refEnd == Position("chr1", 149))
        assert(splitVariants[1] == variantToAdd)
        assert(splitVariants[2].refStart == Position("chr1", 151))
        assert(splitVariants[2].refEnd == Position("chr1", 200))

        //check boundaries
        val leftBoundaryVariant = SimpleVariant(Position("chr1", 100), Position("chr1", 100), "A", "G")
        val splitVariantsLeft = mutateAssemblies.splitRefBlock(variantToSplit, leftBoundaryVariant)
        assert(splitVariantsLeft.size == 2)
        assert(splitVariantsLeft[0] == leftBoundaryVariant)
        assert(splitVariantsLeft[1].refStart == Position("chr1", 101))
        assert(splitVariantsLeft[1].refEnd == Position("chr1", 200))

        val rightBoundaryVariant = SimpleVariant(Position("chr1", 200), Position("chr1", 200), "A", "G")
        val splitVariantsRight = mutateAssemblies.splitRefBlock(variantToSplit, rightBoundaryVariant)
        assert(splitVariantsRight.size == 2)
        assert(splitVariantsRight[0].refStart == Position("chr1", 100))
        assert(splitVariantsRight[0].refEnd == Position("chr1", 199))
        assert(splitVariantsRight[1] == rightBoundaryVariant)

    }

    @Test
    fun updateOverlappingVariantTest() {
        //Need to test to make sure that the removing and adding of variants is working as expected
        val mutateAssemblies = MutateAssemblies()

        //need a range map to work with
        val baseVariantMap = createSimpleBaseVariantMap()

        val initialSize = baseVariantMap.asMapOfRanges().size

        //check a variant outside of the existing ranges
        val nonOverlappingVariant = SimpleVariant(Position("chr1", 500), Position("chr1", 500), "C", "G")
        mutateAssemblies.updateOverlappingVariant(baseVariantMap, nonOverlappingVariant)

        assertEquals(initialSize, baseVariantMap.asMapOfRanges().size)

        //Try other chromosomes
        val nonOverlappingVariantOtherChr = SimpleVariant(Position("chr2", 150), Position("chr2", 150), "C", "G")
        mutateAssemblies.updateOverlappingVariant(baseVariantMap, nonOverlappingVariantOtherChr)
        assertEquals(initialSize, baseVariantMap.asMapOfRanges().size)


        //Update the SNP at position 150 with the same variant
        val sameSNPVariant = SimpleVariant(Position("chr1", 150), Position("chr1", 150), "C", "G")
        mutateAssemblies.updateOverlappingVariant(baseVariantMap, sameSNPVariant)
        assertEquals(initialSize, baseVariantMap.asMapOfRanges().size)

        //Update the SNP at position 150
        val overlappingSNPVariant = SimpleVariant(Position("chr1", 150), Position("chr1", 150), "C", "T")
        mutateAssemblies.updateOverlappingVariant(baseVariantMap, overlappingSNPVariant)
        assertEquals(initialSize, baseVariantMap.asMapOfRanges().size)
        val retrievedVariant = baseVariantMap.get(Position("chr1", 150))
        assertEquals(overlappingSNPVariant, retrievedVariant)


        //add a SNP at position 175
        val newSNPVariant = SimpleVariant(Position("chr1", 175), Position("chr1", 175), "A", "T")
        mutateAssemblies.updateOverlappingVariant(baseVariantMap, newSNPVariant)
        assertEquals(initialSize + 2, baseVariantMap.asMapOfRanges().size)
        val retrievedNewVariant = baseVariantMap.get(Position("chr1", 175))
        assertEquals(newSNPVariant, retrievedNewVariant)
        //Check to see if the surrounding refBlocks were updated correctly
        val leftRefBlock = baseVariantMap.get(Position("chr1", 174))
        assertEquals(SimpleVariant(Position("chr1", 151), Position("chr1", 174), "T", "<NON_REF>"), leftRefBlock)
        val rightRefBlock = baseVariantMap.get(Position("chr1", 176))
        assertEquals(SimpleVariant(Position("chr1", 176), Position("chr1", 200), "T", "<NON_REF>"), rightRefBlock)


        //Leaving this in for now as I may want to re-implement this functionality later
//        //add an indel that is fully contained within an existing indel
//        val newIndelVariant = SimpleVariant(Position("chr1", 405), Position("chr1", 407), "GGG", "G")
//        mutateAssemblies.updateOverlappingVariant(founderVariantMap, newIndelVariant)
//        assertEquals(initialSize, founderVariantMap.asMapOfRanges().size)//Should stay the same size
//        //The first ref block should be extended
//        val firstRefBlock = founderVariantMap.get(Position("chr1", 401))
//        assertEquals(SimpleVariant(Position("chr1", 302), Position("chr1", 404), "T", "<NON_REF>"), firstRefBlock)
//        //The new indel should be present
//        val retrievedIndel = founderVariantMap.get(Position("chr1", 405))
//        assertEquals(newIndelVariant, retrievedIndel)
//        //The last ref block should be extended
//        val lastRefBlock = founderVariantMap.get(Position("chr1", 408))
//        assertEquals(SimpleVariant(Position("chr1", 408), Position("chr1", 500), "G", "<NON_REF>"), lastRefBlock)

    }

    @Test
    fun testWriteMutatedGVCF() {
        val mutateAssemblies = MutateAssemblies()


        File(outputDir).mkdirs()

        //need a range map to work with
        val baseVariantMap = createSimpleBaseVariantMap()

        mutateAssemblies.writeMutatedGVCF(File(outputDir), "testSample", baseVariantMap)

        //load in the GVCF and check to make sure the variants match
        VCFFileReader(File("${outputDir}testSample_mutated.g.vcf"), false).use { vcfReader ->
            val variants = vcfReader.iterator().asSequence().toList()

            //should be 7 variants
            assertEquals(7, variants.size)

            //check a few variants
            val variant150 = variants.find { it.contig == "chr1" && it.start == 150 }
            assertEquals("G", variant150!!.genotypes.get(0).alleles[0].displayString)

            val variant301 = variants.find { it.contig == "chr1" && it.start == 301 }
            assertEquals("C", variant301!!.genotypes.get(0).alleles[0].displayString)

            //check one of the indels position 201
            val variant201 = variants.find { it.contig == "chr1" && it.start == 201 }
            assertEquals("GGGGG", variant201!!.reference.baseString)
            assertEquals("G", variant201!!.genotypes.get(0).alleles[0].displayString)

        }

    }


    @Test
    fun testExtractVCAndAddToRangeMap() {
        val mutateAssemblies = MutateAssemblies()

        //need a range map to work with
        val baseVariantMap = createSimpleBaseVariantMap()

        val testVariantContextSNPBuilder = VariantContextBuilder()
            .chr("chr1")
            .start(150)
            .stop(150)
            .alleles(listOf(Allele.create("C", true), Allele.create("A", false))).make()

        mutateAssemblies.extractVCAndAddToRangeMap(testVariantContextSNPBuilder, baseVariantMap)

        //Size should stay the same, the variant should be different
        assertEquals(7, baseVariantMap.asMapOfRanges().size)
        val variant150 = baseVariantMap.get(Position("chr1", 150))
        assertEquals("A", variant150!!.altAllele)

        //Split a ref block with a SNP
        val testVariantContextSNP2Builder = VariantContextBuilder()
            .chr("chr1")
            .start(175)
            .stop(175)
            .alleles(listOf(Allele.create("A", true), Allele.create("T", false))).make()

        mutateAssemblies.extractVCAndAddToRangeMap(testVariantContextSNP2Builder, baseVariantMap)
        //Size should increase by 2, 1 for new SNP and 1 for trailing ref block
        assertEquals(9, baseVariantMap.asMapOfRanges().size)
        val variant175 = baseVariantMap.get(Position("chr1", 175))
        assertEquals("T", variant175!!.altAllele)


        //Introduce a RefBlock overlapping a SNP  This should skip
        val testVariantContextRefBlockBuilder = VariantContextBuilder()
            .chr("chr1")
            .start(150)
            .stop(200)
            .alleles(listOf(Allele.create("C", true), Allele.create("<NON_REF>", false))).make()
        mutateAssemblies.extractVCAndAddToRangeMap(testVariantContextRefBlockBuilder, baseVariantMap)
        //Size should keep same size as we skip introducing a ref blocks
        assertEquals(9, baseVariantMap.asMapOfRanges().size)

        //add in a completely new variant
        val testVariantContextNewSNPBuilder = VariantContextBuilder()
            .chr("chr1")
            .start(500)
            .stop(500)
            .alleles(listOf(Allele.create("A", true), Allele.create("G", false))).make()
        mutateAssemblies.extractVCAndAddToRangeMap(testVariantContextNewSNPBuilder, baseVariantMap)
        //Size should increase by 2, 1 for new SNP and 1 for trailing ref block
        assertEquals(10, baseVariantMap.asMapOfRanges().size)
        val variant250 = baseVariantMap.get(Position("chr1", 500))
        assertEquals("G", variant250!!.altAllele)

        //Try to add an overlapping indel
        val testVariantContextIndelBuilder = VariantContextBuilder()
            .chr("chr1")
            .start(201)
            .stop(203)
            .alleles(listOf(Allele.create("GGG", true), Allele.create("G", false))).make()
        mutateAssemblies.extractVCAndAddToRangeMap(testVariantContextIndelBuilder, baseVariantMap)
        //Size should stay the same
        assertEquals(10, baseVariantMap.asMapOfRanges().size)
        val variant201 = baseVariantMap.get(Position("chr1", 201))
        assertEquals("G", variant201!!.altAllele)

        //Try to add a snp at an indel... this should not add anything
        val testVariantContextSNPAtIndelBuilder = VariantContextBuilder()
            .chr("chr1")
            .start(202)
            .stop(202)
            .alleles(listOf(Allele.create("G", true), Allele.create("A", false))).make()
        mutateAssemblies.extractVCAndAddToRangeMap(testVariantContextSNPAtIndelBuilder, baseVariantMap)
        //Size should stay the same
        assertEquals(10, baseVariantMap.asMapOfRanges().size)
        val variant202 = baseVariantMap.get(Position("chr1", 202))
        assertEquals("G", variant202!!.altAllele)



    }

    @Test
    fun testIsIndel() {
        val mutateAssemblies = MutateAssemblies()

        val refBlockVariant = SimpleVariant(Position("chr1", 100), Position("chr1", 150), "A", "<NON_REF>")
        assert(!VariantContextUtils.isIndel(refBlockVariant))

        val snpVariant = SimpleVariant(Position("chr1", 150), Position("chr1", 150), "C", "G")
        assert(!VariantContextUtils.isIndel(snpVariant))

        val insertionVariant = SimpleVariant(Position("chr1", 200), Position("chr1", 200), "A", "AGG")
        assert(VariantContextUtils.isIndel(insertionVariant))

        val deletionVariant = SimpleVariant(Position("chr1", 201), Position("chr1", 205), "GGGGG", "G")
        assert(VariantContextUtils.isIndel(deletionVariant))
    }

    private fun createSimpleBaseVariantMap(): RangeMap<Position, SimpleVariant> {
        val rangeMap = TreeRangeMap.create<Position, SimpleVariant>()

        rangeMap.put(Range.closed(Position("chr1", 100), Position("chr1", 150)), SimpleVariant(Position("chr1", 100), Position("chr1", 150), "A", "<NON_REF>"))
        rangeMap.put(Range.closed(Position("chr1", 150), Position("chr1", 150)), SimpleVariant(Position("chr1", 150), Position("chr1", 150), "C", "G"))
        rangeMap.put(Range.closed(Position("chr1", 151), Position("chr1", 200)), SimpleVariant(Position("chr1", 151), Position("chr1", 200), "T", "<NON_REF>"))
        rangeMap.put(Range.closed(Position("chr1", 201), Position("chr1", 205)), SimpleVariant(Position("chr1", 201), Position("chr1", 205), "GGGGG", "G"))
        rangeMap.put(Range.closed(Position("chr1", 206), Position("chr1", 300)), SimpleVariant(Position("chr1", 206), Position("chr1", 300), "A", "<NON_REF>"))
        rangeMap.put(Range.closed(Position("chr1", 301), Position("chr1", 301)), SimpleVariant(Position("chr1", 301), Position("chr1", 301), "G", "C"))
        rangeMap.put(Range.closed(Position("chr1", 302), Position("chr1", 400)), SimpleVariant(Position("chr1", 302), Position("chr1", 400), "T", "<NON_REF>"))

//        rangeMap.put(Range.closed(Position("chr1",401), Position("chr1",410)), SimpleVariant(Position("chr1",401), Position("chr1",410), "GGGGGGGGGG", "G"))
//        rangeMap.put(Range.closed(Position("chr1",411), Position("chr1",500)), SimpleVariant(Position("chr1",411), Position("chr1",500), "C", "<NON_REF>"))
        return rangeMap
    }
}