package open.ghost.core.expiry

import java.time.Duration
import java.time.Instant

/**
 * Durée après lecture choisie par l'expéditeur (CDC §4.1).
 * Attachée au message à l'envoi ; ne change jamais rétroactivement (CDC §4.3).
 */
enum class ExpiryMode(val wireName: String, val afterRead: Duration?) {
    /** « À la lecture » : expire à la fermeture de la première consultation. */
    ON_READ("ONCE", null),

    /** Expire 15 minutes après la première lecture, plafonné à envoi + 24 h. */
    MINUTES_15("15_MIN", Duration.ofMinutes(15)),

    /** Expire 24 heures après la première lecture, plafonné à envoi + 24 h. */
    HOURS_24("24_H", Duration.ofHours(24));

    companion object {
        fun fromWireName(name: String): ExpiryMode =
            entries.firstOrNull { it.wireName == name }
                ?: throw IllegalArgumentException("Mode d'expiration inconnu : $name")
    }
}

/**
 * Horodatages pertinents d'un message, du point de vue d'un appareil destinataire.
 *
 * @property sentAt date d'envoi de référence (voir [SentAtNormalizer] : jamais la seule
 *   valeur déclarée par l'expéditeur).
 * @property firstReadAt première ouverture effective ; unique, ne se réinitialise jamais.
 * @property firstViewClosedAt fin de la première consultation (utile au mode [ExpiryMode.ON_READ]).
 */
data class MessageTimeline(
    val sentAt: Instant,
    val mode: ExpiryMode,
    val firstReadAt: Instant? = null,
    val firstViewClosedAt: Instant? = null,
) {
    init {
        if (firstViewClosedAt != null) {
            requireNotNull(firstReadAt) { "Une consultation ne peut se terminer sans avoir commencé." }
            require(!firstViewClosedAt.isBefore(firstReadAt)) {
                "La fin de consultation précède la première lecture."
            }
        }
    }
}

/**
 * Algorithme contractuel d'expiration (CDC §14.2).
 *
 * ```
 * A = sent_at + 24h
 * if first_read_at is absent:  expiry_at = A
 * elif mode == "ONCE":         expiry_at = min(A, first_view_closed_at)
 * elif mode == "15_MIN":       expiry_at = min(A, first_read_at + 15min)
 * elif mode == "24_H":         expiry_at = min(A, first_read_at + 24h)
 * ```
 *
 * Précision apportée au pseudo-code du CDC : en mode ONCE, tant que la première
 * consultation est en cours (lecture commencée, pas encore fermée), seule la limite
 * absolue A s'applique.
 */
object ExpiryPolicy {

    /** Plafond absolu non négociable : envoi + 24 h (CDC §00). */
    val ABSOLUTE_LIFETIME: Duration = Duration.ofHours(24)

    fun absoluteDeadline(sentAt: Instant): Instant = sentAt.plus(ABSOLUTE_LIFETIME)

    fun expiresAt(timeline: MessageTimeline): Instant {
        val absolute = absoluteDeadline(timeline.sentAt)
        val readAt = timeline.firstReadAt ?: return absolute
        // Une lecture enregistrée après la limite absolue ne peut rien prolonger.
        if (!readAt.isBefore(absolute)) return absolute

        val candidate: Instant? = when (timeline.mode) {
            ExpiryMode.ON_READ -> timeline.firstViewClosedAt
            ExpiryMode.MINUTES_15, ExpiryMode.HOURS_24 -> readAt.plus(timeline.mode.afterRead!!)
        }
        return if (candidate == null) absolute else minOf(absolute, candidate)
    }

    /** Vrai dès que `now >= expiry_at` : l'accès et le déchiffrement doivent être refusés. */
    fun isExpired(timeline: MessageTimeline, now: Instant): Boolean =
        !now.isBefore(expiresAt(timeline))

    /** Temps restant affichable (jamais négatif), calculé localement (CDC §11). */
    fun remaining(timeline: MessageTimeline, now: Instant): Duration {
        val left = Duration.between(now, expiresAt(timeline))
        return if (left.isNegative) Duration.ZERO else left
    }
}
