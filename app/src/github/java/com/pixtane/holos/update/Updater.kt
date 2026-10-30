package com.pixtane.holos.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pixtane.holos.BuildConfig
import com.pixtane.holos.CrashReport
import com.pixtane.holos.HolosApp
import com.pixtane.holos.Prefs
import com.pixtane.holos.R
import com.pixtane.holos.playback.PlayerBus
import com.pixtane.holos.synth.SynthesisManager
import com.pixtane.holos.t
import com.pixtane.holos.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Self-updater for builds distributed outside Google Play. Checks the latest GitHub release,
 * downloads its APK, makes sure it is signed with the same key as the installed app, and hands
 * it to PackageInstaller. After Holos has installed one update itself it is the "installer of
 * record", and on Android 12+ later updates install without asking.
 */
object Updater {
    private const val REPO = "Pixtane/transcribe-local-apk"
    private const val TAG = "Updater"
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 77
    const val ACTION_INSTALL_STATUS = "com.pixtane.holos.INSTALL_STATUS"

    data class Release(val build: Int, val version: String, val notes: String, val apkUrl: String, val size: Long)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val done: Long, val total: Long) : State
        data class Ready(val release: Release, val apk: File) : State
        data class Installing(val release: Release) : State
        data class Failed(val message: String, val release: Release? = null) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var job: Job? = null

    private fun dir(context: Context) = File(context.filesDir, "updates").apply { mkdirs() }

    fun init(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(CHANNEL, t("App updates", "Оновлення застосунку"), android.app.NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        // Remove downloads of versions that are already installed.
        dir(context).listFiles()?.forEach { f ->
            val b = f.name.removePrefix("Holos-").substringBefore('.').toIntOrNull()
            if (b == null || b <= BuildConfig.BUILD_NUMBER) f.delete()
        }
        schedulePeriodic(context)
        if (Prefs.autoUpdate) checkInBackground(context, auto = true)
    }

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("update-check", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Manual or automatic check; with [auto] the update is also downloaded and, if possible, installed. */
    fun checkInBackground(context: Context, auto: Boolean = false) {
        if (job?.isActive == true) return
        job = HolosApp.scope.launch(Dispatchers.IO) { runCatching { checkAndMaybeUpdate(context, auto) } }
    }

    suspend fun checkAndMaybeUpdate(context: Context, auto: Boolean) {
        val current = _state.value
        if (current is State.Downloading || current is State.Installing) return
        _state.value = State.Checking
        val release = try {
            fetchLatest()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed", e)
            _state.value = if (auto) State.Idle else State.Failed(t("Could not check for updates", "Не вдалося перевірити оновлення"))
            return
        }
        if (release == null || release.build <= BuildConfig.BUILD_NUMBER) {
            _state.value = State.UpToDate
            return
        }
        CrashReport.crumb("Update available: build ${release.build}")
        _state.value = State.Available(release)
        if (!auto) return
        val apk = download(context, release) ?: return
        if (canInstallSilently(context) && isIdle()) {
            install(context, apk, release)
        } else {
            notifyReady(context, release)
        }
    }

    /** Download (if needed) and install; called from the UI "Update" button. */
    fun updateNow(context: Context) {
        HolosApp.scope.launch(Dispatchers.IO) {
            val release = when (val s = _state.value) {
                is State.Available -> s.release
                is State.Ready -> { install(context, s.apk, s.release); return@launch }
                is State.Failed -> s.release ?: return@launch
                else -> return@launch
            }
            val apk = download(context, release) ?: return@launch
            install(context, apk, release)
        }
    }

    private fun fetchLatest(): Release? {
        val conn = (URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Holos-Android/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (conn.responseCode == 404) return null
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            val tag = json.optString("tag_name")
            val build = tag.substringAfterLast('.').toIntOrNull() ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    return Release(
                        build = build,
                        version = tag.removePrefix("v"),
                        notes = json.optString("body").trim(),
                        apkUrl = a.getString("browser_download_url"),
                        size = a.optLong("size", -1),
                    )
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun download(context: Context, release: Release): File? {
        val dest = File(dir(context), "Holos-${release.build}.apk")
        if (dest.exists() && (release.size <= 0 || dest.length() == release.size) && verify(context, dest)) {
            _state.value = State.Ready(release, dest)
            return dest
        }
        val part = File(dest.path + ".part")
        try {
            var have = if (part.exists()) part.length() else 0L
            val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000; readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Holos-Android/${BuildConfig.VERSION_NAME}")
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                if (code == 200) have = 0
                val total = if (conn.contentLengthLong > 0) have + conn.contentLengthLong else release.size
                FileOutputStream(part, have > 0).use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(1 shl 16)
                        var last = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); have += n
                            if (have - last > 512 * 1024) { last = have; _state.value = State.Downloading(release, have, total) }
                        }
                    }
                }
                if (total > 0 && have != total) throw IOException("Incomplete download")
            } finally {
                conn.disconnect()
            }
            dest.delete()
            if (!part.renameTo(dest)) throw IOException("Could not save update")
            if (!verify(context, dest)) {
                dest.delete()
                throw IOException(t("Update is not signed by the Holos developer", "Оновлення підписане не розробником Holos"))
            }
            _state.value = State.Ready(release, dest)
            return dest
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Update download failed", e)
            _state.value = State.Failed(e.message ?: t("Download failed", "Помилка завантаження"), release)
            return null
        }
    }

    /** Same package, newer build and identical signing certificate as the installed app. */
    private fun verify(context: Context, apk: File): Boolean {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: return false
        if (archive.packageName != context.packageName) return false
        val installed = pm.getPackageInfo(context.packageName, flags)
        return certs(archive) == certs(installed) && certs(installed).isNotEmpty()
    }

    private fun certs(info: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val s = info.signingInfo ?: return emptySet()
            if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory
        } else @Suppress("DEPRECATION") info.signatures
        return sigs.orEmpty().map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    fun canRequestInstalls(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Android 12+ lets the installer of record update an app without a confirmation dialog. */
    fun canInstallSilently(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31 || !canRequestInstalls(context)) return false
        val installer = runCatching { context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName }.getOrNull()
        return installer == context.packageName
    }

    /** True while an activity of the app is on screen (set by MainActivity). */
    @Volatile var uiVisible = false

    /** Never restart the app under someone who is using it, listening or generating. */
    private fun isIdle(): Boolean = !uiVisible && !PlayerBus.snapshot.value.playWhenReady && SynthesisManager.isIdle()

    suspend fun install(context: Context, apk: File, release: Release) = withContext(Dispatchers.IO) {
        if (!canRequestInstalls(context)) {
            _state.value = State.Ready(release, apk)
            withContext(Dispatchers.Main) { openInstallPermissionSettings(context) }
            return@withContext
        }
        _state.value = State.Installing(release)
        CrashReport.crumb("Installing update build ${release.build}")
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                if (Build.VERSION.SDK_INT >= 34) setRequestUpdateOwnership(true)
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("holos.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 1 shl 16) }
                    session.fsync(out)
                }
                val intent = Intent(context, InstallResultReceiver::class.java).setAction(ACTION_INSTALL_STATUS)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pi.intentSender)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            _state.value = State.Failed(e.message ?: t("Install failed", "Помилка встановлення"), release)
        }
    }

    internal fun onInstallStatus(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val release = (_state.value as? State.Installing)?.release
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirm != null) {
                    // Works while the app is open; otherwise the system blocks it and the user taps "Update" again.
                    runCatching { context.startActivity(confirm) }
                }
                release?.let { _state.value = State.Available(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> CrashReport.crumb("Update installed")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: t("Install failed", "Помилка встановлення")
                _state.value = State.Failed(msg, release)
            }
        }
    }

    private fun notifyReady(context: Context, release: Release) {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_holos)
            .setContentTitle(t("Holos ${release.version} is ready", "Holos ${release.version} готовий до встановлення"))
            .setContentText(t("Tap to update", "Торкніться, щоб оновити"))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n) }
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Updater.onInstallStatus(context.applicationContext, intent)
    }
}

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (Prefs.autoUpdate) Updater.checkAndMaybeUpdate(applicationContext, auto = true)
        return Result.success()
    }
}
