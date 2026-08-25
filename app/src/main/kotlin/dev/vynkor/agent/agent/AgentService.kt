package dev.vynkor.agent.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.vynkor.agent.Agent
import dev.vynkor.agent.AgentConfig
import dev.vynkor.agent.AgentObserver
import dev.vynkor.agent.ChatActivity
import dev.vynkor.agent.ConnectionStatus
import dev.vynkor.agent.R
import dev.vynkor.agent.agent.HostStatus
import dev.vynkor.agent.caps.BatteryProviderImpl
import dev.vynkor.agent.caps.BluetoothProviderImpl
import dev.vynkor.agent.caps.BrightnessProviderImpl
import dev.vynkor.agent.caps.CalendarProviderImpl
import dev.vynkor.agent.caps.CallsProviderImpl
import dev.vynkor.agent.caps.ClipboardProviderImpl
import dev.vynkor.agent.caps.ContactsProviderImpl
import dev.vynkor.agent.caps.DeviceInfoProviderImpl
import dev.vynkor.agent.caps.DndProviderImpl
import dev.vynkor.agent.caps.FlashlightProviderImpl
import dev.vynkor.agent.caps.LauncherProviderImpl
import dev.vynkor.agent.caps.LocationProviderImpl
import dev.vynkor.agent.caps.RingerProviderImpl
import dev.vynkor.agent.caps.SmsProviderImpl
import dev.vynkor.agent.caps.SpeakerSinkImpl
import dev.vynkor.agent.caps.WifiProviderImpl
import java.util.concurrent.Executors

/** Foreground service holding the agent connection. One per active host. */
class AgentService : Service() {
    private var agent: Agent? = null

    @Volatile
    private var sink: SpeakerSinkImpl? = null
    private val micCapture = MicCapture()
    private val micSession by lazy { MicSessionController(micCapture) }
    private var batteryEvents: BatteryEventSource? = null
    private val cleanupExecutor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "vynkor-agent-cleanup") }

    /** Live line shown in the foreground notification (updated by observer). */
    @Volatile
    private var connectionLine: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAgent()
            stopSelf()
            return START_NOT_STICKY
        }
        startAgent()
        return START_STICKY
    }

    private fun startAgent() {
        if (agent != null) return
        val profile = ProfileStore.active(this)
        if (profile == null || profile.hostUrl.isBlank()) {
            Log.w(TAG, "no host configured, stopping")
            stopSelf()
            return
        }
        val config = AgentConfig(
            hostUrl = profile.hostUrl,
            jwtToken = profile.jwtToken,
            deviceSecret = profile.deviceSecret,
            certPem = profile.certPem,
            deviceId = profile.deviceId,
            capabilities = listOf(
                "geo", "battery", "notifications", "clipboard", "contacts", "mic", "speaker", "chat",
                "device", "wifi", "bluetooth", "dnd", "ringer", "brightness",
                "flashlight", "launcher", "sms", "calls", "calendar",
            ),
            osVersion = Build.VERSION.RELEASE,
            arch = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            userId = profile.userId.ifBlank { "default" },
        )
        val a = Agent(config)
        a.setBattery(BatteryProviderImpl(this))
        a.setLocation(LocationProviderImpl(this))
        a.setClipboard(ClipboardProviderImpl(this))
        a.setContacts(ContactsProviderImpl(this))
        val speaker = SpeakerSinkImpl()
        a.setSpeaker(speaker)
        sink = speaker
        a.setDeviceInfo(DeviceInfoProviderImpl(this))
        a.setWifi(WifiProviderImpl(this))
        a.setBluetooth(BluetoothProviderImpl(this))
        a.setDnd(DndProviderImpl(this))
        a.setRinger(RingerProviderImpl(this))
        a.setBrightness(BrightnessProviderImpl(this))
        a.setFlashlight(FlashlightProviderImpl(this))
        a.setLauncher(LauncherProviderImpl(this))
        a.setSms(SmsProviderImpl(this))
        a.setCalls(CallsProviderImpl(this))
        a.setCalendar(CalendarProviderImpl(this))

        val hostLabel = profile.name.ifBlank {
            profile.hostUrl.substringAfter("://").substringBefore(':')
        }
        connectionLine = getString(R.string.service_connecting)
        a.setObserver(object : AgentObserver {
            override fun onStateChanged(connected: Boolean) {
                AgentHolder.connectionState.value = connected
                AgentHolder.hostStatus.value =
                    if (connected) HostStatus.Connected else HostStatus.Reconnecting
                connectionLine = if (connected) {
                    getString(R.string.service_connected_to, hostLabel)
                } else {
                    getString(R.string.service_reconnecting)
                }
                updateNotification()
            }

            override fun onStatus(status: ConnectionStatus) {
                when (status) {
                    is ConnectionStatus.Connecting ->
                        AgentHolder.hostStatus.value = HostStatus.Connecting
                    is ConnectionStatus.ReachabilityFailed ->
                        // Meaningful only while nothing is live; otherwise the
                        // UI already shows Connected/Reconnecting.
                        if (!AgentHolder.connectionState.value) {
                            AgentHolder.hostStatus.value =
                                HostStatus.Unreachable(status.reason)
                        }
                }
            }
        })
        agent = a
        AgentHolder.agent = a
        AgentHolder.micSession = micSession
        a.start()

        batteryEvents = BatteryEventSource(this) { level, charging ->
            a.pushBatteryStatus(level, charging)
        }.also { it.start() }
        updateNotification()
        // R-01: the host-streamed mic is NOT tied to the service lifecycle.
        // Audio leaves the phone only inside an explicit MicSessionController
        // session (chat UI long-press on the mic button).
    }

    private fun stopAgent() {
        val stopping = agent
        agent = null
        batteryEvents?.stop()
        batteryEvents = null
        AgentHolder.agent = null
        AgentHolder.micSession = null
        AgentHolder.connectionState.value = false
        AgentHolder.micStreaming.value = false
        AgentHolder.hostStatus.value = HostStatus.Idle
        // Audio teardown involves bounded joins (MicCapture.stop) and Rust
        // runtime shutdown — keep both off the main thread (R-04).
        cleanupExecutor.execute {
            try {
                micSession.stopSession("service stopped")
                sink?.release()
                sink = null
                stopping?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "agent cleanup failed", e)
            }
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)?.notify(
            NOTIFICATION_ID,
            buildNotification(),
        )
    }

    override fun onDestroy() {
        stopAgent()
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, getString(R.string.service_channel_name), NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, AgentService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, ChatActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(connectionLine ?: getString(R.string.service_notification_text))
            .setSmallIcon(R.drawable.ic_stat_vynkor)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(0, getString(R.string.disconnect_button), stopIntent)
            .build()
    }

    companion object {
        private const val TAG = "AgentService"
        private const val CHANNEL_ID = "vynkor_agent"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.vynkor.agent.STOP"

        fun start(context: Context) {
            val intent = Intent(context, AgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AgentService::class.java).setAction(ACTION_STOP))
        }
    }
}
