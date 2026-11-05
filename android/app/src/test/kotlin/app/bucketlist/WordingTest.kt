package app.bucketlist

import bucketlist.core.ApiException
import bucketlist.core.DecryptionException
import java.io.IOException
import java.net.ConnectException
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WordingTest {
    @Test
    fun aRejectedPasskeyAndAnExpiredSessionReadDifferently() {
        assertEquals("Wrong passkey", describeFailure(ApiException(401, "unauthorized"), Stage.UNLOCK))
        assertEquals("Signed out by the server. Unlock again", describeFailure(ApiException(401, "unauthorized"), Stage.CHANGE))
    }

    @Test
    fun onlyAnExpiredSessionDuringAChangeSignsOut() {
        assertTrue(signsOut(ApiException(401, "unauthorized"), Stage.CHANGE))
        assertFalse(signsOut(ApiException(401, "unauthorized"), Stage.UNLOCK))
        assertFalse(signsOut(ApiException(500, "boom"), Stage.CHANGE))
        assertFalse(signsOut(ConnectException("refused"), Stage.CHANGE))
    }

    @Test
    fun aMissingAccountAndAMissingItemReadDifferently() {
        assertEquals("No list on this server yet. Use first-time setup", describeFailure(ApiException(404, "not found"), Stage.UNLOCK))
        assertEquals("That item was removed on another phone", describeFailure(ApiException(404, "not found"), Stage.CHANGE))
    }

    @Test
    fun setupAndRateLimitFailuresAreExplained() {
        assertEquals("Setup token rejected", describeFailure(ApiException(403, "forbidden"), Stage.UNLOCK))
        assertEquals("A list already exists on this server", describeFailure(ApiException(409, "exists"), Stage.UNLOCK))
        assertEquals("Too many attempts. Try again in 30s", describeFailure(ApiException(429, "slow down", 30), Stage.UNLOCK))
        assertEquals("disk full", describeFailure(ApiException(500, "disk full"), Stage.CHANGE))
    }

    @Test
    fun networkAndKeyProblemsAreExplained() {
        assertEquals("Cannot reach the server", describeFailure(ConnectException("Failed to connect to /10.0.0.1:443"), Stage.CHANGE))
        val mismatch = IOException("server returned a list key that does not match this passkey", DecryptionException("bad tag"))
        assertEquals("This passkey does not match the list on the server", describeFailure(mismatch, Stage.UNLOCK))
        assertEquals("Could not decrypt this data", describeFailure(DecryptionException("bad tag"), Stage.CHANGE))
    }

    @Test
    fun validationAndLockingMessagesComeThrough() {
        assertEquals("Give it a title", describeFailure(IllegalArgumentException("Give it a title"), Stage.CHANGE))
        assertEquals("The list was locked before this finished", describeFailure(IllegalStateException("the list is locked"), Stage.CHANGE))
    }

    @Test
    fun progressReadsNaturallyAndNeverDividesByZero() {
        assertEquals("Nothing on the list yet", describeProgress(0, 0))
        assertEquals("3 of 10 done", describeProgress(3, 10))
        assertEquals(0f, progressFraction(0, 0))
        assertEquals(0.25f, progressFraction(1, 4))
        assertEquals(1f, progressFraction(4, 4))
    }

    @Test
    fun theDoneDateUsesTheGivenZone() {
        val lateEveningUtc = 1_793_833_200_000L
        assertEquals("Done 4 Nov 2026", describeDoneDate(lateEveningUtc, ZoneId.of("Europe/London"), Locale.UK))
        assertEquals("Done 5 Nov 2026", describeDoneDate(lateEveningUtc, ZoneId.of("Asia/Tokyo"), Locale.UK))
    }
}
