package net.nostr.hench.identity

import java.math.BigInteger
import java.security.MessageDigest

object Bip340Schnorr {
    private val p = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private val n = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val gx = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    private val gy = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    private val g = Point(gx, gy)

    fun publicKey(secretKey: ByteArray): ByteArray {
        val d = int(secretKey)
        require(d >= BigInteger.ONE && d < n) { "Invalid secp256k1 secret key" }
        return bytes(scalarMultiply(d, g).x)
    }

    fun sharedSecretX(secretKey: ByteArray, publicKeyXOnly: ByteArray): ByteArray {
        val d = int(secretKey)
        require(d >= BigInteger.ONE && d < n) { "Invalid secp256k1 secret key" }
        require(publicKeyXOnly.size == 32) { "Nostr public key must be 32 bytes" }
        val publicPoint = liftX(int(publicKeyXOnly))
        return bytes(scalarMultiply(d, publicPoint).x)
    }

    fun sign(message32: ByteArray, secretKey: ByteArray, auxRand32: ByteArray = ByteArray(32)): ByteArray {
        require(message32.size == 32) { "BIP-340 message must be 32 bytes" }
        require(auxRand32.size == 32) { "BIP-340 aux rand must be 32 bytes" }
        val d0 = int(secretKey)
        require(d0 >= BigInteger.ONE && d0 < n) { "Invalid secp256k1 secret key" }
        val pPoint = scalarMultiply(d0, g)
        val d = if (hasEvenY(pPoint)) d0 else n - d0
        val px = bytes(pPoint.x)
        val t = bytes(d).xor(taggedHash("BIP0340/aux", auxRand32))
        val k0 = int(taggedHash("BIP0340/nonce", t + px + message32)).mod(n)
        require(k0 != BigInteger.ZERO) { "Failure. This happens only with negligible probability." }
        val rPoint = scalarMultiply(k0, g)
        val k = if (hasEvenY(rPoint)) k0 else n - k0
        val rx = bytes(rPoint.x)
        val e = int(taggedHash("BIP0340/challenge", rx + px + message32)).mod(n)
        return rx + bytes((k + e * d).mod(n))
    }

    /**
     * BIP-340 verification of `sig` (r‖s, 64 bytes) over `message32` for the
     * x-only `pubkey`. Returns false on any malformed input — never throws —
     * so it is safe to run on untrusted wire data.
     */
    fun verify(message32: ByteArray, pubkey: ByteArray, sig: ByteArray): Boolean = runCatching {
        require(message32.size == 32 && pubkey.size == 32 && sig.size == 64) { "bad input sizes" }
        val pPoint = liftX(int(pubkey))
        val r = int(sig.copyOfRange(0, 32))
        val s = int(sig.copyOfRange(32, 64))
        require(r < p && s < n) { "signature scalar out of range" }
        val e = int(taggedHash("BIP0340/challenge", sig.copyOfRange(0, 32) + pubkey + message32)).mod(n)
        // r1 = s*G - e*P; must be a finite point with even y and x == r.
        val r1 = scalarMultiply(s, g).toJacobian()
            .add(scalarMultiply(e, pPoint.negate()).toJacobian())
        if (r1.isInfinity) return@runCatching false
        val r1Affine = r1.toAffine()
        r1Affine.x == r && !r1Affine.y.testBit(0)
    }.getOrDefault(false)

    private data class Point(val x: BigInteger, val y: BigInteger)
    private data class JacobianPoint(val x: BigInteger, val y: BigInteger, val z: BigInteger) {
        val isInfinity: Boolean get() = z == BigInteger.ZERO
    }

    private fun liftX(x: BigInteger): Point {
        require(x >= BigInteger.ZERO && x < p) { "Invalid secp256k1 x coordinate" }
        val ySquared = (x.modPow(BigInteger.valueOf(3), p) + BigInteger.valueOf(7)).mod(p)
        val y = ySquared.modPow((p + BigInteger.ONE).shiftRight(2), p)
        require(y.modPow(TWO, p) == ySquared) { "Invalid secp256k1 public key" }
        return Point(x, if (y.testBit(0)) p - y else y)
    }

