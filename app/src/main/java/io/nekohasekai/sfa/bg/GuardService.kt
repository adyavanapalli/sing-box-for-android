package io.nekohasekai.sfa.bg

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
import io.nekohasekai.sfa.constant.Action

/**
 * detour: starts SFA's service again after Android kills SFA's main process.
 *
 * Android does not restart a VPN service after its process dies, not even with START_STICKY.
 * When the process dies, the kernel closes the tunnel. The VPN code of Android then drops its
 * connection to the dead service, and after that, Android no longer counts the service as part
 * of the dead process. So when Android handles the death, it finds no service to restart.
 *
 * The guard runs in its own process (":guard"). While the service runs, the main process starts
 * and binds the guard. The guard watches the binder of the service. When that binder dies, the
 * main process died, and the guard starts the service again. A stop by the user does not end the
 * main process, and the main process stops the guard before it stops the service.
 *
 * The guard is sticky. If Android kills both processes, Android restarts the guard, and the guard
 * starts the service again.
 */
class GuardService : Service() {
    companion object {
        private const val TAG = "GuardService"
        private const val EXTRA_SERVICE = "service"
        private const val PREFS = "guard"
        private const val RESTART_DELAY_MS = 1_000L
        private const val CONNECT_TIMEOUT_MS = 3_000L
        private const val RESTART_WINDOW_MS = 10 * 60_000L
        private const val RESTART_LIMIT = 3
        private const val CHANNEL = "guard"
        private const val NOTIFICATION_ID = 0x4755

        // The main process keeps this binding while the service runs. The guard then has the
        // priority of the main process.
        private val mainConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {}

            override fun onServiceDisconnected(name: ComponentName) {}
        }
        private var mainBound = false

        /** The main process calls this on its main thread when the service has started. */
        fun watch(context: Context, serviceClass: Class<out Service>) {
            val intent = Intent(context, GuardService::class.java).putExtra(EXTRA_SERVICE, serviceClass.name)
            try {
                context.startService(intent)
            } catch (e: RuntimeException) {
                Log.w(TAG, "cannot start the guard", e)
            }
            if (!mainBound) {
                mainBound = context.bindService(Intent(context, GuardService::class.java), mainConnection, Context.BIND_AUTO_CREATE)
            }
        }

        /** The main process calls this on its main thread before it stops the service. */
        fun unwatch(context: Context) {
            if (mainBound) {
                context.unbindService(mainConnection)
                mainBound = false
            }
            context.stopService(Intent(context, GuardService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val restarts = ArrayDeque<Long>()
    private var serviceClass: String? = null
    private var bound = false
    private var watched: IBinder? = null

    private val death = IBinder.DeathRecipient {
        handler.post { onMainProcessDied() }
    }

    private val startIfNotRunning = Runnable {
        if (watched == null) restartService("the guard restarted, and the service does not run")
    }

    // Binds without BIND_AUTO_CREATE: the guard watches the service but does not keep it alive.
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            handler.removeCallbacks(startIfNotRunning)
            unlinkWatched()
            try {
                binder.linkToDeath(death, 0)
                watched = binder
                Log.i(TAG, "watching ${name.className}")
            } catch (e: RemoteException) {
                onMainProcessDied()
            }
        }

        // A service that stops in a live process arrives here. A dead process arrives at the death recipient.
        override fun onServiceDisconnected(name: ComponentName) {
            unlinkWatched()
        }

        override fun onBindingDied(name: ComponentName) {
            unbindTarget()
            bindTarget()
        }
    }

    override fun onBind(intent: Intent): IBinder = Binder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val name = intent?.getStringExtra(EXTRA_SERVICE)
        if (name != null) {
            serviceClass = name
            prefs.edit().putString(EXTRA_SERVICE, name).apply()
            bindTarget()
        } else {
            // Android restarted the guard after it killed the guard's process. If it killed the
            // main process too, the service does not run, and nothing connects in time.
            serviceClass = prefs.getString(EXTRA_SERVICE, null) ?: VPNService::class.java.name
            bindTarget()
            handler.postDelayed(startIfNotRunning, CONNECT_TIMEOUT_MS)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unlinkWatched()
        unbindTarget()
        super.onDestroy()
    }

    private fun onMainProcessDied() {
        unlinkWatched()
        Log.w(TAG, "the main process died")
        handler.postDelayed({ restartService("the main process died") }, RESTART_DELAY_MS)
    }

    private fun restartService(reason: String) {
        val name = serviceClass ?: return
        val now = SystemClock.elapsedRealtime()
        while (restarts.isNotEmpty() && now - restarts.first() > RESTART_WINDOW_MS) restarts.removeFirst()
        if (restarts.size >= RESTART_LIMIT) {
            Log.e(TAG, "$reason, but the guard restarted the service $RESTART_LIMIT times in 10 minutes")
            alert("SFA stopped $RESTART_LIMIT times in 10 minutes. The guard does not start it again. Tap to open SFA.")
            return
        }
        restarts.addLast(now)
        try {
            ContextCompat.startForegroundService(this, Intent().setClassName(this, name))
            Log.i(TAG, "$reason: started ${name.substringAfterLast('.')} again")
        } catch (e: RuntimeException) {
            Log.e(TAG, "$reason: cannot start ${name.substringAfterLast('.')}", e)
            alert("The VPN is down, and SFA could not start it again: ${e.message}. Tap to open SFA.")
        }
    }

    private fun bindTarget() {
        if (bound) return
        val name = serviceClass ?: return
        bound = bindService(Intent().setClassName(this, name).setAction(Action.SERVICE), connection, 0)
    }

    private fun unbindTarget() {
        if (!bound) return
        unbindService(connection)
        bound = false
    }

    private fun unlinkWatched() {
        watched?.unlinkToDeath(death, 0)
        watched = null
    }

    private fun alert(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Guard alerts", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
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
}
