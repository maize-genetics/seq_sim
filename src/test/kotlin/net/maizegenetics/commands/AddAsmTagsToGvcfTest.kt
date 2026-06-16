package net.maizegenetics.commands

import htsjdk.variant.variantcontext.Allele
import htsjdk.variant.variantcontext.GenotypeBuilder
import htsjdk.variant.variantcontext.VariantContext
import htsjdk.variant.variantcontext.VariantContextBuilder
import net.maizegenetics.net.maizegenetics.commands.AddAsmTagsToGvcf
import net.maizegenetics.net.maizegenetics.commands.MissingCategory
import org.junit.jupiter.api.Test
import kotlin.collections.listOf
import kotlin.test.assertEquals

class AddAsmTagsToGvcfTest {

    @Test
    fun testBuildNewVariantContextRefBlock() {
        val addAsmTagsToGvcf = AddAsmTagsToGvcf()

        val prevVariant = VariantContextBuilder()
            .chr("chr1")
            .start(100)
            .stop(100)
            .attribute("END",100)
            .alleles(listOf(Allele.REF_A, Allele.ALT_T))
            .genotypes(GenotypeBuilder("sample1").alleles(listOf(Allele.REF_A, Allele.ALT_T)).make())
            .make()

        //Make a REF Block, SNP, Insertion and Deletion
        val currentVarRefBlock = VariantContextBuilder()
            .chr("chr1")
            .start(101)
            .stop(110)
            .attribute("END",110)
            .alleles(listOf(Allele.REF_A, Allele.NON_REF_ALLELE))
            .genotypes(GenotypeBuilder("sample1").alleles(listOf(Allele.REF_A, Allele.REF_A)).make())
            .make()

        val currentVarRefBlockGap = VariantContextBuilder()
            .chr("chr1")
            .start(111)
            .stop(120)
            .attribute("END",120)
            .alleles(listOf(Allele.REF_A, Allele.NON_REF_ALLELE))
            .genotypes(GenotypeBuilder("sample1").alleles(listOf(Allele.REF_A, Allele.REF_A)).make())
            .make()


        //Check null prev first
        val newNulLRefVariantOMIT = addAsmTagsToGvcf.buildNewVariantContext(1,null,currentVarRefBlock, MissingCategory.OMIT)
        assertEquals("chr1",newNulLRefVariantOMIT.first.contig)
        assertEquals(101, newNulLRefVariantOMIT.first.start)
        assertEquals(110,newNulLRefVariantOMIT.first.end)
        //Check the ASM_* Tags
        assertEquals("chr1",newNulLRefVariantOMIT.first.getAttribute("ASM_Chr",""))
        assertEquals(1,newNulLRefVariantOMIT.first.getAttribute("ASM_Start",-1))
        assertEquals(10,newNulLRefVariantOMIT.first.getAttribute("ASM_End",-1))
        assertEquals("+",newNulLRefVariantOMIT.first.getAttribute("ASM_Strand"))
        assertEquals(11, newNulLRefVariantOMIT.second)


        testREFOrDELNoGap(addAsmTagsToGvcf, 1,null, currentVarRefBlock, MissingCategory.AS_REF)
        testREFOrDELNoGap(addAsmTagsToGvcf, 1,null, currentVarRefBlock, MissingCategory.AS_N)

        //Check consecutive ones
        testREFOrDELNoGap(addAsmTagsToGvcf, 101,prevVariant, currentVarRefBlock, MissingCategory.OMIT)
        testREFOrDELNoGap(addAsmTagsToGvcf, 101,prevVariant, currentVarRefBlock, MissingCategory.AS_REF)
        testREFOrDELNoGap(addAsmTagsToGvcf, 101,prevVariant, currentVarRefBlock, MissingCategory.AS_N)


        val newGapRefVariantOMIT = addAsmTagsToGvcf.buildNewVariantContext(1,prevVariant,currentVarRefBlockGap, MissingCategory.OMIT)
        assertEquals("chr1",newGapRefVariantOMIT.first.contig)
        assertEquals(111, newGapRefVariantOMIT.first.start)
        assertEquals(120,newGapRefVariantOMIT.first.end)
        //Check the ASM_* Tags
        assertEquals("chr1",newGapRefVariantOMIT.first.getAttribute("ASM_Chr",""))
        assertEquals(1,newGapRefVariantOMIT.first.getAttribute("ASM_Start",-1))
        assertEquals(10,newGapRefVariantOMIT.first.getAttribute("ASM_End",-1))
        assertEquals("+",newGapRefVariantOMIT.first.getAttribute("ASM_Strand"))
        assertEquals(11, newGapRefVariantOMIT.second)

        testREFOrDELWithGap(addAsmTagsToGvcf, 101,prevVariant, currentVarRefBlockGap, MissingCategory.AS_REF)
        testREFOrDELWithGap(addAsmTagsToGvcf, 101,prevVariant, currentVarRefBlockGap, MissingCategory.AS_N)

    }

