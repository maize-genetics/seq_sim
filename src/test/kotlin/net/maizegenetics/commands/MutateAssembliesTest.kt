package net.maizegenetics.commands

import apple.laf.JRSUIConstants
import com.google.common.collect.Range
import com.google.common.collect.RangeMap
import com.google.common.collect.TreeRangeMap
import net.maizegenetics.net.maizegenetics.commands.MutateAssemblies
import net.maizegenetics.net.maizegenetics.commands.Position
import net.maizegenetics.net.maizegenetics.commands.SimpleVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MutateAssembliesTest {

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
        val founderVariantMap = createSimpleFounderMap()

        val initialSize = founderVariantMap.asMapOfRanges().size

        //check a variant outside of the existing ranges
        val nonOverlappingVariant = SimpleVariant(Position("chr1", 500), Position("chr1", 500), "C", "G")
        mutateAssemblies.updateOverlappingVariant(founderVariantMap, nonOverlappingVariant)

        assertEquals(initialSize, founderVariantMap.asMapOfRanges().size)

        //Try other chromosomes
        val nonOverlappingVariantOtherChr = SimpleVariant(Position("chr2", 150), Position("chr2", 150), "C", "G")
        mutateAssemblies.updateOverlappingVariant(founderVariantMap, nonOverlappingVariantOtherChr)
        assertEquals(initialSize, founderVariantMap.asMapOfRanges().size)


        //Update the SNP at position 150 with the same variant
        val sameSNPVariant = SimpleVariant(Position("chr1", 150), Position("chr1", 150), "C", "G")
        mutateAssemblies.updateOverlappingVariant(founderVariantMap, sameSNPVariant)
        assertEquals(initialSize, founderVariantMap.asMapOfRanges().size)

        //Update the SNP at position 150
        val overlappingSNPVariant = SimpleVariant(Position("chr1", 150), Position("chr1", 150), "C", "T")
        mutateAssemblies.updateOverlappingVariant(founderVariantMap, overlappingSNPVariant)
        assertEquals(initialSize, founderVariantMap.asMapOfRanges().size)
        val retrievedVariant = founderVariantMap.get(Position("chr1", 150))
        assertEquals(overlappingSNPVariant, retrievedVariant)


        //add a SNP at position 175
        val newSNPVariant = SimpleVariant(Position("chr1", 175), Position("chr1", 175), "A", "T")
        mutateAssemblies.updateOverlappingVariant(founderVariantMap, newSNPVariant)
        assertEquals(initialSize + 2, founderVariantMap.asMapOfRanges().size)
        val retrievedNewVariant = founderVariantMap.get(Position("chr1", 175))
        assertEquals(newSNPVariant, retrievedNewVariant)
        //Check to see if the surrounding refBlocks were updated correctly
        val leftRefBlock = founderVariantMap.get(Position("chr1", 174))
        assertEquals(SimpleVariant(Position("chr1", 151), Position("chr1", 174), "T", "<NON_REF>"), leftRefBlock)
        val rightRefBlock = founderVariantMap.get(Position("chr1", 176))
        assertEquals(SimpleVariant(Position("chr1", 176), Position("chr1", 200), "T", "<NON_REF>"), rightRefBlock)


    }

    private fun createSimpleFounderMap(): RangeMap<Position, SimpleVariant> {
        val rangeMap = TreeRangeMap.create<Position, SimpleVariant>()

        rangeMap.put(Range.closed(Position("chr1", 100), Position("chr1", 150)), SimpleVariant(Position("chr1", 100), Position("chr1", 150), "A", "<NON_REF>"))
        rangeMap.put(Range.closed(Position("chr1", 150), Position("chr1", 150)), SimpleVariant(Position("chr1", 150), Position("chr1", 150), "C", "G"))
        rangeMap.put(Range.closed(Position("chr1", 151), Position("chr1", 200)), SimpleVariant(Position("chr1", 151), Position("chr1", 200), "T", "<NON_REF>"))
        rangeMap.put(Range.closed(Position("chr1", 201), Position("chr1", 205)), SimpleVariant(Position("chr1", 201), Position("chr1", 205), "GGGGG", "G"))
        rangeMap.put(Range.closed(Position("chr1", 206), Position("chr1", 300)), SimpleVariant(Position("chr1", 206), Position("chr1", 300), "A", "<NON_REF>"))
        rangeMap.put(Range.closed(Position("chr1", 301), Position("chr1", 301)), SimpleVariant(Position("chr1", 301), Position("chr1", 301), "G", "C"))
        rangeMap.put(Range.closed(Position("chr1", 302), Position("chr1", 400)), SimpleVariant(Position("chr1", 302), Position("chr1", 400), "T", "<NON_REF>"))


        return rangeMap
    }
}