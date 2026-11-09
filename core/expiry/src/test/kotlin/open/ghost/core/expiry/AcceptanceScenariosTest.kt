package open.ghost.core.expiry

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Scénarios de recette du CDC §18, pour la partie couverte par la logique de domaine.
 * Référence : envoi le jour J à 08:00 UTC.
 */
class AcceptanceScenariosTest {

    private val day = "2026-11-09"
    private val nextDay = "2026-11-10"
    private fun at(date: String, hhmm: String): Instant = Instant.parse("${date}T$hhmm:00Z")
    private val sent = at(day, "08:00")

    private fun delivered(mode: ExpiryMode) =
        MessageLifecycle(MessageTimeline(sent, mode), MessageState.DELIVERED)

    @Test
    fun `T01 - non lu, inaccessible a J+1 08h00`() {
        val msg = delivered(ExpiryMode.MINUTES_15)
        assertEquals(at(nextDay, "08:00"), ExpiryPolicy.expiresAt(msg.timeline))
        assertTrue(msg.canDisplay(at(nextDay, "07:59")))
        assertFalse(msg.canDisplay(at(nextDay, "08:00")))
    }

    @Test
    fun `T02 - lecture 10h00 en mode 15 min, inaccessible a 10h15`() {
        val msg = delivered(ExpiryMode.MINUTES_15).recordFirstRead(at(day, "10:00"))
        assertEquals(at(day, "10:15"), ExpiryPolicy.expiresAt(msg.timeline))
        assertTrue(msg.canDisplay(at(day, "10:14")))
        assertFalse(msg.canDisplay(at(day, "10:15")))
    }

    @Test
    fun `T03 - lecture J+1 07h55 en mode 15 min, inaccessible a 08h00 et non 08h10`() {
        val msg = delivered(ExpiryMode.MINUTES_15).recordFirstRead(at(nextDay, "07:55"))
        assertEquals(at(nextDay, "08:00"), ExpiryPolicy.expiresAt(msg.timeline))
        assertFalse(msg.canDisplay(at(nextDay, "08:00")))
    }

    @Test
    fun `T04 - lecture J+1 07h00 en mode 24 h, inaccessible a J+1 08h00`() {
        val msg = delivered(ExpiryMode.HOURS_24).recordFirstRead(at(nextDay, "07:00"))
        assertEquals(at(nextDay, "08:00"), ExpiryPolicy.expiresAt(msg.timeline))
        assertFalse(msg.canDisplay(at(nextDay, "08:00")))
    }

    @Test
    fun `T05 - mode a la lecture, impossible de rouvrir apres fermeture de la premiere vue`() {
        val opened = delivered(ExpiryMode.ON_READ).recordFirstRead(at(day, "10:00"))
        // Pendant la consultation, seule la limite absolue s'applique.
        assertTrue(opened.canDisplay(at(day, "10:30")))

        val closed = opened.recordFirstViewClosed(at(day, "10:31"))
        assertEquals(MessageState.EXPIRED, closed.state)
        assertFalse(closed.canDisplay(at(day, "10:31")))
        // Une nouvelle « lecture » ne rouvre rien.
        val retried = closed.recordFirstRead(at(day, "10:32"))
        assertEquals(MessageState.EXPIRED, retried.state)
        assertFalse(retried.canDisplay(at(day, "10:32")))
    }

    @Test
    fun `T06 - non lu, rappels a T+12h, 21h, 23h et 23h45 sans nom ni contenu`() {
        val reminders = ReminderPlanner.plan("ref-1", MessageTimeline(sent, ExpiryMode.ON_READ), now = sent)
        assertEquals(
            listOf(
                sent.plus(Duration.ofHours(12)),
                sent.plus(Duration.ofHours(21)),
                sent.plus(Duration.ofHours(23)),
                sent.plus(Duration.ofHours(23)).plus(Duration.ofMinutes(45)),
            ),
            reminders.map { it.fireAt },
        )
        assertEquals(
            listOf(
                "Vous avez un message non lu qui expirera dans 12 h.",
                "Un message non lu expire dans 3 h.",
                "Un message non lu expire dans 1 h.",
                "Dernier rappel : un message expire dans 15 min.",
            ),
            reminders.map { it.threshold.neutralText },
        )
    }

