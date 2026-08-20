package com.capstone.chatapp.data.security

import java.security.MessageDigest

/**
 * A short, deterministic fingerprint of two users' public keys — identical on both devices no
 * matter who computes it, so two people can read it aloud (or compare it visually) to confirm
 * no man-in-the-middle swapped a public key in transit. Same idea as Signal's "safety number".
 *
 * QR pairing ([com.capstone.chatapp.ui.pairing.PairingScreen]) verifies a contact automatically
 * because the key came straight from the peer's device; this fingerprint is the fallback for
 * verifying without scanning (e.g. reading it over a phone call).
 */
object SafetyNumber {
    fun compute(uidA: String, pubKeyBase64A: String, uidB: String, pubKeyBase64B: String): String {
        val ordered = if (uidA <= uidB) {
            uidA + pubKeyBase64A + uidB + pubKeyBase64B
        } else {
            uidB + pubKeyBase64B + uidA + pubKeyBase64A
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(ordered.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
            .chunked(4)
            .take(10)
            .joinToString(" ")
    }
}
