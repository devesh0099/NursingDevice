package com.example.nursingdevice

import android.content.Intent
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.nio.ByteBuffer
import java.security.PublicKey
import java.util.Arrays
import kotlin.math.min

data class FileData(val name: String, val content: ByteArray)
data class TransferSnapshot(
    val mode: String,
    val text: String?,
    val fileContent: ByteArray?,
    val fileMimeType: String?,
    val appendedFile: ByteArray?,
    val appendedFileName: String
)

class MyHostApduService : HostApduService() {

    private var transferMode = "NONE"
    private var textContent: String? = null
    private var fileContent: ByteArray? = null
    private var fileMimeType: String? = null
    private var fileChunkOffset: Int = 0
    private var singleAppendedFile: ByteArray? = null
    private var currentFileName: String = "appended_data.dat"

    private var tempEncryptedKey: ByteArray? = null
    private var sessionKey: ByteArray? = null
    private var isAuthenticated = false
    // Phase 3: peer's public key, extracted from its certificate during cert exchange.
    private var peerPublicKey: PublicKey? = null
    // Chunked-APDU reassembly for the auth phase (AUTH_CRT / AUTH_KEY / AUTH_SIG) and
    // chunked serving of our own cert (AUTH_CRG). Needed because some controllers
    // (e.g. Galaxy Tab Active5) can't receive extended-length APDUs in HCE mode.
    private var chunkedCmdTag: String? = null
    private val chunkedBuffer = java.io.ByteArrayOutputStream()
    private var certSendOffset = 0