    @Test
    fun `T07 - lecture apres premier rappel, rappels futurs annules`() {
        val read = MessageTimeline(sent, ExpiryMode.HOURS_24, firstReadAt = sent.plus(Duration.ofHours(13)))
        assertTrue(ReminderPlanner.plan("ref-1", read, now = sent.plus(Duration.ofHours(13))).isEmpty())
    }

    @Test
    fun `T08 - appareil hors ligne pendant l'expiration, aucun affichage a la reprise`() {
        val msg = delivered(ExpiryMode.HOURS_24)
        val resumed = msg.refresh(at(nextDay, "09:00"))
        assertEquals(MessageState.EXPIRED, resumed.state)
        assertFalse(resumed.canDisplay(at(nextDay, "09:00")))
        assertEquals(MessageState.PURGED, resumed.transitionTo(MessageState.PURGED).state)
    }

    @Test
    fun `T09 - horloge systeme reculee, aucune prolongation`() {
        var wall = at(nextDay, "08:30")
        var elapsed = Duration.ofHours(5)
        val clock = TrustedClock(wall = { wall }, monotonic = { elapsed })
        clock.anchor(at(nextDay, "08:30"))

        val msg = delivered(ExpiryMode.MINUTES_15)
        assertFalse(msg.canDisplay(clock.now()))

        // L'utilisateur recule l'horloge système de deux heures.
        wall = at(nextDay, "06:30")
        elapsed = elapsed.plusMinutes(1)
        assertFalse(msg.canDisplay(clock.now()))

        // Après redémarrage (ancre perdue), le high watermark persisté reste opposable.
        val rebooted = TrustedClock(wall = { wall }, monotonic = { Duration.ZERO }, clock.highWatermark())
        assertFalse(msg.canDisplay(rebooted.now()))
    }

    @Test
    fun `T10 - redemarrage ou restauration apres expiration, aucun contenu ne redevient accessible`() {
        val expired = delivered(ExpiryMode.MINUTES_15).refresh(at(nextDay, "08:00"))
        for (target in MessageState.entries.filterNot { it.isTerminal }) {
            assertFalse(MessageLifecycle.isAllowed(MessageState.EXPIRED, target), "EXPIRÉ → $target")
            assertFalse(MessageLifecycle.isAllowed(MessageState.PURGED, target), "PURGÉ → $target")
        }
        assertFalse(expired.canDisplay(at(day, "09:00")), "Un état terminal ne dépend plus de l'heure")
    }

    @Test
    fun `T11 - piece jointe, meme politique que le message porteur`() {
        // La pièce jointe partage la MessageTimeline du message : même échéance par construction.
        val timeline = MessageTimeline(sent, ExpiryMode.MINUTES_15, firstReadAt = at(day, "10:00"))
        assertEquals(at(day, "10:15"), ExpiryPolicy.expiresAt(timeline))
    }

    @Test
    fun `T12 - notification groupee sans pseudo, expediteur ni apercu`() {
        val t = MessageTimeline(sent, ExpiryMode.ON_READ)
        val reminders = ReminderPlanner.plan("a", t, sent) + ReminderPlanner.plan("b", t, sent)
        val notifications = ReminderGrouper.group(reminders, discreetMode = true)
        assertEquals(4, notifications.size)
        notifications.forEach {
            assertEquals("Des messages non lus vont bientôt expirer.", it.text)
            assertFalse(it.text.any(Char::isDigit), "Le mode discret n'expose pas le nombre de messages")
        }
    }

    @Test
    fun `T13 - expediteur bloque, aucun rappel`() {
        val t = MessageTimeline(sent, ExpiryMode.ON_READ)
        assertTrue(ReminderPlanner.plan("ref", t, sent, senderBlocked = true).isEmpty())
    }

    @Test
    fun `T14 - destinataire hors ligne 24 h, enveloppe relayee supprimee et non livree`() {
        val envelope = RelayEnvelope("opaque-1", "device-B", byteArrayOf(1, 2, 3), sent)
        assertTrue(RelayRetention.canDeliver(envelope, at(nextDay, "07:59")))
        assertFalse(RelayRetention.canDeliver(envelope, at(nextDay, "08:00")))
        assertEquals(listOf(envelope), RelayRetention.purgeable(listOf(envelope), at(nextDay, "08:00")))
    }
}