    @Test
    fun testBuildNewVariantContextSNP() {
        val addAsmTagsToGvcf = AddAsmTagsToGvcf()

        val prevVariant = VariantContextBuilder()
            .chr("chr1")
            .start(100)
            .stop(100)
            .attribute("END",100)
            .alleles(listOf(Allele.REF_A, Allele.ALT_T))
            .genotypes(GenotypeBuilder("sample1").alleles(listOf(Allele.REF_A, Allele.ALT_T)).make())
            .make()


        val currentVarSNP = VariantContextBuilder()
            .chr("chr1")
            .start(101)
            .stop(101)
            .alleles(listOf(Allele.REF_A, Allele.ALT_T))
            .genotypes(GenotypeBuilder("sample1").alleles(listOf(Allele.ALT_T,Allele.ALT_T)).make())
            .make()

        val currentVarSNPGAP = VariantContextBuilder()
            .chr("chr1")
            .start(111)
            .stop(111)
            .alleles(listOf(Allele.REF_A, Allele.ALT_T))
            .genotypes(GenotypeBuilder("sample1").alleles(listOf(Allele.ALT_T,Allele.ALT_T)).make())
            .make()

        //Check null prev first
        //This one is the only odd one the rest can use the same checks
        val newNullSNPVariantOMIT = addAsmTagsToGvcf.buildNewVariantContext(1,null,currentVarSNP, MissingCategory.OMIT)
        assertEquals("chr1",newNullSNPVariantOMIT.first.contig)
        assertEquals(101, newNullSNPVariantOMIT.first.start)
        assertEquals(101,newNullSNPVariantOMIT.first.end)
        //Check the ASM_* Tags
        assertEquals("chr1",newNullSNPVariantOMIT.first.getAttribute("ASM_Chr",""))
        assertEquals(1,newNullSNPVariantOMIT.first.getAttribute("ASM_Start",-1))
        assertEquals(1,newNullSNPVariantOMIT.first.getAttribute("ASM_End",-1))
        assertEquals("+",newNullSNPVariantOMIT.first.getAttribute("ASM_Strand"))
        assertEquals(2, newNullSNPVariantOMIT.second)

        testSNPOrINSNoGap(addAsmTagsToGvcf, 1,null, currentVarSNP,MissingCategory.AS_REF)
        testSNPOrINSNoGap(addAsmTagsToGvcf, 1,null, currentVarSNP,MissingCategory.AS_N)

        testSNPOrINSNoGap(addAsmTagsToGvcf, 101, prevVariant, currentVarSNP,MissingCategory.OMIT)
        testSNPOrINSNoGap(addAsmTagsToGvcf, 101, prevVariant, currentVarSNP,MissingCategory.AS_REF)
        testSNPOrINSNoGap(addAsmTagsToGvcf, 101,prevVariant, currentVarSNP,MissingCategory.AS_N)

        val newGapSNPVariantOMIT = addAsmTagsToGvcf.buildNewVariantContext(111,prevVariant,currentVarSNPGAP, MissingCategory.OMIT)
        assertEquals("chr1",newGapSNPVariantOMIT.first.contig)
        assertEquals(111, newGapSNPVariantOMIT.first.start)
        assertEquals(111,newGapSNPVariantOMIT.first.end)
        //Check the ASM_* Tags
        assertEquals("chr1",newGapSNPVariantOMIT.first.getAttribute("ASM_Chr",""))
        assertEquals(111,newGapSNPVariantOMIT.first.getAttribute("ASM_Start",-1))
        assertEquals(111,newGapSNPVariantOMIT.first.getAttribute("ASM_End",-1))
        assertEquals("+",newGapSNPVariantOMIT.first.getAttribute("ASM_Strand"))
        assertEquals(112, newGapSNPVariantOMIT.second)

        testSNPOrINSWithGap(addAsmTagsToGvcf, 101, prevVariant, currentVarSNPGAP,MissingCategory.AS_REF)
        testSNPOrINSWithGap(addAsmTagsToGvcf, 101, prevVariant, currentVarSNPGAP,MissingCategory.AS_N)
    }

