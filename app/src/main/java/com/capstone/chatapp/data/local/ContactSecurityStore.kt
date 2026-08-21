package com.capstone.chatapp.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.contactSecurityDataStore: DataStore<Preferences> by preferencesDataStore(name = "contact_security")

/**
 * Local cache of peers' published public keys — so a chat opened once still encrypts/decrypts
 * offline later — plus the "verified" flag set once a contact's key is confirmed out-of-band
 * (QR pairing, or a manually compared [com.capstone.chatapp.data.security.SafetyNumber]).
 * Keyed by (myUid, peerUid) so switching accounts on one device never mixes up contacts.
 */
class ContactSecurityStore(private val context: Context) {

    private fun pubKeyKey(myUid: String, peerUid: String) = stringPreferencesKey("pubkey_${myUid}_$peerUid")
    private fun verifiedKey(myUid: String, peerUid: String) = booleanPreferencesKey("verified_${myUid}_$peerUid")

    suspend fun cachePeerPublicKey(myUid: String, peerUid: String, pubKeyBase64: String) {
        context.contactSecurityDataStore.edit { it[pubKeyKey(myUid, peerUid)] = pubKeyBase64 }
    }

    suspend fun getCachedPeerPublicKey(myUid: String, peerUid: String): String? =
        context.contactSecurityDataStore.data.map { it[pubKeyKey(myUid, peerUid)] }.first()

    suspend fun setVerified(myUid: String, peerUid: String, verified: Boolean) {
        context.contactSecurityDataStore.edit { it[verifiedKey(myUid, peerUid)] = verified }
    }

    fun isVerified(myUid: String, peerUid: String): Flow<Boolean> =
        context.contactSecurityDataStore.data.map { it[verifiedKey(myUid, peerUid)] ?: false }

    /** Every peer uid currently marked verified for [myUid], for list screens (Home, Discover)
     * that need a verified badge per row without a per-row DataStore subscription each. */
    fun verifiedPeerUids(myUid: String): Flow<Set<String>> {
        val prefix = "verified_${myUid}_"
        return context.contactSecurityDataStore.data.map { prefs ->
            prefs.asMap().entries
                .filter { (key, value) -> key.name.startsWith(prefix) && value == true }
                .map { it.key.name.removePrefix(prefix) }
                .toSet()
        }
    }
}
