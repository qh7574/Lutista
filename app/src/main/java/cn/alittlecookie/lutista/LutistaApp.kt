package cn.alittlecookie.lutista

import android.app.Application
import cn.alittlecookie.lutista.di.appModule
import cn.alittlecookie.lutista.gpu.VulkanCapabilityChecker
import cn.alittlecookie.lutista.gpu.VulkanGpuEngine
import cn.alittlecookie.lutista.manager.BuiltinFilterManager
import cn.alittlecookie.lutista.manager.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.get
import timber.log.Timber

class LutistaApp : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        startKoin {
            androidLogger()
            androidContext(this@LutistaApp)
            modules(appModule)
        }

        instance = this
        val tetherSuppression = cn.alittlecookie.lutista.tether.transfer.TetherProcessingSuppression.shared
        tetherSuppression.configure(java.io.File(filesDir, "tether-monitor-exclusions.properties"))
        if (!tetherSuppression.isAvailable()) {
            Timber.e("Tether monitor exclusions unavailable; file monitoring and tether writes fail closed: %s",
                tetherSuppression.availabilityError())
        }

        get<LogManager>(LogManager::class.java).observeLogEnabled()

        initializeVulkan()
        initializeBuiltinFilters()
    }

    private fun initializeVulkan() {
        applicationScope.launch(Dispatchers.IO) {
            try {
                val logger = get<LogManager>(LogManager::class.java)
                val vulkanEngine = get<VulkanGpuEngine>(VulkanGpuEngine::class.java)
                if (VulkanCapabilityChecker.isVulkanAvailable(this@LutistaApp, logger)) {
                    vulkanEngine.init(this@LutistaApp)
                    Timber.i("Vulkan GPU engine initialized: ${vulkanEngine.isAvailable}")
                } else {
                    Timber.i("Vulkan not available, using CPU processing")
                }
            } catch (e: Exception) {
                Timber.e(e, "Vulkan initialization failed")
            }
        }
    }

    private fun initializeBuiltinFilters() {
        applicationScope.launch(Dispatchers.IO) {
            try {
                val builtinFilterManager = get<BuiltinFilterManager>(BuiltinFilterManager::class.java)
                builtinFilterManager.initializeBuiltinFilters()
            } catch (e: Exception) {
                Timber.e(e, "初始化内置滤镜失败")
            }
        }
    }

    companion object {
        lateinit var instance: LutistaApp
            private set
    }
}
