package io.github.shaonialife.cardeck.net

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * T47: CarPlay traffic belongs on the *physical head-unit Wi-Fi*, not the
 * phone's VPN default route. VPN stays enabled for other applications.
 *
 * Android VPN lockdown / disallow-bypass may forbid selecting an underlying
 * Network (EPERM). Never attempt to bypass that policy: try the ordinary
 * system route as a fallback, which works only if the VPN allows local LAN.
 */
object CarPlayNetworkSockets {
    fun isVpnActive(cm: ConnectivityManager): Boolean =
        cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

    fun isPhysicalWifi(cm: ConnectivityManager, network: Network?): Boolean {
        if (network == null) return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        val iface = cm.getLinkProperties(network)?.interfaceName ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            !iface.startsWith("tun", ignoreCase = true) &&
            !iface.startsWith("wg", ignoreCase = true) &&
            !iface.startsWith("ppp", ignoreCase = true)
    }

    fun connectTcp(
        physicalWifi: Network?,
        address: InetAddress,
        port: Int,
        timeoutMillis: Int,
        log: (String) -> Unit,
    ): Socket {
        if (physicalWifi != null) {
            var socket: Socket? = null
            try {
                socket = physicalWifi.socketFactory.createSocket()
                socket.connect(InetSocketAddress(address, port), timeoutMillis)
                log("CAR-NET T47: TCP $port via physical Wi-Fi network=$physicalWifi")
                return socket
            } catch (failure: Exception) {
                runCatching { socket?.close() }
                log(
                    "CAR-NET T47: explicit Wi-Fi TCP $port unavailable " +
                        "(${failure.javaClass.simpleName}: ${failure.message}); " +
                        "trying system LAN route (VPN rules apply)",
                )
            }
        }
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(address, port), timeoutMillis)
            log("CAR-NET T47: TCP $port via system LAN route")
            return socket
        } catch (failure: Exception) {
            runCatching { socket.close() }
            throw failure
        }
    }
}
