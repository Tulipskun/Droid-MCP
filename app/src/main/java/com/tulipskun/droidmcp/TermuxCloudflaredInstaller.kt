package com.tulipskun.droidmcp

import android.content.Context
import android.os.Build
import android.system.Os
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import org.tukaani.xz.XZInputStream

object TermuxCloudflaredInstaller {
    private const val PACKAGE_NAME = "cloudflared"
    private const val PACKAGE_DIR = "cloudflared"
    private const val BINARY_NAME = "cloudflared"
    private const val MAX_PACKAGE_SIZE = 20L * 1024L * 1024L

    private val repositoryBases = listOf(
        "https://packages.termux.dev/apt/termux-main",
        "https://packages-cf.termux.dev/apt/termux-main"
    )

    data class PackageInfo(
        val version: String,
        val filename: String,
        val size: Long,
        val sha256: String
    )

    @Synchronized
    fun ensureInstalled(context: Context): File {
        setInstallState(context, "Checking cloudflared")
        val directory = File(context.filesDir, PACKAGE_DIR)
        val binary = File(directory, BINARY_NAME)
        val versionFile = File(directory, "version")
        val installedVersion = versionFile.takeIf { it.isFile }?.readText()?.trim()
        val architecture = resolveRepositoryArchitecture()
        val runner = cloudflaredRunner(context)

        if (!runner.isFile || !runner.canExecute()) {
            throw IllegalStateException(
                "Cloudflared runner is missing: " + runner.absolutePath
            )
        }

        if (binary.isFile && binary.length() > 1024 * 1024) {
            try {
                val info = fetchPackageInfo(architecture)
                if (installedVersion == info.version) {
                    verifyBinary(runner, binary)
                    setInstallState(context, "cloudflared ready")
                    return binary
                }
                setInstallState(context, "Updating cloudflared")
                installPackage(runner, directory, binary, versionFile, info)
                return binary
            } catch (error: Throwable) {
                if (!installedVersion.isNullOrBlank()) {
                    verifyBinary(runner, binary)
                    setInstallState(context, "cloudflared ready")
                    return binary
                }
                throw error
            }
        }

        val info = fetchPackageInfo(architecture)
        setInstallState(context, "Installing cloudflared")
        installPackage(runner, directory, binary, versionFile, info)
        setInstallState(context, "cloudflared ready")
        return binary
    }

    private fun resolveRepositoryArchitecture(): String {
        return when {
            Build.SUPPORTED_ABIS.any { it == "arm64-v8a" } -> "aarch64"
            Build.SUPPORTED_ABIS.any { it == "armeabi-v7a" } -> "arm"
            Build.SUPPORTED_ABIS.any { it == "x86_64" } -> "x86_64"
            Build.SUPPORTED_ABIS.any { it == "x86" } -> "i686"
            else -> throw IllegalStateException(
                "Unsupported Android ABI: " + Build.SUPPORTED_ABIS.joinToString()
            )
        }
    }

    private fun fetchPackageInfo(architecture: String): PackageInfo {
        var lastError: Throwable? = null

        for (base in repositoryBases) {
            try {
                return fetchPackageInfo(base, architecture)
            } catch (error: Throwable) {
                lastError = error
            }
        }

        throw IllegalStateException(
            "Could not reach Termux repository" +
                (lastError?.message?.let { ": " + it } ?: "")
        )
    }

