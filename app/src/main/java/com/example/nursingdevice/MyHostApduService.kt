package com.example.nursingdevice

import android.content.Intent
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.nio.ByteBuffer
import java.util.Arrays
import kotlin.math.min

data class FileData(val name: String, val content: ByteArray)

class MyHostApduService : HostApduService() {

    private var transferMode = "NONE"
    private var textContent: String? = null
    private var fileContent: ByteArray? = null
    private var fileMimeType: String? = null
    private var fileChunkOffset: Int = 0
    private var singleAppendedFile: ByteArray? = null
    private var currentFileName: String = "appended_data.dat"

    // NSE-AA auth state
    private var sessionKey: ByteArray? = null
    private var isAuthenticated = false
    private var serverNonce: ByteArray? = null
    private var serverVirtualId: ByteArray? = null

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
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun notifyUI(step: String) {
        mainHandler.post {
            onStatusUpdate?.invoke(step)
        }
    }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        // ===== NSE-AA Step 1: SELECT — Generate and send challenge =====
        if (Arrays.equals(commandApdu, Utils.SELECT_APD)) {
            isAuthenticated = false
            sessionKey = null
            serverNonce = null
            serverVirtualId = null
            fileChunkOffset = 0

            transferMode = sharedTransferMode
            textContent = sharedTextContent
            fileContent = sharedFileContent
            fileMimeType = sharedFileMimeType

            // Generate challenge per NSE-AA protocol (Table II, Step 2)
            serverNonce = CryptoUtils.generateNonce()
            serverVirtualId = CryptoUtils.generateMyVirtualIdentity(serverNonce!!)

            // T1 = E(K_UD, id_VS || N_S)
            val t1 = CryptoUtils.aesEncrypt(
                serverVirtualId!! + serverNonce!!,
                CryptoUtils.getKUD()
            )

            notifyUI("Step 1: Challenge Sent")
            return Utils.concatArrays(serverNonce!!, t1, Utils.SELECT_OK_SW)
        }

        // ===== NSE-AA Step 2: AUTH_RESP — Verify reader, derive session key K_S =====
        val cmdResp = CryptoUtils.CMD_AUTH_RESP
        if (commandApdu.size > cmdResp.size &&
            commandApdu.take(cmdResp.size).toByteArray().contentEquals(cmdResp)) {

            val storedNonce = serverNonce
            val storedVirtualId = serverVirtualId
            if (storedNonce == null || storedVirtualId == null) {
                notifyUI("Error: Auth response without challenge")
                return Utils.FILE_NOT_READY_SW
            }

            try {
                val payload = commandApdu.copyOfRange(cmdResp.size, commandApdu.size)
                val idVR = payload.copyOfRange(0, 16)
                val nonceR = payload.copyOfRange(16, 32)
                val t3 = payload.copyOfRange(payload.size - 32, payload.size)
                val t2 = payload.copyOfRange(32, payload.size - 32)

                // Decrypt T2: E(K_UD, pwb_R(32) || N_R(16) || N_S(16))
                val t2Plain = CryptoUtils.aesDecrypt(t2, CryptoUtils.getKUD())
                val pwbR = t2Plain.copyOfRange(0, 32)
                val nR = t2Plain.copyOfRange(32, 48)
                val nS = t2Plain.copyOfRange(48, 64)

                // Verify reader's identity via password-hash
                if (!pwbR.contentEquals(CryptoUtils.OTHER_PWB)) {
                    notifyUI("Auth Failed: Unknown device")
                    return Utils.FILE_NOT_READY_SW
                }
                // Verify nonce freshness (anti-replay)
                if (!nS.contentEquals(storedNonce)) {
                    notifyUI("Auth Failed: Stale challenge")
                    return Utils.FILE_NOT_READY_SW
                }
                if (!nR.contentEquals(nonceR)) {
                    notifyUI("Auth Failed: Nonce mismatch")
                    return Utils.FILE_NOT_READY_SW
                }

                // Derive session key K_S
                val kS = CryptoUtils.deriveSessionKey(pwbR, CryptoUtils.MY_PWB, nonceR, storedNonce)

                // Verify HMAC T3 for integrity
                val expectedT3 = CryptoUtils.hmacSha256(kS,
                    idVR + nonceR + storedVirtualId + storedNonce)
                if (!t3.contentEquals(expectedT3)) {
                    notifyUI("Auth Failed: Integrity check failed")
                    return Utils.FILE_NOT_READY_SW
                }

                sessionKey = kS
                isAuthenticated = true

                notifyUI("Step 2: Mutually Authenticated (NSE-AA)")
                notifyUI("Transmitting Data...")

                // T4 = E(K_S, "AUTH_OK" || pwb_S || N_R) — proves server identity
                val t4Plain = "AUTH_OK".toByteArray(Charsets.UTF_8) +
                    CryptoUtils.MY_PWB + nonceR
                val t4 = CryptoUtils.aesEncrypt(t4Plain, kS)
                return Utils.concatArrays(t4, Utils.SELECT_OK_SW)

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
        if (reason != DEACTIVATION_LINK_LOSS) {
            resetTransferState()
        }
    }
}
