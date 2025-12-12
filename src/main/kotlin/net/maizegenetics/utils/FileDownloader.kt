package net.maizegenetics.utils

import org.apache.logging.log4j.Logger
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.net.URI
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists

object FileDownloader {
    fun downloadAndExtractZip(url: String, destDir: Path, logger: Logger): Boolean {
        return try {
            logger.info("Downloading from: $url")
            val urlObj = URI(url).toURL()

            urlObj.openStream().use { inputStream ->
                ZipInputStream(inputStream).use { zipStream ->
                    var entry = zipStream.nextEntry
                    while (entry != null) {
                        val destFile = destDir.resolve(entry.name).toFile()

                        if (entry.isDirectory) {
                            destFile.mkdirs()
                        } else {
                            destFile.parentFile.mkdirs()
                            destFile.outputStream().use { output ->
                                zipStream.copyTo(output)
                            }
                        }
                        zipStream.closeEntry()
                        entry = zipStream.nextEntry
                    }
                }
            }
            logger.info("Download and extraction completed successfully")
            true
        } catch (e: Exception) {
            logger.error("Failed to download from $url: ${e.message}", e)
            false
        }
    }

    fun copyResourceToFile(resourcePath: String, destFile: File, logger: Logger): Boolean {
        return try {
            val resourceStream = FileDownloader::class.java.getResourceAsStream(resourcePath)
            if (resourceStream == null) {
                logger.error("Resource not found: $resourcePath")
                return false
            }

            logger.debug("Copying resource $resourcePath to ${destFile.absolutePath}")
            resourceStream.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            logger.info("Resource copied successfully")
            true
        } catch (e: Exception) {
            logger.error("Failed to copy resource: ${e.message}", e)
            false
        }
    }

    fun downloadAndExtractTar(url: String, destDir: Path, logger: Logger): Boolean {
        var tempFile: Path? = null
        return try {
            logger.info("Downloading tar file from: $url")

            // Determine file extension and create appropriate temp file
            val isGzipped = url.endsWith(".tar.gz") || url.endsWith(".tgz")
            val suffix = if (isGzipped) ".tar.gz" else ".tar"
            tempFile = createTempFile(suffix = suffix)

            // Download the file
            val urlObj = URI(url).toURL()
            urlObj.openStream().use { inputStream ->
                tempFile.toFile().outputStream().use { output ->
                    inputStream.copyTo(output)
                }
            }
            logger.info("Download completed")

            // Extract using tar command with appropriate flags
            logger.info("Extracting tar file to: $destDir")
            val tarFlags = if (isGzipped) "-xzf" else "-xf"
            val exitCode = ProcessRunner.runCommand(
                "tar", tarFlags, tempFile.toString(),
                "-C", destDir.toString(),
                logger = logger
            )

            if (exitCode != 0) {
                logger.error("Failed to extract tar file")
                return false
            }

            logger.info("Extraction completed successfully")
            true
        } catch (e: Exception) {
            logger.error("Failed to download and extract tar from $url: ${e.message}", e)
            false
        } finally {
            // Clean up temp file
            tempFile?.deleteIfExists()
        }
    }

    /**
     * Downloads and extracts a tar asset from the latest GitHub release.
     * @param apiUrl The GitHub API URL (e.g., "https://api.github.com/repos/owner/repo/releases/latest")
     * @param destDir The destination directory for extraction
     * @param logger The logger instance
     * @return true if successful, false otherwise
     */
    fun downloadLatestGitHubReleaseTar(apiUrl: String, destDir: Path, logger: Logger): Boolean {
        return try {
            logger.info("Fetching latest release info from: $apiUrl")
            val releaseInfo = URI(apiUrl).toURL().openStream().use { inputStream ->
                Yaml().load<Map<String, Any>>(inputStream)
            }

            val assets = releaseInfo["assets"] as? List<*> ?: run {
                logger.error("Could not find assets in GitHub API response")
                return false
            }

            val tarAsset = assets.asSequence()
                .mapNotNull { it as? Map<*, *> }
                .firstOrNull { asset ->
                    val name = asset["name"] as? String
                    name?.endsWith(".tar") == true || name?.endsWith(".tar.gz") == true
                } ?: run {
                logger.error("Could not find .tar asset in GitHub release")
                return false
            }

            val downloadUrl = tarAsset["browser_download_url"] as? String ?: run {
                logger.error("Could not find download URL for tar asset")
                return false
            }

            logger.info("Found tar asset: ${tarAsset["name"]}")
            downloadAndExtractTar(downloadUrl, destDir, logger)
        } catch (e: Exception) {
            logger.error("Failed to download latest GitHub release tar from $apiUrl: ${e.message}", e)
            false
        }
    }
}