    private fun fetchPackageInfo(base: String, architecture: String): PackageInfo {
        val packagesUrl =
            base + "/dists/stable/main/binary-" + architecture + "/Packages.gz"
        val packages = downloadBytes(packagesUrl, 10L * 1024L * 1024L)
        val text = GZIPInputStream(packages.inputStream())
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

        val stanza = text
            .split("\n\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { parseStanza(it) }
            .firstOrNull {
                it["Package"] == PACKAGE_NAME &&
                    it["Architecture"] == architecture &&
                    !it["Filename"].isNullOrBlank() &&
                    !it["Version"].isNullOrBlank() &&
                    !it["Size"].isNullOrBlank() &&
                    !it["SHA256"].isNullOrBlank()
            }
            ?: throw IllegalStateException(
                "Package " + PACKAGE_NAME + " for " + architecture +
                    " was not found in Termux repository"
            )

        return PackageInfo(
            version = stanza.getValue("Version"),
            filename = stanza.getValue("Filename"),
            size = stanza.getValue("Size").toLong(),
            sha256 = stanza.getValue("SHA256")
        )
    }

    private fun parseStanza(stanza: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var currentKey: String? = null

        for (line in stanza.lines()) {
            if (line.startsWith(" ") && currentKey != null) {
                result[currentKey] = result.getValue(currentKey) +
                    "\n" + line.substring(1)
                continue
            }

            val separator = line.indexOf(':')
            if (separator <= 0) continue

            val key = line.substring(0, separator)
            val value = line.substring(separator + 1).trim()
            result[key] = value
            currentKey = key
        }

        return result
    }

    private fun installPackage(
        runner: File,
        directory: File,
        binary: File,
        versionFile: File,
        info: PackageInfo
    ) {
        if (info.size <= 0 || info.size > MAX_PACKAGE_SIZE) {
            throw IllegalStateException(
                "Unexpected Termux cloudflared package size: " + info.size
            )
        }

        val packageBytes = repositoryBases
            .asSequence()
            .map { it + "/" + info.filename }
            .mapNotNull { url ->
                try {
                    val bytes = downloadBytes(url, MAX_PACKAGE_SIZE)
                    if (bytes.size.toLong() != info.size) return@mapNotNull null
                    if (!sha256(bytes).equals(info.sha256, ignoreCase = true)) {
                        return@mapNotNull null
                    }
                    bytes
                } catch (_: Throwable) {
                    null
                }
            }
            .firstOrNull()
            ?: throw IllegalStateException(
                "Could not download cloudflared from Termux repository"
            )

        directory.mkdirs()
        val tempPackage = File.createTempFile("cloudflared-", ".deb", directory)
        val tempBinary = File.createTempFile("cloudflared-", ".tmp", directory)

        try {
            FileOutputStream(tempPackage).use { it.write(packageBytes) }
            extractCloudflared(tempPackage, tempBinary)
            Os.chmod(tempBinary.absolutePath, 700)
            tempBinary.setExecutable(true, true)

            if (!tempBinary.isFile || tempBinary.length() <= 1024 * 1024) {
                throw IllegalStateException(
                    "Extracted cloudflared is invalid: size=" + tempBinary.length()
                )
            }

            verifyBinary(runner, tempBinary)

            if (binary.exists() && !binary.delete()) {
                throw IllegalStateException("Could not replace existing cloudflared")
            }

            if (!tempBinary.renameTo(binary)) {
                throw IllegalStateException("Could not install cloudflared")
            }

            versionFile.writeText(info.version)
            Os.chmod(binary.absolutePath, 700)
        } finally {
            tempPackage.delete()
            tempBinary.delete()
        }
    }


    private fun cloudflaredRunner(context: Context): File {
        return File(
            context.applicationInfo.nativeLibraryDir,
            "libcloudflared_runner.so"
        )
    }

    private fun verifyBinary(runner: File, binary: File) {
        try {
            val process = ProcessBuilder(
                runner.absolutePath,
                binary.absolutePath,
                "version"
            )
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw IllegalStateException(
                    "cloudflared execution failed (" + exitCode + "): " +
                        output.trim().take(500)
                )
            }
        } catch (error: Throwable) {
            throw IllegalStateException(
                "Extracted cloudflared cannot execute: " +
                    (error.message ?: error.javaClass.simpleName),
                error
            )
        }
    }

    private fun extractCloudflared(deb: File, output: File) {
        FileInputStream(deb).buffered().use { input ->
            val magic = ByteArray(8)
            readFully(input, magic)
            check(String(magic, Charsets.US_ASCII) == "!<arch>\n") {
                "Invalid Debian package"
            }

            var found = false

            while (true) {
                val header = ByteArray(60)
                val count = input.read(header)
                if (count < 0) break
                check(count == 60) { "Truncated Debian package header" }

                val name = String(header, 0, 16, Charsets.US_ASCII)
                    .trim()
                    .removeSuffix("/")
                val size = parseDecimal(header, 48, 10)

                if (name == "data.tar.xz") {
                    val limited = LimitedInputStream(input, size)
                    XZInputStream(BufferedInputStream(limited)).use { xz ->
                        extractTarEntry(xz, output)
                    }
                    skipFully(input, size - limited.consumed)
                    if ((size and 1L) != 0L) {
                        skipFully(input, 1)
                    }
                    found = true
                    break
                }

                skipFully(input, size)
                if ((size and 1L) != 0L) {
                    skipFully(input, 1)
                }
            }

            check(found) { "Termux package does not contain data.tar.xz" }
        }
    }

