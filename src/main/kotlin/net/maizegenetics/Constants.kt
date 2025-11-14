package net.maizegenetics

/**
 * Central constants used across the application
 */
object Constants {
    // Directory names
    const val DEFAULT_WORK_DIR = "seq_sim_work"
    const val SRC_DIR = "src"
    const val LOGS_DIR = "logs"

    // File extensions
    val FASTA_EXTENSIONS = setOf("fa", "fasta", "fna")
    val MAF_EXTENSIONS = setOf("maf")
    val GVCF_EXTENSIONS = setOf("gvcf", "gvcf.gz")
    const val TEXT_FILE_EXTENSION = "txt"

    // Download URLs
    const val MLIMPUTE_URL = "https://github.com/maize-genetics/MLImpute/archive/refs/heads/main.zip"
    const val BIOKOTLIN_TOOLS_URL = "https://github.com/maize-genetics/biokotlin-tools/releases/latest/download/biokotlin-tools.tar.gz"

    // Directory names for downloaded tools
    const val MLIMPUTE_DIR = "MLImpute"
    const val BIOKOTLIN_TOOLS_DIR = "biokotlin-tools"
}
