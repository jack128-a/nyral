package all

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Cloudflare 直连建连（v8，Detekt 拆分）。
 *
 * HttpURLConnection 靠“把 URL 域名替换成优选 IP + Host 请求头仍用原域名 +
 * 自定义 SSLSocketFactory 在 createSocket 时传入原域名触发 SNI”实现直连。
 * 每次建连实时调用 CloudflareOptDns.bestIp()，绝不缓存 IP（故障切换实时生效）。
 * Host 请求头、自定义 SSLSocketFactory、HostnameVerifier 的执行顺序不可改动，
 * 否则 Cloudflare 边缘返回 Error 1003 或 TLS 证书校验失败。
 */
object CloudflareSniSocketFactory {

    private const val TAG = "CloudflareSni"

    /** 直连建连超时（SNI 工厂里裸 socket 的 connect 超时） */
    private const val DIRECT_CONNECT_MS = 10000

    /**
     * 打开连接：直连开着且池子有可用 IP 时拨优选 IP（SNI/Host 仍用原域名），
     * 否则走系统 DNS。连接是惰性的（真正建连在写请求体/读响应码时），
     * 失败由调用方按 reportSuccess/reportFailure 回报。
     */
    fun openConnection(urlStr: String): HttpURLConnection {
        val url = URL(urlStr)
        CloudflareOptDns.lastDialedIp = null
        val ip = if (CloudflareOptDns.isDirectEnabled()) CloudflareOptDns.bestIp() else null
        return if (ip == null || url.protocol != "https") {
            url.openConnection() as HttpURLConnection
        } else {
            try {
                CloudflareOptDns.lastDialedIp = ip
                openDirect(url, ip)
            } catch (e: IOException) {
                Log.w(TAG, "直连建连失败($ip)，回落系统 DNS: ${e.message}")
                CloudflareOptDns.lastDialedIp = null
                url.openConnection() as HttpURLConnection
            }
        }
    }

    /** 直连打开 HTTPS：拨 IP，SNI 与 Host 头仍用原域名，证书按原域名校验 */
    internal fun openDirect(url: URL, ip: String): HttpsURLConnection {
        val directUrl = URL(url.protocol, ip, url.port, url.file)
        val conn = directUrl.openConnection() as HttpsURLConnection
        val hostHeader = if (url.port != -1 && url.port != url.defaultPort) {
            "${url.host}:${url.port}"
        } else {
            url.host
        }
        conn.setRequestProperty("Host", hostHeader)
        conn.sslSocketFactory = SniSocketFactory(
            SSLSocketFactory.getDefault() as SSLSocketFactory, url.host
        )
        conn.hostnameVerifier = HostnameVerifier { _, session ->
            HttpsURLConnection.getDefaultHostnameVerifier().verify(url.host, session)
        }
        return conn
    }

    /**
     * SNI 套接字工厂：裸 socket 拨 IP，TLS 层用原域名做 SNI。
     * 具名类（不用匿名内部类，d8 安全）；回调一律走本类的具名重写。
     */
    private class SniSocketFactory(
        private val delegate: SSLSocketFactory,
        private val sniHost: String
    ) : SSLSocketFactory() {

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
        override fun createSocket(): Socket = delegate.createSocket()

        override fun createSocket(host: String, port: Int): Socket =
            wrapNew(host, port)

        override fun createSocket(
            host: String, port: Int, localHost: InetAddress, localPort: Int
        ): Socket = wrapNew(host, port, localHost, localPort)

        override fun createSocket(host: InetAddress, port: Int): Socket =
            wrapNew(host.hostAddress ?: host.toString(), port)

        override fun createSocket(
            address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int
        ): Socket = wrapNew(
            address.hostAddress ?: address.toString(), port, localAddress, localPort
        )

        override fun createSocket(
            s: Socket, host: String, port: Int, autoClose: Boolean
        ): Socket {
            val ssl = delegate.createSocket(s, sniHost, port, autoClose) as SSLSocket
            return setSni(ssl)
        }

        /** 裸 socket 先拨 IP，再套 TLS（SNI 用原域名） */
        private fun wrapNew(
            host: String, port: Int,
            localHost: InetAddress? = null, localPort: Int = 0
        ): Socket {
            val plain = Socket()
            try {
                if (localHost != null) plain.bind(InetSocketAddress(localHost, localPort))
                plain.connect(InetSocketAddress(host, port), DIRECT_CONNECT_MS)
            } catch (e: IOException) {
                try { plain.close() } catch (_: Exception) {}
                throw e
            }
            val ssl = delegate.createSocket(plain, sniHost, port, true) as SSLSocket
            return setSni(ssl)
        }

        /** 兜底再设一次 SNI（delegate 已用 sniHost 建连时此步是多余的、无害的） */
        private fun setSni(ssl: SSLSocket): SSLSocket {
            try {
                val params: SSLParameters = ssl.sslParameters
                params.serverNames = listOf(SNIHostName(sniHost))
                ssl.sslParameters = params
            } catch (_: Exception) {
                // 忽略：delegate 已用 sniHost 建连
            }
            return ssl
        }
    }
}
