package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.document.DocumentIds
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class FtpProtocol(
    val scheme: String,
    val defaultPort: Int,
    internal val security: FtpSecurity,
) {
    FTP("ftp", 21, FtpSecurity.Plain),
    FTPS_EXPLICIT("ftps", 21, FtpSecurity.ExplicitTls),
    FTPS_IMPLICIT("ftps", 990, FtpSecurity.ImplicitTls),
}

data class FtpRemoteSourceConfig(
    val host: String,
    val protocol: FtpProtocol = FtpProtocol.FTP,
    val port: Int = protocol.defaultPort,
    val username: String = "anonymous",
    val password: String = "anonymous@",
    val basePath: String = "/",
    val title: String = host,
    val accountId: String = defaultFtpAccountId(protocol, host, port, username, basePath),
) {
    val normalizedBasePath: String
        get() = normalizeFtpPath(basePath)
}

data class FtpRemoteEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long? = null,
    val modifiedAt: String? = null,
    val rawListing: String? = null,
) {
    val format: DocumentFormat?
        get() = name.toSupportedDocumentFormatOrNull()
}

interface FtpTransport {
    suspend fun list(config: FtpRemoteSourceConfig, directoryPath: String): List<FtpRemoteEntry>

    suspend fun download(config: FtpRemoteSourceConfig, remotePath: String): ByteArray
}

