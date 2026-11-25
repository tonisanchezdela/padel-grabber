package com.padelgrabber.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Anuncia y busca móviles Padel Grabber en la wifi del club (mDNS / NSD).
 */
class Discovery(ctx: Context) {

    companion object {
        const val TYPE = "_padelgrab._tcp."
        private const val TAG = "PG-Nsd"
    }

    data class Found(val name: String, val host: String, val port: Int)

    private val nsd = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun register(name: String, port: Int) {
        unregister()
        val info = NsdServiceInfo()
        info.serviceName = name
        info.serviceType = TYPE
        info.port = port
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) { Log.d(TAG, "Anunciado ${serviceInfo.serviceName}") }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { Log.w(TAG, "Fallo al anunciar $errorCode") }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        regListener = l
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            Log.w(TAG, "registerService", e)
        }
    }

    fun unregister() {
        val l = regListener ?: return
        regListener = null
        try { nsd.unregisterService(l) } catch (_: Exception) {}
    }

    /** Busca servicios. onFound se llama en el hilo principal. */
    fun discover(onFound: (Found) -> Unit) {
        stopDiscovery()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(TAG, "discovery $errorCode") }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                main.post {
                    resolveQueue.addLast(serviceInfo)
                    nextResolve(onFound)
                }
            }
        }
        discListener = l
        try {
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun nextResolve(onFound: (Found) -> Unit) {
        if (resolving || resolveQueue.isEmpty()) return
        resolving = true
        val info = resolveQueue.removeFirst()
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    main.post { resolving = false; nextResolve(onFound) }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host?.hostAddress
                    main.post {
                        resolving = false
                        if (host != null) onFound(Found(serviceInfo.serviceName, host, serviceInfo.port))
                        nextResolve(onFound)
                    }
                }
            })
        } catch (e: Exception) {
            resolving = false
        }
    }

    fun stopDiscovery() {
        val l = discListener ?: return
        discListener = null
        try { nsd.stopServiceDiscovery(l) } catch (_: Exception) {}
    }

    fun close() {
        stopDiscovery()
        unregister()
    }
}
