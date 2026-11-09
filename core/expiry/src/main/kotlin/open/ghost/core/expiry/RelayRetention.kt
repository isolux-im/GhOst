package open.ghost.core.expiry

import java.time.Instant

/**
 * Enveloppe chiffrée conservée par le relais pour un destinataire hors ligne (CDC §13).
 * Le relais ne voit que des données opaques et l'échéance absolue.
 */
data class RelayEnvelope(
    val opaqueMsgId: String,
    val destDevice: String,
    val ciphertext: ByteArray,
    val sentAt: Instant,
) {
    val absoluteExpiresAt: Instant get() = ExpiryPolicy.absoluteDeadline(sentAt)

    override fun equals(other: Any?): Boolean =
        other is RelayEnvelope && other.opaqueMsgId == opaqueMsgId && other.destDevice == destDevice

    override fun hashCode(): Int = 31 * opaqueMsgId.hashCode() + destDevice.hashCode()
}

/** Règles de rétention côté relais (CDC §13, §19, test T14). */
object RelayRetention {

    /** Livraison autorisée uniquement avant l'échéance absolue. */
    fun canDeliver(envelope: RelayEnvelope, now: Instant): Boolean =
        now.isBefore(envelope.absoluteExpiresAt)

    /** Enveloppes à détruire. À appliquer aussi après toute restauration de base. */
    fun purgeable(envelopes: Collection<RelayEnvelope>, now: Instant): List<RelayEnvelope> =
        envelopes.filterNot { canDeliver(it, now) }
}
