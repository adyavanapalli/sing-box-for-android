package io.nekohasekai.sfa.bg

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.MainActivity

/**
 * detour: starts SFA's service again after Android kills SFA's main process.
 *
 * Android does not restart a VPN service after its process dies, not even with START_STICKY.
 * When the process dies, the kernel closes the tunnel. The VPN code of Android then drops its
 * connection to the dead service, and after that, Android no longer counts the service as part
 * of the dead process. So when Android handles the death, it finds no service to restart.
 *
 * The guard runs in its own process (":guard"). While the service runs, the main process starts
 * and binds the guard, and it sends the guard a token: a Binder object that lives in the main
 * process. When the token dies, the main process died, and the guard starts the service again.
 * A stop by the user does not end the main process, and the main process stops the guard before
 * it stops the service.
 *
 * The guard does not watch a binding to the service itself. After a kill, Android keeps stale
 * binding state for the service, and a binding then does not connect again.
 *
 * The guard is sticky. If Android kills both processes, Android restarts the guard, and the guard
 * starts the service again.
 */
class GuardService : Service() {
    companion object {
        private const val TAG = "GuardService"
        private const val EXTRA_SERVICE = "service"
        private const val EXTRA_TOKEN = "token"
        private const val PREFS = "guard"
        private const val RESTART_DELAY_MS = 1_000L
        private const val RESTART_WINDOW_MS = 10 * 60_000L
        private const val RESTART_LIMIT = 3
        private const val CHANNEL = "guard"
        private const val NOTIFICATION_ID = 0x4755

        // The main process uses these fields on its main thread only.
        private val token = Binder()
        private var boundContext: Context? = null
        private var serviceClass: String? = null

        // While the service runs, the main process binds the guard. The guard then has the priority
        // of the main process. When the guard restarts, the binding connects again, and the main
        // process sends the token again.
        private val mainConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                sendToken()
            }

            override fun onServiceDisconnected(name: ComponentName) {}
        }

        /** The main process calls this on its main thread when the service has started. */
        fun watch(context: Context, service: Class<out Service>) {
            serviceClass = service.name
            if (boundContext == null) {
                if (context.bindService(Intent(context, GuardService::class.java), mainConnection, Context.BIND_AUTO_CREATE)) {
                    boundContext = context
                }
            }
            sendToken()
        }

        /** The main process calls this on its main thread before it stops the service. */
        fun unwatch(context: Context) {
            serviceClass = null
            boundContext?.unbindService(mainConnection)
            boundContext = null
            context.stopService(Intent(context, GuardService::class.java))
        }

        /**
         * Posts "SFA stopped" with a high priority. With lockdown, an unplanned stop leaves the phone
         * without a network, so the user must learn about it even when SFA's screen is closed.
         */
        fun notifyStopped(context: Context, text: String) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "SFA stopped", NotificationManager.IMPORTANCE_HIGH))
            }
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_menu)
                .setContentTitle("SFA stopped")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }

        private fun sendToken() {
            val context = boundContext ?: return
            val service = serviceClass ?: return
            val intent = Intent(context, GuardService::class.java)
                .putExtra(EXTRA_SERVICE, service)
                .putExtras(Bundle().apply { putBinder(EXTRA_TOKEN, token) })
            try {
                context.startService(intent)
            } catch (e: RuntimeException) {
                Log.w(TAG, "cannot send the token to the guard", e)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val restarts = ArrayDeque<Long>()
    private var service: String? = null
    private var watched: IBinder? = null

    private val death = IBinder.DeathRecipient {
        handler.post { onMainProcessDied() }
    }

    override fun onBind(intent: Intent): IBinder = Binder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val token = intent?.extras?.getBinder(EXTRA_TOKEN)
        if (intent != null && token != null) {
            service = intent.getStringExtra(EXTRA_SERVICE)
            prefs.edit().putString(EXTRA_SERVICE, service).apply()
            watch(token)
        } else {
            // Android restarted the guard after it killed the guard's process. If the main process
            // runs, it sends a new token when its binding connects again. If it does not run,
            // Android killed both processes.
            service = prefs.getString(EXTRA_SERVICE, null) ?: VPNService::class.java.name
            if (!mainProcessRuns()) restartService("Android killed both processes")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unwatch()
        super.onDestroy()
    }

    private fun watch(token: IBinder) {
        if (token == watched) return
        unwatch()
        try {
            token.linkToDeath(death, 0)
            watched = token
            Log.i(TAG, "watching the main process")
        } catch (e: RemoteException) {
            onMainProcessDied()
        }
    }

    private fun unwatch() {
        watched?.unlinkToDeath(death, 0)
        watched = null
    }

    private fun mainProcessRuns(): Boolean = getSystemService(ActivityManager::class.java)
        .runningAppProcesses.orEmpty()
        .any { it.processName == packageName }

    private fun onMainProcessDied() {
        unwatch()
        Log.w(TAG, "the main process died")
        handler.postDelayed({ restartService("the main process died") }, RESTART_DELAY_MS)
    }

    private fun restartService(reason: String) {
        val name = service ?: return
        val now = SystemClock.elapsedRealtime()
        while (restarts.isNotEmpty() && now - restarts.first() > RESTART_WINDOW_MS) restarts.removeFirst()
        if (restarts.size >= RESTART_LIMIT) {
            Log.e(TAG, "$reason, but the guard restarted the service $RESTART_LIMIT times in 10 minutes")
            notifyStopped(this, "SFA stopped $RESTART_LIMIT times in 10 minutes. The guard does not start it again. Tap to open SFA.")
            return
        }
        restarts.addLast(now)
        try {
            ContextCompat.startForegroundService(this, Intent().setClassName(this, name))
            Log.i(TAG, "$reason: started ${name.substringAfterLast('.')} again")
        } catch (e: RuntimeException) {
            Log.e(TAG, "$reason: cannot start ${name.substringAfterLast('.')}", e)
            notifyStopped(this, "The VPN is down, and SFA could not start it again: ${e.message}. Tap to open SFA.")
        }
    }
}
