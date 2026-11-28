package dev.mineport.launcher

import android.app.Application
import dev.mineport.launcher.crash.CrashRecorder
import dev.mineport.launcher.data.AccountStore
import dev.mineport.launcher.data.InstanceStore
import dev.mineport.launcher.device.DeviceProfile
import dev.mineport.launcher.share.ShareStore

/**
 * 进程级单例容器。这里只放「构造一次、全局共享」的东西；
 * 与界面生命周期相关的一律走 ViewModel。
 */
class MineportApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashRecorder.install(this)
        container = AppContainer(this)
        // 独立进程（:game）里没有界面的初始化顺序，版本清单必须同步就绪。
        container.instanceStore.loadBlocking()
        // 账号决定启动参数（名字/uuid/令牌），同样必须同步就绪。
        container.accountStore.loadBlocking()
    }
}

class AppContainer(app: Application) {

    val instanceStore: InstanceStore = InstanceStore(app)

    /** 账号清单（离线 / 微软）；启动参数从当前账号取名字、uuid、令牌。 */
    val accountStore: AccountStore = AccountStore(app)

    /** 6 位短码的本地登记表；跨设备分享时换远端实现即可。 */
    val shareStore: ShareStore = ShareStore(app.filesDir)

    /** 设备画像只检测一次：SoC / 内存 / GLES / Vulkan / ABI 在本进程内不会变。 */
    val deviceProfile: DeviceProfile by lazy { DeviceProfile.detect(app) }
}