    private fun hasEvenY(point: Point): Boolean = !point.y.testBit(0)

    private fun scalarMultiply(k: BigInteger, point: Point): Point {
        val nValue = k.mod(n)
        var result = INFINITY
        val addend = point.toJacobian()
        for (bit in nValue.bitLength() - 1 downTo 0) {
            result = result.double()
            if (nValue.testBit(bit)) result = result.add(addend)
        }
        return result.toAffine()
    }

    private fun Point.negate(): Point = Point(x, (p - y).mod(p))
    private fun Point.toJacobian(): JacobianPoint = JacobianPoint(x, y, BigInteger.ONE)

    private fun JacobianPoint.toAffine(): Point {
        require(!isInfinity) { "Invalid scalar multiplication result" }
        val zInv = z.modInverse(p)
        val zInv2 = zInv.square()
        return Point(
            x = (x * zInv2).mod(p),
            y = (y * zInv2 * zInv).mod(p),
        )
    }

    private fun JacobianPoint.double(): JacobianPoint {
        if (isInfinity || y == BigInteger.ZERO) return INFINITY
        val yy = y.square()
        val yyyy = yy.square()
        val s = (FOUR * x * yy).mod(p)
        val m = (THREE * x.square()).mod(p)
        val x3 = (m.square() - TWO * s).mod(p)
        val y3 = (m * (s - x3) - EIGHT * yyyy).mod(p)
        val z3 = (TWO * y * z).mod(p)
        return JacobianPoint(x3, y3, z3)
    }

    private fun JacobianPoint.add(other: JacobianPoint): JacobianPoint {
        if (isInfinity) return other
        if (other.isInfinity) return this
        val z1z1 = z.square()
        val z2z2 = other.z.square()
        val u1 = (x * z2z2).mod(p)
        val u2 = (other.x * z1z1).mod(p)
        val s1 = (y * other.z * z2z2).mod(p)
        val s2 = (other.y * z * z1z1).mod(p)
        if (u1 == u2) return if (s1 == s2) double() else INFINITY

        val h = (u2 - u1).mod(p)
        val i = (TWO * h).square()
        val j = (h * i).mod(p)
        val r = (TWO * (s2 - s1)).mod(p)
        val v = (u1 * i).mod(p)
        val x3 = (r.square() - j - TWO * v).mod(p)
        val y3 = (r * (v - x3) - TWO * s1 * j).mod(p)
        val z3 = (((z + other.z).square() - z1z1 - z2z2) * h).mod(p)
        return JacobianPoint(x3, y3, z3)
    }

    private fun BigInteger.square(): BigInteger = (this * this).mod(p)

    private fun int(bytes: ByteArray): BigInteger = BigInteger(1, bytes)

    private fun taggedHash(tag: String, message: ByteArray): ByteArray {
        val tagHash = MessageDigest.getInstance("SHA-256").digest(tag.toByteArray(Charsets.UTF_8))
        return MessageDigest.getInstance("SHA-256").digest(tagHash + tagHash + message)
    }

    private fun bytes(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        val unsigned = if (raw.isNotEmpty() && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
        require(unsigned.size <= 32) { "Integer does not fit in 32 bytes" }
        return ByteArray(32 - unsigned.size) + unsigned
    }

    private fun ByteArray.xor(other: ByteArray): ByteArray = ByteArray(size) { i -> (this[i].toInt() xor other[i].toInt()).toByte() }

    private val INFINITY = JacobianPoint(BigInteger.ZERO, BigInteger.ONE, BigInteger.ZERO)
    private val TWO = BigInteger.valueOf(2)
    private val THREE = BigInteger.valueOf(3)
    private val FOUR = BigInteger.valueOf(4)
    private val EIGHT = BigInteger.valueOf(8)
}

fun String.hexToBytes(): ByteArray {
    val clean = trim().removePrefix("0x")
    require(clean.length % 2 == 0) { "Hex string must have even length" }
    return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
