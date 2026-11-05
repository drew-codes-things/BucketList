package app.bucketlist

import bucketlist.core.ApiException
import bucketlist.core.DecryptionException
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val DONE_DATE_PATTERN = "d MMM yyyy"

enum class Stage { UNLOCK, CHANGE }

fun describeFailure(error: Throwable, stage: Stage): String = when {
    error is ApiException && error.status == 401 && stage == Stage.UNLOCK -> "Wrong passkey"
    error is ApiException && error.status == 401 -> "Signed out by the server. Unlock again"
    error is ApiException && error.status == 429 -> "Too many attempts. Try again in ${error.retryAfterSeconds}s"
    error is ApiException && error.status == 403 -> "Setup token rejected"
    error is ApiException && error.status == 404 && stage == Stage.UNLOCK -> "No list on this server yet. Use first-time setup"
    error is ApiException && error.status == 404 -> "That item was removed on another phone"
    error is ApiException && error.status == 409 -> "A list already exists on this server"
    error is ApiException -> error.message ?: "Server error"
    error is DecryptionException -> "Could not decrypt this data"
    error is IOException && error.cause is DecryptionException -> "This passkey does not match the list on the server"
    error is IOException -> "Cannot reach the server"
    error is IllegalArgumentException -> error.message ?: "Check what you typed"
    error is IllegalStateException -> "The list was locked before this finished"
    else -> error.message ?: "Something went wrong"
}

fun signsOut(error: Throwable, stage: Stage): Boolean = stage == Stage.CHANGE && error is ApiException && error.status == 401

fun describeProgress(doneCount: Int, totalCount: Int): String =
    if (totalCount == 0) "Nothing on the list yet" else "$doneCount of $totalCount done"

fun progressFraction(doneCount: Int, totalCount: Int): Float =
    if (totalCount == 0) 0f else doneCount.toFloat() / totalCount

fun describeDoneDate(doneAtMillis: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    "Done " + DateTimeFormatter.ofPattern(DONE_DATE_PATTERN, locale).format(Instant.ofEpochMilli(doneAtMillis).atZone(zone))
