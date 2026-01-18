package io.nekohasekai.sagernet.bg

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ProxyInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.VpnRequestActivity
import io.nekohasekai.sagernet.utils.Subnet
import android.net.VpnService as BaseVpnService

class VpnService : BaseVpnService(),
    BaseService.Interface {

    companion object {

        const val PRIVATE_VLAN4_CLIENT = "172.19.0.1"
        const val PRIVATE_VLAN4_ROUTER = "172.19.0.2"
        const val FAKEDNS_VLAN4_CLIENT = "198.18.0.0"
        const val PRIVATE_VLAN6_CLIENT = "fdfe:dcba:9876::1"
        const val PRIVATE_VLAN6_ROUTER = "fdfe:dcba:9876::2"

    }

    var conn: ParcelFileDescriptor? = null

    private var metered = false
    
    // ZIVPN: Hysteria Process Management
    private val hysteriaProcesses = mutableListOf<Process>()

    override var upstreamInterfaceName: String? = null

    override suspend fun startProcesses() {
        startHysteriaCores() // Start ZIVPN Cores before Proxy
        DataStore.vpnService = this
        super.startProcesses() // launch proxy instance
    }

    override var wakeLock: PowerManager.WakeLock? = null

    @SuppressLint("WakelockTimeout")
    override fun acquireWakeLock() {
        wakeLock = SagerNet.power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sagernet:vpn")
            .apply { acquire() }
    }

    @Suppress("EXPERIMENTAL_API_USAGE")
    override fun killProcesses() {
        stopHysteriaCores() // Stop ZIVPN Cores
        conn?.close()
        conn = null
        super.killProcesses()
    }

    // ... (Existing code) ...

    override fun onDestroy() {
        DataStore.vpnService = null
        stopHysteriaCores()
        super.onDestroy()
        data.binder.close()
    }
    
    private fun startHysteriaCores() {
        try {
            val nativeDir = applicationInfo.nativeLibraryDir
            val libUz = "$nativeDir/libuz_core.so"
            val libLoad = "$nativeDir/libload_core.so"
            
            // Read config from Prefs
            val prefs = getSharedPreferences("zivpn_prefs", android.content.Context.MODE_PRIVATE)
            val serverIp = prefs.getString("server_ip", "103.175.216.5") ?: ""
            val pass = prefs.getString("password", "maslexx68") ?: ""
            val obfs = prefs.getString("obfs", "hu``hqb`c") ?: ""
            val portRangeStr = prefs.getString("port_range", "6000-19999") ?: "6000-19999"
            val coreCount = prefs.getInt("core_count", 8).coerceIn(4, 16)
            val speedLimit = prefs.getInt("speed_limit", 50)
            
            Logs.i("ZIVPN: Starting $coreCount cores. Server: $serverIp Range: $portRangeStr Limit: $speedLimit Mbps")

            // Kill old just in case
            stopHysteriaCores()
            
            val tunnels = StringBuilder()
            
            // Split ports logic if needed, but here we assume simple range on server side
            // Client side we bind 1080, 1081...
            
            for (i in 0 until coreCount) {
                val port = 1080 + i
                val mbpsConfig = if (speedLimit > 0) ",\"up_mbps\":$speedLimit,\"down_mbps\":$speedLimit" else ""
                
                val config = """{"server":"$serverIp:$portRangeStr","obfs":"$obfs","auth":"$pass","socks5":{"listen":"127.0.0.1:$port"},"insecure":true,"recvwindowconn":65536,"recvwindow":262144,"disable_mtu_discovery":true,"resolver":"8.8.8.8:53"$mbpsConfig}"""
                
                val pb = ProcessBuilder(libUz, "-s", obfs, "--config", config)
                pb.environment()["LD_LIBRARY_PATH"] = nativeDir
                hysteriaProcesses.add(pb.start())
                
                tunnels.append("127.0.0.1:$port ")
            }
            
            // Wait for cores to initialize
            Thread.sleep(1000)
            
            // Start Load Balancer
            val tunnelList = tunnels.toString().trim().split(" ").toTypedArray()
            val lbCmd = mutableListOf(libLoad, "-lport", "7777", "-tunnel")
            lbCmd.addAll(tunnelList)
            
            val lbPb = ProcessBuilder(lbCmd)
            lbPb.environment()["LD_LIBRARY_PATH"] = nativeDir
            hysteriaProcesses.add(lbPb.start())
            
            Logs.i("ZIVPN: Load Balancer started on 7777")
            
        } catch (e: Exception) {
            Logs.e("ZIVPN: Failed to start cores", e)
        }
    }

    private fun stopHysteriaCores() {
        hysteriaProcesses.forEach { 
            try { it.destroy() } catch(e: Exception){} 
        }
        hysteriaProcesses.clear()
        try { Runtime.getRuntime().exec("killall libuz_core.so libload_core.so") } catch(e: Exception){}
        Logs.i("ZIVPN: Cores stopped")
    }
}