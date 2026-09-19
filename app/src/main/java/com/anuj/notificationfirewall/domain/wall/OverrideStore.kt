package com.anuj.notificationfirewall.domain.wall

import com.anuj.notificationfirewall.data.db.OverrideEntity
import com.anuj.notificationfirewall.data.db.dao.OverrideDao
import kotlinx.coroutines.flow.Flow

/**
 * The user's hard decisions: senders that always ring, and senders that never
 * appear at all. These are the only things in the pipeline that can overrule a
 * Jev judgement, and the user sets every one of them deliberately.
 */
class OverrideStore(private val dao: OverrideDao) {

    /**
     * The override governing this notification, or null.
     *
     * A sender-scoped entry beats an app-wide one, so blocking a whole app while
     * keeping one person from it is expressible — the narrower rule is the more
     * specific statement of intent. VIP beats BLOCK at equal specificity, because
     * the cost of wrongly silencing beats the cost of wrongly ringing.
     */
    suspend fun kindFor(pkg: String, sender: String?): OverrideKind? {
        val matches = dao.matching(pkg, sender)
        if (matches.isEmpty()) return null

        val senderScoped = matches.filter { it.senderKey != null }
        val pool = senderScoped.ifEmpty { matches }
        return if (pool.any { it.kind == OverrideKind.VIP }) OverrideKind.VIP else OverrideKind.BLOCK
    }

    suspend fun add(
        kind: OverrideKind,
        pkg: String,
        sender: String?,
        label: String,
        source: OverrideSource,
    ): Long = dao.insert(
        OverrideEntity(
            kind = kind,
            packageName = pkg,
            senderKey = sender,
            label = label,
            source = source,
            createdAtEpochMs = System.currentTimeMillis(),
        ),
    )

    fun observeAll(): Flow<List<OverrideEntity>> = dao.observeAll()

    suspend fun remove(entry: OverrideEntity) = dao.delete(entry)
}
