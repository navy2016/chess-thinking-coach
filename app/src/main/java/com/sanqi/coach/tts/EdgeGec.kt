package com.sanqi.coach.tts

import java.nio.ByteBuffer
import java.nio.ByteOrder

// Protocol v2 port of edge-tts 7.3.1 (360fa8e); notices are in assets/speech/.
internal object EdgeGec {
    const val CLIENT = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    const val VERSION = "2-143.0.3650.75"
    private fun words(value: String) = value.split(' ').map { it.toLong(16).toInt() }.toIntArray()
    private val initial = words("6A09E667 BB67AE85 3C6EF372 A54FF53A 510E527F 9B05688C 1F83D9AB 5BE0CD19")
    private val seed = words("FD9FBEA2 91243CE7 BE499094 CD2E1E0A 6C006F49 04BD35AB FEB0A7E4 03EFFBD2")
    private val schedule = words("EB24BF9E 1558691F A0C09EE2 0CFA32BA A622EE8C D1DD9491 C0DD25A0 F9E70AB6 F0C31573 7058CE4E 7188E606 00E5AB33 D258B060 72766564")
    private val constants = words("428A2F98 71374491 B5C0FBCF E9B5DBA5 3956C25B 59F111F1 923F82A4 AB1C5ED5 D807AA98 12835B01 243185BE 550C7DC3 72BE5D74 80DEB1FE 9BDC06A7 C19BF174 E49B69C1 EFBE4786 0FC19DC6 240CA1CC 2DE92C6F 4A7484AA 5CB0A9DC 76F988DA 983E5152 A831C66D B00327C8 BF597FC7 C6E00BF3 D5A79147 06CA6351 14292967 27B70A85 2E1B2138 4D2C6DFC 53380D13 650A7354 766A0ABB 81C2C92E 92722C85 A2BFE8A1 A81A664B C24B8B70 C76C51A3 D192E819 D6990624 F40E3585 106AA070 19A4C116 1E376C08 2748774C 34B0BCB5 391C0CB3 4ED8AA4A 5B9CCA4F 682E6FF3 748F82EE 78A5636F 84C87814 8CC70208 90BEFFFA A4506CEB BEF9A3F7 C67178F2")
    private val coefficients = words("E82E91B1 B32700D0 44DE24C6 88DFF51D 3BEB65BC 29514038 C89C5933 B328CBDF 8D1EC211 D405B4D5 314EF5D5 26E55F3C E92491B1 607801C6 F0A5B9D5 82B2C1FB 06064CF3 FA51A3DA DA79FA60 207CC464 BED20560 3C3C74F3 89F9C9F8 D2D14B61 35FA38C6 BB04A2C1 CB49E3C6 C5626167")
    private fun r(value: Int, count: Int) = Integer.rotateRight(value, count)
    private fun s0(value: Int) = r(value, 7) xor r(value, 18) xor (value ushr 3)
    private fun s1(value: Int) = r(value, 17) xor r(value, 19) xor (value ushr 10)
    private fun swap(value: Int) = Integer.rotateLeft(value, 16)

    private fun expand(w: IntArray, from: Int = 16) {
        for (i in from until 64) w[i] = s1(w[i - 2]) + w[i - 7] + s0(w[i - 15]) + w[i - 16]
    }

