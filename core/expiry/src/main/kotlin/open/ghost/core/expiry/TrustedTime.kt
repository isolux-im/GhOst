package open.ghost.core.expiry

import java.time.Duration
import java.time.Instant

/**
 * Détermine la date d'envoi de référence. La valeur déclarée par l'expéditeur n'est pas fiable
 * (horloge modifiée → message prolongé). On retient donc la plus ancienne des deux valeurs
 * disponibles : déclarée et horodatage de réception par le relais. Une erreur raccourcit la vie
 * du message, jamais ne la prolonge (« les erreurs favorisent la non-divulgation », CDC §14.3).
 */
object SentAtNormalizer {
    fun effectiveSentAt(claimedBySender: Instant, receivedByRelay: Instant?): Instant =
        if (receivedByRelay == null) claimedBySender else minOf(claimedBySender, receivedByRelay)
}

/** Source d'heure murale (horloge système, modifiable par l'utilisateur). */
fun interface WallClock {
    fun now(): Instant
}

/** Source monotone (sur Android : `SystemClock.elapsedRealtime()`), remise à zéro au redémarrage. */
fun interface MonotonicClock {
    fun elapsed(): Duration
}

/**
 * Horloge de confiance locale (CDC §14.3, test T09).
 *
 * L'heure retenue est le maximum de :
 *  - l'heure système ;
 *  - l'heure serveur la plus récente, prolongée par l'horloge monotone ;
 *  - le plus haut instant déjà observé (« high watermark », à persister).
 *
 * Reculer l'horloge système ne peut donc jamais rendre un message expiré à nouveau lisible ;
 * l'avancer ne fait qu'expirer les messages plus tôt (sens de la non-divulgation).
 */
class TrustedClock(
    private val wall: WallClock,
    private val monotonic: MonotonicClock,
    persistedHighWatermark: Instant? = null,
) {
    private var anchorServerTime: Instant? = null
    private var anchorElapsed: Duration? = null
    private var highWatermark: Instant? = persistedHighWatermark

    /** Enregistre une heure serveur authentifiée (ex. réponse signée du service de signalisation). */
    @Synchronized
    fun anchor(serverTime: Instant) {
        anchorServerTime = serverTime
        anchorElapsed = monotonic.elapsed()
    }

    /** À appeler après un redémarrage : l'horloge monotone repart de zéro, l'ancre est invalide. */
    @Synchronized
    fun invalidateAnchor() {
        anchorServerTime = null
        anchorElapsed = null
    }

    @Synchronized
    fun now(): Instant {
        var result = wall.now()
        val server = anchorServerTime
        val base = anchorElapsed
        if (server != null && base != null) {
            val delta = monotonic.elapsed().minus(base)
            if (!delta.isNegative) result = maxOf(result, server.plus(delta)) else invalidateAnchor()
        }
        highWatermark?.let { result = maxOf(result, it) }
        highWatermark = result
        return result
    }

    /** Valeur à persister pour survivre aux redémarrages. */
    @Synchronized
    fun highWatermark(): Instant? = highWatermark
}
