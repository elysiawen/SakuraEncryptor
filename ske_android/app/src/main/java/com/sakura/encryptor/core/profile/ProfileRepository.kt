package com.sakura.encryptor.core.profile

import com.sakura.encryptor.core.alist.AListRepository
import com.sakura.encryptor.core.alist.AListSession
import com.sakura.encryptor.core.security.KeyStoreManager
import com.sakura.encryptor.core.session.VaultSession
import com.sakura.encryptor.data.db.ProfileDao
import com.sakura.encryptor.data.db.ProfileEntity
import com.sakura.encryptor.data.prefs.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Manages cloud profiles and the switch between them.
 *
 * A profile bundles an AList account with its own encryption password. Both
 * secrets are stored only as Keystore-sealed blobs, and switching a profile
 * swaps the active [VaultSession] password and the AList session together.
 */
class ProfileRepository(
    private val dao: ProfileDao,
    private val settings: SettingsStore,
    private val keyStore: KeyStoreManager,
    private val cloudVault: VaultSession,
    private val aListSession: AListSession,
    private val aListRepository: AListRepository,
) {

    fun observeProfiles(): Flow<List<ProfileEntity>> = dao.observeAll()

    val activeProfileId: Flow<Long?> = settings.activeProfileId

    suspend fun find(id: Long): ProfileEntity? = dao.find(id)

    suspend fun activeProfile(): ProfileEntity? =
        settings.activeProfileId.first()?.let { dao.find(it) }

    /** Create or update a profile. Null passwords leave the stored ones untouched. */
    suspend fun save(
        id: Long?,
        name: String,
        server: String,
        username: String,
        basePath: String,
        alistPassword: String?,
        masterPassword: String?,
    ): Long {
        val existing = id?.let { dao.find(it) }

        val entity = (existing ?: ProfileEntity(name = name)).copy(
            name = name.trim().ifBlank { server.trim().ifBlank { "未命名配置" } },
            server = server.trim().trimEnd('/'),
            username = username.trim(),
            basePath = basePath.trim(),
            sealedAlistPassword = alistPassword?.let { keyStore.seal(it) }
                ?: existing?.sealedAlistPassword,
            sealedMasterPassword = masterPassword?.let { keyStore.seal(it) }
                ?: existing?.sealedMasterPassword,
        )

        val newId = dao.upsert(entity)
        return if (newId > 0) newId else entity.id
    }

    /**
     * Forget a profile. If it was active, the vault is locked and the AList
     * session dropped so nothing stale keeps running.
     */
    suspend fun delete(id: Long) {
        dao.delete(id)
        if (settings.activeProfileId.first() == id) {
            settings.setActiveProfileId(null)
            cloudVault.lock()
            aListSession.clear()
        }
    }

    /**
     * Make [id] the active profile, restoring stored secrets when available.
     *
     * @return true when the encryption password could be restored (vault unlocked).
     */
    suspend fun activateStored(id: Long): Boolean {
        val profile = dao.find(id) ?: return false

        settings.setActiveProfileId(id)
        dao.touch(id, System.currentTimeMillis())
        aListSession.clear()

        val master = profile.sealedMasterPassword?.let { keyStore.open(it) }
        if (!master.isNullOrEmpty()) {
            cloudVault.unlock(master)
        } else {
            cloudVault.lock()
        }

        // Auto-connect the cloud account when both secrets are available.
        if (profile.canAutoLogin && !master.isNullOrEmpty()) {
            runCatching {
                val password = keyStore.open(profile.sealedAlistPassword!!)
                if (!password.isNullOrEmpty()) {
                    aListRepository.login(profile.server, profile.username, password, profile.basePath)
                }
            }
        }

        return !master.isNullOrEmpty()
    }

    /** Unlock the active profile, optionally remembering the typed password. */
    suspend fun unlockActive(password: String, remember: Boolean) {
        cloudVault.unlock(password)
        val profile = activeProfile() ?: return
        dao.upsert(
            profile.copy(
                sealedMasterPassword = if (remember) keyStore.seal(password) else null
            )
        )
    }

    /** Connect the active profile's AList account using its stored password. */
    suspend fun connectActive(): Result<Unit> {
        val profile = activeProfile()
            ?: return Result.failure(IllegalStateException("还没有选择云端配置"))
        if (!profile.hasServer) {
            return Result.failure(IllegalStateException("该配置没有填写服务器地址"))
        }
        val password = profile.sealedAlistPassword?.let { keyStore.open(it) }
            ?: return Result.failure(IllegalStateException("请先在配置中填写 AList 密码"))

        return aListRepository.login(profile.server, profile.username, password, profile.basePath)
    }

    /** Drop the active session without deleting the profile. */
    fun disconnect() {
        aListSession.clear()
    }

    /** Drop all in-memory secrets. */
    fun lockAll() {
        cloudVault.lock()
        aListSession.clear()
    }
}
