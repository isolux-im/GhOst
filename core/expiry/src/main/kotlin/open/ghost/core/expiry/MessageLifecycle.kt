package open.ghost.core.expiry

import java.time.Instant

/**
 * États d'un message (CDC §14.1) :
 * CRÉÉ → CHIFFRÉ → ENVOYÉ → (EN_ATTENTE / LIVRÉ) → OUVERT (facultatif) → EXPIRÉ → PURGÉ.
 * EXPIRÉ et PURGÉ sont terminaux : aucune réapparition par synchronisation ou restauration.
 */
enum class MessageState {
    CREATED, ENCRYPTED, SENT, PENDING, DELIVERED, OPENED, EXPIRED, PURGED;

    val isTerminal: Boolean get() = this == EXPIRED || this == PURGED
}

class IllegalTransitionException(from: MessageState, to: MessageState) :
    IllegalStateException("Transition interdite : $from → $to")

/**
 * Cycle de vie d'un message côté appareil. Immuable : chaque opération renvoie une nouvelle
 * instance, ce qui rend les opérations concurrentes lecture / suppression idempotentes (CDC §4.3).
 */
data class MessageLifecycle(
    val timeline: MessageTimeline,
    val state: MessageState = MessageState.CREATED,
) {

    fun transitionTo(target: MessageState): MessageLifecycle {
        if (target == state) return this
        if (!isAllowed(state, target)) throw IllegalTransitionException(state, target)
        return copy(state = target)
    }

    /**
     * Enregistre une ouverture. L'horodatage de première lecture ne se réinitialise jamais.
     *
     * Mode « À la lecture » : une nouvelle ouverture alors que la première consultation n'a
     * jamais été déclarée close (processus tué, redémarrage…) vaut clôture de celle-ci, et le
     * message expire. Interprétation prudente d'un cas non traité par le CDC §14.2.
     */
    fun recordFirstRead(now: Instant): MessageLifecycle {
        val current = refresh(now)
        if (current.state.isTerminal) return current
        if (current.timeline.firstReadAt != null) {
            return if (current.timeline.mode == ExpiryMode.ON_READ) current.recordFirstViewClosed(now)
            else current
        }
        check(current.state == MessageState.DELIVERED) { "Lecture impossible dans l'état ${current.state}" }
        return current.copy(
            timeline = current.timeline.copy(firstReadAt = now),
            state = MessageState.OPENED,
        )
    }

    /** Fin de la première consultation : déclenche l'expiration en mode « À la lecture ». */
    fun recordFirstViewClosed(now: Instant): MessageLifecycle {
        val current = refresh(now)
        if (current.state.isTerminal) return current
        val t = current.timeline
        if (t.firstReadAt == null || t.firstViewClosedAt != null) return current
        return current.copy(timeline = t.copy(firstViewClosedAt = maxOf(now, t.firstReadAt))).refresh(now)
    }

    /** Applique l'échéance : passe en EXPIRÉ si `now >= expiry_at`. */
    fun refresh(now: Instant): MessageLifecycle =
        if (!state.isTerminal && ExpiryPolicy.isExpired(timeline, now)) copy(state = MessageState.EXPIRED)
        else this

    /** Le contenu ne peut être affiché / déchiffré que si cette fonction renvoie vrai. */
    fun canDisplay(now: Instant): Boolean {
        val current = refresh(now)
        return current.state == MessageState.DELIVERED || current.state == MessageState.OPENED
    }

    companion object {
        private val ALLOWED: Map<MessageState, Set<MessageState>> = mapOf(
            MessageState.CREATED to setOf(MessageState.ENCRYPTED, MessageState.EXPIRED),
            MessageState.ENCRYPTED to setOf(MessageState.SENT, MessageState.EXPIRED),
            MessageState.SENT to setOf(MessageState.PENDING, MessageState.DELIVERED, MessageState.EXPIRED),
            MessageState.PENDING to setOf(MessageState.DELIVERED, MessageState.EXPIRED),
            MessageState.DELIVERED to setOf(MessageState.OPENED, MessageState.EXPIRED),
            MessageState.OPENED to setOf(MessageState.EXPIRED),
            MessageState.EXPIRED to setOf(MessageState.PURGED),
            MessageState.PURGED to emptySet(),
        )

        fun isAllowed(from: MessageState, to: MessageState): Boolean =
            ALLOWED.getValue(from).contains(to)
    }
}
