package io.parity.truapi.provider

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.truapi_provider.ChainCloseReason
import uniffi.truapi_provider.ChainLifecycle
import uniffi.truapi_provider.ChainLifecycleListener
import uniffi.truapi_provider.ChainMessageListener
import uniffi.truapi_provider.ChainPhase
import uniffi.truapi_provider.ChainProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "PreviewnetSmoke"
private const val RELAY = "860145753657e73c29b9388ffa0a8aebc643ea87434b4b271b6c3c3cc9e6bf92"
private const val PEOPLE = "55e3e689ecfa9d2fffcf7d309b8011956671493982230bfd0420c683542249e9"

@RunWith(AndroidJUnit4::class)
class PreviewnetSmokeTest {

    @Test
    fun peopleChainAnswersAfterRelayIsReady() {
        val start = SystemClock.elapsedRealtime()
        fun elapsed() = "${(SystemClock.elapsedRealtime() - start) / 1000.0}s"

        val ready = CountDownLatch(1)
        val answered = CountDownLatch(1)

        val provider = ChainProvider()
        val connection = provider.connect(
            PEOPLE.hexToBytes(),
            object : ChainMessageListener {
                override fun onMessage(message: String) {
                    Log.i(TAG, "${elapsed()} message: ${message.take(300)}")
                    if (message.contains("\"id\":1")) answered.countDown()
                }

                override fun onClosed(reason: ChainCloseReason) {
                    Log.i(TAG, "${elapsed()} closed: $reason")
                }
            }
        )

        val watch = provider.watchLifecycle(
            RELAY.hexToBytes(),
            object : ChainLifecycleListener {
                override fun onLifecycle(state: ChainLifecycle) {
                    Log.i(TAG, "${elapsed()} relay: $state")
                    if (state.phase is ChainPhase.Ready) ready.countDown()
                }

                override fun onEnded() {
                    Log.i(TAG, "${elapsed()} relay watch ended")
                }
            }
        )

        assertTrue("relay not Ready within 5 min", ready.await(5, TimeUnit.MINUTES))
        Log.i(TAG, "${elapsed()} relay ready")

        connection.send("""{"jsonrpc":"2.0","id":1,"method":"chain_getFinalizedHead","params":[]}""")
        assertTrue("no answer within 2 min", answered.await(2, TimeUnit.MINUTES))
        Log.i(TAG, "${elapsed()} people answered")

        watch.stop()
        connection.disconnect()
    }
}

private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
