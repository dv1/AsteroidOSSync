package org.asteroidos.sync.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

// This BroadcastReceiver is automatically spawned whenever an external message is received
// as a custom intent from another app through a broadcast. Once it receives that intent,
// it forwards it to an internal service. This redirection is done because that way, it
// becomes possible to take advantage of that auto-spawning mechanism, which tends to work
// more reliably than manually registering a broadcast receiver that was previously created
// manually. Instead, this "relay" broadcast receiver is auto-created, while the actual
// external app service is started manually inside SynchronizationService.

private const val ACTION_EXTERNAL_APP_MESSAGE_PUSH =
    "org.asteroidos.sync.connectivity.extappmessage.PUSH"

class ExternalAppMessageReceiver : BroadcastReceiver() {
    companion object {
        private val TAG = ExternalAppMessageReceiver::class.simpleName
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_EXTERNAL_APP_MESSAGE_PUSH -> {
                Log.d(
                    TAG,
                    "Got external app message; forwarding to internal ext app message service"
                )
                context.sendBroadcast(
                    Intent("org.asteroidos.sync.FORWARD_EXT_APP_MESSAGE")
                        .putExtras(intent)
                )
            }
            else -> Unit
        }
    }
}