    @Test
    fun testBuildNewVariantContextINS() {
        val addAsmTagsToGvcf = AddAsmTagsToGvcf()

        val prevVariant = VariantContextBuilder()
            .chr("chr1")
            .start(100)
            .stop(100)
            .attribute("END",100)
            .alleles(listOf(Allele.REF_A, Allele.ALT_T))
            .genotypes(GenotypeBuilder("sample1").alleles(listOf(Allele.REF_A, Allele.ALT_T)).make())
            .make()


        val currentVarINS = VariantContextBuilder()
            .chr("chr1")
            .start(101)
            .stop(101)
            .alleles(listOf(Allele.REF_A, Allele.create("AGGG",false)))
            .genotypes(GenotypeBuilder("sample1").alleles(listOf(Allele.create("AGGG",false), Allele.create("AGGG",false))).make())
            .make()

        val currentVarINSGAP = VariantContextBuilder()
            .chr("chr1")
            .start(111)
            .stop(111)
            .alleles(listOf(Allele.REF_A, Allele.create("AGGG",false)))
            .genotypes(GenotypeBuilder("sample1").alleles(listOf(Allele.create("AGGG",false), Allele.create("AGGG",false))).make())
            .make()

        //Check null prev first
        //This one is the only odd one the rest can use the same checks
        val newNullSNPVariantOMIT = addAsmTagsToGvcf.buildNewVariantContext(1,null,currentVarINS, MissingCategory.OMIT)
        assertEquals("chr1",newNullSNPVariantOMIT.first.contig)
        assertEquals(101, newNullSNPVariantOMIT.first.start)
        assertEquals(101,newNullSNPVariantOMIT.first.end)
        //Check the ASM_* Tags
        assertEquals("chr1",newNullSNPVariantOMIT.first.getAttribute("ASM_Chr",""))
        assertEquals(1,newNullSNPVariantOMIT.first.getAttribute("ASM_Start",-1))
        assertEquals(1,newNullSNPVariantOMIT.first.getAttribute("ASM_End",-1))
        assertEquals("+",newNullSNPVariantOMIT.first.getAttribute("ASM_Strand"))
        assertEquals(2, newNullSNPVariantOMIT.second)

        testSNPOrINSNoGap(addAsmTagsToGvcf, 1,null, currentVarINS,MissingCategory.AS_REF)
        testSNPOrINSNoGap(addAsmTagsToGvcf, 1,null, currentVarINS,MissingCategory.AS_N)

        testSNPOrINSNoGap(addAsmTagsToGvcf, 101, prevVariant, currentVarINS,MissingCategory.OMIT)
        testSNPOrINSNoGap(addAsmTagsToGvcf, 101, prevVariant, currentVarINS,MissingCategory.AS_REF)
        testSNPOrINSNoGap(addAsmTagsToGvcf, 101,prevVariant, currentVarINS,MissingCategory.AS_N)


        val newGapSNPVariantOMIT = addAsmTagsToGvcf.buildNewVariantContext(111,prevVariant,currentVarINSGAP, MissingCategory.OMIT)
        assertEquals("chr1",newGapSNPVariantOMIT.first.contig)
        assertEquals(111, newGapSNPVariantOMIT.first.start)
        assertEquals(111,newGapSNPVariantOMIT.first.end)
        //Check the ASM_* Tags
        assertEquals("chr1",newGapSNPVariantOMIT.first.getAttribute("ASM_Chr",""))
        assertEquals(111,newGapSNPVariantOMIT.first.getAttribute("ASM_Start",-1))
        assertEquals(111,newGapSNPVariantOMIT.first.getAttribute("ASM_End",-1))
        assertEquals("+",newGapSNPVariantOMIT.first.getAttribute("ASM_Strand"))
        assertEquals(112, newGapSNPVariantOMIT.second)

        testSNPOrINSWithGap(addAsmTagsToGvcf, 101, prevVariant, currentVarINSGAP,MissingCategory.AS_REF)
        testSNPOrINSWithGap(addAsmTagsToGvcf, 101, prevVariant, currentVarINSGAP,MissingCategory.AS_N)
    }