    companion object {
        private var sharedTransferMode = "NONE"
        private var sharedTextContent: String? = null
        private var sharedFileContent: ByteArray? = null
        private var sharedFileMimeType: String? = null
        private var sharedAppendedFile: ByteArray? = null
        private var sharedCurrentFileName: String = "appended_data.dat"

        var onStatusUpdate: ((String) -> Unit)? = null

        fun setTextForTransfer(text: String) {
            sharedTransferMode = "TEXT"
            sharedTextContent = text
            sharedFileContent = null
            sharedFileMimeType = null
        }

        fun setFileForTransfer(content: ByteArray, mimeType: String) {
            sharedTransferMode = "FILE"
            sharedFileContent = content
            sharedFileMimeType = mimeType
            sharedTextContent = null
        }

        fun setSingleAppendedFileForTransfer(fileData: ByteArray, fileName: String = "appended_data.dat") {
            sharedTransferMode = "MULTI_FILE"
            sharedAppendedFile = fileData
            sharedCurrentFileName = fileName
            sharedTextContent = null
            sharedFileContent = null
            sharedFileMimeType = null
        }

        fun resetTransferState() {
            sharedTransferMode = "NONE"
            sharedTextContent = null
            sharedFileContent = null
            sharedFileMimeType = null
            sharedAppendedFile = null
            sharedCurrentFileName = "appended_data.dat"
        }

        fun snapshotTransferState(): TransferSnapshot =
            TransferSnapshot(
                mode = sharedTransferMode,
                text = sharedTextContent,
                fileContent = sharedFileContent,
                fileMimeType = sharedFileMimeType,
                appendedFile = sharedAppendedFile,
                appendedFileName = sharedCurrentFileName
            )
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun notifyUI(step: String) {
        mainHandler.post {
            onStatusUpdate?.invoke(step)
        }
    }

    /**
     * Reassemble a chunked auth command ([cmd(8)][flag(1)][chunk]). Returns null
     * while more chunks are pending (caller acks with SELECT_OK_SW), or the fully
     * assembled payload on the final chunk. Switching to a different command tag
     * discards any half-finished buffer from an interrupted exchange.
     */
    private fun reassembleChunks(cmdName: String, commandApdu: ByteArray): ByteArray? {
        if (chunkedCmdTag != cmdName) {
            chunkedCmdTag = cmdName
            chunkedBuffer.reset()
        }
        val flag = commandApdu[8]
        chunkedBuffer.write(commandApdu, 9, commandApdu.size - 9)
        val last = flag == CryptoUtils.AUTH_CHUNK_LAST
        Log.d("HCE_AUTH", "reassemble[$cmdName]: rxApduLen=${commandApdu.size} chunk=${commandApdu.size - 9} last=$last total=${chunkedBuffer.size()}")
        if (!last) return null
        val payload = chunkedBuffer.toByteArray()
        chunkedBuffer.reset()
        chunkedCmdTag = null
        return payload
    }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        if (Arrays.equals(commandApdu, Utils.SELECT_APD)) {
            isAuthenticated = false
            sessionKey = null
            tempEncryptedKey = null
            peerPublicKey = null
            fileChunkOffset = 0
            chunkedCmdTag = null
            chunkedBuffer.reset()
            certSendOffset = 0

            transferMode = sharedTransferMode
            textContent = sharedTextContent
            fileContent = sharedFileContent
            fileMimeType = sharedFileMimeType
            singleAppendedFile = sharedAppendedFile
            currentFileName = sharedCurrentFileName

            notifyUI("Step 1: Connection Established")
            return Utils.SELECT_OK_SW
        }

        // Phase 3: certificate exchange (reader -> card). The reader uploads its cert
        // in chunks; on the final chunk we verify it against our CA and remember its
        // public key. The reader then pulls our cert in chunks via AUTH_CRG.
        if (CryptoUtils.CERT_AUTH_ENABLED && commandApdu.size > 8 &&
            Arrays.equals(commandApdu.take(8).toByteArray(), CryptoUtils.CMD_AUTH_SEND_CERT)) {
            val myCert = CryptoUtils.getMyCertificatePem()
            if (myCert == null) {
                notifyUI("Cert exchange failed: no credential on this device — log in with your PIN")
                return Utils.FILE_NOT_READY_SW
            }
            val peerCertBytes = reassembleChunks("AUTH_CRT", commandApdu) ?: return Utils.SELECT_OK_SW
            try {
                peerPublicKey = CryptoUtils.verifyPeerCert(String(peerCertBytes, Charsets.UTF_8))
            } catch (e: PeerCertException) {
                notifyUI("Peer cert rejected: ${e.message}")
                return Utils.FILE_NOT_READY_SW
            }
            certSendOffset = 0
            notifyUI("Certificates exchanged")
            return Utils.SELECT_OK_SW
        }

        // Phase 3: serve our own cert to the reader, one chunk per AUTH_CRG command.
        if (CryptoUtils.CERT_AUTH_ENABLED && Arrays.equals(commandApdu, CryptoUtils.CMD_AUTH_GET_CERT)) {
            val myCert = CryptoUtils.getMyCertificatePem()?.toByteArray(Charsets.UTF_8)
            if (myCert == null) {
                notifyUI("Cert exchange failed: no credential on this device — log in with your PIN")
                return Utils.FILE_NOT_READY_SW
            }
            val end = min(certSendOffset + CryptoUtils.AUTH_CHUNK_SIZE, myCert.size)
            val flag = if (end == myCert.size) CryptoUtils.AUTH_CHUNK_LAST else CryptoUtils.AUTH_CHUNK_MORE
            val chunk = myCert.copyOfRange(certSendOffset, end)
            Log.d("HCE_AUTH", "serveCert -> chunk from $certSendOffset..$end of ${myCert.size} last=${end == myCert.size}")
            certSendOffset = if (end == myCert.size) 0 else end
            return Utils.concatArrays(byteArrayOf(flag), chunk, Utils.SELECT_OK_SW)
        }

        if (commandApdu.size > 8 && Arrays.equals(commandApdu.take(8).toByteArray(), CryptoUtils.CMD_AUTH_SEND_KEY)) {
            tempEncryptedKey = reassembleChunks("AUTH_KEY", commandApdu) ?: return Utils.SELECT_OK_SW
            notifyUI("Step 2: Key Received")
            return Utils.SELECT_OK_SW
        }

        if (commandApdu.size > 8 && Arrays.equals(commandApdu.take(8).toByteArray(), CryptoUtils.CMD_AUTH_SEND_SIG)) {
            val signature = reassembleChunks("AUTH_SIG", commandApdu) ?: return Utils.SELECT_OK_SW

            if (tempEncryptedKey == null) {
                notifyUI("Error: Signature without Key")
                return Utils.FILE_NOT_READY_SW
            }

            try {
                // Phase 3: verify with the peer's cert key + decrypt with our own
                // credential; fall back to the hardcoded pair when cert-auth is off.
                val verifyKey = if (CryptoUtils.CERT_AUTH_ENABLED) peerPublicKey else CryptoUtils.getOtherPublicKey()
                if (verifyKey == null) {
                    notifyUI("Auth Failed: certificate not exchanged")
                    return Utils.FILE_NOT_READY_SW
                }
                val isValid = CryptoUtils.rsaVerify(tempEncryptedKey!!, signature, verifyKey)

                if (isValid) {
                    val myPriv = if (CryptoUtils.CERT_AUTH_ENABLED) CryptoUtils.getSessionPrivateKey() else CryptoUtils.getMyPrivateKey()
                    if (myPriv == null) {
                        notifyUI("Auth Failed: no credential on this device")
                        return Utils.FILE_NOT_READY_SW
                    }
                    sessionKey = CryptoUtils.rsaDecrypt(tempEncryptedKey!!, myPriv)
                    isAuthenticated = true

                    notifyUI("Step 3: Authenticated Securely")
                    notifyUI("Transmitting Data...")

                    val ack = "AUTH_OK".toByteArray(Charsets.UTF_8)
                    val encryptedAck = CryptoUtils.xorEncryptDecrypt(ack, sessionKey!!)
                    return Utils.concatArrays(encryptedAck, Utils.SELECT_OK_SW)
                } else {
                    notifyUI("Authentication Failed: Invalid Signature")
                }
            } catch (e: Exception) {
                notifyUI("Auth Error: ${e.message}")
            }
            return Utils.FILE_NOT_READY_SW
        }

        if (!isAuthenticated || sessionKey == null) {
            return Utils.FILE_NOT_READY_SW
        }

        val response = when (transferMode) {
            "TEXT" -> handleTextTransfer(commandApdu)
            "FILE" -> handleFileTransfer(commandApdu)
            "MULTI_FILE" -> handleAppendedFileTransfer(commandApdu)
            else -> Utils.FILE_NOT_READY_SW
        }

        if (response.size >= 2 && Arrays.equals(response.takeLast(2).toByteArray(), Utils.SELECT_OK_SW)) {
            val rawData = response.copyOfRange(0, response.size - 2)
            val encryptedData = CryptoUtils.xorEncryptDecrypt(rawData, sessionKey!!)
            return Utils.concatArrays(encryptedData, Utils.SELECT_OK_SW)
        }

        return response
    }

