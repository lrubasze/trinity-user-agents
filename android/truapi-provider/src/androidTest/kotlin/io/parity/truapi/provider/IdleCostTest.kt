package io.parity.truapi.provider

import android.os.SystemClock
import android.system.Os
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.truapi_provider.ChainCloseReason
import uniffi.truapi_provider.ChainConnection
import uniffi.truapi_provider.ChainMessageListener
import uniffi.truapi_provider.ChainProvider
import java.io.File
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "IdleCost"

// Paseo Next V2: relay, Asset Hub, People, Bulletin.
private val CHAINS = linkedMapOf(
    "relay" to "374057be67b355151f271ff70c3db98308c62c8adc48dc6724b6a009a1a014fd",
    "asset-hub" to "4349b00e54897e21196fd331015fc5be0f14e118beb0375ed2bb1793737bb57a",
    "people" to "4a2b5b737de1da59e209b0000a876ec2fa20035dc34fd292a848da32d255ad48",
    "bulletin" to "8cfe6717dc4becfda2e13c488a1e2061ff2dfee96e7d031157f72d36716c0a22",
)

/**
 * Keeps smoldot following the Paseo Next V2 chains for a fixed time, for measuring its
 * baseline power, CPU and traffic without an app on top. Not a pass/fail test.
 *
 * Instrumentation arguments:
 * - `mode`: `none` (chains added, no requests), `heads` (new/finalized heads and runtime
 *   version per chain, as the app subscribes) or `subscriptions` (`heads` plus the storage
 *   subscriptions listed in `keysFile`). Default `heads`.
 * - `chains`: comma-separated subset of `relay`, `asset-hub`, `people`, `bulletin`. Default all.
 * - `durationSec`: how long to keep following. Default 600.
 * - `keysFile`: JSON object mapping a genesis hash (hex, no 0x) to an array of key arrays,
 *   one `state_subscribeStorage` per key array. Required for `subscriptions`.
 * - `log`: smoldot log level (`TRUAPI_PROVIDER_LOG`), e.g. `debug`. Off by default.
 */
@RunWith(AndroidJUnit4::class)
class IdleCostTest {

    @Test
    fun followChains() {
        val args = InstrumentationRegistry.getArguments()
        val mode = args.getString("mode", "heads")
        val durationSec = args.getString("durationSec", "600").toLong()
        args.getString("log")?.let { Os.setenv("TRUAPI_PROVIDER_LOG", it, true) }
        val keys = args.getString("keysFile")?.let { JSONObject(File(it).readText()) }
        val chains = args.getString("chains")?.split(',')?.associateWith { CHAINS.getValue(it) } ?: CHAINS
        require(mode in setOf("none", "heads", "subscriptions")) { "unknown mode: $mode" }
        require(mode != "subscriptions" || keys != null) { "subscriptions needs keysFile" }

        val start = SystemClock.elapsedRealtime()
        val provider = ChainProvider()
        val received = chains.keys.associateWith { AtomicLong() }

        val connections = chains.map { (name, genesis) ->
            val connection = provider.connect(genesis.hexToBytes(), object : ChainMessageListener {
                override fun onMessage(message: String) {
                    received.getValue(name).incrementAndGet()
                    if (message.contains("\"error\"")) Log.w(TAG, "$name: ${message.take(300)}")
                }

                override fun onClosed(reason: ChainCloseReason) {
                    Log.w(TAG, "$name closed: $reason")
                }
            })
            if (mode != "none") subscribe(connection, genesis, keys.takeIf { mode == "subscriptions" })
            connection
        }
        Log.i(TAG, "mode=$mode durationSec=$durationSec chains=${chains.keys}")

        val end = start + durationSec * 1000
        while (SystemClock.elapsedRealtime() < end) {
            SystemClock.sleep(minOf(60_000L, end - SystemClock.elapsedRealtime()))
            val elapsed = (SystemClock.elapsedRealtime() - start) / 1000
            Log.i(TAG, "${elapsed}s messages ${received.mapValues { it.value.get() }}")
        }

        connections.forEach { it.disconnect() }
    }

    private fun subscribe(connection: ChainConnection, genesis: String, keys: JSONObject?) {
        var id = 0
        fun send(method: String, params: String = "[]") =
            connection.send("""{"jsonrpc":"2.0","id":${++id},"method":"$method","params":$params}""")

        send("chain_subscribeNewHeads")
        send("chain_subscribeFinalizedHeads")
        send("state_subscribeRuntimeVersion")
        val subscriptions = keys?.optJSONArray(genesis) ?: JSONArray()
        for (i in 0 until subscriptions.length()) {
            send("state_subscribeStorage", "[${subscriptions.getJSONArray(i)}]")
        }
    }
}

private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
