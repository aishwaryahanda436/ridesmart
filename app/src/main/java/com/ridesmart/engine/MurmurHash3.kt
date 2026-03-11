package com.ridesmart.engine

/**
 * 32-bit MurmurHash3 implementation.
 * Fast, non-cryptographic hash with excellent avalanche characteristics.
 * Used for O(1) session fingerprinting and deduplication.
 */
object MurmurHash3 {

    fun hash32(input: String, seed: Int = 0): Int {
        val data = input.toByteArray(Charsets.UTF_8)
        var h1 = seed
        val c1 = 0xcc9e2d51.toInt()
        val c2 = 0x1b873593.toInt()

        val length = data.size
        val nBlocks = length / 4

        for (i in 0 until nBlocks) {
            var k1 = (data[i * 4].toInt() and 0xff) or
                    ((data[i * 4 + 1].toInt() and 0xff) shl 8) or
                    ((data[i * 4 + 2].toInt() and 0xff) shl 16) or
                    ((data[i * 4 + 3].toInt() and 0xff) shl 24)

            k1 *= c1
            k1 = (k1 shl 15) or (k1 ushr 17)
            k1 *= c2

            h1 = h1 xor k1
            h1 = (h1 shl 13) or (h1 ushr 19)
            h1 = h1 * 5 + 0xe6546b64.toInt()
        }

        var k1 = 0
        val tailStart = nBlocks * 4
        val left = length % 4

        if (left >= 3) k1 = k1 xor ((data[tailStart + 2].toInt() and 0xff) shl 16)
        if (left >= 2) k1 = k1 xor ((data[tailStart + 1].toInt() and 0xff) shl 8)
        if (left >= 1) {
            k1 = k1 xor (data[tailStart].toInt() and 0xff)
            k1 *= c1
            k1 = (k1 shl 15) or (k1 ushr 17)
            k1 *= c2
            h1 = h1 xor k1
        }

        h1 = h1 xor length
        h1 = h1 xor (h1 ushr 16)
        h1 *= 0x85ebca6b.toInt()
        h1 = h1 xor (h1 ushr 13)
        h1 *= 0xc2b2ae35.toInt()
        h1 = h1 xor (h1 ushr 16)

        return h1
    }
}
