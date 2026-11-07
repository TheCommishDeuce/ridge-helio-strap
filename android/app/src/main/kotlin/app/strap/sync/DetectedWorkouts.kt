package app.strap.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.strap.MainActivity
import app.strap.R
import strap.protocol.model.Workout
import strap.science.Sports
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "Workout detected" after a sync, as the Zepp app does it: a workout the strap recognised by
 * itself (summary subtype 1), new to the phone and ended within a day. No held connection (D30).
 * Not for one inside a session the owner already logged in openGym (D44): that is known training.
 */
object DetectedWorkouts {
    private val RECENT: Duration = Duration.ofHours(24)

    /**
     * The fetched workouts to tell the owner about; [known] are the starts the phone held before
     * this sync, [logged] the windows of sessions logged elsewhere.
     */
    fun toNotify(fetched: List<Workout>, known: Set<Instant>, now: Instant, logged: List<Pair<Instant, Instant>> = emptyList()): List<Workout> =
        fetched.filter { w ->
            val end = w.start.plusSeconds(w.durationSec.toLong())
            w.subtype == Sports.SUBTYPE_AUTO_DETECTED && w.start !in known && Duration.between(end, now) <= RECENT &&
                logged.none { (a, b) -> w.start.isBefore(b) && end.isAfter(a) }
        }.distinctBy { it.start }.sortedBy { it.start }

    fun text(w: Workout, zone: ZoneId): String {
        val from = DateTimeFormatter.ofPattern("HH:mm").format(w.start.atZone(zone))
        val minutes = (w.durationSec + 30) / 60
        val length = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
        return "${Sports.label(w.sportType)} · $length from $from. Tap to check it, edit it or hide it."
    }
}

/** Posts [DetectedWorkouts] notices; each opens that workout. */
class WorkoutNotifier(private val context: Context) {
    fun notify(workouts: List<Workout>) {
        if (workouts.isEmpty()) return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Detected workouts", NotificationManager.IMPORTANCE_DEFAULT))
        val manager = NotificationManagerCompat.from(context)
        workouts.forEach { w ->
            val id = (w.start.epochSecond % Int.MAX_VALUE).toInt()
            val open = PendingIntent.getActivity(
                context, id,
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_WORKOUT, w.start.toEpochMilli())
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val text = DetectedWorkouts.text(w, ZoneId.systemDefault())
            manager.notify(TAG, id, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("Workout detected")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        }
    }

    private companion object {
        const val CHANNEL = "workouts"
        const val TAG = "workout"
    }
}
