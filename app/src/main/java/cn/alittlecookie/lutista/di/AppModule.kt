package cn.alittlecookie.lutista.di

import androidx.room.Room
import cn.alittlecookie.lutista.data.database.AppDatabase
import cn.alittlecookie.lutista.data.database.dao.FilterDao
import cn.alittlecookie.lutista.data.database.dao.TaskDao
import cn.alittlecookie.lutista.data.database.dao.WatermarkDao
import cn.alittlecookie.lutista.data.repository.FilterRepository
import cn.alittlecookie.lutista.data.repository.TaskRepository
import cn.alittlecookie.lutista.data.repository.WatermarkRepository
import cn.alittlecookie.lutista.domain.engine.ImageProcessingEngine
import cn.alittlecookie.lutista.domain.engine.LutProcessor
import cn.alittlecookie.lutista.domain.usecase.ApplyFilterUseCase
import cn.alittlecookie.lutista.domain.usecase.ExportFilterUseCase
import cn.alittlecookie.lutista.domain.usecase.ImportLutUseCase
import cn.alittlecookie.lutista.domain.usecase.ProcessImageUseCase
import cn.alittlecookie.lutista.gpu.VulkanGpuEngine
import cn.alittlecookie.lutista.manager.BuiltinFilterManager
import cn.alittlecookie.lutista.manager.LogManager
import cn.alittlecookie.lutista.manager.PermissionManager
import cn.alittlecookie.lutista.manager.SettingsManager
import cn.alittlecookie.lutista.service.ImageProcessingService
import cn.alittlecookie.lutista.service.TaskSubmissionManager
import cn.alittlecookie.lutista.tether.debug.TetherDebugConfig
import cn.alittlecookie.lutista.tether.debug.TetherDiagLogger
import cn.alittlecookie.lutista.tether.driver.CameraDriverFactory
import cn.alittlecookie.lutista.tether.driver.PtpUsbDriverFactory
import cn.alittlecookie.lutista.tether.profile.CameraProfileRegistry
import cn.alittlecookie.lutista.tether.repository.TetherRepository
import cn.alittlecookie.lutista.tether.transfer.*
import cn.alittlecookie.lutista.data.database.dao.CameraObjectDao
import cn.alittlecookie.lutista.data.database.dao.TetherTransferDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import cn.alittlecookie.lutista.tether.session.TetherRuntime
import cn.alittlecookie.lutista.tether.session.UsbHub
import cn.alittlecookie.lutista.tether.store.CapabilityCache
import cn.alittlecookie.lutista.tether.transport.TransportFactory
import cn.alittlecookie.lutista.tether.transport.UsbTransportFactory
import kotlinx.coroutines.flow.first
import cn.alittlecookie.lutista.util.FileUtils
import cn.alittlecookie.lutista.util.ImageUtils
import cn.alittlecookie.lutista.util.LutParser
import cn.alittlecookie.lutista.util.MediaStoreScanner
import cn.alittlecookie.lutista.watermark.cache.BitmapCache
import cn.alittlecookie.lutista.watermark.manager.HistoryManager
import cn.alittlecookie.lutista.watermark.manager.ResourceManager
import cn.alittlecookie.lutista.watermark.renderer.OutputRenderer
import cn.alittlecookie.lutista.watermark.renderer.WatermarkProcessor
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

val appModule = module {
    // Database
    single<AppDatabase> {
        Room.databaseBuilder(
            androidContext(),
            AppDatabase::class.java,
            "Lutista.db"
        ).addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5).build()
    }

    // DAOs
    single<FilterDao> { get<AppDatabase>().filterDao() }
    single<TaskDao> { get<AppDatabase>().taskDao() }
    single<WatermarkDao> { get<AppDatabase>().watermarkDao() }
    single<CameraObjectDao> { get<AppDatabase>().cameraObjectDao() }
    single<TetherTransferDao> { get<AppDatabase>().tetherTransferDao() }

    // Repositories
    singleOf(::FilterRepository)
    singleOf(::TaskRepository)
    singleOf(::WatermarkRepository)

    // Managers
    singleOf(::LogManager)
    singleOf(::PermissionManager)
    singleOf(::SettingsManager)
    singleOf(::BuiltinFilterManager)

    // Utilities
    singleOf(::FileUtils)
    singleOf(::ImageUtils)
    singleOf(::LutParser)
    singleOf(::MediaStoreScanner)

    // Image Processing Engine
    singleOf(::LutProcessor)
    singleOf(::VulkanGpuEngine)
    singleOf(::ImageProcessingEngine)

    // Use Cases
    singleOf(::ApplyFilterUseCase)
    singleOf(::ImportLutUseCase)
    singleOf(::ExportFilterUseCase)
    singleOf(::ProcessImageUseCase)

    // Services
    single { ImageProcessingService(androidContext(), get(), get(), get(), get(), get(), get()) }
    single { TaskSubmissionManager(get(), get(), get(), get()) }

    // Watermark System
    single { BitmapCache() }
    singleOf(::OutputRenderer)
    singleOf(::WatermarkProcessor)
    singleOf(::ResourceManager)
    singleOf(::HistoryManager)

    // Tether: each runtime owns one hub; both factories receive that same hub explicitly.
    single { CameraProfileRegistry() }
    single { TetherDebugConfig(get()) }
    single { TetherDiagLogger() }
    single {
        val settings = get<SettingsManager>()
        CapabilityCache(read = { settings.tetherCapsCache.first() }, write = settings::setTetherCapsCache)
    }
    factory { UsbHub(androidContext()) }
    factory<TransportFactory> { (hub: UsbHub) -> UsbTransportFactory(hub, get()) }
    factory<CameraDriverFactory> { (hub: UsbHub) ->
        PtpUsbDriverFactory(
            transportFactory = get<TransportFactory> { parametersOf(hub) },
            registry = get(),
            cache = get(),
            config = get(),
            logger = get(),
            requestPermission = { hub.requestPermission(it.device) },
        )
    }
    factory {
        val hub = get<UsbHub>()
        TetherRuntime(hub, get<CameraDriverFactory> { parametersOf(hub) }, get(), get())
    }
    // One formal owner across Activity rotation and the connected-device foreground service.
    single<TransferStore> { RoomTransferStore(get()) }
    single<TransferTargetStorage> { AndroidTransferTargetStorage(androidContext(), get()) }
    single<TetherTaskSubmitter> { TaskSubmissionTetherSubmitter(get(), get(), get(), get()) }
    single { TransferEngine(CoroutineScope(SupervisorJob() + Dispatchers.IO), get(), get(), get()) }
    single { TetherRepository(androidContext(), get(), get(), get(), get(), get(), get()) }
}
