package com.sakura.encryptor.core.profile

import com.sakura.encryptor.core.remote.RemoteProvider
import com.sakura.encryptor.core.remote.RemoteRepository
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
 * A profile bundles a cloud account (AList or OpenList) with its own encryption
 * password. Both secrets are stored only as Keystore-sealed blobs, and switching
 * a profile swaps the active [VaultSession] password and the backend session
 * together. The backend itself is chosen by [RemoteRepository], so nothing here
 * has to know which API is in use.
 */
class ProfileRepository(
    internal val dao: ProfileDao,
    private val settings: SettingsStore,
    private val keyStore: KeyStoreManager,
    private val cloudVault: VaultSession,
    private val remoteRepository: RemoteRepository,
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
        provider: RemoteProvider,
        alistPassword: String?,
        masterPassword: String?,
    ): Long {
        val existing = id?.let { dao.find(it) }

        val entity = (existing ?: ProfileEntity(name = name)).copy(
            name = name.trim().ifBlank { server.trim().ifBlank { "未命名配置" } },
            server = server.trim().trimEnd('/'),
            username = username.trim(),
            basePath = basePath.trim(),
            provider = provider.storageKey,
            sealedAlistPassword = alistPassword?.let { keyStore.seal(it) }
                ?: existing?.sealedAlistPassword,
            sealedMasterPassword = masterPassword?.let { keyStore.seal(it) }
                ?: existing?.sealedMasterPassword,
        )

        val newId = dao.upsert(entity)
        return if (newId > 0) newId else entity.id
    }

    /**
     * Forget a profile. If it was active, the vault is locked and the cloud
     * session dropped so nothing stale keeps running.
     */
    suspend fun delete(id: Long) {
        dao.delete(id)
        if (settings.activeProfileId.first() == id) {
            settings.setActiveProfileId(null)
            cloudVault.lock()
            remoteRepository.clear()
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
        remoteRepository.clear()

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
                    remoteRepository.login(
                        provider = profile.remoteProvider,
                        server = profile.server,
                        username = profile.username,
                        password = password,
                        manualBasePath = profile.basePath,
                    ).getOrThrow()
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

    /**
     * Connect the active profile's cloud account using its stored password.
     *
     * OpenList and AList accounts with two-factor login fail with an exception
     * carrying `needsOtp = true`; the caller then collects the one-time code
     * and retries through [otpCode].
     */
    suspend fun connectActive(otpCode: String = ""): Result<Unit> {
        val profile = activeProfile()
            ?: return Result.failure(IllegalStateException("还没有选择云端配置"))
        if (!profile.hasServer) {
            return Result.failure(IllegalStateException("该配置没有填写服务器地址"))
        }
        val password = profile.sealedAlistPassword?.let { keyStore.open(it) }
            ?: return Result.failure(IllegalStateException("请先在配置中填写登录密码"))

        return remoteRepository.login(
            provider = profile.remoteProvider,
            server = profile.server,
            username = profile.username,
            password = password,
            manualBasePath = profile.basePath,
            otpCode = otpCode,
        )
    }

    /** Drop the active session without deleting the profile. */
    fun disconnect() {
        remoteRepository.clear()
    }

    /** Drop all in-memory secrets. */
    fun lockAll() {
        cloudVault.lock()
        remoteRepository.clear()
    }
}
