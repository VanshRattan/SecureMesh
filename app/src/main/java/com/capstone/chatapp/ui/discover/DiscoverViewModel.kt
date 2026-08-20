package com.capstone.chatapp.ui.discover

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.capstone.chatapp.data.model.User
import com.capstone.chatapp.di.appContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One person shown in the Nearby tab, discovered over the BLE mesh. */
data class NearbyEntry(val uid: String, val name: String)

data class DiscoverUiState(
    val query: String = "",
    val users: List<User> = emptyList(),
    val loadingUsers: Boolean = true,
    val nearby: List<NearbyEntry> = emptyList(),
    val neighborCount: Int = 0,
    val meshRunning: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * Backs the Discover screen: the online directory of all users (Firestore) and the
 * offline list of people physically nearby over the BLE mesh.
 */
class DiscoverViewModel(app: Application) : AndroidViewModel(app) {

    private val container = app.appContainer()
    private val mesh = container.bleMeshManager
    private val myUid = container.authRepository.currentUid.orEmpty()

    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    init {
        loadUsers()
        observeNearby()
    }

    fun onQueryChange(value: String) = _state.update { it.copy(query = value) }
    fun consumeError() = _state.update { it.copy(errorMessage = null) }

    /** The directory filtered by the current search text (name or email). */
    fun filteredUsers(): List<User> {
        val q = _state.value.query.trim().lowercase()
        val all = _state.value.users
        if (q.isEmpty()) return all
        return all.filter { it.displayName.lowercase().contains(q) || it.email.lowercase().contains(q) }
    }

    fun loadUsers() {
        _state.update { it.copy(loadingUsers = true) }
        viewModelScope.launch {
            runCatching { container.userRepository.listUsers(myUid) }
                .onSuccess { users -> _state.update { it.copy(users = users, loadingUsers = false) } }
                .onFailure { e -> _state.update { it.copy(loadingUsers = false, errorMessage = e.message ?: "Could not load users") } }
        }
    }

    private fun observeNearby() {
        viewModelScope.launch {
            mesh.nearbyPeers.collect { peers ->
                _state.update { s ->
                    s.copy(nearby = peers.map { NearbyEntry(it.uid, it.name) })
                }
            }
        }
        viewModelScope.launch {
            mesh.status.collect { status ->
                _state.update { it.copy(meshRunning = status.running, neighborCount = status.neighborCount) }
            }
        }
    }
}
