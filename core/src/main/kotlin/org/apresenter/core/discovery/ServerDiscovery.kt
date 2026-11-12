package org.apresenter.core.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.apresenter.core.BrandConfig

data class DiscoveredServer(
    val name: String,
    val host: String,
    val port: Int,
)

/**
 * Discovers Apresenter Desktop instances advertising `_churchpresenter._tcp.` via Android NsdManager.
 */
class ServerDiscovery(context: Context) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val _servers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val servers: StateFlow<List<DiscoveredServer>> = _servers.asStateFlow()

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        val manager = nsdManager ?: return
        if (discoveryListener != null) return

        runCatching {
            val lock = wifiManager?.createMulticastLock("apresenter_mdns")?.apply {
                setReferenceCounted(true)
                acquire()
            }
            multicastLock = lock
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                stop()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.contains("churchpresenter", ignoreCase = true)) return
                resolve(manager, serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                _servers.update { list -> list.filterNot { it.name == serviceInfo.serviceName } }
            }
        }

        discoveryListener = listener
        runCatching {
            manager.discoverServices(BrandConfig.MDNS_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            discoveryListener = null
        }
    }

    fun stop() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        runCatching { nsdManager?.stopServiceDiscovery(listener) }
        runCatching {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        }
        multicastLock = null
    }

    @Suppress("DEPRECATION")
    private fun resolve(manager: NsdManager, serviceInfo: NsdServiceInfo) {
        runCatching {
            manager.resolveService(
                serviceInfo,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val host = resolved.host?.hostAddress?.takeIf { it.isNotBlank() } ?: return
                        val port = resolved.port.takeIf { it > 0 } ?: BrandConfig.DEFAULT_PORT
                        val entry = DiscoveredServer(
                            name = resolved.serviceName.ifBlank { host },
                            host = host,
                            port = port,
                        )
                        _servers.update { current ->
                            (current.filterNot { it.name == entry.name && it.host == entry.host } + entry)
                                .sortedBy { it.name.lowercase() }
                        }
                    }
                },
            )
        }
    }
}
