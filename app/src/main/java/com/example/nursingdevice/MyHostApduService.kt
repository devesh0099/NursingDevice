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

    private var tempEncryptedKey: ByteArray? = null
    private var sessionKey: ByteArray? = null
    private var isAuthenticated = false

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
        if (Arrays.equals(commandApdu, Utils.SELECT_APD)) {
            isAuthenticated = false
            sessionKey = null
            tempEncryptedKey = null
            fileChunkOffset = 0

            transferMode = sharedTransferMode
            textContent = sharedTextContent
            fileContent = sharedFileContent
            fileMimeType = sharedFileMimeType

            notifyUI("Step 1: Connection Established")
            return Utils.SELECT_OK_SW
        }

        if (commandApdu.size > 8 && Arrays.equals(commandApdu.take(8).toByteArray(), CryptoUtils.CMD_AUTH_SEND_KEY)) {
            tempEncryptedKey = commandApdu.copyOfRange(8, commandApdu.size)
            notifyUI("Step 2: Key Received")
            return Utils.SELECT_OK_SW
        }

        if (commandApdu.size > 8 && Arrays.equals(commandApdu.take(8).toByteArray(), CryptoUtils.CMD_AUTH_SEND_SIG)) {
            val signature = commandApdu.copyOfRange(8, commandApdu.size)

            if (tempEncryptedKey == null) {
                notifyUI("Error: Signature without Key")
                return Utils.FILE_NOT_READY_SW
            }

            try {
                val isValid = CryptoUtils.rsaVerify(tempEncryptedKey!!, signature, CryptoUtils.getOtherPublicKey())

                if (isValid) {
                    sessionKey = CryptoUtils.rsaDecrypt(tempEncryptedKey!!, CryptoUtils.getMyPrivateKey())
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
        if (reason != DEACTIVATION_LINK_LOSS) {
            resetTransferState()
        }
    }
}
