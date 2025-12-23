package net.maizegenetics.net.maizegenetics.utils

import htsjdk.variant.vcf.VCFFormatHeaderLine
import htsjdk.variant.vcf.VCFHeader
import htsjdk.variant.vcf.VCFHeaderLine
import htsjdk.variant.vcf.VCFHeaderLineCount
import htsjdk.variant.vcf.VCFHeaderLineType
import htsjdk.variant.vcf.VCFInfoHeaderLine
import java.util.HashSet

data class Position(val contig: String, val position: Int) : Comparable<Position> {
    override fun compareTo(other: Position): Int {

        if (this.contig == other.contig) {
            return this.position.compareTo(other.position)
        }

        val thisContig = this.contig.replace("chr", "", ignoreCase = true).trim()
        val otherContig = other.contig.replace("chr", "", ignoreCase = true).trim()

        return try {
            thisContig.toInt() - otherContig.toInt()
        } catch (e: NumberFormatException) {
            // If we can't convert contigs to an int, then compare the strings
            contig.compareTo(other.contig)
        }

    }

    override fun toString(): String {
        return "$contig:$position"
    }
}
data class SimpleVariant(val refStart: Position, val refEnd: Position,
                         val refAllele: String, val altAllele: String,
                         val isAddedMutation: Boolean = false)

class VariantContextUtils {
    companion object {
        fun createGenericHeader(taxaNames: List<String>, altLines:Set<VCFHeaderLine>): VCFHeader {
            val headerLines = createGenericHeaderLineSet() as MutableSet<VCFHeaderLine>
            headerLines.addAll(altLines)
            return VCFHeader(headerLines, taxaNames)
        }

        fun createGenericHeaderLineSet(): Set<VCFHeaderLine> {
            val headerLines: MutableSet<VCFHeaderLine> = HashSet()
            headerLines.add(VCFFormatHeaderLine("AD", 3, VCFHeaderLineType.Integer, "Allelic depths for the ref and alt alleles in the order listed"))
            headerLines.add(
                VCFFormatHeaderLine("DP", 1, VCFHeaderLineType.Integer, "Read Depth (only filtered reads used for calling)")
            )
            headerLines.add(VCFFormatHeaderLine("GQ", 1, VCFHeaderLineType.Integer, "Genotype Quality"))
            headerLines.add(VCFFormatHeaderLine("GT", 1, VCFHeaderLineType.String, "Genotype"))
            headerLines.add(
                VCFFormatHeaderLine("PL", VCFHeaderLineCount.G, VCFHeaderLineType.Integer, "Normalized, Phred-scaled likelihoods for genotypes as defined in the VCF specification")
            )
            headerLines.add(VCFInfoHeaderLine("DP", 1, VCFHeaderLineType.Integer, "Total Depth"))
            headerLines.add(VCFInfoHeaderLine("NS", 1, VCFHeaderLineType.Integer, "Number of Samples With Data"))
            headerLines.add(VCFInfoHeaderLine("AF", 3, VCFHeaderLineType.Integer, "Allele Frequency"))
            headerLines.add(VCFInfoHeaderLine("END", 1, VCFHeaderLineType.Integer, "Stop position of the interval"))
            // These last 4 are needed for assembly g/hvcfs, but not for reference.  I am keeping them in as header
            // lines but they will only be added to the data lines if they are present in the VariantContext.
            headerLines.add(VCFInfoHeaderLine("ASM_Chr", 1, VCFHeaderLineType.String, "Assembly chromosome"))
            headerLines.add(VCFInfoHeaderLine("ASM_Start", 1, VCFHeaderLineType.Integer, "Assembly start position"))
            headerLines.add(VCFInfoHeaderLine("ASM_End", 1, VCFHeaderLineType.Integer, "Assembly end position"))
            headerLines.add(VCFInfoHeaderLine("ASM_Strand", 1, VCFHeaderLineType.String, "Assembly strand"))
            return headerLines
        }

        fun isIndel(variant: SimpleVariant?): Boolean {
            if(variant == null) return false
            return !((variant.refAllele.length == 1 && variant.altAllele.length == 1) || //SNP
                    (variant.refAllele.length == 1 && variant.altAllele == "<NON_REF>")) //RefBlock
        }
    }
}