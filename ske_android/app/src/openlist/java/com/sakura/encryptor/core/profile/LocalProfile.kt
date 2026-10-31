package com.sakura.encryptor.core.profile

import com.sakura.encryptor.data.db.ProfileEntity
import kotlinx.coroutines.flow.first

/**
 * Profile row that fronts the embedded OpenList server.
 *
 * Matched by the rememberd row first and by address otherwise, so a port change
 * updates the existing row instead of leaving a stale one behind. The account
 * password is always known, which is why only it is stored here. Lives in the
 * `openlist` flavor because the plain package never creates such a profile.
 */
suspend fun ProfileRepository.ensureLocalProfile(
    profileId: Long?,
    server: String,
    username: String,
    sealedPassword: String,
): Long {
    // The remembered row wins over a changed port: editing the port in settings
    // must update this profile instead of leaving a second one behind.
    val known = profileId?.let { dao.find(it) }
    val existing = known ?: dao.all().firstOrNull { it.server == server }
    val entity = (existing ?: ProfileEntity(name = "内置服务器")).copy(
        server = server,
        username = username,
        sealedAlistPassword = sealedPassword,
    )
    val id = dao.upsert(entity)
    return if (id > 0) id else entity.id
}

/** The profile pointing at [server], if the user still has one. */
suspend fun ProfileRepository.findLocalProfile(server: String): ProfileEntity? =
    dao.all().firstOrNull { it.server == server }
