package open.ghost.core.expiry

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EdgeCasesTest {

    private val sent = Instant.parse("2026-11-09T08:00:00Z")

    @Test
    fun `premiere lecture idempotente, l'horodatage ne se reinitialise jamais`() {
        val first = MessageLifecycle(MessageTimeline(sent, ExpiryMode.MINUTES_15), MessageState.DELIVERED)
            .recordFirstRead(sent.plusSeconds(60))
        val second = first.recordFirstRead(sent.plusSeconds(120))
        assertEquals(sent.plusSeconds(60), second.timeline.firstReadAt)
    }

    @Test
    fun `mode a la lecture, reouverture apres interruption de la premiere vue vaut expiration`() {
        val opened = MessageLifecycle(MessageTimeline(sent, ExpiryMode.ON_READ), MessageState.DELIVERED)
            .recordFirstRead(sent.plusSeconds(60))
        // Processus tué : aucune fermeture enregistrée. Nouvelle ouverture :
        val reopened = opened.recordFirstRead(sent.plusSeconds(600))
        assertEquals(MessageState.EXPIRED, reopened.state)
        assertEquals(sent.plusSeconds(60), reopened.timeline.firstReadAt)
    }

    @Test
    fun `mode 24 h apres lecture toujours borne par la limite absolue`() {
        // first_read_at >= sent_at  =>  first_read_at + 24h >= sent_at + 24h : le plafond l'emporte toujours.
        listOf(0L, 1L, 3_600L, 86_399L).forEach { s ->
            val t = MessageTimeline(sent, ExpiryMode.HOURS_24, firstReadAt = sent.plusSeconds(s))
            assertEquals(ExpiryPolicy.absoluteDeadline(sent), ExpiryPolicy.expiresAt(t))
        }
    }

    @Test
    fun `livraison tardive, les rappels echus ne sont pas rejoues`() {
        val t = MessageTimeline(sent, ExpiryMode.ON_READ)
        val lateDelivery = sent.plus(Duration.ofHours(22))
        val reminders = ReminderPlanner.plan("ref", t, lateDelivery)
        assertEquals(listOf(ReminderThreshold.H1, ReminderThreshold.MIN15), reminders.map { it.threshold })
    }

    @Test
    fun `seuils desactives par l'utilisateur`() {
        val t = MessageTimeline(sent, ExpiryMode.ON_READ)
        assertTrue(ReminderPlanner.plan("ref", t, sent, enabled = emptySet()).isEmpty())
    }

    @Test
    fun `date d'envoi declaree dans le futur bornee par l'horodatage du relais`() {
        val claimed = sent.plus(Duration.ofHours(6))
        assertEquals(sent, SentAtNormalizer.effectiveSentAt(claimed, receivedByRelay = sent))
        assertEquals(sent.minusSeconds(30), SentAtNormalizer.effectiveSentAt(sent.minusSeconds(30), sent))
    }

    @Test
    fun `lecture enregistree apres la limite absolue sans effet`() {
        val t = MessageTimeline(sent, ExpiryMode.HOURS_24, firstReadAt = sent.plus(Duration.ofHours(25)))
        assertEquals(sent.plus(Duration.ofHours(24)), ExpiryPolicy.expiresAt(t))
    }

    @Test
    fun `fin de consultation anterieure a la lecture rejetee`() {
        assertFailsWith<IllegalArgumentException> {
            MessageTimeline(sent, ExpiryMode.ON_READ, firstReadAt = sent.plusSeconds(10), firstViewClosedAt = sent)
        }
    }

    @Test
    fun `transition interdite depuis un etat terminal`() {
        val purged = MessageLifecycle(MessageTimeline(sent, ExpiryMode.ON_READ), MessageState.PURGED)
        assertFailsWith<IllegalTransitionException> { purged.transitionTo(MessageState.DELIVERED) }
    }

    @Test
    fun `temps restant jamais negatif`() {
        val t = MessageTimeline(sent, ExpiryMode.ON_READ)
        assertEquals(Duration.ZERO, ExpiryPolicy.remaining(t, sent.plus(Duration.ofDays(3))))
        assertEquals(Duration.ofHours(24), ExpiryPolicy.remaining(t, sent))
    }

    @Test
    fun `nom filaire des modes conforme au pseudo-code du CDC`() {
        assertEquals(ExpiryMode.ON_READ, ExpiryMode.fromWireName("ONCE"))
        assertEquals(ExpiryMode.MINUTES_15, ExpiryMode.fromWireName("15_MIN"))
        assertEquals(ExpiryMode.HOURS_24, ExpiryMode.fromWireName("24_H"))
    }
}
