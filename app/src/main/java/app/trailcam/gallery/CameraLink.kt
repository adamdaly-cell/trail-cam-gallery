package app.trailcam.gallery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.PatternMatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.util.UUID

/** An open link to the camera. Keeps the hotspot alive until closed. */
class CameraSession(val api: CameraApi, private val release: () -> Unit) : Closeable {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        // The camera drops its hotspot within seconds unless it is polled.
        scope.launch {
            while (isActive) {
                delay(5_000)
                api.keepAlive()
            }
        }
    }

    override fun close() {
        scope.cancel()
        release()
    }
}

/**
 * Reaches the camera the same way the official app does:
 *  1. Bluetooth LE: write "AT+WAKEPULSE=10\r\n" to the camera's UART service. That wakes the
 *     main processor, which raises a Wi-Fi hotspot. The link is held open and pulsed until
 *     the hotspot has been joined — a single write does not reliably bring it up.
 *  2. Wi-Fi: join the hotspot (WPA2) as an app-only network, so the phone keeps its normal
 *     internet connection.
 *  3. HTTP on 192.168.8.1:8080, see [CameraApi].
 */
@SuppressLint("MissingPermission")
class CameraLink(private val ctx: Context) {

    companion object {
        const val CAMERA_URL = "http://192.168.8.1:8080"
        private val NUS_SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_RX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val WAKE_CHAR = UUID.fromString("6e400004-b5a3-f393-e0a9-e50e24dcca9e")
        private val WAKE_CMD = "AT+WAKEPULSE=10\r\n".toByteArray(Charsets.US_ASCII)
    }

    suspend fun open(status: (String) -> Unit): CameraSession = withContext(Dispatchers.IO) {
        val direct = Prefs.directUrl(ctx)
        if (direct.isNotEmpty()) {
            status("Connecting to $direct…")
            val api = CameraApi(direct, null)
            if (!api.keepAlive()) throw CamException("No camera answering at $direct")
            return@withContext CameraSession(api) { api.sleepNow() }
        }

        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) throw CamException("Bluetooth is off — turn it on to wake the camera")

        status("Looking for the camera over Bluetooth…")
        val device = findCamera(adapter)

        status("Waking the camera…")
        val waker = Waker(device)
        val wakeJob = waker.start(this)
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val joined = CompletableDeferred<Network?>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                joined.complete(network)
            }

            override fun onUnavailable() {
                joined.complete(null)
            }
        }
        try {
            cm.requestNetwork(hotspotRequest(), callback, 100_000)
            status("Waiting for the camera's Wi-Fi…")
            val network = withTimeoutOrNull(110_000) { joined.await() }
                ?: throw CamException(
                    "The camera's Wi-Fi never came up. Move closer (within about 10 m) and try again."
                )
            status("Connected — starting up…")
            val api = CameraApi(CAMERA_URL, network)
            var answering = false
            for (i in 0 until 12) {
                if (api.keepAlive()) {
                    answering = true
                    break
                }
                delay(2_000)
            }
            if (!answering) throw CamException("Joined the camera's Wi-Fi but it is not answering")
            wakeJob.cancel()
            waker.stop()
            CameraSession(api) {
                api.sleepNow()
                try {
                    cm.unregisterNetworkCallback(callback)
                } catch (_: Exception) {
                }
            }
        } catch (e: Throwable) {
            wakeJob.cancel()
            waker.stop()
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
            throw e
        }
    }

    private fun hotspotRequest(): NetworkRequest {
        val spec = WifiNetworkSpecifier.Builder().apply {
            val exact = Prefs.ssid(ctx)
            if (exact.isNotBlank()) {
                setSsid(exact)
            } else {
                // The hotspot is named like the Bluetooth device up to the first underscore
                // ("CAM8Z8_NoName_G_E8" -> "CAM8Z8_<MAC>"), so match on that prefix.
                val name = Prefs.bleName(ctx).orEmpty()
                val prefix = if ('_' in name) name.substringBefore('_') + "_" else Prefs.namePrefix(ctx)
                setSsidPattern(PatternMatcher(prefix, PatternMatcher.PATTERN_PREFIX))
            }
            setWpa2Passphrase(Prefs.wifiPass(ctx))
        }.build()
        return NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
    }

    /** Scan until the remembered camera (or, the first time, any device named CAM…) shows up. */
    private suspend fun findCamera(adapter: BluetoothAdapter): BluetoothDevice {
        val savedMac = Prefs.bleMac(ctx)
        val prefix = Prefs.namePrefix(ctx)
        val scanner = adapter.bluetoothLeScanner ?: throw CamException("Bluetooth is off")
        val found = CompletableDeferred<ScanResult>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: ""
                val hit = if (savedMac != null) result.device.address.equals(savedMac, true)
                else name.startsWith(prefix, ignoreCase = true)
                if (hit) found.complete(result)
            }

            override fun onScanFailed(errorCode: Int) {
                found.completeExceptionally(CamException("Bluetooth scan failed (code $errorCode)"))
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, cb)
        try {
            val result = withTimeoutOrNull(30_000) { found.await() } ?: throw CamException(
                if (savedMac != null) "Camera not found. It may be out of Bluetooth range (about 10 m) or switched off."
                else "No camera found nearby. Stand within about 10 m of it with the camera switched on."
            )
            val name = result.scanRecord?.deviceName ?: result.device.name
            Prefs.sp(ctx).edit().putString("ble_mac", result.device.address).apply {
                if (!name.isNullOrBlank()) putString("ble_name", name)
            }.apply()
            return result.device
        } finally {
            try {
                scanner.stopScan(cb)
            } catch (_: Exception) {
            }
        }
    }

    /** Holds the Bluetooth link and repeats the wake command until stopped. */
    private inner class Waker(private val device: BluetoothDevice) {
        @Volatile private var gatt: BluetoothGatt? = null
        @Volatile private var wakeChar: BluetoothGattCharacteristic? = null
        @Volatile private var stopped = false

        private val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    wakeChar = null
                    g.close()
                    if (gatt === g) gatt = null   // the pulse loop reconnects
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val nus = g.getService(NUS_SERVICE)
                wakeChar = nus?.getCharacteristic(WAKE_CHAR)
                    ?: g.services.firstNotNullOfOrNull { it.getCharacteristic(WAKE_CHAR) }
                    ?: nus?.getCharacteristic(NUS_RX)
            }
        }

        fun start(scope: CoroutineScope): Job = scope.launch {
            while (isActive && !stopped) {
                val g = gatt
                if (g == null) {
                    gatt = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    wakeChar?.let { pulse(g, it) }
                }
                delay(1_500)
            }
        }

        @Suppress("DEPRECATION")
        private fun pulse(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            try {
                val noResponse = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                c.writeType = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                c.value = WAKE_CMD
                g.writeCharacteristic(c)
            } catch (_: Exception) {
            }
        }

        fun stop() {
            stopped = true
            val g = gatt
            gatt = null
            try {
                g?.disconnect()
                g?.close()
            } catch (_: Exception) {
            }
        }
    }
}