    private fun rounds(state: IntArray, w: IntArray, first: Int = 0): IntArray {
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        for (i in first until 64) {
            val k = if (first == 14 && i == 14) 0xC9DC06A7.toInt() else constants[i]
            val t1 = h + (r(e, 6) xor r(e, 11) xor r(e, 25)) + (g xor (e and (f xor g))) + k + w[i]
            val t2 = (r(a, 2) xor r(a, 13) xor r(a, 22)) + ((a and b) or (c and (a or b)))
            h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        return intArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun digest(data: ByteArray): ByteArray {
        val w = IntArray(64)
        w[14] = ((data[0].toInt() and 255) shl 16) or ((data[1].toInt() and 255) shl 8) or (data[2].toInt() and 255)
        w[15] = ByteBuffer.wrap(data, 3, 4).int
        for (i in 16 until 30) {
            var value = schedule[i - 16]
            if (i - 2 >= 14) value += s1(w[i - 2]) xor (if (i == 16) 0x000B92C0 else 0)
            if (i - 7 >= 14) value += w[i - 7]
            if (i - 15 >= 14) value += s0(w[i - 15]) xor (if (i == 29) 0x059C0B80 else 0)
            w[i] = value
        }
        w[30] = s1(w[28]) + w[23] + s0(w[15]) + w[14] + 0xC9DC06A7.toInt() - constants[14]
        expand(w, 31)
        val first = rounds(seed, w, 14)
        var state = IntArray(8) { initial[it] + first[it] }
        val length = 57 + data.size
        val padding = ((55 - length) % 64 + 64) % 64
        val buffer = ByteBuffer.allocate(data.size - 7 + 1 + padding + 8)
        buffer.put(data, 7, data.size - 7).put(0x80.toByte()).put(ByteArray(padding)).putLong(length * 8L)
        (buffer as java.nio.Buffer).flip()
        while (buffer.hasRemaining()) {
            val block = IntArray(64)
            for (i in 0 until 16) block[i] = buffer.int
            expand(block)
            val working = rounds(state, block)
            state = IntArray(8) { state[it] + working[it] }
        }
        return ByteBuffer.allocate(32).apply { state.forEach { putInt(it) } }.array()
    }

    private fun cs64(data: ByteArray, hash: ByteArray): ByteArray {
        val keys = ByteBuffer.wrap(hash).order(ByteOrder.LITTLE_ENDIAN)
        val a = keys.int or 1
        val b = keys.int or 1
        val factors = intArrayOf(a * 0x82893933.toInt(), b * 0x82893933.toInt())
        val count = ((data.size + 1 + 7) / 8) * 2
        val source = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN).apply { put(0.toByte()); put(data) }
        (source as java.nio.Buffer).rewind()
        val terms = IntArray(14 + count)
        val offsets = IntArray(terms.size)
        for (i in 0 until 14) {
            terms[i] = coefficients[i * 2] * factors[i % 2]
            offsets[i] = coefficients[i * 2 + 1]
        }
        for (i in 0 until count) {
            val word = source.int
            terms[14 + i] = word * (if (i % 2 == 0) a else b)
            offsets[14 + i] = swap(word) * (if (i % 2 == 0) 0xEF0569FB.toInt() else 0xC31713DB.toInt())
            if (i == 0) {
                terms[14] += 0xF2F90B1A.toInt() * factors[0]
                offsets[14] += 0x0B1A0000
            }
        }
        var swapAcc = 0; var swapLast = 0; var state = 0; var acc = 0
        for (i in terms.indices step 2) {
            val x = terms[i]; val y = terms[i + 1]
            var q = swap(offsets[i] + x) * 0x76F50F34
            swapAcc += swap(q) * 0xC3EFEA97.toInt() + q
            q = swap(offsets[i + 1] + y) * 0x1881CE3C
            swapLast = swap(q) * 0x35BD1EC9 + q
            swapAcc += swapLast
            state = state * a + x
            state = swap(state) * 0xCF98B111.toInt()
            state = swap(state) * 0x87085B9F.toInt()
            state = swap(state) * 0x12CEB96D
            state = swap(state) * 0x257E1D83
            acc += state
            var t = state * b + y
            t = swap(t) * 0xA27416F5.toInt()
            t = swap(t) * 0xD38396FF.toInt()
            state = swap(swap(t) * 0x7C932B89) * 0xBFA49F69.toInt()
            acc += state
        }
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(swapLast xor state).putInt(swapAcc xor acc).array()
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it.toInt() and 255) }

    fun token(unixSeconds: Long = System.currentTimeMillis() / 1000): String {
        val ticks = ((unixSeconds + 11644473600L) / 300) * 300 * 10000000L
        val data = "$ticks$CLIENT".toByteArray(Charsets.US_ASCII)
        val hash = digest(data)
        return hex(cs64(data, hash)) + "-" + hex(hash)
    }
}
