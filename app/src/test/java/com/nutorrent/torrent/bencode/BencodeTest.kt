package com.nutorrent.torrent.bencode

import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets

class BencodeTest {

    @Test
    fun `test valid integers`() {
        assertEquals(0L, (BencodeDecoder.decode("i0e".toByteArray()) as BencodeElement.BInteger).value)
        assertEquals(42L, (BencodeDecoder.decode("i42e".toByteArray()) as BencodeElement.BInteger).value)
        assertEquals(-42L, (BencodeDecoder.decode("i-42e".toByteArray()) as BencodeElement.BInteger).value)
        assertEquals(Long.MAX_VALUE, (BencodeDecoder.decode("i${Long.MAX_VALUE}e".toByteArray()) as BencodeElement.BInteger).value)
    }

    @Test
    fun `test invalid integer formats`() {
        assertThrows(BencodeException::class.java) { BencodeDecoder.decode("i03e".toByteArray()) }
        assertThrows(BencodeException::class.java) { BencodeDecoder.decode("i-0e".toByteArray()) }
        assertThrows(BencodeException::class.java) { BencodeDecoder.decode("ie".toByteArray()) }
        assertThrows(BencodeException::class.java) { BencodeDecoder.decode("i-e".toByteArray()) }
        assertThrows(BencodeException::class.java) { BencodeDecoder.decode("i12a3e".toByteArray()) }
        assertThrows(BencodeException::class.java) { BencodeDecoder.decode("i99999999999999999999999e".toByteArray()) }
    }

    @Test
    fun `test valid strings and binary data`() {
        val s = BencodeDecoder.decode("4:spam".toByteArray()) as BencodeElement.BString
        assertEquals("spam", s.asUtf8String)

        val empty = BencodeDecoder.decode("0:".toByteArray()) as BencodeElement.BString
        assertEquals("", empty.asUtf8String)
        assertEquals(0, empty.value.size)

        val binaryBytes = byteArrayOf(0x00, 0xFF.toByte(), 0x7F, 0x12)
        val encodedBinary = "4:".toByteArray() + binaryBytes
        val binaryDecoded = BencodeDecoder.decode(encodedBinary) as BencodeElement.BString
        assertArrayEquals(binaryBytes, binaryDecoded.value)
    }

    @Test
    fun `test invalid strings`() {
        assertThrows(BencodeException::class.java) { BencodeDecoder.decode("04:spam".toByteArray()) }
        assertThrows(BencodeException::class.java) { BencodeDecoder.decode("4:spa".toByteArray()) } // truncated
        assertThrows(BencodeException::class.java) { BencodeDecoder.decode("4spam".toByteArray()) } // missing colon
        assertThrows(BencodeException::class.java) { BencodeDecoder.decode("-5:spam".toByteArray()) }
    }

    @Test
    fun `test lists`() {
        val list = BencodeDecoder.decode("l4:spami42ee".toByteArray()) as BencodeElement.BList
        assertEquals(2, list.value.size)
        assertEquals("spam", (list.value[0] as BencodeElement.BString).asUtf8String)
        assertEquals(42L, (list.value[1] as BencodeElement.BInteger).value)

        val emptyList = BencodeDecoder.decode("le".toByteArray()) as BencodeElement.BList
        assertTrue(emptyList.value.isEmpty())
    }

    @Test
    fun `test dictionaries and nesting`() {
        val dict = BencodeDecoder.decode("d3:bar4:spam3:fooi42ee".toByteArray()) as BencodeElement.BDictionary
        assertEquals("spam", dict.getString("bar"))
        assertEquals(42L, dict.getInteger("foo"))

        val nested = BencodeDecoder.decode("d4:spaml1:a1:bee".toByteArray()) as BencodeElement.BDictionary
        val innerList = nested.getList("spam")
        assertNotNull(innerList)
        assertEquals(2, innerList?.size)
    }

    @Test
    fun `test depth limit`() {
        // Deeply nested list exceeding depth limit
        val deep = "l".repeat(40) + "i1e" + "e".repeat(40)
        assertThrows(BencodeException::class.java) {
            BencodeDecoder(deep.toByteArray(), maxDepth = 32).decodeElement(0)
        }
    }

    @Test
    fun `test raw info range extraction`() {
        val raw = "d4:infod4:name4:test12:piece lengthi16384ee8:announce9:http://txe".toByteArray(StandardCharsets.ISO_8859_1)
        val (root, infoRange) = BencodeDecoder.decodeWithInfoRange(raw)
        assertTrue(root is BencodeElement.BDictionary)
        assertNotNull(infoRange)
        val extractedInfoBytes = raw.sliceArray(infoRange!!)
        val expected = "d4:name4:test12:piece lengthi16384ee".toByteArray(StandardCharsets.ISO_8859_1)
        assertArrayEquals(expected, extractedInfoBytes)
    }

    @Test
    fun `test bencode encoder roundtrip`() {
        val originalDict = linkedMapOf<String, BencodeElement>(
            "z" to BencodeElement.BInteger(100),
            "a" to BencodeElement.BString("apple".toByteArray()),
            "list" to BencodeElement.BList(listOf(BencodeElement.BInteger(1), BencodeElement.BInteger(2)))
        )
        val element = BencodeElement.BDictionary(originalDict)
        val encoded = BencodeEncoder.encode(element)

        val decoded = BencodeDecoder.decode(encoded) as BencodeElement.BDictionary
        assertEquals(100L, decoded.getInteger("z"))
        assertEquals("apple", decoded.getString("a"))
        assertEquals(2, decoded.getList("list")?.size)

        // Ensure key ordering in encoded output is lexicographical: 'a', 'list', 'z'
        val encodedStr = String(encoded, StandardCharsets.US_ASCII)
        assertTrue(encodedStr.startsWith("d1:a5:apple4:listli1ei2ee1:zi100ee"))
    }
}
