package com.szdict.app.feature.voice

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.szdict.voice.VoiceEngines
import com.szdict.voice.VoiceModel
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/** The Android voice services (registered by `PlatformServices` under [VoiceServices.KEY]). */
object AndroidVoice {
    fun services(context: Context): VoiceServices {
        val app = context.applicationContext
        val activities = ActivityTracker(app as Application)
        return VoiceServices(
            capture = AndroidAudioCapture(app),
            recognizer = SherpaSpeechRecognizer(app.assets),
            modelPack = BundledVoiceModelPack(app),
            permission = AndroidMicPermission(app, activities),
            foreground = activities,
        )
    }
}

/**
 * Direct-APK delivery (ARCH-142): the model is inside the APK, so there is no download state
 * (VOICE-095). A build without the model or the native library is a build error (VOICE-023); at
 * runtime it degrades to "unavailable". A Play flavour replaces this with an on-demand asset pack.
 */
class BundledVoiceModelPack(private val context: Context) : VoiceModelPack {
    private val _state = MutableStateFlow(read())
    override val state: StateFlow<VoicePackState> = _state

    override fun refresh() {
        _state.value = read()
    }

    override fun request() = refresh()

    private fun read(): VoicePackState =
        if (VoiceEngines.AVAILABLE && VoiceModel.isBundled(context.assets)) VoicePackState.Present
        else VoicePackState.Failed(retryable = false)
}

/**
 * Follows the app's activities without touching `MainActivity`: which one is in front (to ask for
 * the permission) and whether any is started (VOICE-062). A configuration change is not a background.
 */
class ActivityTracker(app: Application) : ForegroundMonitor, Application.ActivityLifecycleCallbacks {
    private val started = HashSet<Int>()
    private var front = WeakReference<Activity>(null)
    private val _foreground = MutableStateFlow(true)
    override val foreground: StateFlow<Boolean> = _foreground

    val current: ComponentActivity? get() = front.get() as? ComponentActivity

    init {
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        started += System.identityHashCode(activity)
        front = WeakReference(activity)
        _foreground.value = true
    }

    override fun onActivityResumed(activity: Activity) {
        front = WeakReference(activity)
    }

    override fun onActivityStopped(activity: Activity) {
        started -= System.identityHashCode(activity)
        if (started.isEmpty() && !activity.isChangingConfigurations) _foreground.value = false
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (front.get() === activity) front = WeakReference(null)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}

/** `RECORD_AUDIO`, asked at the moment of use through the activity in front (VOICE-010, VOICE-011). */
class AndroidMicPermission(private val context: Context, private val activities: ActivityTracker) : MicPermission {
    private var refusal: MicPermissionStatus? = null
    private var requests = 0

    private val granted: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun status(): MicPermissionStatus {
        if (granted) {
            refusal = null
            return MicPermissionStatus.GRANTED
        }
        return refusal ?: MicPermissionStatus.NOT_REQUESTED
    }

    override suspend fun request(): MicPermissionStatus {
        if (granted) return status()
        val activity = activities.current ?: return status()
        val rationaleBefore = ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
        val askedAt = SystemClock.elapsedRealtime()
        val isGranted = suspendCancellableCoroutine { continuation ->
            val launcher = activity.activityResultRegistry.register(
                "voice-microphone-${requests++}",
                ActivityResultContracts.RequestPermission(),
            ) { result -> if (continuation.isActive) continuation.resume(result) }
            continuation.invokeOnCancellation { launcher.unregister() }
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
        if (isGranted || granted) {
            refusal = null
            return MicPermissionStatus.GRANTED
        }
        // Permanent: the system no longer offers its prompt — it answered at once without showing
        // it, or the user just chose not to be asked again. A dismissed prompt can be shown again.
        val rationaleAfter = ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
        val answeredAtOnce = SystemClock.elapsedRealtime() - askedAt < PROMPT_SHOWN_MS
        val permanent = !rationaleAfter && (rationaleBefore || answeredAtOnce)
        return (if (permanent) MicPermissionStatus.DENIED_PERMANENTLY else MicPermissionStatus.DENIED).also { refusal = it }
    }

    override fun openSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private companion object {
        const val PROMPT_SHOWN_MS = 350L
    }
}
