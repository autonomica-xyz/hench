package net.nostr.hench.identity

object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GENERATORS = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    fun encode(hrp: String, payload: ByteArray): String {
        require(hrp.isNotBlank()) { "hrp required" }
        val data = convertBits(payload.map { it.toInt() and 0xff }, fromBits = 8, toBits = 5, pad = true)
        val checksum = createChecksum(hrp, data)
        return hrp.lowercase() + "1" + (data + checksum).joinToString("") { CHARSET[it].toString() }
    }

    fun decode(expectedHrp: String, bech32: String): ByteArray {
        val value = bech32.lowercase()
        val split = value.lastIndexOf('1')
        require(split > 0 && split + 7 <= value.length) { "Invalid bech32 separator" }
        val hrp = value.substring(0, split)
        require(hrp == expectedHrp) { "Unexpected bech32 hrp" }
        val data = value.substring(split + 1).map { char ->
            val index = CHARSET.indexOf(char)
            require(index >= 0) { "Invalid bech32 character" }
            index
        }
        require(verifyChecksum(hrp, data)) { "Invalid bech32 checksum" }
        val payload5 = data.dropLast(6)
        return convertBits(payload5, fromBits = 5, toBits = 8, pad = false).map { it.toByte() }.toByteArray()
    }

    private fun hrpExpand(hrp: String): List<Int> =
        hrp.map { it.code shr 5 } + listOf(0) + hrp.map { it.code and 31 }

    private fun polymod(values: List<Int>): Int {
        var chk = 1
        for (value in values) {
            val top = chk ushr 25
            chk = (chk and 0x1ffffff) shl 5 xor value
            for (i in 0 until 5) {
                if (((top ushr i) and 1) == 1) chk = chk xor GENERATORS[i]
            }
        }
        return chk
    }

    private fun createChecksum(hrp: String, data: List<Int>): List<Int> {
        val mod = polymod(hrpExpand(hrp) + data + List(6) { 0 }) xor 1
        return (0 until 6).map { i -> (mod ushr (5 * (5 - i))) and 31 }
    }

    private fun verifyChecksum(hrp: String, data: List<Int>): Boolean = polymod(hrpExpand(hrp) + data) == 1

    private fun convertBits(data: List<Int>, fromBits: Int, toBits: Int, pad: Boolean): List<Int> {
        var acc = 0
        var bits = 0
        val ret = mutableListOf<Int>()
        val maxv = (1 shl toBits) - 1
        val maxAcc = (1 shl (fromBits + toBits - 1)) - 1
        for (value in data) {
            require(value >= 0 && (value shr fromBits) == 0) { "Invalid bit group" }
            acc = ((acc shl fromBits) or value) and maxAcc
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                ret += (acc shr bits) and maxv
            }
        }
        if (pad) {
            if (bits > 0) ret += (acc shl (toBits - bits)) and maxv
        } else {
            require(bits < fromBits) { "Invalid padding" }
            require(((acc shl (toBits - bits)) and maxv) == 0) { "Non-zero padding" }
        }
        return ret
    }
}