class FtpRemoteBookSource(
    private val config: FtpRemoteSourceConfig,
    private val transport: FtpTransport = SocketFtpTransport(),
) : RemoteBookSource {
    override val sourceType: RemoteSourceType = RemoteSourceType.FtpServer
    override val accountId: String = config.accountId

    override suspend fun connect(): RemoteSourceConnection {
        val items = list()
        return RemoteSourceConnection(
            sourceType = sourceType,
            accountId = accountId,
            title = config.title,
            itemCount = items.size,
        )
    }

    override suspend fun list(): List<RemoteBookItem> {
        return browse(config.normalizedBasePath).toRemoteBookItems()
    }

    suspend fun browse(directoryPath: String = config.normalizedBasePath): List<FtpRemoteEntry> {
        return transport.list(config, normalizeFtpPath(directoryPath))
            .sortedWith(compareByDescending<FtpRemoteEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    override suspend fun search(query: String): List<RemoteBookItem> {
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isBlank()) return list()
        return list().filter { item ->
            item.title.lowercase().contains(normalizedQuery) ||
                item.format.name.lowercase().contains(normalizedQuery) ||
                item.downloadUrl.lowercase().contains(normalizedQuery)
        }
    }

    override suspend fun download(item: RemoteBookItem): ByteArray {
        require(item.identity.sourceType == sourceType) {
            "Remote item belongs to ${item.identity.sourceType}, not $sourceType."
        }
        require(item.identity.accountId == accountId) {
            "Remote item belongs to ${item.identity.accountId}, not $accountId."
        }
        return transport.download(config, item.identity.remoteId)
    }

    override suspend fun refresh(): List<RemoteBookItem> {
        return list()
    }

    private fun List<FtpRemoteEntry>.toRemoteBookItems(): List<RemoteBookItem> {
        return filter { entry -> !entry.isDirectory && entry.format != null }
            .map { entry ->
                RemoteBookItem(
                    identity = RemoteBookIdentity(
                        sourceType = sourceType,
                        accountId = accountId,
                        remoteId = entry.path,
                    ),
                    title = entry.name.removeSupportedExtension(),
                    format = requireNotNull(entry.format),
                    downloadUrl = entry.toDownloadUrl(),
                    sizeBytes = entry.sizeBytes,
                    updatedAt = entry.modifiedAt,
                )
            }
    }

    private fun FtpRemoteEntry.toDownloadUrl(): String {
        val portPart = if (config.port == config.protocol.defaultPort) "" else ":${config.port}"
        return "${config.protocol.scheme}://${config.host}$portPart${path.encodeFtpPathForUrl()}"
    }
}

object FtpDirectoryParser {
    fun parseMlsd(rawListing: String, directoryPath: String): List<FtpRemoteEntry> {
        return rawListing.lineSequence()
            .mapNotNull { line -> parseMlsdLine(line, directoryPath) }
            .toList()
    }

    fun parseList(rawListing: String, directoryPath: String): List<FtpRemoteEntry> {
        return rawListing.lineSequence()
            .mapNotNull { line -> parseListLine(line, directoryPath) }
            .toList()
    }

    private fun parseMlsdLine(line: String, directoryPath: String): FtpRemoteEntry? {
        val trimmed = line.trim()
        if (trimmed.isBlank()) return null
        val separator = trimmed.indexOf(' ')
        if (separator <= 0) return null

        val facts = trimmed.substring(0, separator)
            .split(';')
            .mapNotNull { fact ->
                val equals = fact.indexOf('=')
                if (equals <= 0) {
                    null
                } else {
                    fact.substring(0, equals).lowercase() to fact.substring(equals + 1)
                }
            }
            .toMap()
        val name = trimmed.substring(separator + 1).trim()
        if (name.isBlank() || name == "." || name == "..") return null

        val type = facts["type"]?.lowercase().orEmpty()
        if (type == "cdir" || type == "pdir") return null

        return FtpRemoteEntry(
            path = joinFtpPath(directoryPath, name),
            name = name,
            isDirectory = type.startsWith("dir"),
            sizeBytes = facts["size"]?.toLongOrNull(),
            modifiedAt = facts["modify"],
            rawListing = line,
        )
    }

    private fun parseListLine(line: String, directoryPath: String): FtpRemoteEntry? {
        val match = unixListRegex.matchEntire(line.trim()) ?: return null
        val type = match.groupValues[1]
        val size = match.groupValues[2].toLongOrNull()
        val name = match.groupValues[3].substringBefore(" -> ").trim()
        if (name.isBlank() || name == "." || name == "..") return null

        return FtpRemoteEntry(
            path = joinFtpPath(directoryPath, name),
            name = name,
            isDirectory = type == "d",
            sizeBytes = size,
            rawListing = line,
        )
    }

    private val unixListRegex = Regex("""^([d-])[rwxStTs-]{9}\s+\d+\s+\S+\s+\S+\s+(\d+)\s+\S+\s+\d+\s+(?:\d{2}:\d{2}|\d{4})\s+(.+)$""")
}

class SocketFtpTransport(
    private val socketFactory: (FtpRemoteSourceConfig) -> Socket = ::createControlSocket,
) : FtpTransport {
    override suspend fun list(
        config: FtpRemoteSourceConfig,
        directoryPath: String,
    ): List<FtpRemoteEntry> = withContext(Dispatchers.IO) {
        FtpSession(config, socketFactory(config)).use { session ->
            session.login()
            runCatching {
                FtpDirectoryParser.parseMlsd(
                    rawListing = session.transferText("MLSD ${normalizeFtpPath(directoryPath)}"),
                    directoryPath = directoryPath,
                )
            }.getOrElse {
                FtpDirectoryParser.parseList(
                    rawListing = session.transferText("LIST ${normalizeFtpPath(directoryPath)}"),
                    directoryPath = directoryPath,
                )
            }
        }
    }

    override suspend fun download(
        config: FtpRemoteSourceConfig,
        remotePath: String,
    ): ByteArray = withContext(Dispatchers.IO) {
        FtpSession(config, socketFactory(config)).use { session ->
            session.login()
            session.transferBytes("RETR ${normalizeFtpPath(remotePath)}")
        }
    }
}

private class FtpSession(
    private val config: FtpRemoteSourceConfig,
    private var controlSocket: Socket,
) : Closeable {
    private var reader = controlSocket.reader()
    private var writer = controlSocket.writer()

    fun login() {
        expectPositive(readReply())
        if (config.protocol.security == FtpSecurity.ExplicitTls) {
            expectPositive(sendCommand("AUTH TLS"))
            wrapControlSocketWithTls()
        }

        val userReply = sendCommand("USER ${config.username}")
        if (userReply.code == 331) {
            expectPositive(sendCommand("PASS ${config.password}"))
        } else {
            expectPositive(userReply)
        }

        if (config.protocol.security != FtpSecurity.Plain) {
            runCatching { sendCommand("PBSZ 0") }
            runCatching { sendCommand("PROT P") }
        }
        expectPositive(sendCommand("TYPE I"))
    }

    fun transferText(command: String): String {
        return transferBytes(command).toString(Charsets.UTF_8)
    }

    fun transferBytes(command: String): ByteArray {
        val dataSocket = openPassiveDataSocket()
        try {
            expectPreliminary(sendCommand(command))
            val bytes = dataSocket.getInputStream().use { input -> input.readBytes() }
            expectPositive(readReply())
            return bytes
        } finally {
            dataSocket.close()
        }
    }

    override fun close() {
        runCatching { sendCommand("QUIT") }
        controlSocket.close()
    }

    private fun openPassiveDataSocket(): Socket {
        val epsv = runCatching { sendCommand("EPSV") }.getOrNull()
        val endpoint = if (epsv?.code == 229) {
            PassiveEndpoint(config.host, parseEpsvPort(epsv.lines.joinToString(" ")))
        } else {
            val pasv = sendCommand("PASV")
            require(pasv.code == 227) { "FTP passive mode failed: ${pasv.lines.joinToString(" ")}" }
            parsePasvEndpoint(pasv.lines.joinToString(" "))
        }

        val rawSocket = Socket().apply {
            connect(InetSocketAddress(endpoint.host, endpoint.port), ConnectTimeoutMillis)
            soTimeout = ReadTimeoutMillis
        }
        return if (config.protocol.security == FtpSecurity.Plain) {
            rawSocket
        } else {
            (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(rawSocket, endpoint.host, endpoint.port, true)
                .asSslSocket()
                .also { socket -> socket.startHandshake() }
        }
    }

    private fun sendCommand(command: String): FtpReply {
        writer.write(command)
        writer.write("\r\n")
        writer.flush()
        return readReply()
    }

    private fun readReply(): FtpReply {
        val first = reader.readLine() ?: throw IOException("FTP server closed the control connection.")
        val code = first.take(3).toIntOrNull()
            ?: throw IOException("Invalid FTP reply: $first")
        val lines = mutableListOf(first)
        if (first.length > 3 && first[3] == '-') {
            while (true) {
                val line = reader.readLine() ?: throw IOException("FTP multi-line reply was not terminated.")
                lines += line
                if (line.startsWith("$code ")) break
            }
        }
        return FtpReply(code, lines)
    }

    private fun wrapControlSocketWithTls() {
        controlSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(controlSocket, config.host, config.port, true)
            .asSslSocket()
            .also { socket -> socket.startHandshake() }
        reader = controlSocket.reader()
        writer = controlSocket.writer()
    }

    private fun expectPositive(reply: FtpReply) {
        require(reply.code in 200..399) { "FTP command failed: ${reply.lines.joinToString(" ")}" }
    }

    private fun expectPreliminary(reply: FtpReply) {
        require(reply.code in 100..199 || reply.code in 200..299) {
            "FTP transfer failed: ${reply.lines.joinToString(" ")}"
        }
    }

    private companion object {
        const val ConnectTimeoutMillis = 15_000
        const val ReadTimeoutMillis = 30_000
    }
}

private data class FtpReply(
    val code: Int,
    val lines: List<String>,
)

private data class PassiveEndpoint(
    val host: String,
    val port: Int,
)

internal enum class FtpSecurity {
    Plain,
    ExplicitTls,
    ImplicitTls,
}

private fun createControlSocket(config: FtpRemoteSourceConfig): Socket {
    val rawSocket = Socket().apply {
        connect(InetSocketAddress(config.host, config.port), 15_000)
        soTimeout = 30_000
    }
    return if (config.protocol.security == FtpSecurity.ImplicitTls) {
        (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(rawSocket, config.host, config.port, true)
            .asSslSocket()
            .also { socket -> socket.startHandshake() }
    } else {
        rawSocket
    }
}

private fun Socket.asSslSocket(): SSLSocket {
    return this as SSLSocket
}

private fun Socket.reader(): BufferedReader {
    return BufferedReader(InputStreamReader(getInputStream(), Charsets.UTF_8))
}

private fun Socket.writer(): BufferedWriter {
    return BufferedWriter(OutputStreamWriter(getOutputStream(), Charsets.UTF_8))
}

private fun parseEpsvPort(reply: String): Int {
    val match = Regex("""\(\|\|\|(\d+)\|\)""").find(reply)
        ?: error("Could not parse EPSV reply: $reply")
    return match.groupValues[1].toInt()
}

private fun parsePasvEndpoint(reply: String): PassiveEndpoint {
    val values = Regex("""(\d+),(\d+),(\d+),(\d+),(\d+),(\d+)""")
        .find(reply)
        ?.groupValues
        ?.drop(1)
        ?.map { it.toInt() }
        ?: error("Could not parse PASV reply: $reply")
    return PassiveEndpoint(
        host = values.take(4).joinToString("."),
        port = values[4] * 256 + values[5],
    )
}

private fun defaultFtpAccountId(
    protocol: FtpProtocol,
    host: String,
    port: Int,
    username: String,
    basePath: String,
): String {
    return DocumentIds.sha256(
        listOf(protocol.name, host.trim().lowercase(), port.toString(), username.trim(), normalizeFtpPath(basePath))
            .joinToString("|"),
    ).take(16)
}

private fun joinFtpPath(directoryPath: String, name: String): String {
    return normalizeFtpPath("${normalizeFtpPath(directoryPath).trimEnd('/')}/$name")
}

private fun normalizeFtpPath(path: String): String {
    val normalized = path.trim().replace(Regex("/{2,}"), "/")
    return when {
        normalized.isBlank() -> "/"
        normalized.startsWith("/") -> normalized
        else -> "/$normalized"
    }
}

private fun String.toSupportedDocumentFormatOrNull(): DocumentFormat? {
    val lower = lowercase()
    return when {
        lower.endsWith(".epub") -> DocumentFormat.EPUB
        lower.endsWith(".pdf") -> DocumentFormat.PDF
        lower.endsWith(".md") || lower.endsWith(".markdown") -> DocumentFormat.MARKDOWN
        lower.endsWith(".txt") -> DocumentFormat.TEXT
        else -> null
    }
}

private fun String.removeSupportedExtension(): String {
    return replace(Regex("""\.(epub|pdf|md|markdown|txt)$""", RegexOption.IGNORE_CASE), "")
}

private fun String.encodeFtpPathForUrl(): String {
    return split('/').joinToString("/") { segment ->
        segment.replace(" ", "%20")
    }
}
