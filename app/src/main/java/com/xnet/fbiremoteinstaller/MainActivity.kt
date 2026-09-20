package com.xnet.fbiremoteinstaller

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val DEFAULT_HOST_PORT = 8080
private const val FBI_URL_RECEIVER_PORT = 5000
private val ACCEPTED_EXT = setOf("cia", "tik", "cetk", "3dsx")

class MainActivity : AppCompatActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(false)
    private val requestCounter = AtomicLong(0)

    private lateinit var targetPathInput: EditText
    private lateinit var threeDsIpInput: EditText
    private lateinit var hostIpInput: EditText
    private lateinit var hostPortInput: EditText
    private lateinit var retriesInput: EditText
    private lateinit var retryDelayInput: EditText
    private lateinit var connectTimeoutInput: EditText
    private lateinit var ackWaitInput: EditText
    private lateinit var chunkKbInput: EditText
    private lateinit var noSendCheck: CheckBox
    private lateinit var copyOnlyCheck: CheckBox
    private lateinit var pickFileButton: Button
    private lateinit var startButton: Button
    private lateinit var resendButton: Button
    private lateinit var stopButton: Button
    private lateinit var recentIpsText: TextView
    private lateinit var logView: TextView

    private var serverSocket: ServerSocket? = null
    private var currentConfig: ServerConfig? = null
    private var currentSession: SessionData? = null
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            addLog("File picker cancelled.")
            return@registerForActivityResult
        }
        try {
            val localFile = copyPickedFileToAppStorage(uri)
            targetPathInput.setText(localFile.absolutePath)
            addLog("Selected file: ${localFile.name}")
        } catch (e: Exception) {
            addLog("Failed to load selected file: ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        applyEdgeToEdgeInsets()

        targetPathInput = findViewById(R.id.targetPathInput)
        threeDsIpInput = findViewById(R.id.threeDsIpInput)
        hostIpInput = findViewById(R.id.hostIpInput)
        hostPortInput = findViewById(R.id.hostPortInput)
        retriesInput = findViewById(R.id.retriesInput)
        retryDelayInput = findViewById(R.id.retryDelayInput)
        connectTimeoutInput = findViewById(R.id.connectTimeoutInput)
        ackWaitInput = findViewById(R.id.ackWaitInput)
        chunkKbInput = findViewById(R.id.chunkKbInput)
        noSendCheck = findViewById(R.id.noSendCheck)
        copyOnlyCheck = findViewById(R.id.copyOnlyCheck)
        pickFileButton = findViewById(R.id.pickFileButton)
        startButton = findViewById(R.id.startButton)
        resendButton = findViewById(R.id.resendButton)
        stopButton = findViewById(R.id.stopButton)
        recentIpsText = findViewById(R.id.recentIpsText)
        logView = findViewById(R.id.logView)

        targetPathInput.setText(".")
        hostIpInput.setText(detectHostIp() ?: "")
        updateRecentIps()

        pickFileButton.setOnClickListener { filePickerLauncher.launch(arrayOf("*/*")) }
        startButton.setOnClickListener { startServer() }
        resendButton.setOnClickListener { resendUrls() }
        stopButton.setOnClickListener { stopServer() }
    }

    override fun onDestroy() {
        stopServer()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun startServer() {
        if (running.get()) {
            addLog("Server already running.")
            return
        }

        val targetIp = threeDsIpInput.text.toString().trim()
        if (!isValidIpv4(targetIp)) {
            addLog("Invalid 3DS IP.")
            return
        }

        val hostIp = hostIpInput.text.toString().trim().ifBlank { detectHostIp().orEmpty() }
        if (!isValidIpv4(hostIp)) {
            addLog("Invalid host IP.")
            return
        }

        val config = try {
            ServerConfig(
                targetPath = targetPathInput.text.toString().trim().ifBlank { "." },
                targetIp = targetIp,
                hostIp = hostIp,
                hostPort = hostPortInput.text.toString().toIntOrNull() ?: DEFAULT_HOST_PORT,
                noSend = noSendCheck.isChecked,
                copyOnly = copyOnlyCheck.isChecked,
                retries = retriesInput.text.toString().toIntOrNull() ?: 5,
                retryDelaySeconds = retryDelayInput.text.toString().toDoubleOrNull() ?: 1.0,
                connectTimeoutSeconds = connectTimeoutInput.text.toString().toDoubleOrNull() ?: 10.0,
                ackWaitSeconds = ackWaitInput.text.toString().toDoubleOrNull() ?: 2.0,
                chunkBytes = (chunkKbInput.text.toString().toIntOrNull() ?: 256).coerceAtLeast(16) * 1024
            )
        } catch (e: Exception) {
            addLog("Invalid input: ${e.message}")
            return
        }

        val collection = try {
            collectFiles(config.targetPath)
        } catch (e: Exception) {
            addLog("Error: ${e.message}")
            return
        }

        val urls = buildUrls(config.hostIp, config.hostPort, collection.files)
        val payload = urls.joinToString("\n").toByteArray(StandardCharsets.US_ASCII)
        val logFile = createLogFile()
        val session = SessionData(
            collection = collection,
            urls = urls,
            payload = payload,
            logFile = logFile,
            totalBytes = collection.files.sumOf { it.length() }
        )

        currentConfig = config
        currentSession = session
        running.set(true)
        requestCounter.set(0)
        saveIpHistory(config.targetIp)
        updateRecentIps()

        appendLogFile(session.logFile, "START | 3DS=${config.targetIp} | HOST=${config.hostIp}:${config.hostPort} | DIR=${collection.directory.absolutePath} | FILES=${collection.files.size} | BYTES=${session.totalBytes}")
        session.urls.forEach { appendLogFile(session.logFile, "URL http://$it") }

        addLog("Server starting on ${config.hostIp}:${config.hostPort}")
        addLog("Serving ${session.collection.files.size} file(s), ${fmtBytes(session.totalBytes)}")

        io.execute {
            try {
                runHttpServer(config, session)
            } catch (e: Exception) {
                addLog("HTTP server failed: ${e.message}")
            } finally {
                running.set(false)
                appendLogFile(session.logFile, "STOP")
            }
        }

        if (!config.copyOnly && !config.noSend) {
            io.execute { sendUrls(config, session) }
        } else {
            addLog("COPY-ONLY/NO-SEND active: URLs not sent automatically.")
        }
    }

    private fun runHttpServer(config: ServerConfig, session: SessionData) {
        ServerSocket(config.hostPort).use { server ->
            serverSocket = server
            while (running.get()) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                io.execute { handleClient(socket, config, session) }
            }
        }
    }

    private fun handleClient(client: Socket, config: ServerConfig, session: SessionData) {
        client.use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                writeSimpleResponse(socket, 405, "Method Not Allowed", "Only GET supported")
                return
            }

            var line = reader.readLine()
            while (line != null && line.isNotEmpty()) {
                line = reader.readLine()
            }

            val encodedName = parts[1].removePrefix("/").substringBefore('?')
            val fileName = URLDecoder.decode(encodedName, StandardCharsets.UTF_8.name())
            val file = session.collection.files.firstOrNull { it.name == fileName }
            if (file == null) {
                writeSimpleResponse(socket, 404, "Not Found", "File not found")
                return
            }

            val start = System.currentTimeMillis()
            val requestNumber = requestCounter.incrementAndGet()
            val output = BufferedOutputStream(socket.getOutputStream())
            val header = "HTTP/1.1 200 OK\r\nContent-Length: ${file.length()}\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n\r\n"
            output.write(header.toByteArray(StandardCharsets.US_ASCII))

            var sent = 0L
            val clientIp = socket.inetAddress.hostAddress ?: "unknown"
            BufferedInputStream(FileInputStream(file)).use { input ->
                val buffer = ByteArray(config.chunkBytes)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    sent += read
                }
            }
            output.flush()
            val elapsedSeconds = ((System.currentTimeMillis() - start).coerceAtLeast(1L).toDouble() / 1000.0)
            val speed = sent / elapsedSeconds / (1024.0 * 1024.0)
            addLog("✔ $clientIp ► ${file.name} | ${requestNumber}/${session.collection.files.size} | DONE | ${"%.2f".format(Locale.US, speed)} MB/s | ${fmtBytes(sent)}")
        }
    }

    private fun writeSimpleResponse(socket: Socket, code: Int, status: String, message: String) {
        val body = message.toByteArray(StandardCharsets.UTF_8)
        val data = "HTTP/1.1 $code $status\r\nContent-Length: ${body.size}\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\n\r\n"
            .toByteArray(StandardCharsets.US_ASCII)
        val out = socket.getOutputStream()
        out.write(data)
        out.write(body)
        out.flush()
    }

    private fun resendUrls() {
        val config = currentConfig
        val session = currentSession
        if (config == null || session == null) {
            addLog("Nothing to resend.")
            return
        }
        io.execute { sendUrls(config, session) }
    }

    private fun sendUrls(config: ServerConfig, session: SessionData) {
        var lastErr: String? = null
        val attempts = config.retries.coerceAtLeast(1)
        repeat(attempts) { idx ->
            val result = pushUrlsOnce(config.targetIp, session.payload, config.connectTimeoutSeconds, config.ackWaitSeconds)
            if (result.delivered) {
                val status = if (result.acked) "URL_PUSH delivered ACK" else "URL_PUSH delivered NO_ACK"
                appendLogFile(session.logFile, status)
                addLog(if (result.acked) "✔ URLs sent (ACK)" else "⚠ URLs sent (no ACK)")
                return
            }
            lastErr = result.error
            if (idx < attempts - 1) {
                Thread.sleep((config.retryDelaySeconds * 1000.0).toLong().coerceAtLeast(100L))
            }
        }
        appendLogFile(session.logFile, "URL_PUSH_FAIL $lastErr")
        addLog("✖ URL push failed: $lastErr")
    }

    private fun pushUrlsOnce(targetIp: String, payload: ByteArray, connectTimeoutSeconds: Double, ackWaitSeconds: Double): PushResult {
        return try {
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(targetIp, FBI_URL_RECEIVER_PORT), (connectTimeoutSeconds * 1000).toInt())
                val out = DataOutputStream(socket.getOutputStream())
                out.writeInt(payload.size)
                out.write(payload)
                out.flush()

                val ackDeadline = System.currentTimeMillis() + (ackWaitSeconds * 1000).toLong()
                val input = DataInputStream(socket.getInputStream())
                var acked = false
                socket.soTimeout = 250
                while (System.currentTimeMillis() < ackDeadline) {
                    try {
                        if (input.read() != -1) {
                            acked = true
                            break
                        }
                    } catch (_: SocketTimeoutException) {
                    }
                }
                PushResult(delivered = true, acked = acked, error = null)
            }
        } catch (e: Exception) {
            PushResult(delivered = false, acked = false, error = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun stopServer() {
        if (!running.get()) return
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        addLog("Server stopped.")
    }

    private fun updateRecentIps() {
        val ips = loadIpHistory()
        recentIpsText.text = if (ips.isEmpty()) "(none)" else ips.joinToString("\n")
        if (threeDsIpInput.text.toString().isBlank() && ips.isNotEmpty()) {
            threeDsIpInput.setText(ips.first())
        }
    }

    private fun loadIpHistory(): List<String> {
        val prefs = getSharedPreferences("ip_history", MODE_PRIVATE)
        val value = prefs.getString("ips", "") ?: ""
        return value.split('|').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun applyEdgeToEdgeInsets() {
        val root = findViewById<android.view.View>(R.id.rootContainer)
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                baseLeft + systemBars.left,
                baseTop + systemBars.top,
                baseRight + systemBars.right,
                baseBottom + systemBars.bottom
            )
            insets
        }
    }

    private fun copyPickedFileToAppStorage(uri: Uri): File {
        val fileName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                } else {
                    null
                }
            }
            ?.takeIf { it.isNotBlank() }
            ?: "picked_file_${System.currentTimeMillis()}"
        val extension = fileName.substringAfterLast('.', "")
        if (extension.lowercase(Locale.US) !in ACCEPTED_EXT) {
            throw IllegalArgumentException("Unsupported file extension. Supported: $ACCEPTED_EXT")
        }

        val destinationDir = File(filesDir, "picked_files").apply { mkdirs() }
        val destinationFile = File(destinationDir, fileName)
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destinationFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Unable to read selected file.")
        return destinationFile
    }

    private fun saveIpHistory(ip: String) {
        val old = loadIpHistory().filter { it != ip }
        val merged = listOf(ip) + old
        getSharedPreferences("ip_history", MODE_PRIVATE)
            .edit()
            .putString("ips", merged.take(20).joinToString("|"))
            .apply()
    }

    private fun detectHostIp(): String? {
        val ips = listLocalIpv4()
        return ips.firstOrNull()
    }

    private fun listLocalIpv4(): List<String> {
        val ips = mutableSetOf<String>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        while (interfaces.hasMoreElements()) {
            val ni = interfaces.nextElement()
            val addresses = ni.inetAddresses
            while (addresses.hasMoreElements()) {
                val addr = addresses.nextElement()
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    ips.add(addr.hostAddress ?: "")
                }
            }
        }
        return ips.filter { isValidIpv4(it) }.sorted()
    }

    private fun collectFiles(targetPath: String): FileCollection {
        val resolved = if (targetPath == ".") filesDir else File(targetPath)
        if (!resolved.exists()) {
            throw IllegalArgumentException("$targetPath: No such file or directory.")
        }
        if (resolved.isFile) {
            if (!isAcceptedFile(resolved)) {
                throw IllegalArgumentException("Unsupported file extension. Supported: $ACCEPTED_EXT")
            }
            return FileCollection(resolved.parentFile ?: filesDir, listOf(resolved))
        }
        val files = resolved.listFiles()
            ?.filter { it.isFile && isAcceptedFile(it) }
            ?.sortedBy { it.name.lowercase(Locale.US) }
            .orEmpty()
        if (files.isEmpty()) {
            throw IllegalArgumentException("No supported files to serve in that directory.")
        }
        return FileCollection(resolved, files)
    }

    private fun isAcceptedFile(file: File): Boolean {
        val ext = file.extension.lowercase(Locale.US)
        return ACCEPTED_EXT.contains(ext)
    }

    private fun buildUrls(hostIp: String, hostPort: Int, files: List<File>): List<String> {
        return files.map {
            "$hostIp:$hostPort/${URLEncoder.encode(it.name, StandardCharsets.UTF_8.name()).replace("+", "%20")}" 
        }
    }

    private fun createLogFile(): File {
        val dir = File(filesDir, "logs")
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        return File(dir, "servefiles_log_$stamp.txt")
    }

    private fun appendLogFile(file: File, line: String) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        synchronized(file.absolutePath.intern()) {
            FileOutputStream(file, true).use {
                it.write("[$ts] $line\n".toByteArray(StandardCharsets.UTF_8))
            }
        }
    }

    private fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            val number = part.toIntOrNull() ?: return false
            number in 0..255
        }
    }

    private fun fmtBytes(value: Long): String {
        var n = value.toDouble()
        val units = listOf("B", "KB", "MB", "GB", "TB")
        for (unit in units) {
            if (n < 1024.0 || unit == "TB") {
                return if (unit == "B") "${n.toInt()} B" else String.format(Locale.US, "%.2f %s", n, unit)
            }
            n /= 1024.0
        }
        return "$value B"
    }

    private fun addLog(message: String) {
        mainHandler.post {
            val existing = logView.text?.toString().orEmpty()
            logView.text = if (existing.isBlank()) message else "$existing\n$message"
        }
    }
}

data class ServerConfig(
    val targetPath: String,
    val targetIp: String,
    val hostIp: String,
    val hostPort: Int,
    val noSend: Boolean,
    val copyOnly: Boolean,
    val retries: Int,
    val retryDelaySeconds: Double,
    val connectTimeoutSeconds: Double,
    val ackWaitSeconds: Double,
    val chunkBytes: Int
)

data class FileCollection(val directory: File, val files: List<File>)

data class SessionData(
    val collection: FileCollection,
    val urls: List<String>,
    val payload: ByteArray,
    val logFile: File,
    val totalBytes: Long
)

data class PushResult(val delivered: Boolean, val acked: Boolean, val error: String?)