    private fun handleTextTransfer(commandApdu: ByteArray): ByteArray {
        if (!Arrays.equals(commandApdu, Utils.GET_FILE_INFO_COMMAND)) return Utils.UNKNOWN_CMD_SW
        val text = textContent ?: return Utils.FILE_NOT_READY_SW
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val modeByte = "T".toByteArray(Charsets.UTF_8)
        val textPayload = Utils.concatArrays(modeByte, textBytes)
        notifyUI("Transfer Complete")
        return Utils.concatArrays(textPayload, Utils.SELECT_OK_SW)
    }

    private fun handleFileTransfer(commandApdu: ByteArray): ByteArray {
        return when {
            Arrays.equals(commandApdu, Utils.GET_FILE_INFO_COMMAND) -> {
                val content = fileContent ?: return Utils.FILE_NOT_READY_SW
                val mimeBytes = fileMimeType?.toByteArray(Charsets.UTF_8) ?: return Utils.FILE_NOT_READY_SW
                val modeByte = "F".toByteArray(Charsets.UTF_8)
                val sizeBytes = ByteBuffer.allocate(4).putInt(content.size).array()
                val fileInfoPayload = Utils.concatArrays(modeByte, sizeBytes, mimeBytes)
                Utils.concatArrays(fileInfoPayload, Utils.SELECT_OK_SW)
            }
            Arrays.equals(commandApdu, Utils.GET_NEXT_DATA_CHUNK_COMMAND) -> {
                val content = fileContent ?: return Utils.FILE_NOT_READY_SW
                val remaining = content.size - fileChunkOffset
                if (remaining <= 0) return Utils.FILE_NOT_READY_SW
                val chunkSize = min(remaining, 245)
                val chunk = content.copyOfRange(fileChunkOffset, fileChunkOffset + chunkSize)
                fileChunkOffset += chunkSize
                if (fileChunkOffset >= content.size) {
                    notifyUI("Transfer Complete")
                }
                Utils.concatArrays(chunk, Utils.SELECT_OK_SW)
            }
            else -> Utils.UNKNOWN_CMD_SW
        }
    }

    private fun handleAppendedFileTransfer(commandApdu: ByteArray): ByteArray {
        return when {
            Arrays.equals(commandApdu, Utils.GET_FILE_INFO_COMMAND) -> {
                val fileData = singleAppendedFile ?: return Utils.FILE_NOT_READY_SW
                val modeByte = "M".toByteArray(Charsets.UTF_8)
                val sizeBytes = ByteBuffer.allocate(4).putInt(fileData.size).array()
                val fileNameBytes = currentFileName.toByteArray(Charsets.UTF_8)
                val fileInfoPayload = Utils.concatArrays(modeByte, sizeBytes, fileNameBytes)
                fileChunkOffset = 0
                Utils.concatArrays(fileInfoPayload, Utils.SELECT_OK_SW)
            }
            Arrays.equals(commandApdu, Utils.GET_NEXT_DATA_CHUNK_COMMAND) -> {
                val fileData = singleAppendedFile ?: return Utils.FILE_NOT_READY_SW
                val remaining = fileData.size - fileChunkOffset
                if (remaining <= 0) return Utils.FILE_NOT_READY_SW
                val chunkSize = min(remaining, 245)
                val chunk = fileData.copyOfRange(fileChunkOffset, fileChunkOffset + chunkSize)
                fileChunkOffset += chunkSize
                if (fileChunkOffset >= fileData.size) {
                    notifyUI("Transfer Complete")
                }
                Utils.concatArrays(chunk, Utils.SELECT_OK_SW)
            }
            else -> Utils.UNKNOWN_CMD_SW
        }
    }

    override fun onDeactivated(reason: Int) {
        peerPublicKey = null
        if (reason != DEACTIVATION_LINK_LOSS) {
            resetTransferState()
        }
    }
}
