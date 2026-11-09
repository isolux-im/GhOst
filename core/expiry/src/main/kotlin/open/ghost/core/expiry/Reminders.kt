package open.ghost.core.expiry

import java.time.Duration
import java.time.Instant

/** Seuils de rappel avant la limite absolue (CDC §5.1). Tous actifs par défaut. */
enum class ReminderThreshold(val beforeDeadline: Duration, val neutralText: String) {
    H12(Duration.ofHours(12), "Vous avez un message non lu qui expirera dans 12 h."),
    H3(Duration.ofHours(3), "Un message non lu expire dans 3 h."),
    H1(Duration.ofHours(1), "Un message non lu expire dans 1 h."),
    MIN15(Duration.ofMinutes(15), "Dernier rappel : un message expire dans 15 min."),
}

/** Rappel planifié. Ne contient aucun nom, pseudo, aperçu ni pièce jointe (CDC §5.1, T12). */
data class Reminder(
    val messageRef: String,
    val threshold: ReminderThreshold,
    val fireAt: Instant,
)

/**
 * Planification des rappels de messages non lus (CDC §5.2).
 *  - uniquement pour un message non lu, non expiré, d'un expéditeur non bloqué ;
 *  - les seuils déjà échus (livraison tardive) ne sont pas rejoués ;
 *  - la première lecture, l'expiration ou le blocage annulent les rappels restants.
 *
 * [messageRef] doit être une référence locale opaque, jamais un identifiant lisible.
 */
object ReminderPlanner {

    fun plan(
        messageRef: String,
        timeline: MessageTimeline,
        now: Instant,
        enabled: Set<ReminderThreshold> = ReminderThreshold.entries.toSet(),
        senderBlocked: Boolean = false,
    ): List<Reminder> {
        if (senderBlocked || timeline.firstReadAt != null) return emptyList()
        if (ExpiryPolicy.isExpired(timeline, now)) return emptyList()
        val deadline = ExpiryPolicy.absoluteDeadline(timeline.sentAt)
        return ReminderThreshold.entries
            .filter { it in enabled }
            .map { Reminder(messageRef, it, deadline.minus(it.beforeDeadline)) }
            .filter { it.fireAt.isAfter(now) }
            .sortedBy { it.fireAt }
    }
}

/** Notification regroupée réellement affichée. */
data class GroupedNotification(
    val fireAt: Instant,
    val text: String,
    val messageRefs: List<String>,
)

/**
 * Regroupe les rappels proches pour limiter les réveils et notifications (CDC §5.2, §16 Batterie).
 * En mode discret, le texte n'expose pas le nombre exact de messages.
 */
object ReminderGrouper {

    fun group(
        reminders: List<Reminder>,
        window: Duration = Duration.ofMinutes(5),
        discreetMode: Boolean = true,
    ): List<GroupedNotification> {
        val sorted = reminders.sortedBy { it.fireAt }
        val groups = mutableListOf<MutableList<Reminder>>()
        for (r in sorted) {
            val current = groups.lastOrNull()
            if (current != null && Duration.between(current.first().fireAt, r.fireAt) <= window) {
                current += r
            } else {
                groups += mutableListOf(r)
            }
        }
        return groups.map { g ->
            GroupedNotification(
                // Déclenchement au plus tôt du groupe : un rappel n'est jamais retardé.
                fireAt = g.first().fireAt,
                text = textFor(g, discreetMode),
                messageRefs = g.map { it.messageRef }.distinct(),
            )
        }
    }

    private fun textFor(group: List<Reminder>, discreetMode: Boolean): String {
        val distinct = group.map { it.messageRef }.distinct()
        if (distinct.size == 1) return group.first().threshold.neutralText
        return if (discreetMode) "Des messages non lus vont bientôt expirer."
        else "${distinct.size} messages non lus vont bientôt expirer."
    }
}
