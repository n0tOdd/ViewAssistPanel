/*
 * Copyright (c) 2022 WallPanel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.wallpanel.app.network

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.*
import android.os.PowerManager.WakeLock
import android.view.Display
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.Observer
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.koushikdutta.async.AsyncServer
import com.koushikdutta.async.ByteBufferList
import com.koushikdutta.async.http.body.JSONObjectBody
import com.koushikdutta.async.http.body.StringBody
import com.koushikdutta.async.http.server.AsyncHttpServer
import com.koushikdutta.async.http.server.AsyncHttpServerResponse
import com.koushikdutta.async.util.Charsets
import dagger.android.AndroidInjection
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import xyz.wallpanel.app.R
import xyz.wallpanel.app.modules.*
import xyz.wallpanel.app.persistence.Configuration
import xyz.wallpanel.app.ui.activities.BaseBrowserActivity.Companion.BROADCAST_ACTION_CLEAR_BROWSER_CACHE
import xyz.wallpanel.app.ui.activities.BaseBrowserActivity.Companion.BROADCAST_ACTION_JS_EXEC
import xyz.wallpanel.app.ui.activities.BaseBrowserActivity.Companion.BROADCAST_ACTION_LOAD_URL
import xyz.wallpanel.app.ui.activities.BaseBrowserActivity.Companion.BROADCAST_ACTION_OPEN_SETTINGS
import xyz.wallpanel.app.ui.activities.BaseBrowserActivity.Companion.BROADCAST_ACTION_RELOAD_PAGE
import xyz.wallpanel.app.utils.MqttUtils
import xyz.wallpanel.app.utils.MqttUtils.Companion.COMMAND_AUDIO
import xyz.wallpanel.app.utils.MqttUtils.Companion.COMMAND_BRIGHTNESS
import xyz.wallpanel.app.utils.MqttUtils.Companion.COMMAND_CAMERA
import xyz.wallpanel.app.utils.MqttUtils.Companion.COMMAND_CLEAR_CACHE
import xyz.wallpanel.app.utils.MqttUtils.Companion.COMMAND_EVAL
import xyz.wallpanel.app.utils.MqttUtils.Companion.COMMAND_RELAUNCH
import xyz.wallpanel.app.utils.MqttUtils.Companion.COMMAND_RELOAD
import xyz.wallpanel.app.utils.MqttUtils.Companion.COMMAND_SENSOR
import xyz.wallpanel.app.utils.MqttUtils.Companion.COMMAND_SENSOR_FACE
import xyz.wallpanel.app.utils.MqttUtils.Companion.COMMAND_SENSOR_MOTION
import xyz.wallpanel.app.utils.MqttUtils.Companion.COMMAND_SENSOR_QR_CODE
import xyz.wallpanel.app.utils.MqttUtils.Companion.COMMAND_SETTINGS
import xyz.wallpanel.app.utils.MqttUtils.Companion.COMMAND_SPEAK
import xyz.wallpanel.app.utils.MqttUtils.Companion.COMMAND_STATE
import xyz.wallpanel.app.utils.MqttUtils.Companion.COMMAND_URL
import xyz.wallpanel.app.utils.MqttUtils.Companion.COMMAND_VOLUME
import xyz.wallpanel.app.utils.MqttUtils.Companion.COMMAND_STARTAPPLICATION

import xyz.wallpanel.app.utils.MqttUtils.Companion.COMMAND_WAKE
import xyz.wallpanel.app.utils.MqttUtils.Companion.COMMAND_WAKETIME
import xyz.wallpanel.app.utils.MqttUtils.Companion.VALUE
import xyz.wallpanel.app.utils.NotificationUtils
import xyz.wallpanel.app.utils.ScreenUtils
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.*
import net.sourceforge.lame.lowlevel.LameEncoder
import kotlin.experimental.and
import net.sourceforge.lame.mp3.Lame
// TODO move this to internal class within application, no longer run as service
class WallPanelService : LifecycleService(), MQTTModule.MQTTListener {

    @Inject
    lateinit var configuration: Configuration

    private var cameraReader: CameraReader? = null

    @Inject
    lateinit var sensorReader: SensorReader

    @Inject
    lateinit var mqttOptions: MQTTOptions

    @Inject
    lateinit var screenUtils: ScreenUtils

    private val mJpegSockets = ArrayList<AsyncHttpServerResponse>()
    private var audioStream: ByteArrayOutputStream? = null

    private var partialWakeLock: WakeLock? = null
    private var screenWakeLock: WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var keyguardLock: KeyguardManager.KeyguardLock? = null
    private var audioPlayer: MediaPlayer? = null
    private var audioPlayerBusy: Boolean = false
    private var httpServer: AsyncHttpServer? = null
    private val mBinder = WallPanelServiceBinder()
    private val motionClearHandler = Handler(Looper.getMainLooper())
    private val appStateClearHandler = Handler(Looper.getMainLooper())
    private val qrCodeClearHandler = Handler(Looper.getMainLooper())
    private val faceClearHandler = Handler(Looper.getMainLooper())
    private val wakeScreenHandler = Handler(Looper.getMainLooper())
    private var textToSpeechModule: TextToSpeechModule? = null
    private var mqttModule: MQTTModule? = null
    private var connectionLiveData: ConnectionLiveData? = null
    private var hasNetwork = AtomicBoolean(true)
    private var motionDetected: Boolean = false
    private var appStatePublished: Boolean = false
    private var qrCodeRead: Boolean = false
    private var faceDetected: Boolean = false
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var appLaunchUrl: String? = null
    private var localBroadCastManager: LocalBroadcastManager? = null
    private var mqttAlertMessageShown = false
    private var mqttConnecting = false
    private var mqttInitConnection = AtomicBoolean(true)

    private val restartMqttRunnable = Runnable {
        clearAlertMessage() // clear any dialogs
        mqttAlertMessageShown = false
        mqttConnecting = false
        //sendToastMessage(getString(R.string.toast_connect_retry))
        mqttModule?.restart()
    }

    inner class WallPanelServiceBinder : Binder() {
        val service: WallPanelService
            get() = this@WallPanelService
    }

    override fun onCreate() {
        super.onCreate()

        Timber.d("onCreate")

        AndroidInjection.inject(this)

        startForeground()

        // prepare the lock types we may use
        val pm = getSystemService(POWER_SERVICE) as PowerManager

        //noinspection deprecation
        partialWakeLock = if (Build.VERSION.SDK_INT > Build.VERSION_CODES.KITKAT) {
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wallPanel:partialWakeLock")
        } else {
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "wallPanel:partialWakeLock")
        }

        screenWakeLock = if (Build.VERSION.SDK_INT > Build.VERSION_CODES.KITKAT) {
            pm.newWakeLock(PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "wallPanel:fullWakeLock")
        } else {
            pm.newWakeLock(PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "wallPanel:fullWakeLock")
        }

        // wifi lock
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, "wallPanel:wifiLock")

        // Some Amazon devices are not seeing this permission so we are trying to check
        val permission = "android.permission.DISABLE_KEYGUARD"
        val checkSelfPermission = ContextCompat.checkSelfPermission(this@WallPanelService, permission)
        if (checkSelfPermission == PackageManager.PERMISSION_GRANTED) {
            val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            keyguardLock = keyguardManager.newKeyguardLock("ALARM_KEYBOARD_LOCK_TAG")
            keyguardLock!!.disableKeyguard()
        }

        this.appLaunchUrl = configuration.appLaunchUrl

        configureMqtt()
        configurePowerOptions()
        configureCamera()
        startHttp()
        configureAudioPlayer()
        configureTextToSpeech()
        startSensors()

        val filter = IntentFilter()
        filter.addAction(BROADCAST_EVENT_URL_CHANGE)
        filter.addAction(BROADCAST_EVENT_SCREEN_TOUCH)
        filter.addAction(Intent.ACTION_SCREEN_ON)
        filter.addAction(Intent.ACTION_SCREEN_OFF)
        filter.addAction(Intent.ACTION_USER_PRESENT)
        localBroadCastManager = LocalBroadcastManager.getInstance(this)
        localBroadCastManager?.registerReceiver(mBroadcastReceiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()
        mqttModule?.let {
            it.pause()
            mqttModule = null
        }
        if (localBroadCastManager != null) {
            localBroadCastManager?.unregisterReceiver(mBroadcastReceiver)
        }
        cameraReader?.stopCamera()
        sensorReader.stopReadings()
        stopHttp()
        stopPowerOptions()
        reconnectHandler.removeCallbacksAndMessages(null)
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return mBinder
    }

    private val isScreenOn: Boolean
        get() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT_WATCH){
                val powerManager = getSystemService(POWER_SERVICE) as PowerManager
                return powerManager.isInteractive
            }
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH){
                val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
                for (display in displayManager.displays){
                    return display.state != Display.STATE_OFF
                }
                return false
            }
            return false
        }

    private val state: JSONObject
        get() {
            val state = JSONObject()
            try {
                state.put(MqttUtils.STATE_CURRENT_URL, appLaunchUrl)
                state.put(MqttUtils.STATE_SCREEN_ON, isScreenOn)
                state.put(MqttUtils.STATE_CAMERA, configuration.cameraEnabled)
                state.put(MqttUtils.STATE_AUDIO, configuration.audioEnabled)
                state.put(MqttUtils.STATE_BRIGHTNESS, screenUtils.getCurrentScreenBrightness())
            } catch (e: JSONException) {
                e.printStackTrace()
            }
            return state
        }

    private fun startForeground() {
        // make a continuously running notification
        val notificationUtils = NotificationUtils(applicationContext, application.resources)
        val notification = notificationUtils.createNotification(getString(R.string.wallpanel_service_notification_title), getString(R.string.wallpanel_service_notification_message))
        startForeground(ONGOING_NOTIFICATION_ID, notification)

        // listen for network connectivity changes
        connectionLiveData = ConnectionLiveData(this)
        connectionLiveData?.observe(this, Observer { connected ->
            if (connected!!) {
                handleNetworkConnect()
            } else {
                handleNetworkDisconnect()
            }
        })

        sendServiceStarted()
    }

    private fun handleNetworkConnect() {
        mqttModule?.let {
            if (!hasNetwork()) {
                it.restart()
            }
        }
        hasNetwork.set(true)
    }

    private fun handleNetworkDisconnect() {
        mqttModule?.let {
            if (hasNetwork()) {
                it.pause()
            }
        }
        hasNetwork.set(false)
    }

    private fun hasNetwork(): Boolean {
        return hasNetwork.get()
    }

    private fun configurePowerOptions() {
        partialWakeLock?.let { acquireWakeLock(it) }
        if (!wifiLock!!.isHeld) {
            wifiLock!!.acquire()
        }
        try {
            keyguardLock?.disableKeyguard()
        } catch (ex: Exception) {
            Timber.i("Disabling keyguard didn't work")
            ex.printStackTrace()
        }
    }

    private fun stopPowerOptions() {
        Timber.i("Releasing Screen/WiFi Locks")
        partialWakeLock?.let { releaseWakeLock(it) }
        if (wifiLock != null && wifiLock!!.isHeld) {
            wifiLock!!.release()
        }
        try {
            keyguardLock!!.reenableKeyguard()
        } catch (ex: Exception) {
            Timber.i("Enabling keyguard didn't work")
            ex.printStackTrace()
        }
    }

    private fun startSensors() {
        if (configuration.sensorsEnabled && mqttOptions.isValid) {
            sensorReader.startReadings(configuration.mqttSensorFrequency, sensorCallback)
        }
    }

    private fun configureMqtt() {
        Timber.d("configureMqtt")
        if (mqttModule == null && mqttOptions.isValid) {
            mqttModule = MQTTModule(this@WallPanelService.applicationContext, mqttOptions, this@WallPanelService)
            lifecycle.addObserver(mqttModule!!)
        }
    }

    override fun onMQTTConnect() {
        Timber.w("onMQTTConnect")
        if (mqttAlertMessageShown) {
            clearAlertMessage() // clear any dialogs
            mqttAlertMessageShown = false
        }
        clearFaceDetected()
        clearMotionDetected()
        publishApplicationState()
        if (configuration.sensorsEnabled) {
            sensorReader.refreshSensors()
        }
        if (configuration.mqttDiscovery) {
            publishDiscovery()
        }
        mqttInitConnection.set(false)
    }

    override fun onMQTTDisconnect() {
        Timber.e("onMQTTDisconnect")
        handleMQTTDisconnected()
    }

    override fun onMQTTException(message: String) {
        Timber.e("onMQTTException: $message")
        handleMQTTDisconnected()
    }

    private fun handleMQTTDisconnected() {
        if (hasNetwork()) {
            if (mqttInitConnection.get()) {
                mqttInitConnection.set(false)
                sendAlertMessage(getString(R.string.error_mqtt_exception))
                mqttAlertMessageShown = true
            }
            if (!mqttConnecting) {
                reconnectHandler.removeCallbacksAndMessages(null)
                reconnectHandler.postDelayed(restartMqttRunnable, 30000)
                mqttConnecting = true
            }
        }
    }

    override fun onMQTTMessage(id: String, topic: String, payload: String) {
        Timber.i("onMQTTMessage: $id, $topic, $payload")
        processCommand(payload)
    }

    private fun publishCommand(command: String, data: JSONObject) {
        publishMessage("${configuration.mqttBaseTopic}${command}", data.toString(), false)
    }

    private fun publishMessage(topic: String, message: String, retain: Boolean) {
        mqttModule?.publish(topic, message, retain)
    }

    private fun configureCamera() {
        val cameraEnabled = configuration.cameraEnabled
        if (cameraEnabled && cameraReader == null) {
            cameraReader = CameraReader(applicationContext)
            cameraReader?.startCamera(cameraDetectorCallback, configuration)
        } else if (cameraEnabled) {
            cameraReader?.startCamera(cameraDetectorCallback, configuration)
        }
    }

    private fun configureTextToSpeech() {
        if (textToSpeechModule == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            textToSpeechModule = TextToSpeechModule(applicationContext)
            textToSpeechModule?.let {
                lifecycle.addObserver(it)
            }
        }
    }

    private fun configureAudioPlayer() {
        audioPlayer = MediaPlayer()
        audioPlayer?.setOnPreparedListener { audioPlayer ->
            Timber.d("audioPlayer: File buffered, playing it now")
            audioPlayerBusy = false
            audioPlayer.start()
        }
        audioPlayer?.setOnCompletionListener { audioPlayer ->
            Timber.d("audioPlayer: Cleanup")
            if (audioPlayer.isPlaying) {  // should never happen, just in case
                audioPlayer.stop()
            }
            audioPlayer.reset()
            audioPlayerBusy = false
        }
        audioPlayer?.setOnErrorListener { audioPlayer, i, i1 ->
            Timber.d("audioPlayer: Error playing file")
            audioPlayerBusy = false
            false
        }
    }

    // TODO text to speech requies content type 'Content-Type': 'application/json; charset=UTF-8'
    private fun startHttp() {
        if (httpServer == null && configuration.httpEnabled) {
            Timber.d("startHttp")

            // TODO this is a hack to get utf-8 working, we need to switch http server libraries
            val charsetsClass = Charsets::class.java
            val us_ascii = charsetsClass.getDeclaredField("US_ASCII")
            us_ascii.isAccessible = true
            us_ascii.set(Charsets::class.java, Charsets.UTF_8)
            httpServer = AsyncHttpServer()

            httpServer?.addAction("*", "*") { request, response ->
                Timber.i("Unhandled Request Arrived")
                response.code(404)
                response.send("")
            }
            httpServer?.listen(AsyncServer.getDefault(), configuration.httpPort)
            Timber.i("%s%s", "Started HTTP server on ", configuration.httpPort.toString())
        }

        if (httpServer != null && configuration.httpRestEnabled) {
            httpServer?.addAction("POST", "/api/command") { request, response ->
                var result = false
                if (request.body is JSONObjectBody) {
                    Timber.i("POST Json Arrived (command)")
                    val body = (request.body as JSONObjectBody).get()
                    result = processCommand(body)
                } else if (request.body is StringBody) {
                    Timber.i("POST String Arrived (command)")
                    result = processCommand((request.body as StringBody).get())
                }
                val j = JSONObject()
                try {
                    j.put("result", result)
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
                response.send(j)
            }

            httpServer?.addAction("GET", "/api/state") { request, response ->
                Timber.i("GET Arrived (/api/state)")
                response.send(state)
            }
            Timber.i("Enabled REST Endpoints")
        }

        if (httpServer != null && configuration.httpMJPEGEnabled) {
            startMJPEG()
            httpServer?.addAction("GET", "/camera/stream") { _, response ->
                Timber.i("GET Arrived (/camera/stream)")
                startMJPEG(response)
            }
            Timber.i("Enabled MJPEG Endpoint")
        }
        if (httpServer != null && configuration.audioEnabled) {
            startAudio()
            httpServer?.addAction("GET", "/camera/audio") { _, response ->
                Timber.i("GET Arrived (/camera/audio)")
               // response.setContentType("audio/L16; rate=16384; channels=1")
               // response.headers.add("transfer-encoding", "chunked") // For streaming
                sendAudioStream(response)
            }
            Timber.i("Enabled MJPEG Endpoint")
        }
    }



    var bufferSize = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    private var audioRecord: AudioRecord? = null
    private  fun stopAudio(){
        if(audioRecord != null &&  audioRecord?.state != AudioRecord.RECORDSTATE_STOPPED)
        {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord= null
        }
    }
    @SuppressLint("MissingPermission")
    private fun startAudio() {
        if(audioRecord == null || audioRecord?.state != AudioRecord.RECORDSTATE_RECORDING) {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                44100,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 100
            )
            if(audioRecord?.state != AudioRecord.STATE_INITIALIZED){
                Timber.i("Failed to initialize audio recorder")
                return
            }
            audioRecord?.startRecording()

            // Start sending data til HTTP-serveren her
        }

    }
    private fun createWavHeader(sampleRate: Int, channels: Int, bufferSize: Int): ByteArray {
        val headerSize = 44
        val byteRate = sampleRate * channels * 2 // 2 bytes per sample (16-bit)
        val blockAlign = channels * 2
        val dataSize = bufferSize * 10 // Estimert datastørrelse
        val header = ByteArray(headerSize)

        // RIFF chunk
        header[0] = 'R'.toByte()
        header[1] = 'I'.toByte()
        header[2] = 'F'.toByte()
        header[3] = 'F'.toByte()
        writeInt(header, 4, 36 + dataSize) // Chunk size

        // WAVE format
        header[8] = 'W'.toByte()
        header[9] = 'A'.toByte()
        header[10] = 'V'.toByte()
        header[11] = 'E'.toByte()

        // fmt subchunk
        header[12] = 'f'.toByte()
        header[13] = 'm'.toByte()
        header[14] = 't'.toByte()
        header[15] = ' '.toByte()
        writeInt(header, 16, 16) // Subchunk size if(audioStream == null)

        writeShort(header, 20, 1.toShort()) // Audio format (PCM)
        writeShort(header, 22, channels.toShort()) // Number of channels
        writeInt(header, 24, sampleRate) // Sample rate
        writeInt(header, 28, byteRate) // Byte rate
        writeShort(header, 32, blockAlign.toShort()) // Block align
        writeShort(header, 34, 16.toShort()) // Bits per sample

        // data subchunk
        header[36] = 'd'.toByte()
        header[37] = 'a'.toByte()
        header[38] = 't'.toByte()
        header[39] = 'a'.toByte()
        writeInt(header, 40, dataSize) // Subchunk size

        return header
    }

    // Hjelpefunksjoner for å skrive data til byte-array
    private fun writeInt(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xff).toByte()
        buffer[offset + 1] = (value shr 8 and 0xff).toByte()
        buffer[offset + 2] = (value shr 16 and 0xff).toByte()
        buffer[offset + 3] = (value shr 24 and 0xff).toByte()
    }

    private fun writeShort(buffer: ByteArray, offset: Int, value: Short) {
        buffer[offset] = (value and 0xff).toByte()
        buffer[offset + 1] = (value.toInt() shr 8 and 0xff).toByte()
    }

    private fun sendAudioStream(response: AsyncHttpServerResponse) {

        val wavHeader = createWavHeader(44100, 1, bufferSize)
        response.setContentType("audio/wav; rate=44100; channels=1")//
        response.headers.add("content-type", "audio/wav")
        response.headers.add("transfer-encoding", "chunked")
        response.writeHead()
        val bb = ByteBufferList()
        bb.recycle()
        bb.add((ByteBuffer.wrap(wavHeader)))
        response.write(bb) // Send WAV-header
        Thread {
            var encoder: LameEncoder
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (audioRecord!!.recordingState == AudioRecord.RECORDSTATE_RECORDING && response.isOpen) {
                try {
                    Timber.i("writing audio response")
                    val buffer = ByteArray(bufferSize)
                    val read = audioRecord?.read(buffer, 0, bufferSize) ?: break

                    if (read == AudioRecord.ERROR_BAD_VALUE || read == AudioRecord.ERROR_INVALID_OPERATION)
                        break
                    if(read == -1) break
                    if (read > 0) {
                        val ba = ByteBufferList()
                        ba.recycle()
                        ba.add((java.nio.ByteBuffer.wrap(buffer)))
                        response.write(ba)

                    }
                }
                catch (ex: Exception)
                {
                    Timber.i(ex)
                }
            }
            response.end() // Avslutt responsen når opptaket er ferdig
        }.start()

    }


    private fun stopHttp() {
        Timber.d("stopHttp")
        httpServer?.let {
            stopMJPEG()
            stopAudio()
            it.stop()
            httpServer = null
        }
    }

    private fun startMJPEG() {
        Timber.d("startMJPEG")
        cameraReader?.let {
            it.getJpeg().observe(this, Observer { jpeg ->
                if (mJpegSockets.isNotEmpty() && jpeg != null) {
                    var i = 0
                    while (i < mJpegSockets.size) {
                        val s = mJpegSockets[i]
                        val bb = ByteBufferList()
                        if (s.isOpen) {
                            bb.recycle()
                            bb.add(ByteBuffer.wrap("--jpgboundary\r\nContent-Type: image/jpeg\r\n".toByteArray()))
                            bb.add(ByteBuffer.wrap(("Content-Length: " + jpeg.size + "\r\n\r\n").toByteArray()))
                            bb.add(ByteBuffer.wrap(jpeg))
                            bb.add(ByteBuffer.wrap("\r\n".toByteArray()))
                            s.write(bb)
                        } else {
                            mJpegSockets.removeAt(i)
                            i--
                            Timber.i("MJPEG Session Count is " + mJpegSockets.size)
                        }
                        i++
                    }
                }
            })
        }
    }


    private fun acquireWakeLock(wakeLock: WakeLock, timeout: Long = -1) {
        if (timeout >= 0) {
            releaseWakeLock(wakeLock)
        }
        if (!wakeLock.isHeld) {
            if (timeout >= 0) {
                wakeLock.acquire(timeout)
            } else {
                wakeLock.acquire(10*60*1000L /*10 minutes*/)
            }
        }
    }

    private fun releaseWakeLock(wakeLock: WakeLock){
        if (wakeLock.isHeld){
            wakeLock.release()
        }
    }
    // Attempt to restart camera and any optional camera options such as motion and streaming
    private fun restartCamera() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && configuration.cameraPermissionsShown) {
            configuration.cameraEnabled = true
            configureCamera()
            startHttp()
            publishDiscovery()
            publishApplicationState()
        } else {
            configuration.cameraEnabled = true
            configureCamera()
            startHttp()
            publishDiscovery()
            publishApplicationState()
        }
    }

    // Attempt to stop camera and any optional camera options such as motion and streaming
    private fun stopCamera() {
        Timber.d("stopCamera")
        configuration.cameraEnabled = false
        stopMJPEG()
        stopHttp()
        cameraReader?.stopCamera()
        publishDiscovery()
        publishApplicationState()
    }

    // TODO we stop entire camera not just streaming
    private fun stopMJPEG() {
        Timber.d("stopMJPEG Called")
        mJpegSockets.clear()
        //cameraReader?.getJpeg()?.removeObservers(this)
        httpServer?.removeAction("GET", "/camera/stream")
    }

    private fun startMJPEG(response: AsyncHttpServerResponse) {
        Timber.d("startmJpeg Called")
        if (mJpegSockets.size < configuration.httpMJPEGMaxStreams) {
            Timber.i("Starting new MJPEG stream")
            response.headers.add("Cache-Control", "no-cache")
            response.headers.add("Connection", "close")
            response.headers.add("Pragma", "no-cache")
            response.setContentType("multipart/x-mixed-replace; boundary=--jpgboundary")
            response.code(200)
            response.writeHead()
            mJpegSockets.add(response)
        } else {
            Timber.i("MJPEG stream limit was reached, not starting")
            response.send("Max streams exceeded")
            response.end()
        }
        Timber.i("%s%s", "MJPEG Session Count is ", mJpegSockets.size)
    }

    private fun processCommand(commandJson: JSONObject): Boolean {
        Timber.d("processCommand $commandJson")
        try {
            if (commandJson.has(COMMAND_CAMERA)) {
                val enableCamera = commandJson.getBoolean(COMMAND_CAMERA)
                if (!enableCamera) {
                    stopCamera()
                } else if (enableCamera) {
                    restartCamera()
                }

            }
            if (commandJson.has(COMMAND_URL)) {
                browseUrl(commandJson.getString(COMMAND_URL))
            }
            if (commandJson.has(COMMAND_STARTAPPLICATION)) {
                startNewActivity(commandJson.getString(COMMAND_URL))
            }
            if (commandJson.has(COMMAND_RELAUNCH)) {
                if (commandJson.getBoolean(COMMAND_RELAUNCH)) {
                    browseUrl(configuration.appLaunchUrl)
                }
            }
            if (commandJson.has(COMMAND_WAKE)) {
                if (commandJson.getBoolean(COMMAND_WAKE).or(false)) {
                    val fallback = configuration.inactivityTime/1000 // if no wake time, use inactivity time, convert to seconds
                    val wakeTime = commandJson.optLong(COMMAND_WAKETIME, fallback) * 1000 // convert to milliseconds
                    if(wakeTime > 0) {
                        wakeScreenOn(wakeTime)
                    } else {
                        wakeScreen()
                    }
                } else {
                    wakeScreenOff()
                }
            }
            if (commandJson.has(COMMAND_BRIGHTNESS)) {
                // This will permanently change the screen brightness level
                val brightness = commandJson.getInt(COMMAND_BRIGHTNESS)
                changeScreenBrightness(brightness)
            }
            if (commandJson.has(COMMAND_RELOAD)) {
                if (commandJson.getBoolean(COMMAND_RELOAD)) {
                    reloadPage()

                }
            }
            if (commandJson.has(COMMAND_CLEAR_CACHE)) {
                if (commandJson.getBoolean(COMMAND_CLEAR_CACHE)) {
                    clearBrowserCache()
                }
            }
            if (commandJson.has(COMMAND_EVAL)) {
                evalJavascript(commandJson.getString(COMMAND_EVAL))
            }
            if (commandJson.has(COMMAND_AUDIO)) {
                playAudio(commandJson.getString(COMMAND_AUDIO))
            }
            if (commandJson.has(COMMAND_SPEAK)) {
                speakMessage(commandJson.getString(COMMAND_SPEAK))
            }
            if (commandJson.has(COMMAND_SETTINGS)) {
                openSettings()
            }
            if (commandJson.has(COMMAND_VOLUME)) {
                setVolume((commandJson.getInt(COMMAND_VOLUME).toFloat() / 100))
            }
        } catch (ex: JSONException) {
            Timber.e("%s%s", "Invalid JSON passed as a command: ", commandJson.toString())
            return false
        }

        return true
    }

    private fun processCommand(command: String): Boolean {
        Timber.d("processCommand Called -> $command")
        return try {
            processCommand(JSONObject(command))
        } catch (ex: JSONException) {
            Timber.e("Invalid JSON passed as a command: $command")
            false
        }
    }


    ///Start a new android application if package name exists
    private fun  startNewActivity(packageName: String ) {
        var intent = getPackageManager().getLaunchIntentForPackage(packageName)
        if (intent == null) {
            // Bring user to the market or let them choose an app?
            intent = Intent(Intent.ACTION_VIEW)
            intent.setData(Uri.parse("market://details?id=$packageName"))
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }
    private fun browseUrl(url: String) {
        Timber.d("browseUrl")
        val intent = Intent(BROADCAST_ACTION_LOAD_URL)
        intent.putExtra(BROADCAST_ACTION_LOAD_URL, url)
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(intent)
    }

    private fun playAudio(audioUrl: String) {
        if (audioPlayerBusy) {
            Timber.d("audioPlayer: Cancelling all previous buffers because new audio was requested")
            audioPlayer?.reset()
        } else if (audioPlayer?.isPlaying == true) {
            Timber.d("audioPlayer: Stopping all media playback because new audio was requested")
            audioPlayer?.stop()
            audioPlayer?.reset()
        }
        audioPlayerBusy = true
        try {
            audioPlayer!!.setDataSource(audioUrl)
        } catch (e: IOException) {
            Timber.e("%s%s%s", "audioPlayer: An error occurred while preparing audio (" , e.message,  ")")
            audioPlayerBusy = false
            audioPlayer?.reset()
            return
        }
        audioPlayer?.prepareAsync()
    }

    private fun setVolume(vol: Float) {
        audioPlayer?.setVolume(vol, vol)
    }

    // TODO we need to url decode incoming strings to support other languages
    private fun speakMessage(message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            textToSpeechModule?.speakText(message)
        } else {
            sendAlertMessage("Text to Speech is not supported on this device's version of Android")
        }
    }

    // TODO temporarily wake screen
    private fun wakeScreen() {
        screenWakeLock?.let { acquireWakeLock(it) }
        val intent = Intent(BROADCAST_SCREEN_WAKE)
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(intent)
    }

    @SuppressLint("WakelockTimeout")
    private fun wakeScreenOn(wakeTime: Long) {
        wakeScreenHandler.postDelayed(clearWakeScreenRunnable, wakeTime)
        screenWakeLock?.let { acquireWakeLock(it, wakeTime) }
        sendWakeScreenOn()
    }

    private val clearWakeScreenRunnable = Runnable {
        wakeScreenOff()
    }

    private fun wakeScreenOff() {
        wakeScreenHandler.removeCallbacks(clearWakeScreenRunnable)
        screenWakeLock?.let { releaseWakeLock(it) }
        sendWakeScreenOff()
    }

    private fun changeScreenBrightness(brightness: Int) {
        if (configuration.screenBrightness != brightness && configuration.useScreenBrightness) {
            screenUtils.updateScreenBrightness(brightness)
            sendScreenBrightnessChange()
        }
    }

    private fun evalJavascript(js: String) {
        val intent = Intent(BROADCAST_ACTION_JS_EXEC)
        intent.putExtra(BROADCAST_ACTION_JS_EXEC, js)
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(intent)
    }

    private fun reloadPage() {
        val intent = Intent(BROADCAST_ACTION_RELOAD_PAGE)
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(intent)
    }

    private fun openSettings() {
        val intent = Intent(BROADCAST_ACTION_OPEN_SETTINGS)
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(intent)
    }

    private fun clearBrowserCache() {
        val intent = Intent(BROADCAST_ACTION_CLEAR_BROWSER_CACHE)
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(intent)
    }

    private fun publishMotionDetected() {
        val delay = (configuration.motionResetTime * 1000).toLong()
        if (!motionDetected) {
            val data = JSONObject()
            try {
                data.put(VALUE, true)
            } catch (ex: JSONException) {
                ex.printStackTrace()
            }
            motionDetected = true
            publishCommand(COMMAND_SENSOR_MOTION, data)
            motionClearHandler.postDelayed({ clearMotionDetected() }, delay)
        }
    }

    private fun publishApplicationState(delay: Int = 300) {
        if (!appStatePublished) {
            appStatePublished = true
            publishCommand(COMMAND_STATE, state)
            appStateClearHandler.postDelayed({ clearPublishApplicationState() }, delay.toLong())
        }
    }

    private fun clearPublishApplicationState() {
        appStatePublished = false
    }

    private fun publishFaceDetected() {
        if (!faceDetected) {
            val data = JSONObject()
            try {
                data.put(VALUE, true)
            } catch (ex: JSONException) {
                ex.printStackTrace()
            }
            faceDetected = true
            publishCommand(COMMAND_SENSOR_FACE, data)

        }
        faceClearHandler.removeCallbacksAndMessages(null)
        faceClearHandler.postDelayed({ clearFaceDetected() }, 3000)
    }

    private fun getDeviceDiscoveryDef(): JSONObject {
        val deviceJson = JSONObject()
        deviceJson.put("identifiers", listOf("wallpanel_${configuration.mqttClientId}"))
        deviceJson.put("name", configuration.mqttDiscoveryDeviceName)
        deviceJson.put("manufacturer", Build.MANUFACTURER.toString().lowercase().capitalize())
        deviceJson.put("model", Build.MODEL)
        return deviceJson
    }

    private fun getSensorDiscoveryDef(displayName: String, stateTopic: String, deviceClass: String?, unit: String?, sensorId: String): JSONObject {
        val discoveryDef = JSONObject()
        if (configuration.mqttLegacyDiscoveryEntities) {
            discoveryDef.put("name", "${configuration.mqttDiscoveryDeviceName} $displayName")
        } else {
            discoveryDef.put("name", displayName)
        }
        val originDef = JSONObject()
        var version = ""
        try {
            val pInfo: PackageInfo =
                applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0)
            version = pInfo.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }
        originDef.put("name", "WallPanel")
        originDef.put("sw", version)
        originDef.put("url", "https://wallpanel.xyz")
        discoveryDef.put("origin", originDef)
        discoveryDef.put("state_topic", "${configuration.mqttBaseTopic}${stateTopic}")
        if (unit != null) {
            discoveryDef.put("unit_of_measurement", unit)
        }
        discoveryDef.put("value_template", "{{ value_json.value | float }}")
        if (deviceClass != null) {
            discoveryDef.put("device_class", deviceClass)
        }
        discoveryDef.put("unique_id", "wallpanel_${configuration.mqttClientId}_${sensorId}")
        discoveryDef.put("device", getDeviceDiscoveryDef())
        discoveryDef.put("availability_topic", "${configuration.mqttBaseTopic}connection")

        return discoveryDef
    }

    private fun getBinarySensorDiscoveryDef(displayName: String, stateTopic: String, fieldName: String, deviceClass: String, sensorId: String): JSONObject {
        val discoveryDef = JSONObject()
        if (configuration.mqttLegacyDiscoveryEntities) {
            discoveryDef.put("name", "${configuration.mqttDiscoveryDeviceName} $displayName")
        } else {
            discoveryDef.put("name", displayName)
        }
        val originDef = JSONObject()
        var version = ""
        try {
            val pInfo: PackageInfo =
                applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0)
            version = pInfo.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }
        originDef.put("name", "WallPanel")
        originDef.put("sw", version)
        originDef.put("url", "https://wallpanel.xyz")
        discoveryDef.put("origin", originDef)
        discoveryDef.put("state_topic", "${configuration.mqttBaseTopic}${stateTopic}")
        discoveryDef.put("payload_on", true)
        discoveryDef.put("payload_off", false)
        discoveryDef.put("value_template", "{{ value_json.${fieldName} }}")
        discoveryDef.put("device_class", deviceClass)
        discoveryDef.put("unique_id", "wallpanel_${configuration.mqttClientId}_${sensorId}")
        discoveryDef.put("device", getDeviceDiscoveryDef())
        discoveryDef.put("availability_topic", "${configuration.mqttBaseTopic}connection")

        return discoveryDef
    }

    private fun publishDiscovery() {
        if (configuration.sensorsEnabled) {
            val batteryDiscovery = getSensorDiscoveryDef(getString(R.string.mqtt_sensor_battery_level), "sensor/battery", "battery", "%", "battery")
            publishMessage("${configuration.mqttDiscoveryTopic}/sensor/${configuration.mqttClientId}/battery/config", batteryDiscovery.toString(), true)
            val usbPluggedDiscovery = getBinarySensorDiscoveryDef(getString(R.string.mqtt_sensor_usb_plugged), "sensor/battery", "usbPlugged", "power", "usbPlugged")
            publishMessage("${configuration.mqttDiscoveryTopic}/binary_sensor/${configuration.mqttClientId}/usbPlugged/config", usbPluggedDiscovery.toString(), true)
            val acPluggedDiscovery = getBinarySensorDiscoveryDef(getString(R.string.mqtt_sensor_ac_plugged), "sensor/battery", "acPlugged", "power", "acPlugged")
            publishMessage("${configuration.mqttDiscoveryTopic}/binary_sensor/${configuration.mqttClientId}/acPlugged/config", acPluggedDiscovery.toString(), true)
            val chargeDiscovery = getBinarySensorDiscoveryDef(getString(R.string.mqtt_sensor_charging), "sensor/battery", "charging", "battery_charging", "charging")
            publishMessage("${configuration.mqttDiscoveryTopic}/binary_sensor/${configuration.mqttClientId}/charging/config", chargeDiscovery.toString(), true)
            val sensors = sensorReader.getSensors()
            for (sensor in sensors) {
                if (sensor.sensorType != null) {
                    val sensorDiscoveryDef = getSensorDiscoveryDef(sensor.displayName!!, "sensor/${sensor.sensorType!!}", sensor.deviceClass, sensor.unit, sensor.sensorType!!)
                    publishMessage("${configuration.mqttDiscoveryTopic}/sensor/${configuration.mqttClientId}/${sensor.sensorType!!}/config", sensorDiscoveryDef.toString(), true)
                }
            }

        } else {
            publishMessage("${configuration.mqttDiscoveryTopic}/sensor/${configuration.mqttClientId}/battery/config", "", false)
            publishMessage("${configuration.mqttDiscoveryTopic}/binary_sensor/${configuration.mqttClientId}/usbPlugged/config", "", false)
            publishMessage("${configuration.mqttDiscoveryTopic}/binary_sensor/${configuration.mqttClientId}/acPlugged/config", "", false)
            publishMessage("${configuration.mqttDiscoveryTopic}/binary_sensor/${configuration.mqttClientId}/charging/config", "", false)
            val sensors = sensorReader.getSensors()
            for (sensor in sensors) {
                if (sensor.sensorType != null) {
                    publishMessage("${configuration.mqttDiscoveryTopic}/sensor/${configuration.mqttClientId}/${sensor.sensorType!!}/config", "", false)
                }
            }
        }

        if (configuration.cameraFaceEnabled && configuration.cameraEnabled) {
            val faceDiscovery = getBinarySensorDiscoveryDef(getString(R.string.mqtt_sensor_face_detected), COMMAND_SENSOR_FACE, "value", "occupancy", "face")
            publishMessage("${configuration.mqttDiscoveryTopic}/binary_sensor/${configuration.mqttClientId}/face/config", faceDiscovery.toString(), true)
        } else {
            publishMessage("${configuration.mqttDiscoveryTopic}/binary_sensor/${configuration.mqttClientId}/face/config", "", false)
        }

        if (configuration.cameraMotionEnabled && configuration.cameraEnabled) {
            val motionDiscovery = getBinarySensorDiscoveryDef(getString(R.string.mqtt_sensor_motion_detected), COMMAND_SENSOR_MOTION, "value", "motion", "motion")
            publishMessage("${configuration.mqttDiscoveryTopic}/binary_sensor/${configuration.mqttClientId}/motion/config", motionDiscovery.toString(), true)
        } else {
            publishMessage("${configuration.mqttDiscoveryTopic}/binary_sensor/${configuration.mqttClientId}/motion/config", "", false)
        }

        if (configuration.cameraQRCodeEnabled && configuration.cameraEnabled) {
            val qrDiscovery = JSONObject()
            qrDiscovery.put("topic", "${configuration.mqttBaseTopic}${COMMAND_SENSOR_QR_CODE}")
            qrDiscovery.put("value_template", "{{ value_json.value }}")
            qrDiscovery.put("device", getDeviceDiscoveryDef())
            publishMessage("${configuration.mqttDiscoveryTopic}/tag/${configuration.mqttClientId}/qr/config", qrDiscovery.toString(), true)
        } else {
            publishMessage("${configuration.mqttDiscoveryTopic}/tag/${configuration.mqttClientId}/qr/config", "", false)
        }
    }

    private fun clearMotionDetected() {
        Timber.d("Clearing motion detected status")
        if (motionDetected) {
            motionDetected = false
            val data = JSONObject()
            try {
                data.put(VALUE, false)
            } catch (ex: JSONException) {
                ex.printStackTrace()
            }
            publishCommand(COMMAND_SENSOR_MOTION, data)
        }
    }

    private fun clearFaceDetected() {
        if (faceDetected) {
            Timber.d("Clearing face detected status")
            val data = JSONObject()
            try {
                data.put(VALUE, false)
            } catch (ex: JSONException) {
                ex.printStackTrace()
            }
            faceDetected = false
            publishCommand(COMMAND_SENSOR_FACE, data)
        }
    }

    private fun publishQrCode(data: String) {
        if (!qrCodeRead) {
            Timber.d("publishQrCode")
            val jdata = JSONObject()
            try {
                jdata.put(VALUE, data)
            } catch (ex: JSONException) {
                ex.printStackTrace()
            }
            qrCodeRead = true
            sendToastMessage(getString(R.string.toast_qr_code_read))
            publishCommand(COMMAND_SENSOR_QR_CODE, jdata)
            qrCodeClearHandler.postDelayed({ clearQrCodeRead() }, 5000)
        }
    }

    private fun clearQrCodeRead() {
        if (qrCodeRead) {
            qrCodeRead = false
        }
    }

    private fun sendAlertMessage(message: String) {
        Timber.d("sendAlertMessage")
        val intent = Intent(BROADCAST_ALERT_MESSAGE)
        intent.putExtra(BROADCAST_ALERT_MESSAGE, message)
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(intent)
    }

    private fun clearAlertMessage() {
        Timber.d("clearAlertMessage")
        val intent = Intent(BROADCAST_CLEAR_ALERT_MESSAGE)
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(intent)
    }

    private fun sendWakeScreenOn() {
        Timber.d("sendWakeScreen")
        screenWakeLock?.let { acquireWakeLock(it) }
        val intent = Intent(BROADCAST_SCREEN_WAKE_ON)
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(intent)
    }

    private fun sendWakeScreenOff() {
        Timber.d("sendWakeScreenOff")
        screenWakeLock?.let { releaseWakeLock(it) }
        val intent = Intent(BROADCAST_SCREEN_WAKE_OFF)
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(intent)
    }

    private fun sendScreenBrightnessChange() {
        Timber.d("sendWakeScreen")
        val intent = Intent(BROADCAST_SCREEN_BRIGHTNESS_CHANGE)
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(intent)
    }

    private fun sendToastMessage(message: String) {
        Timber.d("sendToastMessage")
        val intent = Intent(BROADCAST_TOAST_MESSAGE)
        intent.putExtra(BROADCAST_TOAST_MESSAGE, message)
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(intent)
    }

    private fun sendServiceStarted() {
        Timber.d("clearAlertMessage")
        val intent = Intent(BROADCAST_SERVICE_STARTED)
        val bm = LocalBroadcastManager.getInstance(applicationContext)
        bm.sendBroadcast(intent)
    }

    // TODO don't change the user settings when receiving command
    private val mBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (BROADCAST_EVENT_URL_CHANGE == intent.action) {
                appLaunchUrl = intent.getStringExtra(BROADCAST_EVENT_URL_CHANGE)
                if (appLaunchUrl != configuration.appLaunchUrl) {
                    Timber.i("Url changed to $appLaunchUrl")
                    publishApplicationState()
                }
            } else if (Intent.ACTION_SCREEN_OFF == intent.action ||
                    intent.action == Intent.ACTION_SCREEN_ON ||
                    intent.action == Intent.ACTION_USER_PRESENT) {
                Timber.i("Screen state changed")
                publishApplicationState()
            } else if (BROADCAST_EVENT_SCREEN_TOUCH == intent.action) {
                Timber.i("Screen touched")
                //TODO: This overrides URL Change. Do we really need this?
                //publishApplicationState()
            }
        }
    }

    private val sensorCallback = object : SensorCallback {
        override fun publishSensorData(sensorName: String, sensorData: JSONObject) {
            publishApplicationState()
            publishCommand(COMMAND_SENSOR + sensorName, sensorData)
        }
    }

    private val cameraDetectorCallback = object : CameraCallback {
        override fun onDetectorError() {
            if (configuration.cameraFaceEnabled || configuration.cameraQRCodeEnabled) {
                sendToastMessage(getString(R.string.error_missing_vision_lib))
            }
        }

        override fun onCameraError() {
            sendToastMessage(getString(R.string.toast_camera_source_error))
        }

        override fun onMotionDetected() {
            Timber.i("Motion detected")
            if (configuration.cameraMotionWake) {
                configurePowerOptions()
                wakeScreen()
            }
            publishMotionDetected()
        }

        override fun onTooDark() {
            // Timber.i("Too dark for motion detection")
        }

        override fun onFaceDetected() {
            Timber.i("Face detected")
            Timber.d("configuration.cameraMotionBright ${configuration.cameraMotionBright}")
            if (configuration.cameraFaceWake) {
                configurePowerOptions()
                wakeScreen() // temp turn on screen
            }
            publishFaceDetected()
        }

        override fun onQRCode(data: String) {
            Timber.i("QR Code Received: $data")
            publishQrCode(data)
        }
    }

    companion object {
        const val ONGOING_NOTIFICATION_ID = 1
        const val BROADCAST_EVENT_URL_CHANGE = "BROADCAST_EVENT_URL_CHANGE"
        const val BROADCAST_EVENT_SCREEN_TOUCH = "BROADCAST_EVENT_SCREEN_TOUCH"
        const val SCREEN_WAKE_TIME = 30000L
        const val BROADCAST_ALERT_MESSAGE = "BROADCAST_ALERT_MESSAGE"
        const val BROADCAST_CLEAR_ALERT_MESSAGE = "BROADCAST_CLEAR_ALERT_MESSAGE"
        const val BROADCAST_TOAST_MESSAGE = "BROADCAST_TOAST_MESSAGE"
        const val BROADCAST_SERVICE_STARTED = "BROADCAST_SERVICE_STARTED"
        const val BROADCAST_SCREEN_WAKE = "BROADCAST_SCREEN_WAKE"
        const val BROADCAST_SCREEN_WAKE_ON = "BROADCAST_SCREEN_WAKE_ON"
        const val BROADCAST_SCREEN_WAKE_OFF = "BROADCAST_SCREEN_WAKE_OFF"
        const val BROADCAST_SCREEN_BRIGHTNESS_CHANGE = "BROADCAST_SCREEN_BRIGHTNESS_CHANGE"
        const val BROADCAST_CONNTED = "BROADCAST_SCREEN_BRIGHTNESS_CHANGE"
    }
}