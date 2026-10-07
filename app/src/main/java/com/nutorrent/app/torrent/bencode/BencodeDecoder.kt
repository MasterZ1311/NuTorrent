package com.nutorrent.app.torrent.bencode

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Strict and robust Bencode decoder adhering to BEP 3.
 * Includes security guardrails against malicious nesting, unbounded allocation,
 * invalid integer syntax, and truncated data.
 */
class BencodeDecoder(
    private val bytes: ByteArray,
    private val maxDepth: Int = 32,
    private val maxStringLength: Int = 64 * 1024 * 1024 // 64 MiB limit per string
) {
    private var pos: Int = 0

    // Stores byte ranges [start, endExclusive) for keys in the root dictionary,
    // which allows extracting the exact raw byte range of the "info" dictionary for SHA-1 calculation.
    private val rootKeyByteRanges = mutableMapOf<String, IntRange>()

    companion object {
        fun decode(bytes: ByteArray): BencodeElement {
            val decoder = BencodeDecoder(bytes)
            val result = decoder.decodeElement(depth = 0)
            if (decoder.hasRemaining()) {
                throw BencodeException("Trailing bytes after root element at position ${decoder.pos}")
            }
            return result
        }

        fun decodeWithInfoRange(bytes: ByteArray): Pair<BencodeElement, IntRange?> {
            val decoder = BencodeDecoder(bytes)
            val result = decoder.decodeRootDictionary()
            val infoRange = decoder.rootKeyByteRanges["info"]
            return Pair(result, infoRange)
        }
    }

    fun hasRemaining(): Boolean = pos < bytes.size

    fun getPosition(): Int = pos

    private fun peek(): Byte {
        if (pos >= bytes.size) throw BencodeException("Unexpected end of data at position $pos")
        return bytes[pos]
    }

    private fun next(): Byte {
        if (pos >= bytes.size) throw BencodeException("Unexpected end of data at position $pos")
        return bytes[pos++]
    }

    fun decodeRootDictionary(): BencodeElement.BDictionary {
        val element = decodeElement(depth = 0, isRoot = true)
        if (element !is BencodeElement.BDictionary) {
            throw BencodeException("Expected root element to be a dictionary, got ${element.javaClass.simpleName}")
        }
        return element
    }

    fun decodeElement(depth: Int, isRoot: Boolean = false): BencodeElement {
        if (depth > maxDepth) {
            throw BencodeException("Exceeded maximum nesting depth of $maxDepth at position $pos")
        }

        return when (val b = peek().toInt().toChar()) {
            'i' -> decodeInteger()
            'l' -> decodeList(depth)
            'd' -> decodeDictionary(depth, isRoot)
            in '0'..'9' -> decodeString()
            else -> throw BencodeException("Unexpected bencode prefix '$b' (0x${peek().toInt().toString(16)}) at position $pos")
        }
    }

    private fun decodeInteger(): BencodeElement.BInteger {
        // Must start with 'i'
        val startPos = pos
        next() // consume 'i'

        if (!hasRemaining()) throw BencodeException("Truncated integer at position $pos")

        val isNegative = peek() == '-'.code.toByte()
        if (isNegative) {
            next()
            if (!hasRemaining()) throw BencodeException("Truncated negative integer at position $pos")
            if (peek() == '0'.code.toByte()) {
                throw BencodeException("Negative zero 'i-0e' is invalid bencode at position $pos")
            }
        }

        var value: Long = 0
        var digitsCount = 0
        var firstDigit: Char? = null

        while (hasRemaining()) {
            val b = next()
            if (b == 'e'.code.toByte()) {
                break
            }
            val char = b.toInt().toChar()
            if (char !in '0'..'9') {
                throw BencodeException("Invalid character '$char' in integer at position ${pos - 1}")
            }
            if (digitsCount == 0) {
                firstDigit = char
            }
            // Check for leading zero violation: i03e is invalid, but i0e is valid
            if (digitsCount > 0 && firstDigit == '0') {
                throw BencodeException("Leading zeros are not allowed in integer at position $startPos")
            }

            val digit = char - '0'
            // Check for 64-bit signed integer overflow
            if (value > (Long.MAX_VALUE - digit) / 10) {
                throw BencodeException("Integer overflow at position $startPos")
            }
            value = value * 10 + digit
            digitsCount++
        }

        if (digitsCount == 0) {
            throw BencodeException("Empty integer 'ie' at position $startPos")
        }

        val finalValue = if (isNegative) -value else value
        return BencodeElement.BInteger(finalValue)
    }

    private fun decodeString(): BencodeElement.BString {
        val startPos = pos
        var length: Long = 0
        var firstChar: Char? = null
        var digitsCount = 0

        while (hasRemaining()) {
            val b = next()
            if (b == ':'.code.toByte()) {
                break
            }
            val char = b.toInt().toChar()
            if (char !in '0'..'9') {
                throw BencodeException("Invalid string length character '$char' at position ${pos - 1}")
            }
            if (digitsCount == 0) {
                firstChar = char
            }
            if (digitsCount > 0 && firstChar == '0') {
                throw BencodeException("Leading zeros in string length at position $startPos")
            }

            val digit = char - '0'
            if (length > (Long.MAX_VALUE - digit) / 10) {
                throw BencodeException("String length overflow at position $startPos")
            }
            length = length * 10 + digit
            digitsCount++
        }

        if (digitsCount == 0) {
            throw BencodeException("Missing string length before ':' at position $startPos")
        }
        if (length < 0) {
            throw BencodeException("Negative string length $length at position $startPos")
        }
        if (length > maxStringLength) {
            throw BencodeException("String length $length exceeds safety maximum $maxStringLength at position $startPos")
        }

        val intLen = length.toInt()
        if (pos + intLen > bytes.size) {
            throw BencodeException("Truncated string: expected $intLen bytes, only ${bytes.size - pos} available at position $pos")
        }

        val stringBytes = ByteArray(intLen)
        System.arraycopy(bytes, pos, stringBytes, 0, intLen)
        pos += intLen

        return BencodeElement.BString(stringBytes)
    }

    private fun decodeList(depth: Int): BencodeElement.BList {
        next() // consume 'l'
        val list = mutableListOf<BencodeElement>()

        while (hasRemaining()) {
            if (peek() == 'e'.code.toByte()) {
                next() // consume 'e'
                return BencodeElement.BList(list)
            }
            list.add(decodeElement(depth + 1))
        }

        throw BencodeException("Unterminated list starting before end of data")
    }

    private fun decodeDictionary(depth: Int, isRoot: Boolean = false): BencodeElement.BDictionary {
        next() // consume 'd'
        val map = linkedMapOf<String, BencodeElement>()
        val rawEntries = mutableListOf<Pair<BencodeElement.BString, BencodeElement>>()

        while (hasRemaining()) {
            if (peek() == 'e'.code.toByte()) {
                next() // consume 'e'
                return BencodeElement.BDictionary(map, rawEntries)
            }

            // Keys must be strings
            val keyStartPos = pos
            if (peek().toInt().toChar() !in '0'..'9') {
                throw BencodeException("Dictionary key must be a string, found '${peek().toInt().toChar()}' at position $pos")
            }
            val keyString = decodeString()
            val key = keyString.asUtf8String

            val valStartPos = pos
            val value = decodeElement(depth + 1)
            val valEndPos = pos

            if (isRoot) {
                rootKeyByteRanges[key] = IntRange(valStartPos, valEndPos - 1)
            }

            map[key] = value
            rawEntries.add(Pair(keyString, value))
        }

        throw BencodeException("Unterminated dictionary starting before end of data")
    }
}