    private fun extractTarEntry(input: InputStream, output: File) {
        val buffer = ByteArray(8192)

        while (true) {
            val header = ByteArray(512)
            val count = readAtMost(input, header)
            if (count == 0) break
            check(count == 512) { "Truncated tar header" }
            if (header.all { it.toInt() == 0 }) break

            val name = tarName(header)
            val size = parseOctal(header, 124, 12)
            val type = header[156].toInt().toChar()

            if (
                (type == '\u0000' || type == '0') &&
                (name == "./data/data/com.termux/files/usr/bin/cloudflared" ||
                    name == "data/data/com.termux/files/usr/bin/cloudflared")
            ) {
                FileOutputStream(output).use { out ->
                    var remaining = size
                    while (remaining > 0) {
                        val wanted = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = input.read(buffer, 0, wanted)
                        check(read > 0) { "Truncated cloudflared payload" }
                        out.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            } else {
                skipFully(input, size)
            }

            val padding = (512L - (size % 512L)) % 512L
            skipFully(input, padding)
        }

        if (!output.isFile) {
            throw IllegalStateException(
                "cloudflared executable was not found in Termux package"
            )
        }
    }

    private fun tarName(header: ByteArray): String {
        val name = asciiField(header, 0, 100)
        val prefix = asciiField(header, 345, 155)
        return if (prefix.isBlank()) name else prefix + "/" + name
    }

    private fun asciiField(buffer: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = offset + length
        while (end < limit && buffer[end].toInt() != 0) {
            end++
        }
        return String(buffer, offset, end - offset, Charsets.US_ASCII).trim()
    }

    private fun parseDecimal(buffer: ByteArray, offset: Int, length: Int): Long {
        return asciiField(buffer, offset, length).toLong()
    }

    private fun parseOctal(buffer: ByteArray, offset: Int, length: Int): Long {
        val value = asciiField(buffer, offset, length)
        return if (value.isEmpty()) 0L else value.toLong(8)
    }

    private fun readAtMost(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read < 0) break
            if (read == 0) continue
            total += read
        }
        return total
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            check(read > 0) { "Unexpected end of stream" }
            offset += read
        }
    }

    private fun skipFully(input: InputStream, length: Long) {
        var remaining = length
        val buffer = ByteArray(8192)

        while (remaining > 0) {
            val read = input.read(
                buffer,
                0,
                minOf(buffer.size.toLong(), remaining).toInt()
            )
            check(read > 0) { "Unexpected end of stream" }
            remaining -= read
        }
    }

    private fun downloadBytes(url: String, maxSize: Long): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Droid-MCP")
        }

        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "HTTP " + connection.responseCode + " from " + url
            }

            val announcedSize = connection.contentLengthLong
            if (announcedSize > maxSize) {
                throw IllegalStateException(
                    "Download is too large: " + announcedSize
                )
            }

            val initialSize =
                if (announcedSize in 0..Int.MAX_VALUE) {
                    announcedSize.toInt()
                } else {
                    8192
                }
            val output = ByteArrayOutputStream(initialSize)

            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                var total = 0L

                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxSize) {
                        throw IllegalStateException(
                            "Download exceeded maximum size"
                        )
                    }
                    output.write(buffer, 0, read)
                }
            }

            return output.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    private fun setInstallState(context: Context, status: String) {
        context.getSharedPreferences("droid_mcp", Context.MODE_PRIVATE)
            .edit()
            .putString("cloudflared_install_status", status)
            .apply()
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private class LimitedInputStream(
        private val source: InputStream,
        private val limit: Long
    ) : InputStream() {
        var consumed: Long = 0
            private set

        override fun read(): Int {
            if (consumed >= limit) return -1
            val value = source.read()
            if (value >= 0) consumed++
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (consumed >= limit) return -1
            val allowed =
                minOf(length.toLong(), limit - consumed).toInt()
            val count = source.read(buffer, offset, allowed)
            if (count > 0) consumed += count
            return count
        }
    }
}
