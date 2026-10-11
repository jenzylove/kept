package app.kept.chain

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps

/**
 * Name lookups that survive a phone network whose own resolver drops names (seen on a real phone:
 * "Unable to resolve host" for our site while everything else worked). The phone's resolver is
 * asked first. If it has no answer, the same name is asked of a public resolver over HTTPS, reached
 * by its numeric address so that lookup needs no lookup of its own. Answers are remembered for a
 * few minutes so one bad moment does not stall every request.
 */
object ResilientDns : Dns {
    private const val KEEP_MS = 5 * 60_000L
    private class Answer(val at: Long, val addresses: List<InetAddress>)
    private val remembered = ConcurrentHashMap<String, Answer>()

    private val overHttps: List<Dns> by lazy {
        val bootstrap = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
        fun doh(url: String, vararg ips: String) = DnsOverHttps.Builder().client(bootstrap).url(url.toHttpUrl())
            .bootstrapDnsHosts(ips.map { InetAddress.getByName(it) }).includeIPv6(false).build()
        listOf(
            doh("https://cloudflare-dns.com/dns-query", "1.1.1.1", "1.0.0.1"),
            doh("https://dns.google/dns-query", "8.8.8.8", "8.8.4.4"),
        )
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        val system = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrNull()
        if (!system.isNullOrEmpty()) { remembered[hostname] = Answer(now, system); return system }
        remembered[hostname]?.takeIf { now - it.at < KEEP_MS }?.let { return it.addresses }
        for (resolver in overHttps) {
            val found = runCatching { resolver.lookup(hostname) }.getOrNull()
            if (!found.isNullOrEmpty()) { remembered[hostname] = Answer(now, found); return found }
        }
        // Better a stale address than none at all.
        remembered[hostname]?.let { return it.addresses }
        throw UnknownHostException("No address found for $hostname")
    }
}

/** The one place network clients are made, so every request gets the same resilient name lookup. */
object Net {
    fun client(connectSec: Long = 15, readSec: Long = 30, pingSec: Long = 0): OkHttpClient =
        OkHttpClient.Builder()
            .dns(ResilientDns)
            .connectTimeout(connectSec, TimeUnit.SECONDS)
            .readTimeout(readSec, TimeUnit.SECONDS)
            .apply { if (pingSec > 0) pingInterval(pingSec, TimeUnit.SECONDS) }
            .build()
}