    @Test
    fun testBuildNewVariantContextDEL() {
        val addAsmTagsToGvcf = AddAsmTagsToGvcf()

        val prevVariant = VariantContextBuilder()
            .chr("chr1")
            .start(100)
            .stop(100)
            .attribute("END",100)
            .alleles(listOf(Allele.REF_A, Allele.ALT_T))
            .genotypes(GenotypeBuilder("sample1").alleles(listOf(Allele.REF_A, Allele.ALT_T)).make())
            .make()


        val currentVarDEL = VariantContextBuilder()
            .chr("chr1")
            .start(101)
            .stop(110)
            .alleles(listOf(Allele.create("AAAAAAAAAA",true), Allele.ALT_A))
            .genotypes(GenotypeBuilder("sample1").alleles(listOf(Allele.ALT_A, Allele.ALT_A)).make())
            .make()

        val currentVarDelGap = VariantContextBuilder()
            .chr("chr1")
            .start(111)
            .stop(120)
            .alleles(listOf(Allele.create("AAAAAAAAAA",true), Allele.ALT_A))
            .genotypes(GenotypeBuilder("sample1").alleles(listOf(Allele.ALT_A, Allele.ALT_A)).make())
            .make()

        //Check null prev first
        //This one is the only odd one the rest can use the same checks
        val newNullSNPVariantOMIT = addAsmTagsToGvcf.buildNewVariantContext(1,null,currentVarDEL, MissingCategory.OMIT)
        assertEquals("chr1",newNullSNPVariantOMIT.first.contig)
        assertEquals(101, newNullSNPVariantOMIT.first.start)
        assertEquals(110,newNullSNPVariantOMIT.first.end)
        //Check the ASM_* Tags
        assertEquals("chr1",newNullSNPVariantOMIT.first.getAttribute("ASM_Chr",""))
        assertEquals(1,newNullSNPVariantOMIT.first.getAttribute("ASM_Start",-1))
        assertEquals(10,newNullSNPVariantOMIT.first.getAttribute("ASM_End",-1))
        assertEquals("+",newNullSNPVariantOMIT.first.getAttribute("ASM_Strand"))
        assertEquals(11, newNullSNPVariantOMIT.second)

        testREFOrDELNoGap(addAsmTagsToGvcf, 1,null, currentVarDEL,MissingCategory.AS_REF)
        testREFOrDELNoGap(addAsmTagsToGvcf, 1,null, currentVarDEL,MissingCategory.AS_N)

        testREFOrDELNoGap(addAsmTagsToGvcf, 101, prevVariant, currentVarDEL,MissingCategory.OMIT)
        testREFOrDELNoGap(addAsmTagsToGvcf, 101, prevVariant, currentVarDEL,MissingCategory.AS_REF)
        testREFOrDELNoGap(addAsmTagsToGvcf, 101,prevVariant, currentVarDEL,MissingCategory.AS_N)


        val newGapDELVariantOMIT = addAsmTagsToGvcf.buildNewVariantContext(1,prevVariant,currentVarDelGap, MissingCategory.OMIT)
        assertEquals("chr1",newGapDELVariantOMIT.first.contig)
        assertEquals(111, newGapDELVariantOMIT.first.start)
        assertEquals(120,newGapDELVariantOMIT.first.end)
        //Check the ASM_* Tags
        assertEquals("chr1",newGapDELVariantOMIT.first.getAttribute("ASM_Chr",""))
        assertEquals(1,newGapDELVariantOMIT.first.getAttribute("ASM_Start",-1))
        assertEquals(10,newGapDELVariantOMIT.first.getAttribute("ASM_End",-1))
        assertEquals("+",newGapDELVariantOMIT.first.getAttribute("ASM_Strand"))
        assertEquals(11, newGapDELVariantOMIT.second)

        testREFOrDELWithGap(addAsmTagsToGvcf, 101,prevVariant, currentVarDelGap, MissingCategory.AS_REF)
        testREFOrDELWithGap(addAsmTagsToGvcf, 101,prevVariant, currentVarDelGap, MissingCategory.AS_N)
    }


