package dev.mineport.launcher.net

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException

/**
 * Minecraft 局域网世界发现（D37：不自建联机服务）。
 *
 * Java 版在「对局域网开放」时向 224.0.2.60:4445 发送
 * `[MOTD]标题[/MOTD][AD]端口[/AD]`，本机在同一 WiFi 下就能看到这些世界。
 * Android 默认会过滤多播包，必须持有 WifiManager.MulticastLock 才收得到。
 */
object LanDiscovery {

    data class LanWorld(
        val address: String,
        val port: Int,
        val motd: String
    ) {
        /** 游戏内「多人 → 直接连接」要填的地址。 */
        val connectAddress: String get() = address + ":" + port
    }

    private const val GROUP = "224.0.2.60"
    private const val PORT = 4445

    suspend fun scan(
        context: Context,
        timeoutMs: Int = 4000,
        onWorld: (LanWorld) -> Unit = {}
    ): List<LanWorld> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("mineport-lan")?.apply {
            setReferenceCounted(true)
            acquire()
        }
        val found = LinkedHashMap<String, LanWorld>()
        val socket = MulticastSocket(PORT)
        try {
            socket.soTimeout = 800
            socket.joinGroup(InetAddress.getByName(GROUP))
            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(1024)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val payload = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val world = parse(payload, packet.address.hostAddress ?: continue)
                if (world != null && found.put(world.connectAddress, world) == null) {
                    onWorld(world)
                }
            }
        } finally {
            runCatching { socket.leaveGroup(InetAddress.getByName(GROUP)) }
            socket.close()
            lock?.let { if (it.isHeld) it.release() }
        }
        found.values.toList()
    }

    /** 解析 `[MOTD]…[/MOTD][AD]端口[/AD]`；格式不对返回 null。 */
    fun parse(payload: String, address: String): LanWorld? {
        val motdStart = payload.indexOf("[MOTD]")
        val motdEnd = payload.indexOf("[/MOTD]")
        val adStart = payload.indexOf("[AD]")
        val adEnd = payload.indexOf("[/AD]")
        if (motdStart < 0 || motdEnd < 0 || adStart < 0 || adEnd < 0) return null
        val motd = payload.substring(motdStart + 6, motdEnd)
        val port = payload.substring(adStart + 4, adEnd).toIntOrNull() ?: return null
        return LanWorld(address = address, port = port, motd = motd)
    }
}
