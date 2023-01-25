package org.asteroidos.sync.connectivity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import org.asteroidos.sync.asteroid.IAsteroidDevice
import org.asteroidos.sync.utils.AsteroidUUIDS
import java.util.HashMap
import java.util.UUID
import kotlin.math.min

// Intent action that is sent internally by the ExternalAppMessageReceiver.
private const val ACTION_FORWARD_EXT_APP_MESSAGE =
    "org.asteroidos.sync.FORWARD_EXT_APP_MESSAGE"

class ExternalAppMessageService(
    private val ctx: Context,
    private val device: IAsteroidDevice
) : IConnectivityService {
    companion object {
        private val TAG = ExternalAppMessageService::class.simpleName
    }

    private val receiver: BroadcastReceiver

    init {
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_FORWARD_EXT_APP_MESSAGE -> {
                        // We got the external app message forwarded from the
                        // ExternalAppMessageReceiver. Now forward it further to the watch over BLE.
                        //
                        // The external message format goes as follows:
                        //
                        // First comes the sender string (without the terminating null character).
                        // Next comes a newline character to specify the end of the sender string.
                        // The destination string follows, again without the terminating null
                        // character, and is also followed by a newline character. After that
                        // character, the actual payload follows, and this can be any binary data.
                        //
                        // The use of newlines as delimiters is the reason why the sender and
                        // destination strings must be single-line strings that only contain
                        // printable characters.

                        val sender = intent.getStringExtra("sender") ?: return
                        val destination = intent.getStringExtra("destination") ?: return
                        val payload = intent.getByteArrayExtra("payload") ?: return

                        Log.d(
                            TAG,
                            "Got external app message; " +
                                    "sender: $sender; body has ${payload.size} byte(s)"
                        )

                        // Messages must contain at least 1 byte. See the implementation
                        // of pushMessage() for the reason why.
                        if (payload.isEmpty()) {
                            Log.w(TAG, "Message payload is empty; ignoring invalid message")
                            return
                        }

                        val message =
                            "$sender\n$destination\n".toByteArray(Charsets.UTF_8) + payload
                        pushMessage(message)
                    }
                    else -> Unit
                }
            }
        };
    }

    override fun sync() {
        val filter = IntentFilter(ACTION_FORWARD_EXT_APP_MESSAGE)
        ctx.registerReceiver(receiver, filter)
    }

    override fun unsync() {
        try {
            ctx.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // Ignore IllegalArgumentException that is thrown
            // when the receiver is already unregistered
        }
    }

    override fun getCharacteristicUUIDs() = HashMap<UUID, IConnectivityService.Direction>().apply {
        put(
            AsteroidUUIDS.EXTERNAL_APP_MESSAGE_PUSH_MSG_CHAR,
            IConnectivityService.Direction.TO_WATCH
        )
    }

    override fun getServiceUUID(): UUID = AsteroidUUIDS.EXTERNAL_APP_MESSAGE_SERVICE_UUID

    private var messageCounter = 0

    private fun pushMessage(message: ByteArray) {
        // We use a simple custom protocol here to transmit payloads that are potentially
        // larger than the configured BLE MTU allows. It splits messages into chunks and adds
        // metadata to let the receiver know how to stitch the chunks back together.
        // The payloads we send over GATT are structured as follows:
        //
        // First comes a byte that is the "message counter". This counter is the same value for
        // all GATT chunk transmissions. That way, the receiver knows when received chunks belong
        // to the same message and when a new message starts. It also solves the problem of partial
        // messages: If for some reason a message is only partially transmitted before another one
        // gets sent, the receiver will see chunks come in with a different counter value. When
        // this happens, the receiver knows that it needs to discard any previously received chunk
        // that used the old counter value. The counter value is incremented here after fully
        // sending the message. When the counter is at 255 and is incremented, it wraps around
        // back to 0.
        //
        // Next comes a 16 bit little endian integer that contains the chunk's message offset.
        // It specifies where within the message the chunk got its data from.
        //
        // This is followed by another 16 bit little endian integer that is the total message size
        // minus 1. This means that (a) the maximum message size and (b) messages must have at
        // least 1 byte.
        //
        // After that comes the actual chunk payload.

        // Offset inside the message byte array to know where
        // the data for the next chunk shall come from.
        var messageOffset = 0

        require(message.isNotEmpty())
        // NOTE: This is _only_ used as the value that gets stored in the chunk. This is not
        // used for actually
        val messageSizeValue = message.size - 1

        try {
            while (messageOffset < message.size) {
                // Chunks can contain a maximum of (maxGattTransmissionSize - 5) bytes.
                // Larger transmissions will be transmitted in multiple chunks.
                val chunkSize = min(
                    device.maxGattTransmissionSize - 5,
                    message.size - messageOffset
                )

                // Extract the chunk out of the message byte array.
                val chunk = byteArrayOf(
                    messageCounter.toByte(),
                    ((messageOffset ushr 0) and 0xFF).toByte(),
                    ((messageOffset ushr 8) and 0xFF).toByte(),
                    ((messageSizeValue ushr 0) and 0xFF).toByte(),
                    ((messageSizeValue ushr 8) and 0xFF).toByte(),
                ) + message.copyOfRange(messageOffset, messageOffset + chunkSize)

                // Finally we send the chunk.
                device.send(AsteroidUUIDS.EXTERNAL_APP_MESSAGE_PUSH_MSG_CHAR, chunk, this)

                messageOffset += chunkSize

                Log.v(TAG, "Sent $messageOffset out of ${message.size} byte(s)")
            }
        } finally {
            // Increment in the finally block in case message transmission
            // is interrupted by an exception.
            messageCounter = (messageCounter + 1) and 255
        }
    }
}