    private fun testREFOrDELNoGap(addAsmTagsToGvcf: AddAsmTagsToGvcf, currentStart: Int, prevVariant: VariantContext?, currentVarREFOrDEL: VariantContext, missingCategory: MissingCategory) {
        val newSNPVariant = addAsmTagsToGvcf.buildNewVariantContext(currentStart,prevVariant,currentVarREFOrDEL, missingCategory)
        assertEquals("chr1",newSNPVariant.first.contig)
        assertEquals(101, newSNPVariant.first.start)
        assertEquals(110,newSNPVariant.first.end)
        //Check the ASM_* Tags
        assertEquals("chr1",newSNPVariant.first.getAttribute("ASM_Chr",""))
        assertEquals(101,newSNPVariant.first.getAttribute("ASM_Start",-1))
        assertEquals(110,newSNPVariant.first.getAttribute("ASM_End",-1))
        assertEquals("+",newSNPVariant.first.getAttribute("ASM_Strand"))
        assertEquals(111, newSNPVariant.second)
    }

    private fun testREFOrDELWithGap(addAsmTagsToGvcf: AddAsmTagsToGvcf, currentStart: Int, prevVariant: VariantContext?, currentVarREFOrDEL: VariantContext, missingCategory: MissingCategory) {
        val newSNPVariant = addAsmTagsToGvcf.buildNewVariantContext(currentStart,prevVariant,currentVarREFOrDEL, missingCategory)
        assertEquals("chr1",newSNPVariant.first.contig)
        assertEquals(111, newSNPVariant.first.start)
        assertEquals(120,newSNPVariant.first.end)
        //Check the ASM_* Tags
        assertEquals("chr1",newSNPVariant.first.getAttribute("ASM_Chr",""))
        assertEquals(111,newSNPVariant.first.getAttribute("ASM_Start",-1))
        assertEquals(120,newSNPVariant.first.getAttribute("ASM_End",-1))
        assertEquals("+",newSNPVariant.first.getAttribute("ASM_Strand"))
        assertEquals(121, newSNPVariant.second)
    }

    private fun testSNPOrINSNoGap(addAsmTagsToGvcf: AddAsmTagsToGvcf, currentStart:Int, prevVariant: VariantContext?, currentVarSNPOrINS: VariantContext, missingCategory: MissingCategory) {
        val newSNPVariant = addAsmTagsToGvcf.buildNewVariantContext(currentStart,prevVariant,currentVarSNPOrINS, missingCategory)
        assertEquals("chr1",newSNPVariant.first.contig)
        assertEquals(101, newSNPVariant.first.start)
        assertEquals(101,newSNPVariant.first.end)
        //Check the ASM_* Tags
        assertEquals("chr1",newSNPVariant.first.getAttribute("ASM_Chr",""))
        assertEquals(101,newSNPVariant.first.getAttribute("ASM_Start",-1))
        assertEquals(101,newSNPVariant.first.getAttribute("ASM_End",-1))
        assertEquals("+",newSNPVariant.first.getAttribute("ASM_Strand"))
        assertEquals(102, newSNPVariant.second)
    }

