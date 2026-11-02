package com.nutorrent.torrent.peer

import com.nutorrent.core.model.AppError
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

object PeerWireCodec {

    fun writeHandshake(output: OutputStream, handshake: PeerHandshake) {
        output.write(handshake.toByteArray())
        output.flush()
    }

    fun readHandshake(input: InputStream): PeerHandshake {
        val buffer = ByteArray(PeerHandshake.HANDSHAKE_LENGTH)
        readFully(input, buffer)
        return PeerHandshake.parse(buffer)
    }

    fun writeMessage(output: OutputStream, message: PeerMessage) {
        output.write(message.toWireBytes())
        output.flush()
    }

    fun readMessage(input: InputStream): PeerMessage {
        val length = readInt(input)

        if (length == 0) {
            return PeerMessage.KeepAlive
        }

        if (length < 0 || length > PeerMessage.MAX_MESSAGE_LENGTH) {
            throw AppError.InvalidTorrent("Peer sent invalid message length: $length (max: ${PeerMessage.MAX_MESSAGE_LENGTH})")
        }

        val id = input.read()
        if (id == -1) {
            throw EOFException("Connection closed by peer while reading message id")
        }

        // Fast-path for Piece messages (ID 7): read block payload directly into target buffer
        // without allocating an intermediate payload buffer or performing redundant copies.
        if (id == 7) {
            if (length < 9) {
                throw AppError.InvalidTorrent("Invalid piece message length: $length")
            }
            val pieceIndex = readInt(input)
            val beginOffset = readInt(input)
            val blockLength = length - 9
            val block = ByteArray(blockLength)
            readFully(input, block)
            return PeerMessage.Piece(pieceIndex, beginOffset, block)
        }

        val payload = ByteArray(length)
        payload[0] = id.toByte()
        readFully(input, payload, 1, length - 1)
        return PeerMessage.parse(payload)
    }

    private fun readInt(input: InputStream): Int {
        val ch1 = input.read()
        val ch2 = input.read()
        val ch3 = input.read()
        val ch4 = input.read()
        if ((ch1 or ch2 or ch3 or ch4) < 0) {
            throw EOFException("Connection closed by peer while reading integer")
        }
        return (ch1 shl 24) + (ch2 shl 16) + (ch3 shl 8) + ch4
    }

    fun readFully(input: InputStream, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset) {
        var bytesRead = 0
        while (bytesRead < length) {
            val count = input.read(buffer, offset + bytesRead, length - bytesRead)
            if (count == -1) {
                throw EOFException("Connection closed by peer while reading $length bytes (read $bytesRead)")
            }
            bytesRead += count
        }
    }
}
