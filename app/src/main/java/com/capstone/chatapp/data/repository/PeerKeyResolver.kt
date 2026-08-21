package com.capstone.chatapp.data.repository

import com.capstone.chatapp.data.local.ContactSecurityStore

/**
 * Resolves + caches a peer's published X25519 public key: the local cache first (works
 * offline, and is how a QR-paired contact's key is found before it's ever been fetched
 * online), falling back to their Firestore profile. Shared by [ChatRepository] (online sends)
 * and `OfflineMessageRouter` (BLE/Wi-Fi Direct sends/acks) so both encrypt against the same
 * cache instead of resolving/caching the key twice.
 */
class PeerKeyResolver(
    private val userRepository: UserRepository,
    private val contactSecurityStore: ContactSecurityStore,
) {
    /** Throws if neither the cache nor Firestore has the key -- meaning the peer has never
     * logged in since encryption shipped, so there is nothing to encrypt to yet. */
    suspend fun resolve(myUid: String, peerId: String): String {
        contactSecurityStore.getCachedPeerPublicKey(myUid, peerId)?.let { return it }
        val fetched = userRepository.getUser(peerId)?.pubKey
        require(!fetched.isNullOrBlank()) { "This contact hasn't set up encryption yet" }
        contactSecurityStore.cachePeerPublicKey(myUid, peerId, fetched)
        return fetched
    }
}
