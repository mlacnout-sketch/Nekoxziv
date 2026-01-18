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
import android.util.Log // Direct Android Log for ZIVPN
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.VpnRequestActivity
import io.nekohasekai.sagernet.utils.Subnet
import android.net.VpnService as BaseVpnService
import kotlinx.coroutines.*

class VpnService : BaseVpnService(),
    BaseService.Interface, CoroutineScope by CoroutineScope(Dispatchers.Default) {

    companion object {
        const val PRIVATE_VLAN4_CLIENT = "172.19.0.1"
        const val PRIVATE_VLAN4_ROUTER = "172.19.0.2"
        const val FAKEDNS_VLAN4_CLIENT = "198.18.0.0"
        const val PRIVATE_VLAN6_CLIENT = "fdfe:dcba:9876::1"
        const val PRIVATE_VLAN6_ROUTER = "fdfe:dcba:9876::2"
    }

    var conn: ParcelFileDescriptor? = null

    private var metered = false

    private val supervisorJobs = mutableListOf<Job>()
    private val coreProcesses = mutableListOf<Process>()

    override var upstreamInterfaceName: String? = null

    override suspend fun startProcesses() {
        startHysteriaCores()
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
        stopHysteriaCores()
        conn?.close()
        conn = null
        super.killProcesses()
    }

    override fun onBind(intent: Intent) = when (intent.action) {
        SERVICE_INTERFACE -> super<BaseVpnService>.onBind(intent)
        else -> super<BaseService.Interface>.onBind(intent)
    }

    override val data = BaseService.Data(this)
    override val tag = "SagerNetVpnService"
    override fun createNotification(profileName: String) =
        ServiceNotification(this, profileName, "service-vpn")

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DataStore.serviceMode == Key.MODE_VPN) {
            if (prepare(this) != null) {
                startActivity(
                    Intent(
                        this,
                        VpnRequestActivity::class.java
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else return super<BaseService.Interface>.onStartCommand(intent, flags, startId)
        }
        stopRunner()
        return Service.START_NOT_STICKY
    }

    inner class NullConnectionException : NullPointerException(),
        BaseService.ExpectedException {
        override fun getLocalizedMessage() = getString(R.string.reboot_required)
    }

    fun startVpn(tunOptionsJson: String, tunPlatformOptionsJson: String): Int {
//        Logs.d(tunOptionsJson)
//        Logs.d(tunPlatformOptionsJson)
//        val tunOptions = JSONObject(tunOptionsJson)

        // address & route & MTU ...... use NB4A GUI config
        val builder = Builder().setConfigureIntent(SagerNet.configureIntent(this))
            .setSession(getString(R.string.app_name))
            .setMtu(DataStore.mtu)
        val ipv6Mode = DataStore.ipv6Mode

        // address
        builder.addAddress(PRIVATE_VLAN4_CLIENT, 30)
        if (ipv6Mode != IPv6Mode.DISABLE) {
            builder.addAddress(PRIVATE_VLAN6_CLIENT, 126)
        }
        builder.addDnsServer(PRIVATE_VLAN4_ROUTER)

        // route
        if (DataStore.bypassLan) {
            resources.getStringArray(R.array.bypass_private_route).forEach {
                val subnet = Subnet.fromString(it)!!
                builder.addRoute(subnet.address.hostAddress!!, subnet.prefixSize)
            }
            builder.addRoute(PRIVATE_VLAN4_ROUTER, 32)
            builder.addRoute(FAKEDNS_VLAN4_CLIENT, 15)
            // https://issuetracker.google.com/issues/149636790
            if (ipv6Mode != IPv6Mode.DISABLE) {
                builder.addRoute("2000::", 3)
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
            if (ipv6Mode != IPv6Mode.DISABLE) {
                builder.addRoute("::", 0)
            }
        }

        updateUnderlyingNetwork(builder)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(metered)

        // app route
        val packageName = packageName
        val proxyApps = DataStore.proxyApps
        var bypass = DataStore.bypass
        val workaroundSYSTEM = false /* DataStore.tunImplementation == TunImplementation.SYSTEM */
        val needBypassRootUid = workaroundSYSTEM || data.proxy!!.config.trafficMap.values.any {
            it[0].hysteriaBean?.protocol == HysteriaBean.PROTOCOL_FAKETCP
        }

        if (proxyApps || needBypassRootUid) {
            val individual = mutableSetOf<String>()
            val allApps by lazy {
                packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS).filter {
                    when (it.packageName) {
                        packageName -> false
                        "android" -> true
                        else -> it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
                    }
                }.map {
                    it.packageName
                }
            }
            if (proxyApps) {
                individual.addAll(DataStore.individual.split('\n').filter { it.isNotBlank() })
                if (bypass && needBypassRootUid) {
                    val individualNew = allApps.toMutableList()
                    individualNew.removeAll(individual)
                    individual.clear()
                    individual.addAll(individualNew)
                    bypass = false
                }
            } else {
                individual.addAll(allApps)
                bypass = false
            }

            val added = mutableListOf<String>()

            individual.apply {
                remove(packageName)
            }.forEach {
                try {
                    if (bypass) {
                        builder.addDisallowedApplication(it)
                    } else {
                        builder.addAllowedApplication(it)
                    }
                    added.add(it)
                } catch (ex: PackageManager.NameNotFoundException) {
                    Logs.w(ex)
                }
            }

            if (bypass) {
                Logs.d("Add bypass: ${added.joinToString(", ")}")
            } else {
                Logs.d("Add allow: ${added.joinToString(", ")}")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && DataStore.appendHttpProxy) {
            builder.setHttpProxy(ProxyInfo.buildDirectProxy(LOCALHOST, DataStore.mixedPort))
        }

        metered = DataStore.meteredNetwork
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(metered)
        conn = builder.establish() ?: throw NullConnectionException()

        return conn!!.fd
    }

    fun updateUnderlyingNetwork(builder: Builder? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            SagerNet.underlyingNetwork?.let {
                builder?.setUnderlyingNetworks(arrayOf(SagerNet.underlyingNetwork))
                    ?: setUnderlyingNetworks(arrayOf(SagerNet.underlyingNetwork))
            }
        }
    }

    override fun onRevoke() = stopRunner()

    override fun onDestroy() {
        DataStore.vpnService = null
        stopHysteriaCores()
        super.onDestroy()
        data.binder.close()
        cancel() // Cancel CoroutineScope
    }

    private fun isPortListening(port: Int): Boolean {
        return try {
            java.net.Socket("127.0.0.1", port).use { true }
        } catch (e: Exception) {
            false
        }
    }

    private fun startProcessLogger(process: Process, tag: String) {
        Thread {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { Log.i("ZIVPN", "[$tag] $it") }
                }
            } catch (_: Exception) { }
        }.start()
    }

    private fun startDaemon(name: String, command: List<String>, env: Map<String, String>, monitorPort: Int) {
        val job = launch(Dispatchers.IO) {
            while (isActive && DataStore.serviceState.started) {
                var process: Process? = null
                try {
                    Log.i("ZIVPN", "Starting $name on port $monitorPort...")
                    val pb = ProcessBuilder(command)
                    pb.redirectErrorStream(true)
                    pb.environment().putAll(env)
                    process = pb.start()

                    synchronized(coreProcesses) {
                        coreProcesses.add(process)
                    }

                    startProcessLogger(process, name)

                    // Health Check Loop
                    val healthCheckJob = launch {
                        delay(5000)
                        var failCount = 0
                        while (isActive && process!!.isAlive) {
                            if (!isPortListening(monitorPort)) {
                                failCount++
                                Log.w("ZIVPN", "$name Health Check Failed ($failCount/3)")
                                if (failCount >= 3) {
                                    Log.e("ZIVPN", "$name is unresponsive. Killing...")
                                    process!!.destroy()
                                    break
                                }
                            } else {
                                failCount = 0
                            }
                            delay(10000)
                        }
                    }

                    val exitCode = process.waitFor()
                    healthCheckJob.cancel()

                    synchronized(coreProcesses) {
                        coreProcesses.remove(process)
                    }

                    if (!DataStore.serviceState.started) break

                    Log.w("ZIVPN", "$name exited (Code: $exitCode). Restarting in 2s...")
                    delay(2000)
                } catch (e: Exception) {
                    Log.e("ZIVPN", "Error running $name", e)
                    process?.destroy()
                    delay(5000)
                }
            }
        }
        supervisorJobs.add(job)
    }

    private fun startHysteriaCores() {
        launch(Dispatchers.IO) {
            try {
                val nativeDir = applicationInfo.nativeLibraryDir
                val libUz = "$nativeDir/libuz_core.so"
                val libLoad = "$nativeDir/libload_core.so"

                val prefs = getSharedPreferences("zivpn_prefs", android.content.Context.MODE_PRIVATE)
                val serverIp = prefs.getString("server_ip", "") ?: ""
                val pass = prefs.getString("password", "") ?: ""
                val obfs = prefs.getString("obfs", "") ?: ""
                val portRangeStr = prefs.getString("port_range", "") ?: ""
                val coreCount = prefs.getInt("core_count", 8).coerceIn(4, 16)
                val speedLimit = prefs.getInt("speed_limit", 50)

                stopHysteriaCores()

                val env = mapOf("LD_LIBRARY_PATH" to nativeDir)
                val tunnels = mutableListOf<String>()
                
                for (i in 0 until coreCount) {
                    val port = 1080 + i
                    val mbpsConfig = if (speedLimit > 0) ",\"up_mbps\":$speedLimit,\"down_mbps\":$speedLimit" else ""
                    val configContent = """{"server":"$serverIp:$portRangeStr","obfs":"$obfs","auth":"$pass","socks5":{"listen":"127.0.0.1:$port"},"insecure":true,"recvwindowconn":65536,"recvwindow":262144,"disable_mtu_discovery":true,"resolver":"8.8.8.8:53"$mbpsConfig}"""

                    if (i == 0) Log.i("ZIVPN", "Config: $configContent")

                    val command = listOf(libUz, "-s", obfs, "--config", configContent)
                    startDaemon("ZIVPN-Core-$i", command, env, port)
                    tunnels.add("127.0.0.1:$port")
                }

                Thread.sleep(1000)

                val lbCmd = mutableListOf(libLoad, "-lport", "7777", "-tunnel")
                lbCmd.addAll(tunnels)

                startDaemon("ZIVPN-LB", lbCmd, env, 7777)
                
                Log.i("ZIVPN", "All cores started")

            } catch (e: Exception) {
                Log.e("ZIVPN", "Failed to start cores", e)
            }
        }
    }

    private fun stopHysteriaCores() {
        supervisorJobs.forEach { it.cancel() }
        supervisorJobs.clear()

        synchronized(coreProcesses) {
            coreProcesses.forEach { it.destroy() }
            coreProcesses.clear()
        }
        try {
            Runtime.getRuntime().exec("killall libuz_core.so libload_core.so")
        } catch (e: Exception) {
        }
        Log.i("ZIVPN", "Cores stopped")
    }
}
