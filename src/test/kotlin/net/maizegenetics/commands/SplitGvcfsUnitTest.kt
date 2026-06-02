package net.maizegenetics.commands

import com.github.ajalt.clikt.core.parse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [SplitGvcfs] (v2 pipeline step 03). Covers keyfile parsing
 * (full vs non-full rows, header detection and fallback) and the end-to-end
 * split that copies base / mutation-donor gVCFs and writes the path + pairs
 * files. No external tools are involved (dummy gVCF files are sufficient
 * since the split never parses VCF content).
 */
class SplitGvcfsUnitTest {

    private fun writeKeyfile(dir: Path, contents: String): Path {
        val keyfile = dir.resolve("keyfile.txt")
        keyfile.writeText(contents.trimIndent() + "\n")
        return keyfile
    }

    @Test
    fun parseKeyfileExcludesNonFullRows(@TempDir tmp: Path) {
        val keyfile = writeKeyfile(
            tmp,
            """
            Base	MutationDonor
            baseA	donor1
            baseB	
            	donor2
            baseC	donor3
            """
        )

        val pairs = SplitGvcfs().parseKeyfile(keyfile)

        assertEquals(2, pairs.size, "Only the two full rows should be kept")
        assertEquals(SplitGvcfs.KeyfilePair("baseA", "donor1"), pairs[0])
        assertEquals(SplitGvcfs.KeyfilePair("baseC", "donor3"), pairs[1])
    }

    @Test
    fun parseKeyfileFallsBackToFirstTwoColumns(@TempDir tmp: Path) {
        // Header tokens are not the recognized names, so columns 1 & 2 are
        // assumed and the first row is treated as a header (skipped).
        val keyfile = writeKeyfile(
            tmp,
            """
            sample	donor
            baseA	donor1
            baseB	donor2
            """
        )

        val pairs = SplitGvcfs().parseKeyfile(keyfile)

        assertEquals(2, pairs.size)
        assertEquals(SplitGvcfs.KeyfilePair("baseA", "donor1"), pairs[0])
        assertEquals(SplitGvcfs.KeyfilePair("baseB", "donor2"), pairs[1])
    }

    @Test
    fun parseKeyfileRespectsColumnOrderFromHeader(@TempDir tmp: Path) {
        // MutationDonor before Base -- indices must follow the header, not position.
        val keyfile = writeKeyfile(
            tmp,
            """
            MutationDonor	Base
            donor1	baseA
            """
        )

        val pairs = SplitGvcfs().parseKeyfile(keyfile)
        assertEquals(1, pairs.size)
        assertEquals(SplitGvcfs.KeyfilePair("baseA", "donor1"), pairs[0])
    }

    @Test
    fun endToEndSplitProducesDirsPathFilesAndPairs(@TempDir workDir: Path) {
        // The command validates the working directory exists.
        workDir.createDirectories()

        val gvcfDir = workDir.resolve("gvcfs").also { it.createDirectories() }
        // base shared between two pairs; two distinct donors.
        gvcfDir.resolve("baseA.g.vcf.gz").writeText("dummy")
        gvcfDir.resolve("baseB.g.vcf.gz").writeText("dummy")
        gvcfDir.resolve("donor1.g.vcf.gz").writeText("dummy")
        gvcfDir.resolve("donor2.g.vcf.gz").writeText("dummy")
        // An extra gVCF not referenced by the keyfile should be ignored.
        gvcfDir.resolve("unused.g.vcf.gz").writeText("dummy")

        val keyfile = writeKeyfile(
            workDir,
            """
            Base	MutationDonor
            baseA	donor1
            baseB	donor2
            baseB	
            """
        )

        SplitGvcfs().parse(
            listOf(
                "--work-dir", workDir.toString(),
                "--keyfile", keyfile.toString(),
                "--gvcf-dir", gvcfDir.toString()
            )
        )

        val outDir = workDir.resolve("output/03_split_gvcfs_results")
        val baseDir = outDir.resolve("base")
        val donorDir = outDir.resolve("mutation_donor")

        assertTrue(baseDir.resolve("baseA.g.vcf.gz").exists(), "baseA should be copied")
        assertTrue(baseDir.resolve("baseB.g.vcf.gz").exists(), "baseB should be copied")
        assertTrue(donorDir.resolve("donor1.g.vcf.gz").exists(), "donor1 should be copied")
        assertTrue(donorDir.resolve("donor2.g.vcf.gz").exists(), "donor2 should be copied")
        assertTrue(!baseDir.resolve("unused.g.vcf.gz").exists(), "unused gVCF must not be copied")

        // Path files
        val basePaths = baseDir.resolve("base_gvcf_paths.txt")
        val donorPaths = donorDir.resolve("mutation_donor_gvcf_paths.txt")
        assertTrue(basePaths.exists())
        assertTrue(donorPaths.exists())
        assertEquals(2, basePaths.readLines().filter { it.isNotBlank() }.size)
        assertEquals(2, donorPaths.readLines().filter { it.isNotBlank() }.size)

        // Pairs file: header + 2 full resolved rows (the empty-donor row excluded).
        val pairs = outDir.resolve("pairs.tsv").readLines().filter { it.isNotBlank() }
        assertEquals(listOf("Base\tMutationDonor", "baseA\tdonor1", "baseB\tdonor2"), pairs)
    }
}
