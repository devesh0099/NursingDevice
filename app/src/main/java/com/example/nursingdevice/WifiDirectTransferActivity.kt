package com.example.nursingdevice

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.LocationManager
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.PublicKey
import java.util.Arrays
import kotlin.math.min

class WifiDirectTransferActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var receivedDataText: TextView
    private lateinit var qrImage: ImageView
    private lateinit var scanButton: MaterialButton

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val manager by lazy { getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager }
    private val channel by lazy { manager.initialize(this, mainLooper, null) }
    private var targetPeerName: String? = null
    private var serverStarted = false
    private var clientStarted = false
    private var wifiStarted = false
    private var connectRequested = false
    private var deviceName = ""
    private val direction by lazy { intent.getStringExtra(EXTRA_DIRECTION) ?: DIRECTION_RECEIVE }
    private val purpose by lazy { intent.getStringExtra(EXTRA_PURPOSE) ?: PURPOSE_SCAN_PATIENT }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) beginWifiDirect()
        else logStep("Wi-Fi Direct needs Camera, Nearby Wi-Fi, and Fine Location permissions.")
    }
    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val scannedName = result.contents.trim()
            Toast.makeText(this, "Scanning for $scannedName...", Toast.LENGTH_SHORT).show()
            targetPeerName = scannedName
            logStep("Scanning for $scannedName...")
            discoverPeers()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, WifiP2pManager.WIFI_P2P_STATE_DISABLED)
                    if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        statusText.text = "Enable Wi-Fi to use Wi-Fi Direct"
                        logStep("Wi-Fi Direct is disabled by the system.")
                    }
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE) as? WifiP2pDevice
                    }
                    device?.let { updateDeviceName(it) }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> connectToTargetPeer()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (networkInfo?.isConnected == true) {
                        manager.requestConnectionInfo(channel) { info ->
                            if (!info.groupFormed) return@requestConnectionInfo
                            statusText.text = "Wi-Fi Direct connected. Opening socket..."
                            if (info.isGroupOwner && !serverStarted) {
                                serverStarted = true
                                scope.launch { startServer() }
                            } else if (!info.isGroupOwner && !clientStarted) {
                                clientStarted = true
                                scope.launch { startClient(info.groupOwnerAddress) }
                            }
                        }
                    } else {
                        serverStarted = false
                        clientStarted = false
                        connectRequested = false
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wifi_direct_transfer)
        statusText = findViewById(R.id.wifiStatusText)
        logText = findViewById(R.id.wifiLogText)
        receivedDataText = findViewById(R.id.wifiReceivedDataText)
        qrImage = findViewById(R.id.wifiQrImage)
        scanButton = findViewById(R.id.wifiScanButton)
        findViewById<TextView>(R.id.wifiTitleText).text =
            if (direction == DIRECTION_SEND) "Wi-Fi Direct Sender" else "Wi-Fi Direct Receiver"

        scanButton.visibility = if (direction == DIRECTION_RECEIVE) View.VISIBLE else View.GONE
        scanButton.setOnClickListener { openQrScanner() }
        requestWifiPermissions()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (hasWifiPermissions()) beginWifiDirect()
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(receiver) }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (direction == DIRECTION_SEND) {
            MyHostApduService.resetTransferState()
        }
    }

    private fun requestWifiPermissions() {
        if (hasWifiPermissions()) return
        permissionLauncher.launch(requiredWifiPermissions())
    }

    private fun hasWifiPermissions(): Boolean {
        return requiredWifiPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requiredWifiPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return permissions.toTypedArray()
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isLocationEnabled
    }

    @SuppressLint("MissingPermission")
    private fun beginWifiDirect() {
        if (wifiStarted) return
        wifiStarted = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            manager.requestDeviceInfo(channel) { device -> device?.let { updateDeviceName(it) } }
        }
        discoverPeers()
        if (direction == DIRECTION_SEND) {
            statusText.text = "Show this QR to the receiving device"
            logStep("Waiting for Wi-Fi Direct receiver...")
        } else {
            statusText.text = "Scan the sender QR"
            logStep("Ready to scan Wi-Fi Direct QR.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverPeers(retryBusy: Boolean = true) {
        if (!hasWifiPermissions()) {
            requestWifiPermissions()
            return
        }
        if (!isLocationEnabled()) {
            statusText.text = "Turn on Location, then retry Wi-Fi Direct"
            logStep("Location Services are off. Android blocks Wi-Fi Direct peer discovery until Location is enabled.")
            return
        }
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                logStep("Wi-Fi Direct peer discovery started.")
            }
            override fun onFailure(reason: Int) {
                val message = "Discovery failed: ${wifiP2pReason(reason)}"
                logStep(message)
                Toast.makeText(this@WifiDirectTransferActivity, message, Toast.LENGTH_SHORT).show()
                if (reason == WifiP2pManager.BUSY && retryBusy) {
                    scope.launch {
                        delay(700)
                        manager.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                scope.launch {
                                    delay(700)
                                    discoverPeers(retryBusy = false)
                                }
                            }
                            override fun onFailure(reason: Int) {
                                logStep("Stop discovery failed: ${wifiP2pReason(reason)}")
                            }
                        })
                    }
                }
            }
        })
    }

    private fun wifiP2pReason(reason: Int): String = when (reason) {
        WifiP2pManager.ERROR -> "ERROR ($reason)"
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED ($reason)"
        WifiP2pManager.BUSY -> "BUSY ($reason)"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS ($reason)"
        else -> "UNKNOWN ($reason)"
    }

    private fun updateDeviceName(device: WifiP2pDevice) {
        deviceName = device.deviceName
        if (direction == DIRECTION_SEND) {
            generateQr(device.deviceName)?.let {
                qrImage.setImageBitmap(it)
                qrImage.visibility = View.VISIBLE
            }
        }
    }

    private fun openQrScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Scan the Host's QR Code")
            setCameraId(0)
            setBeepEnabled(true)
            setOrientationLocked(false)
        }
        barcodeLauncher.launch(options)
    }

    @SuppressLint("MissingPermission")
    private fun connectToTargetPeer() {
        val target = targetPeerName ?: return
        if (connectRequested) return
        manager.requestPeers(channel) { peers ->
            val device = peers.deviceList.firstOrNull {
                it.deviceName.trim().equals(target.trim(), ignoreCase = true)
            }
            if (device == null) return@requestPeers
            targetPeerName = null
            connectRequested = true
            statusText.text = "Connecting to ${device.deviceName}..."
            logStep("Found ${device.deviceName}. Connecting...")
            val config = WifiP2pConfig().apply {
                deviceAddress = device.deviceAddress
                wps.setup = WpsInfo.PBC
            }
            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { logStep("Wi-Fi Direct connect requested.") }
                override fun onFailure(reason: Int) {
                    connectRequested = false
                    logStep("Connect failed: $reason")
                }
            })
        }
    }

    private suspend fun startServer() = withContext(Dispatchers.IO) {
        try {
            withContext(Dispatchers.Main) { statusText.text = "Wi-Fi Direct group owner. Waiting for socket..." }
            ServerSocket(SOCKET_PORT).use { serverSocket ->
                serverSocket.reuseAddress = true
                serverSocket.accept().use { socket ->
                    handleConnectedSocket(socket)
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { statusText.text = "Wi-Fi Direct sender stopped"; logStep(e.message ?: "Socket closed") }
        }
    }

    private suspend fun startClient(hostAddress: InetAddress) = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                var connected = false
                repeat(5) {
                    if (!connected) {
                        try {
                            socket.connect(InetSocketAddress(hostAddress, SOCKET_PORT), 5000)
                            connected = true
                        } catch (_: IOException) {
                            delay(700)
                        }
                    }
                }
                if (!connected) throw IOException("Could not reach Wi-Fi Direct host")
                handleConnectedSocket(socket)
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                statusText.text = "Wi-Fi Direct failed"
                logStep("Error: ${e.message}")
                Toast.makeText(this@WifiDirectTransferActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun handleConnectedSocket(socket: Socket) {
        if (direction == DIRECTION_SEND) {
            runSenderProcessor(socket)
        } else {
            runReaderProtocol(SocketTransceiver(socket))
        }
    }

    private suspend fun runSenderProcessor(socket: Socket) {
        withContext(Dispatchers.Main) { statusText.text = "Receiver connected. Authenticating..." }
        val processor = WifiApduProcessor(MyHostApduService.snapshotTransferState()) { msg -> logStep(msg) }
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        while (!socket.isClosed) {
            val command = input.readFrame()
            val response = processor.process(command)
            output.writeFrame(response)
        }
    }

    private suspend fun runReaderProtocol(link: SocketTransceiver) {
        withContext(Dispatchers.Main) { statusText.text = "Authenticating..." }
        logStep("Step 1: Wi-Fi Direct socket connected.")
        var response = link.transceive(Utils.SELECT_APD)
        if (!response.isSuccess()) throw IOException("Application selection failed")

        val peerPublicKey = if (CryptoUtils.CERT_AUTH_ENABLED) {
            WifiAuth.exchangeCerts(link).also { logStep("Step 2: Certificates verified") }
        } else CryptoUtils.getOtherPublicKey()
        val myPrivateKey = if (CryptoUtils.CERT_AUTH_ENABLED) {
            CryptoUtils.getSessionPrivateKey() ?: throw IOException("No credential — log in with your PIN.")
        } else CryptoUtils.getMyPrivateKey()

        val sessionKey = CryptoUtils.generateSessionKey()
        val encryptedKey = CryptoUtils.rsaEncrypt(sessionKey, peerPublicKey)
        val signature = CryptoUtils.rsaSign(encryptedKey, myPrivateKey)
        response = WifiAuth.sendChunked(link, CryptoUtils.CMD_AUTH_SEND_KEY, encryptedKey)
        if (!response.isSuccess()) throw IOException("Session key rejected")
        response = WifiAuth.sendChunked(link, CryptoUtils.CMD_AUTH_SEND_SIG, signature)
        if (!response.isSuccess()) throw IOException("Signature rejected")
        val ack = CryptoUtils.xorEncryptDecrypt(response.getData(), sessionKey)
        if (String(ack, Charsets.UTF_8) != "AUTH_OK") throw IOException("Authentication rejected by peer")

        logStep("Step 3: Secure connection established.")
        withContext(Dispatchers.Main) { statusText.text = "Receiving data..." }
        response = link.transceive(Utils.GET_FILE_INFO_COMMAND)
        if (response.size <= 2 || !response.isSuccess()) throw IOException("No metadata received")
        val metadata = CryptoUtils.xorEncryptDecrypt(response.getData(), sessionKey)
        val mode = String(metadata.copyOfRange(0, 1), Charsets.UTF_8)
        when (mode) {
            "T" -> handleReceivedText(String(metadata.copyOfRange(1, metadata.size), Charsets.UTF_8), purpose)
            "F", "M" -> {
                val fileInfo = metadata.copyOfRange(1, metadata.size)
                val size = ByteBuffer.wrap(fileInfo.copyOfRange(0, 4)).int
                val content = receiveFile(link, sessionKey, size)
                handleReceivedText(String(content, Charsets.UTF_8), purpose)
            }
            else -> throw IOException("Unknown transfer mode: $mode")
        }
    }

    private suspend fun receiveFile(link: SocketTransceiver, sessionKey: ByteArray, fileSize: Int): ByteArray {
        val output = ByteArrayOutputStream()
        var received = 0
        while (received < fileSize) {
            val response = link.transceive(Utils.GET_NEXT_DATA_CHUNK_COMMAND)
            if (response.size <= 2 || !response.isSuccess()) throw IOException("Transfer interrupted")
            val chunk = CryptoUtils.xorEncryptDecrypt(response.getData(), sessionKey)
            output.write(chunk)
            received += chunk.size
            logStep("Transferring: $received / $fileSize bytes")
        }
        return output.toByteArray()
    }

    private suspend fun handleReceivedText(content: String, purpose: String) = withContext(Dispatchers.Main) {
        receivedDataText.text = content
        when (purpose) {
            PURPOSE_FETCH_RECORD -> {
                SessionCache.setFetchedRecord(content)
                NursePatientManager(this@WifiDirectTransferActivity).saveFetchedRecord(content)
                Toast.makeText(this@WifiDirectTransferActivity, "Record fetched over Wi-Fi Direct", Toast.LENGTH_LONG).show()
            }
            else -> {
                SessionCache.processScannedData(content)
                NursePatientManager(this@WifiDirectTransferActivity).savePatient(content)
                Toast.makeText(this@WifiDirectTransferActivity, "Patient data cached over Wi-Fi Direct", Toast.LENGTH_LONG).show()
            }
        }
        statusText.text = "Transfer complete"
        logStep("Transfer securely completed.")
    }

    private fun logStep(message: String) {
        runOnUiThread {
            val current = logText.text?.toString().orEmpty()
            logText.text = if (current.isBlank() || current == "Ready.") message else "$current\n$message"
        }
    }

    private fun generateQr(text: String): Bitmap? = try {
        if (text.isBlank()) null else {
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512)
            Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565).also { bitmap ->
                for (x in 0 until 512) for (y in 0 until 512) {
                    bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
        }
    } catch (_: Exception) { null }

    companion object {
        const val EXTRA_DIRECTION = "direction"
        const val EXTRA_PURPOSE = "purpose"
        const val DIRECTION_SEND = "send"
        const val DIRECTION_RECEIVE = "receive"
        const val PURPOSE_SCAN_PATIENT = "scan_patient"
        const val PURPOSE_FETCH_RECORD = "fetch_record"
        private const val SOCKET_PORT = 8888
    }
}

private class SocketTransceiver(private val socket: Socket) {
    private val input = DataInputStream(socket.getInputStream())
    private val output = DataOutputStream(socket.getOutputStream())
    @Synchronized fun transceive(command: ByteArray): ByteArray {
        output.writeFrame(command)
        return input.readFrame()
    }
}

private fun DataInputStream.readFrame(): ByteArray {
    val length = readInt()
    if (length < 0 || length > 10_000_000) throw IOException("Invalid frame length: $length")
    return ByteArray(length).also { readFully(it) }
}

private fun DataOutputStream.writeFrame(bytes: ByteArray) {
    writeInt(bytes.size)
    write(bytes)
    flush()
}

private object WifiAuth {
    fun exchangeCerts(link: SocketTransceiver): PublicKey {
        val myCert = CryptoUtils.getMyCertificatePem()
            ?: throw IOException("No credential on this device — log in with your PIN first.")
        val res = sendChunked(link, CryptoUtils.CMD_AUTH_SEND_CERT, myCert.toByteArray(Charsets.UTF_8))
        if (!res.isSuccess()) throw IOException("Certificate exchange rejected by peer")
        val peerCert = fetchPeerCert(link)
        return try {
            CryptoUtils.verifyPeerCert(peerCert)
        } catch (e: PeerCertException) {
            throw IOException("Peer certificate rejected: ${e.message}")
        }
    }

    fun sendChunked(link: SocketTransceiver, cmd: ByteArray, payload: ByteArray): ByteArray {
        var offset = 0
        while (true) {
            val end = min(offset + CryptoUtils.AUTH_CHUNK_SIZE, payload.size)
            val last = end == payload.size
            val frame = Utils.concatArrays(
                cmd,
                byteArrayOf(if (last) CryptoUtils.AUTH_CHUNK_LAST else CryptoUtils.AUTH_CHUNK_MORE),
                payload.copyOfRange(offset, end)
            )
            val res = link.transceive(frame)
            if (last) return res
            if (!res.isSuccess()) throw IOException("Peer rejected auth chunk")
            offset = end
        }
    }

    private fun fetchPeerCert(link: SocketTransceiver): String {
        val out = ByteArrayOutputStream()
        repeat(64) {
            val res = link.transceive(CryptoUtils.CMD_AUTH_GET_CERT)
            if (res.size < 3 || !res.isSuccess()) throw IOException("Failed to fetch peer certificate")
            val flag = res[0]
            out.write(res, 1, res.size - 3)
            if (flag == CryptoUtils.AUTH_CHUNK_LAST) return String(out.toByteArray(), Charsets.UTF_8)
        }
        throw IOException("Peer certificate too large")
    }
}

private class WifiApduProcessor(
    private val snapshot: TransferSnapshot,
    private val logger: (String) -> Unit
) {
    private var sessionKey: ByteArray? = null
    private var encryptedKey: ByteArray? = null
    private var peerPublicKey: PublicKey? = null
    private var authenticated = false
    private var chunkedCmdTag: String? = null
    private val chunkedBuffer = ByteArrayOutputStream()
    private var certSendOffset = 0
    private var fileOffset = 0

    fun process(command: ByteArray): ByteArray {
        if (Arrays.equals(command, Utils.SELECT_APD)) {
            sessionKey = null
            encryptedKey = null
            peerPublicKey = null
            authenticated = false
            fileOffset = 0
            logger("Step 1: Wi-Fi Direct connection established")
            return Utils.SELECT_OK_SW
        }
        if (CryptoUtils.CERT_AUTH_ENABLED && command.size > CryptoUtils.CMD_AUTH_SEND_CERT.size &&
            command.take(CryptoUtils.CMD_AUTH_SEND_CERT.size).toByteArray().contentEquals(CryptoUtils.CMD_AUTH_SEND_CERT)) {
            val certBytes = reassembleChunks("AUTH_CRT", command) ?: return Utils.SELECT_OK_SW
            peerPublicKey = try {
                CryptoUtils.verifyPeerCert(String(certBytes, Charsets.UTF_8))
            } catch (e: PeerCertException) {
                logger("Peer cert rejected: ${e.message}")
                return Utils.FILE_NOT_READY_SW
            }
            certSendOffset = 0
            logger("Certificates exchanged")
            return Utils.SELECT_OK_SW
        }
        if (CryptoUtils.CERT_AUTH_ENABLED && Arrays.equals(command, CryptoUtils.CMD_AUTH_GET_CERT)) {
            val cert = CryptoUtils.getMyCertificatePem()?.toByteArray(Charsets.UTF_8) ?: return Utils.FILE_NOT_READY_SW
            val end = min(certSendOffset + CryptoUtils.AUTH_CHUNK_SIZE, cert.size)
            val flag = if (end == cert.size) CryptoUtils.AUTH_CHUNK_LAST else CryptoUtils.AUTH_CHUNK_MORE
            val chunk = cert.copyOfRange(certSendOffset, end)
            certSendOffset = if (end == cert.size) 0 else end
            return Utils.concatArrays(byteArrayOf(flag), chunk, Utils.SELECT_OK_SW)
        }
        if (command.size > CryptoUtils.CMD_AUTH_SEND_KEY.size &&
            command.take(CryptoUtils.CMD_AUTH_SEND_KEY.size).toByteArray().contentEquals(CryptoUtils.CMD_AUTH_SEND_KEY)) {
            encryptedKey = reassembleChunks("AUTH_KEY", command) ?: return Utils.SELECT_OK_SW
            logger("Step 2: Key received")
            return Utils.SELECT_OK_SW
        }
        if (command.size > CryptoUtils.CMD_AUTH_SEND_SIG.size &&
            command.take(CryptoUtils.CMD_AUTH_SEND_SIG.size).toByteArray().contentEquals(CryptoUtils.CMD_AUTH_SEND_SIG)) {
            val signature = reassembleChunks("AUTH_SIG", command) ?: return Utils.SELECT_OK_SW
            val encrypted = encryptedKey ?: return Utils.FILE_NOT_READY_SW
            val verifyKey = if (CryptoUtils.CERT_AUTH_ENABLED) peerPublicKey else CryptoUtils.getOtherPublicKey()
            val privateKey = if (CryptoUtils.CERT_AUTH_ENABLED) CryptoUtils.getSessionPrivateKey() else CryptoUtils.getMyPrivateKey()
            if (verifyKey == null || privateKey == null || !CryptoUtils.rsaVerify(encrypted, signature, verifyKey)) {
                logger("Authentication failed")
                return Utils.FILE_NOT_READY_SW
            }
            sessionKey = CryptoUtils.rsaDecrypt(encrypted, privateKey)
            authenticated = true
            logger("Step 3: Authenticated securely")
            return Utils.concatArrays(CryptoUtils.xorEncryptDecrypt("AUTH_OK".toByteArray(), sessionKey!!), Utils.SELECT_OK_SW)
        }
        if (!authenticated || sessionKey == null) return Utils.FILE_NOT_READY_SW
        val raw = when (snapshot.mode) {
            "TEXT" -> handleText(command)
            "FILE" -> handleFile(command, snapshot.fileContent, snapshot.fileMimeType ?: "text/plain")
            "MULTI_FILE" -> handleFile(command, snapshot.appendedFile, snapshot.appendedFileName, true)
            else -> Utils.FILE_NOT_READY_SW
        }
        return if (raw.size > 2 && raw.isSuccess()) {
            Utils.concatArrays(CryptoUtils.xorEncryptDecrypt(raw.getData(), sessionKey!!), Utils.SELECT_OK_SW)
        } else raw
    }

    private fun handleText(command: ByteArray): ByteArray {
        if (!Arrays.equals(command, Utils.GET_FILE_INFO_COMMAND)) return Utils.UNKNOWN_CMD_SW
        val text = snapshot.text ?: return Utils.FILE_NOT_READY_SW
        return Utils.concatArrays("T".toByteArray(), text.toByteArray(Charsets.UTF_8), Utils.SELECT_OK_SW)
    }

    private fun handleFile(command: ByteArray, content: ByteArray?, label: String, multi: Boolean = false): ByteArray {
        val bytes = content ?: return Utils.FILE_NOT_READY_SW
        return when {
            Arrays.equals(command, Utils.GET_FILE_INFO_COMMAND) -> {
                fileOffset = 0
                val mode = if (multi) "M" else "F"
                Utils.concatArrays(mode.toByteArray(), ByteBuffer.allocate(4).putInt(bytes.size).array(), label.toByteArray(), Utils.SELECT_OK_SW)
            }
            Arrays.equals(command, Utils.GET_NEXT_DATA_CHUNK_COMMAND) -> {
                val remaining = bytes.size - fileOffset
                if (remaining <= 0) return Utils.FILE_NOT_READY_SW
                val size = min(remaining, 245)
                val chunk = bytes.copyOfRange(fileOffset, fileOffset + size)
                fileOffset += size
                Utils.concatArrays(chunk, Utils.SELECT_OK_SW)
            }
            else -> Utils.UNKNOWN_CMD_SW
        }
    }

    private fun reassembleChunks(name: String, command: ByteArray): ByteArray? {
        if (chunkedCmdTag != name) {
            chunkedCmdTag = name
            chunkedBuffer.reset()
        }
        val flag = command[8]
        chunkedBuffer.write(command, 9, command.size - 9)
        if (flag != CryptoUtils.AUTH_CHUNK_LAST) return null
        val payload = chunkedBuffer.toByteArray()
        chunkedBuffer.reset()
        chunkedCmdTag = null
        return payload
    }
}

private fun ByteArray.isSuccess(): Boolean =
    size >= 2 && takeLast(2).toByteArray().contentEquals(Utils.SELECT_OK_SW)

private fun ByteArray.getData(): ByteArray = copyOfRange(0, size - 2)
