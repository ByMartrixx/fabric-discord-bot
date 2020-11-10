package net.fabricmc.bot

import com.gitlab.kordlib.core.behavior.channel.createWebhook
import com.gitlab.kordlib.core.cache.data.MessageData
import com.gitlab.kordlib.core.entity.*
import com.gitlab.kordlib.core.entity.channel.GuildMessageChannel
import com.gitlab.kordlib.core.firstOrNull
import com.gitlab.kordlib.rest.Image
import com.gitlab.kordlib.rest.request.RestRequestException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import net.fabricmc.bot.conf.FabricBotConfig
import net.fabricmc.bot.conf.config
import net.fabricmc.bot.enums.Channels
import net.fabricmc.bot.enums.Roles
import net.time4j.Duration
import net.time4j.IsoUnit
import java.time.Instant
import java.time.temporal.ChronoUnit

private const val NEW_DAYS = 3L
private val logger = KotlinLogging.logger {}

/**
 * Convenience function to convert a [Role] object to a [Roles] enum value.
 *
 * @receiver The [Role] to convert.
 * @return The corresponding [Roles] enum value, or `null` if no corresponding value exists.
 */
fun Role.toEnum(): Roles? {
    for (role in Roles.values()) {
        if (this.id == config.getRoleSnowflake(role)) {
            return role
        }
    }

    return null
}

/** ID of the message author. **/
val MessageData.authorId: Long? get() = author.id

/** Is the message author a bot. **/
val MessageData.authorIsBot: Boolean? get() = author.bot

/**
 * The creation timestamp for this user.
 */
val User.createdAt: Instant get() = this.id.timeStamp

/**
 * Check whether this is a user that was created recently.
 *
 * @return Whether the user was created in the last 3 days.
 */
fun User.isNew(): Boolean = this.createdAt.isAfter(Instant.now().minus(NEW_DAYS, ChronoUnit.DAYS))

/**
 * Generate the jump URL for this message.
 *
 * @return A clickable URL to jump to this message.
 */
suspend fun Message.getUrl(): String {
    val guild = getGuildOrNull()?.id?.value ?: "@me"

    return "https://discordapp.com/channels/$guild/${channelId.value}/${id.value}"
}

/**
 * Deletes a message, catching and ignoring a HTTP 404 (Not Found) exception.
 */
suspend fun Message.deleteIgnoringNotFound() {
    try {
        this.delete()
    } catch (e: RestRequestException) {
        if (e.code != HttpStatusCode.NotFound.value) {
            throw e
        }
    }
}

/**
 * Deletes a message after a delay.
 *
 * This function **does not block**.
 *
 * @param millis The delay before deleting the message, in milliseconds.
 * @return Job spawned by the CoroutineScope.
 */
fun Message.deleteWithDelay(millis: Long, retry: Boolean = true): Job {
    val logger = KotlinLogging.logger {}

    return this.kord.launch {
        delay(millis)

        try {
            this@deleteWithDelay.deleteIgnoringNotFound()
        } catch (e: RestRequestException) {
            val message = this@deleteWithDelay

            if (retry) {
                logger.debug(e) {
                    "Failed to delete message, retrying: $message"
                }

                this@deleteWithDelay.deleteWithDelay(millis, false)
            } else {
                logger.error(e) {
                    "Failed to delete message: $message"
                }
            }
        }
    }
}

/** Check if the user has the provided [role]. **/
suspend fun Member.hasRole(role: Role): Boolean =
        this.roles.toList().contains(role)

/**
 * Convert a Time4J Duration object to seconds.
 *
 * @return The duration object folded into a single Long, representing total seconds.
 */
