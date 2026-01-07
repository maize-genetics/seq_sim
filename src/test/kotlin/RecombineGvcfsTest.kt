import htsjdk.variant.variantcontext.Allele
import htsjdk.variant.variantcontext.GenotypeBuilder
import htsjdk.variant.variantcontext.VariantContextBuilder
import net.maizegenetics.net.maizegenetics.commands.RecombineGvcfs
import kotlin.test.DefaultAsserter.assertEquals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RecombineGvcfsTest {

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
        assertEquals("Alleles were not preserved", originalVariantContext.genotypes.first().alleles, renamed.genotypes.first().alleles)
        //check that the rest of the variant matches and that Sample1 does not exist
        assertEquals("Contigs do not match",originalVariantContext.contig, renamed.contig)
        assertEquals("Start positions do not match",originalVariantContext.start, renamed.start)
        assertEquals("Stop positions do not match",originalVariantContext.end, renamed.end)
        assertEquals("IDs do not match",originalVariantContext.id, renamed.id)
        assertEquals("Alleles do not match",originalVariantContext.alleles, renamed.alleles)
        assertFalse( renamed.genotypes.sampleNames.contains("Sample1"), "Old sample name still exists")
    }
}