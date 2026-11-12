package com.szdict.app.feature.voice

import com.szdict.speech.Speech
import com.szdict.speech.SpeechModel
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionRecordPermissionDenied
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/** The iOS voice services (registered under [VoiceServices.KEY]). */
object IosVoice {
    /**
     * [modelDir]: absolute path of the directory holding the voice model files, or null when not
     * bundled. Without the model or the engine the services still exist and the model pack reports
     * itself unavailable (VOICE-023).
     */
    fun services(modelDir: String?): VoiceServices? = VoiceServices(
        capture = IosAudioCapture(),
        recognizer = SherpaSpeechRecognizer(modelDir),
        modelPack = BundledVoiceModelPack(modelDir),
        permission = IosMicPermission(),
        foreground = IosForegroundMonitor(),
    )
}

/**
 * The model shipped inside the app bundle: there is no download state (VOICE-095). A build without
 * the model or without the sherpa-onnx library degrades to "unavailable" (VOICE-023). A Background
 * Assets pack (ARCH-142) replaces this.
 */
class BundledVoiceModelPack(private val modelDir: String?) : VoiceModelPack {
    private val _state = MutableStateFlow(read())
    override val state: StateFlow<VoicePackState> = _state

    override fun refresh() {
        _state.value = read()
    }

    override fun request() = refresh()

    private fun read(): VoicePackState =
        if (Speech.AVAILABLE && modelDir != null && SpeechModel.isPresent(modelDir)) VoicePackState.Present
        else VoicePackState.Failed(retryable = false)
}

/**
 * The microphone permission (`NSMicrophoneUsageDescription`), asked at the moment of use
 * (VOICE-010). iOS shows its prompt once: a refusal is permanent and only the app's page in
 * Settings can change it (VOICE-011).
 */
class IosMicPermission : MicPermission {
    override fun status(): MicPermissionStatus = when (AVAudioSession.sharedInstance().recordPermission) {
        AVAudioSessionRecordPermissionGranted -> MicPermissionStatus.GRANTED
        AVAudioSessionRecordPermissionDenied -> MicPermissionStatus.DENIED_PERMANENTLY
        else -> MicPermissionStatus.NOT_REQUESTED
    }

    override suspend fun request(): MicPermissionStatus {
        val before = status()
        if (before != MicPermissionStatus.NOT_REQUESTED) return before
        return suspendCancellableCoroutine { continuation ->
            AVAudioSession.sharedInstance().requestRecordPermission { granted ->
                // Answered on an arbitrary queue.
                dispatch_async(dispatch_get_main_queue()) {
                    if (continuation.isActive) {
                        continuation.resume(if (granted) MicPermissionStatus.GRANTED else MicPermissionStatus.DENIED_PERMANENTLY)
                    }
                }
            }
        }
    }

    override fun openSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        dispatch_async(dispatch_get_main_queue()) {
            UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
        }
    }
}

/**
 * Foreground state from the application's notifications (VOICE-062). Only entering the background
 * counts: a system alert, Control Centre or a rotation leave the app in the foreground.
 */
class IosForegroundMonitor : ForegroundMonitor {
    private val _foreground = MutableStateFlow(
        !NSThread.isMainThread || UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateBackground,
    )
    override val foreground: StateFlow<Boolean> = _foreground

    // Lives as long as the app, like the services it belongs to.
    private val observers = listOf(
        UIApplicationDidEnterBackgroundNotification to false,
        UIApplicationWillEnterForegroundNotification to true,
    ).map { (name, value) ->
        NSNotificationCenter.defaultCenter.addObserverForName(name, `object` = null, queue = NSOperationQueue.mainQueue) {
            _foreground.value = value
        }
    }
}