fun Duration<IsoUnit>.toSeconds(): Long {
    val amount = this.toTemporalAmount()
    var seconds = 0L

    seconds += amount.get(ChronoUnit.MILLENNIA) * ChronoUnit.MILLENNIA.duration.seconds
    seconds += amount.get(ChronoUnit.CENTURIES) * ChronoUnit.CENTURIES.duration.seconds
    seconds += amount.get(ChronoUnit.DECADES) * ChronoUnit.DECADES.duration.seconds
    seconds += amount.get(ChronoUnit.YEARS) * ChronoUnit.YEARS.duration.seconds
    seconds += amount.get(ChronoUnit.MONTHS) * ChronoUnit.MONTHS.duration.seconds
    seconds += amount.get(ChronoUnit.WEEKS) * ChronoUnit.WEEKS.duration.seconds
    seconds += amount.get(ChronoUnit.DAYS) * ChronoUnit.DAYS.duration.seconds
    seconds += amount.get(ChronoUnit.HOURS) * ChronoUnit.HOURS.duration.seconds
    seconds += amount.get(ChronoUnit.MINUTES) * ChronoUnit.MINUTES.duration.seconds
    seconds += amount.get(ChronoUnit.SECONDS)

    return seconds
}

/**
 * Run a block of code within a coroutine scope, defined by a given dispatcher.
 *
 * This is intended for use with code that normally isn't designed to be run within a coroutine, such as
 * database actions.
 *
 * @param dispatcher The dispatcher to use - defaults to [Dispatchers.IO].
 * @param body The block of code to be run.
 */
suspend fun <T> runSuspended(dispatcher: CoroutineDispatcher = Dispatchers.IO, body: suspend CoroutineScope.() -> T) =
        withContext(dispatcher, body)

/**
 * Ensure a webhook is created for the bot in a given channel, and return it.
 *
 * This will not create a new webhook if one named "Fabric Bot" already exists.
 *
 * @param channel Configured channel to ensure a webhook exists for.
 * @return Webhook object for the newly created webhook, or the existing one if it's already there.
 */
suspend fun ensureWebhook(channel: Channels): Webhook = ensureWebhook(config.getChannel(channel) as GuildMessageChannel)


/**
 * Ensure a webhook is created for the bot in a given channel, and return it.
 *
 * This will not create a new webhook if one named "Fabric Bot" already exists.
 *
 * @param channelObj Channel to create the webhook for.
 * @return Webhook object for the newly created webhook, or the existing one if it's already there.
 */
suspend fun ensureWebhook(channelObj: GuildMessageChannel): Webhook {
    val webhook = channelObj.webhooks.firstOrNull { it.name == "Fabric Bot" }

    if (webhook != null) {
        return webhook
    }

    logger.info { "Creating webhook for channel: #${channelObj.name}" }

    return channelObj.createWebhook {
        name = "Fabric Bot"
        avatar = Image.raw(FabricBotConfig::class.java.getResource("/logo.png").readBytes(), Image.Format.PNG)
    }
}


/**
 * Given a Duration, this function will return a String (or null if it represents less than 1 second).
 *
 * The string is intended to be readable for humans - "a days, b hours, c minutes, d seconds".
 */
@Suppress("MagicNumber")  // These are all time units!
fun java.time.Duration.toHuman(): String? {
    val parts = mutableListOf<String>()

    val seconds = this.seconds % 60
    val minutesTotal = this.seconds / 60

    val minutes = minutesTotal % 60
    val hoursTotal = minutesTotal / 60

    val hours = hoursTotal % 24
    val days = hoursTotal / 24

    if (days > 0) {
        parts.add(
                "$days " + if (days > 1) "days" else "day"
        )
    }

    if (hours > 0) {
        parts.add(
                "$hours " + if (hours > 1) "hours" else "hour"
        )
    }

    if (minutes > 0) {
        parts.add(
                "$minutes " + if (minutes > 1) "minutes" else "minute"
        )
    }

    if (seconds > 0) {
        parts.add(
                "$seconds " + if (seconds > 1) "seconds" else "second"
        )
    }

    if (parts.isEmpty()) return null

    var output = ""

    parts.forEachIndexed { i, part ->
        if (i == parts.size - 1 && i > 0) {
            output += " and "  // About to output the last part, and it's not the only one
        } else if (i < parts.size - 1 && i > 0) {
            output += ", "  // Not the last part, but not the first one either
        }

        output += part
    }

    // I have no idea how I should _actually_ do this...
    return parts.joinToString(", ").reversed().replaceFirst(",", "dna ").reversed()
}
