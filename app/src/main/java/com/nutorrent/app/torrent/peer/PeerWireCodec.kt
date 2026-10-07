package com.nutorrent.app.torrent.peer

import com.nutorrent.app.core.model.AppError
import java.io.DataInputStream
import java.io.DataOutputStream
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
        val dis = DataInputStream(input)
        val length = dis.readInt()

        if (length == 0) {
            return PeerMessage.KeepAlive
        }

        if (length < 0 || length > PeerMessage.MAX_MESSAGE_LENGTH) {
            throw AppError.InvalidTorrent("Peer sent invalid message length: $length (max: ${PeerMessage.MAX_MESSAGE_LENGTH})")
        }

        val payload = ByteArray(length)
        dis.readFully(payload)
        return PeerMessage.parse(payload)
    }

    fun readFully(input: InputStream, buffer: ByteArray) {
        var bytesRead = 0
        while (bytesRead < buffer.size) {
            val count = input.read(buffer, bytesRead, buffer.size - bytesRead)
            if (count == -1) {
                throw EOFException("Connection closed by peer while reading ${buffer.size} bytes (read $bytesRead)")
            }
            bytesRead += count
        }
    }
}
