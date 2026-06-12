package net.maizegenetics.commands

import htsjdk.variant.variantcontext.Allele
import htsjdk.variant.variantcontext.GenotypeBuilder
import htsjdk.variant.variantcontext.VariantContextBuilder
import net.maizegenetics.net.maizegenetics.commands.AddAsmTagsToGvcf
import net.maizegenetics.net.maizegenetics.commands.MissingCategory
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AddAsmTagsToGvcfTest {

    @Test
    fun testExtractOutASMStart() {
        val addAsmTagsToGvcf = AddAsmTagsToGvcf()

        //Make some simple GVCF variants and make sure the ASMStart is updated correctly
        //Need to make tests for RefBlocks, SNPs, Insertions and Deletions as well as testing when there is no previous variant(set to null) or when we switch chromosomes
        //Need to test each mode as well to make sure things work correctly
        val refBlockVC1 = VariantContextBuilder(
            ".",
            "chr1",
            10,
            20,
            listOf(Allele.REF_A, Allele.NON_REF_ALLELE))
            .attribute("END", 20)
            .genotypes(listOf(GenotypeBuilder.create("sample1", listOf(Allele.REF_A))))
            .make()

        val refBlockVC2 = VariantContextBuilder(
            ".",
            "chr2",
            10,
            20,
            listOf(Allele.REF_A, Allele.NON_REF_ALLELE)
        )
        .attribute("END", 20)
        .genotypes(listOf(GenotypeBuilder.create("sample1", listOf(Allele.REF_A))))
        .make()

        val refBlockNullOMIT = addAsmTagsToGvcf.extractOutASMStart(null, refBlockVC1, 1, MissingCategory.OMIT)
        //should be 1
        assertEquals(1, refBlockNullOMIT)

        val refBlockNullASREF = addAsmTagsToGvcf.extractOutASMStart(null, refBlockVC1, 1, MissingCategory.AS_REF)
        //should be 10
        assertEquals(10, refBlockNullASREF)

        val refBlockNullASN = addAsmTagsToGvcf.extractOutASMStart(null, refBlockVC1, 1, MissingCategory.AS_N)
        assertEquals(10, refBlockNullASN)

        val refBlockDiffChromOMIT = addAsmTagsToGvcf.extractOutASMStart(refBlockVC1, refBlockVC2, 1, MissingCategory.OMIT)
        //should be 1
        assertEquals(1, refBlockDiffChromOMIT)

        val refBlockDiffChromASREF = addAsmTagsToGvcf.extractOutASMStart(refBlockVC1, refBlockVC2, 1, MissingCategory.AS_REF)
        //should be 10
        assertEquals(10, refBlockDiffChromASREF)
        val refBlockDiffChromASN = addAsmTagsToGvcf.extractOutASMStart(refBlockVC1, refBlockVC2, 1, MissingCategory.AS_N)
        //should be 10
        assertEquals(10, refBlockDiffChromASN)

        TODO("Add more tests")

    }
}