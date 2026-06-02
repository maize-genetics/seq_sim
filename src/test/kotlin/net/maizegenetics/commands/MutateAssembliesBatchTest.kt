package net.maizegenetics.commands

import com.github.ajalt.clikt.core.parse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the batch (keyfile-driven) mode of [MutateAssemblies]
 * (v2 pipeline step 05): pairs-file parsing, donor-variant matching, and an
 * end-to-end batch run over the shared MutateAssemblies fixtures.
 */
class MutateAssembliesBatchTest {

    private fun writePairs(dir: Path, contents: String): Path {
        val pairs = dir.resolve("pairs.tsv")
        pairs.writeText(contents.trimIndent() + "\n")
        return pairs
    }

    @Test
    fun parsePairsExcludesNonFullRows(@TempDir tmp: Path) {
        val pairsFile = writePairs(
            tmp,
            """
            Base	MutationDonor
            baseA	donor1
            baseB	
            	donor2
            baseC	donor3
            """
        )

        val pairs = MutateAssemblies().parsePairs(pairsFile)

        assertEquals(
            listOf("baseA" to "donor1", "baseC" to "donor3"),
            pairs
        )
    }

    @Test
    fun donorVariantsForMatchesOnNameBoundary(@TempDir tmp: Path) {
        val files = listOf(
            "donor1.gvcf",
            "donor1_subsampled.gvcf",
            "donor1_0.1_subsampled.gvcf",
            "donor10_subsampled.gvcf", // must NOT match "donor1"
            "donor2_subsampled.gvcf"
        ).map { tmp.resolve(it) }

        val matches = MutateAssemblies().donorVariantsFor("donor1", files)
            .map { it.fileName.toString() }
            .toSet()

        assertEquals(
            setOf("donor1.gvcf", "donor1_subsampled.gvcf", "donor1_0.1_subsampled.gvcf"),
            matches
        )
    }

    @Test
    fun batchRunMutatesEachBaseDonorVariantPair(@TempDir workDir: Path) {
        workDir.createDirectories()

        val baseDir = workDir.resolve("base").also { it.createDirectories() }
        val donorDir = workDir.resolve("mutation_donor").also { it.createDirectories() }
        val outputDir = workDir.resolve("mutated")

        // Reuse the shared fixtures. Base sample resolves from the file name.
        Path.of("data/MutateAssemblies/base.g.vcf").copyTo(baseDir.resolve("baseSample.g.vcf"))
        // Two downsampled variants of the same donor -> two outputs for the pair.
        Path.of("data/MutateAssemblies/mutationDonor.g.vcf")
            .copyTo(donorDir.resolve("donorSample_r0.1_subsampled.gvcf"))
        Path.of("data/MutateAssemblies/mutationDonor.g.vcf")
            .copyTo(donorDir.resolve("donorSample_r0.2_subsampled.gvcf"))

        val pairsFile = writePairs(
            workDir,
            """
            Base	MutationDonor
            baseSample	donorSample
            """
        )

        MutateAssemblies().parse(
            listOf(
                "--work-dir", workDir.toString(),
                "--keyfile", pairsFile.toString(),
                "--base-dir", baseDir.toString(),
                "--mutation-donor-dir", donorDir.toString(),
                "--output-dir", outputDir.toString()
            )
        )

        val out1 = outputDir.resolve("baseSample__donorSample_r0.1_subsampled_mutated.g.vcf")
        val out2 = outputDir.resolve("baseSample__donorSample_r0.2_subsampled_mutated.g.vcf")
        assertTrue(out1.exists(), "Mutated output for rate 0.1 should exist")
        assertTrue(out2.exists(), "Mutated output for rate 0.2 should exist")

        val pathsFile = outputDir.resolve("mutated_gvcf_file_paths.txt")
        assertTrue(pathsFile.exists())
        assertEquals(2, pathsFile.readLines().filter { it.isNotBlank() }.size)
    }
}
