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
import androidx.core.content.ContextCompat
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
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
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
        current = this
        createChannel()
        // Explicit types (API 29+): connectedDevice for the host link,
        // mediaPlayback for host TTS on the speaker. Audio focus is NOT taken
        // here — holding it for the service lifetime paused the user's music
        // whenever the agent ran; SpeakerSinkImpl takes transient focus per
        // utterance instead.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAgent()
                stopSelf()
                return START_NOT_STICKY
            }
            // Active profile changed (pairing, host switch, edit, delete):
            // the running agent is bound to the old credentials.
            ACTION_RESTART -> stopAgent()
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
            deviceId = profile.deviceId.ifBlank { DeviceIdentity.deviceId(this) },
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
        val speaker = SpeakerSinkImpl(this)
        a.setSpeaker(speaker)
        sink = speaker
        speaker.attachAgent(a)
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
                EventLog.push("agent", if (connected) "connected" else "reconnecting")
                connectionLine = if (connected) {
                    getString(R.string.service_connected_to, hostLabel)
                } else {
                    getString(R.string.service_reconnecting)
                }
                updateNotification()
                dev.vynkor.agent.WidgetSync.pushAll(this@AgentService)
            }

            override fun onStatus(status: ConnectionStatus) {
                when (status) {
                    is ConnectionStatus.Connecting ->
                        AgentHolder.hostStatus.value = HostStatus.Connecting
                    is ConnectionStatus.ReachabilityFailed ->
                        // Meaningful only while nothing is live; otherwise the
                        // UI already shows Connected/Reconnecting.
                        if (!AgentHolder.connectionState.value) {
                            val reason = unreachableReason(status.reason)
                            AgentHolder.hostStatus.value = HostStatus.Unreachable(reason)
                            EventLog.push("agent", "unreachable: ${status.reason}")
                            connectionLine = getString(R.string.service_unreachable, reason)
                            updateNotification()
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

    /**
     * Android 17 drops LAN traffic of apps without the local-network grant
     * without an error — the connect merely times out. Name the real cause
     * instead of a bare timeout.
     */
    private fun unreachableReason(raw: String): String =
        if (!AgentPermissions.hasLocalNetwork(this)) {
            getString(R.string.local_network_permission_missing)
        } else {
            raw
        }

    private fun stopAgent() {
        val stopping = agent
        // Captured now: startAgent() (RESTART) installs a fresh sink before
        // the cleanup below runs, and must not have it released.
        val stoppingSink = sink
        sink = null
        agent = null
        batteryEvents?.stop()
        batteryEvents = null
        AgentHolder.agent = null
        AgentHolder.micSession = null
        AgentHolder.connectionState.value = false
        AgentHolder.micStreaming.value = false
        AgentHolder.hostStatus.value = HostStatus.Idle
        dev.vynkor.agent.WidgetSync.pushAll(this)
        // Audio teardown involves bounded joins (MicCapture.stop) and Rust
        // runtime shutdown — keep both off the main thread (R-04).
        cleanupExecutor.execute {
            try {
                micSession.stopSession("service stopped")
                stoppingSink?.release()
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
        current = null
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
        // "Hidden" mode target: IMPORTANCE_MIN collapses the notice to the
        // bottom section with no heads-up, sound or lock-screen presence.
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_QUIET,
                getString(R.string.service_channel_quiet_name),
                NotificationManager.IMPORTANCE_MIN,
            )
        )
    }

    /**
     * Detail level follows AppPrefs.notifMode: detailed = status line +
     * actions; minimal = title only on the regular channel; hidden = title
     * only on the IMPORTANCE_MIN channel (as invisible as a foreground
     * service legally gets).
     */
    private fun buildNotification(): Notification {
        val mode = dev.vynkor.agent.agent.AppPrefs.notifMode(this)
        val detailed = mode == dev.vynkor.agent.agent.AppPrefs.NOTIF_DETAILED
        val channel = if (mode == dev.vynkor.agent.agent.AppPrefs.NOTIF_HIDDEN) CHANNEL_QUIET else CHANNEL_ID
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, AgentService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, ChatActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val newChatIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, ChatActivity::class.java)
                .putExtra(ChatActivity.EXTRA_NEW_CHAT, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, channel)
            .setContentTitle(getString(R.string.service_notification_title))
            .setSmallIcon(R.drawable.ic_stat_vynkor)
            .setColor(androidx.core.content.ContextCompat.getColor(this, R.color.primary))
            .setContentIntent(openIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setLocalOnly(true)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
        if (detailed) {
            builder.setContentText(connectionLine ?: getString(R.string.service_notification_text))
                .addAction(0, getString(R.string.notification_new_chat), newChatIntent)
                .addAction(0, getString(R.string.disconnect_button), stopIntent)
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "AgentService"
        private const val CHANNEL_ID = "vynkor_agent"
        private const val CHANNEL_QUIET = "vynkor_agent_quiet"

        @Volatile
        private var current: AgentService? = null

        /** Re-renders the running notification after a pref change. */
        fun refreshNotification(context: Context) {
            current?.updateNotification()
        }
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.vynkor.agent.STOP"
        private const val ACTION_RESTART = "dev.vynkor.agent.RESTART"

        fun start(context: Context) {
            EventLog.push("service", "start requested")
            ContextCompat.startForegroundService(context, Intent(context, AgentService::class.java))
        }

        fun stop(context: Context) {
            EventLog.push("service", "stop")
            context.startService(Intent(context, AgentService::class.java).setAction(ACTION_STOP))
        }

        /**
         * Re-binds a running agent to the current active profile (new pairing,
         * host switch, edited or deleted profile). No-op while the service is
         * down — the next start picks the active profile up anyway.
         */
        fun restartIfRunning(context: Context) {
            if (current == null) return
            EventLog.push("service", "restart requested")
            ContextCompat.startForegroundService(
                context,
                Intent(context, AgentService::class.java).setAction(ACTION_RESTART),
            )
        }
    }
}