    private fun testSNPOrINSWithGap(addAsmTagsToGvcf: AddAsmTagsToGvcf, currentStart:Int, prevVariant: VariantContext?, currentVarSNPOrINS: VariantContext, missingCategory: MissingCategory) {
        val newSNPVariant = addAsmTagsToGvcf.buildNewVariantContext(currentStart,prevVariant,currentVarSNPOrINS, missingCategory)
        assertEquals("chr1",newSNPVariant.first.contig)
        assertEquals(111, newSNPVariant.first.start)
        assertEquals(111,newSNPVariant.first.end)
        //Check the ASM_* Tags
        assertEquals("chr1",newSNPVariant.first.getAttribute("ASM_Chr",""))
        assertEquals(111,newSNPVariant.first.getAttribute("ASM_Start",-1))
        assertEquals(111,newSNPVariant.first.getAttribute("ASM_End",-1))
        assertEquals("+",newSNPVariant.first.getAttribute("ASM_Strand"))
        assertEquals(112, newSNPVariant.second)
    }


    @Test
    fun testExtractOutASMStart() {
        val addAsmTagsToGvcf = AddAsmTagsToGvcf()

        //Make some simple GVCF variants and make sure the ASMStart is updated correctly
        //Can do this with ref blocks as it is only looking at the start positions.
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
            "chr1",
            21,
            30,
            listOf(Allele.REF_A, Allele.NON_REF_ALLELE))
            .attribute("END", 30)
            .genotypes(listOf(GenotypeBuilder.create("sample1", listOf(Allele.REF_A))))
            .make()

        val refBlockVC3 = VariantContextBuilder(
            ".",
            "chr1",
            31,
            40,
            listOf(Allele.REF_A, Allele.NON_REF_ALLELE))
            .attribute("END", 40)
            .genotypes(listOf(GenotypeBuilder.create("sample1", listOf(Allele.REF_A))))
            .make()

        val refBlockVCChr2 = VariantContextBuilder(
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

        val refBlockDiffChromOMIT = addAsmTagsToGvcf.extractOutASMStart(refBlockVC1, refBlockVCChr2, 1, MissingCategory.OMIT)
        //should be 1
        assertEquals(1, refBlockDiffChromOMIT)

        val refBlockDiffChromASREF = addAsmTagsToGvcf.extractOutASMStart(refBlockVC1, refBlockVCChr2, 1, MissingCategory.AS_REF)
        //should be 10
        assertEquals(10, refBlockDiffChromASREF)
        val refBlockDiffChromASN = addAsmTagsToGvcf.extractOutASMStart(refBlockVC1, refBlockVCChr2, 1, MissingCategory.AS_N)
        //should be 10
        assertEquals(10, refBlockDiffChromASN)


        //Check consecutive blocks  This is just a passthrough
        val refBlockNextOMIT = addAsmTagsToGvcf.extractOutASMStart(refBlockVC1,refBlockVC2, 11, MissingCategory.OMIT)
        assertEquals(11, refBlockNextOMIT)

        val refBlockNextASREF = addAsmTagsToGvcf.extractOutASMStart(refBlockVC1,refBlockVC2, 11, MissingCategory.AS_REF)
        assertEquals(11, refBlockNextASREF)

        val refBlockNextASN = addAsmTagsToGvcf.extractOutASMStart(refBlockVC1,refBlockVC2, 11, MissingCategory.AS_N)
        assertEquals(11, refBlockNextASN)


        //RefBlocks with a gap
        val refBlockGapOMIT = addAsmTagsToGvcf.extractOutASMStart(refBlockVC1,refBlockVC3, 11, MissingCategory.OMIT)
        assertEquals(11, refBlockGapOMIT)


        val refBlockGapASREF = addAsmTagsToGvcf.extractOutASMStart(refBlockVC1,refBlockVC3, 11, MissingCategory.AS_REF)
        assertEquals(21, refBlockGapASREF)

        val refBlockGapASN = addAsmTagsToGvcf.extractOutASMStart(refBlockVC1,refBlockVC3, 11, MissingCategory.AS_N)
        assertEquals(21, refBlockGapASN)



    }
}