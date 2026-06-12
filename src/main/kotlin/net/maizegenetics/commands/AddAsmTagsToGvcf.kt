package net.maizegenetics.net.maizegenetics.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.path
import htsjdk.variant.variantcontext.VariantContext
import htsjdk.variant.variantcontext.VariantContextBuilder
import htsjdk.variant.variantcontext.writer.Options
import htsjdk.variant.variantcontext.writer.VariantContextWriterBuilder
import htsjdk.variant.vcf.VCFFileReader
import java.io.File

//Enum class for missing types: 'asRef', 'asN', 'omit'
enum class MissingCategory(val value: String) {
    AS_REF("asRef"),
    AS_N("asN"),
    OMIT("omit")
}

class AddAsmTagsToGvcf: CliktCommand(name = "add-asm-tags-to-gvcf") {
    private val inputDir by option(help = "Input GVCF dir")
        .path(canBeFile = false, canBeDir = true)
        .required()


    private val outputDir by option(help = "Output dir")
        .path(canBeFile = false, canBeDir = true)
        .required()


    private val missingCategory by option(
        help = "Processing category for the data"
    ).enum<MissingCategory> { it.value }.default(MissingCategory.AS_REF)

    override fun run() {
        // walk the gvcf directory process files with g.vcf.gz extension
        inputDir.toFile().walk().filter { !it.isHidden && !it.isDirectory }
            .filter { it.name.endsWith("g.vcf.gz")  || it.name.endsWith("g.vcf") ||
                    it.name.endsWith(".gvcf") || it.name.endsWith(".gvcf.gz") }.toList()
            .forEach {
                println("AddAsmTagsToGvcf: processing gvcf file: ${it.name}")
                // Create list of VariantContext from the gvcf file
                val outputFile = outputDir.resolve(it.name)
                addAsmTagsToGvcf(it, outputFile.toFile(), missingCategory)
            }
    }

    fun addAsmTagsToGvcf(inputFile: File, outputFile: File, missingCategory: MissingCategory) {
        // read the gvcf file and add the ASM tags based on the missing category
        // write the output to the output file
        // This is a placeholder for the actual implementation
        println("Processing file: ${inputFile.absolutePath}")
        println("Output file: ${outputFile.absolutePath}")
        println("Missing category: ${missingCategory.value}")


        //Load in the input file into a VCFFileReader
        val fileReader = VCFFileReader(inputFile)
        val header = fileReader.header

        val outputWriter = VariantContextWriterBuilder()
            .unsetOption(Options.INDEX_ON_THE_FLY)
            .setOutputFile(outputFile)
            .setOutputFileType(VariantContextWriterBuilder.OutputType.VCF)
            .setOption(Options.ALLOW_MISSING_FIELDS_IN_HEADER)
            .build()

        outputWriter.writeHeader(header)

        var prevVariant: VariantContext? = null
        var currentStart = 1

        fileReader.forEach { variant ->
            // Process the variant and add the ASM tags based on the missing category
            currentStart = extractOutASMStart(prevVariant, variant, currentStart, missingCategory)


            val refLength = variant.end - variant.start + 1
            val asmEnd = currentStart + refLength

            val newVariant = VariantContextBuilder(variant)
                .attribute("ASM_START", currentStart)
                .attribute("ASM_END", asmEnd)
                .attribute("ASM_Strand","+")
                .make()

            currentStart = asmEnd+1

            // Add the ASM tags based on the missing category
            // This is a placeholder for the actual implementation
            outputWriter.add(newVariant)
            prevVariant = newVariant
        }


    }

    fun extractOutASMStart(
        prevVariant: VariantContext?,
        variant: VariantContext,
        prevASMStart: Int,
        missingCategory: MissingCategory
    ): Int {
        var newASMStart = prevASMStart
        if (prevVariant == null || prevVariant.contig != variant.contig) {
            //Need to set/reset the asm start coordinate depending on the missingCategory
            newASMStart = when (missingCategory) {
                MissingCategory.OMIT -> 1
                MissingCategory.AS_REF, MissingCategory.AS_N -> variant.start
            }
        }
        //Need to check if we have a gap because it can change things a bit.
        // i.e. prevVariant ends at 15 and variant starts at 20 need to shift the currentStart need gap of 4 [16,19]
        else if (variant.start - prevVariant.end > 1) {
            val gap = variant.start - prevVariant.end - 1
            newASMStart = when (missingCategory) {
                MissingCategory.OMIT -> newASMStart
                MissingCategory.AS_REF, MissingCategory.AS_N -> newASMStart + gap
            }
        }
        return newASMStart
    }

}