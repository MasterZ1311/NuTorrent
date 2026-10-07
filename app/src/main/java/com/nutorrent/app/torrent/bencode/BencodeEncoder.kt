package com.nutorrent.app.torrent.bencode

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Encodes Bencode elements into their canonical byte representation according to BEP 3.
 * Dictionary keys are sorted lexicographically by raw byte values.
 */
object BencodeEncoder {

    fun encode(element: BencodeElement): ByteArray {
        val out = ByteArrayOutputStream()
        encode(element, out)
        return out.toByteArray()
    }

    fun encode(element: BencodeElement, out: OutputStream) {
        when (element) {
            is BencodeElement.BInteger -> {
                out.write('i'.code)
                out.write(element.value.toString().toByteArray(StandardCharsets.US_ASCII))
                out.write('e'.code)
            }
            is BencodeElement.BString -> {
                val lenStr = element.value.size.toString().toByteArray(StandardCharsets.US_ASCII)
                out.write(lenStr)
                out.write(':'.code)
                out.write(element.value)
            }
            is BencodeElement.BList -> {
                out.write('l'.code)
                for (item in element.value) {
                    encode(item, out)
                }
                out.write('e'.code)
            }
            is BencodeElement.BDictionary -> {
                out.write('d'.code)
                // Sort keys lexicographically by raw byte values per BEP 3
                val sortedEntries = element.value.entries.sortedWith { e1, e2 ->
                    val b1 = e1.key.toByteArray(StandardCharsets.UTF_8)
                    val b2 = e2.key.toByteArray(StandardCharsets.UTF_8)
                    compareByteArrays(b1, b2)
                }
                for ((k, v) in sortedEntries) {
                    val keyBytes = k.toByteArray(StandardCharsets.UTF_8)
                    val lenStr = keyBytes.size.toString().toByteArray(StandardCharsets.US_ASCII)
                    out.write(lenStr)
                    out.write(':'.code)
                    out.write(keyBytes)
                    encode(v, out)
                }
                out.write('e'.code)
            }
        }
    }

    private fun compareByteArrays(a: ByteArray, b: ByteArray): Int {
        val minLen = minOf(a.size, b.size)
        for (i in 0 until minLen) {
            val byteA = a[i].toInt() and 0xFF
            val byteB = b[i].toInt() and 0xFF
            if (byteA != byteB) {
                return byteA - byteB
            }
        }
        return a.size - b.size
    }
}
