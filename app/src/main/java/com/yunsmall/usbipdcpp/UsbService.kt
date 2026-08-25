package com.yunsmall.usbipdcpp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

class UsbService : Service() {

    companion object {
        private const val TAG = "UsbService"
        private const val NOTIFICATION_CHANNEL_ID = "usbipd_service"
        private const val NOTIFICATION_ID = 1
    }

    private val binder = UsbBinder()
    private val scope = CoroutineScope(Dispatchers.Default)

    // 保存活跃的USB连接
    private data class DeviceInfo(
        val connection: UsbDeviceConnection,
        val fd: Int,
        val busid: String
    )
    // 写操作都在 nativeDispatcher 单线程，但 UI 线程会读（boundDeviceNames/getBusid），
    // 用 ConcurrentHashMap：弱一致迭代不会抛 ConcurrentModificationException
    private val activeDevices = java.util.concurrent.ConcurrentHashMap<String, DeviceInfo>()

    // 写在 nativeDispatcher 线程、读在 UI 线程，需要 @Volatile 保证可见性
    @Volatile
    var serverRunning = false
        private set
    @Volatile
    var port = 3240
        private set

    val boundDeviceNames: Set<String>
        // 返回拷贝而非 keys 视图：UI 线程迭代时 native 线程可能正在改 map，
        // 视图迭代会抛 ConcurrentModificationException
        get() = activeDevices.keys.toSet()

    fun getBusid(deviceName: String): String? = activeDevices[deviceName]?.busid

    inner class UsbBinder : Binder() {
        fun getService(): UsbService = this@UsbService
    }

    // native 层初始化状态：init 失败（库加载失败等）时后续绑定/启服全部
    // 不可用，暴露给 UI 层检查
    @Volatile
    var nativeReady = false
        private set

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        nativeReady = UsbIpNative.init()
        if (!nativeReady) {
            Log.e(TAG, "Native initialization failed, USB/IP features unavailable")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        // native 清理含 join 线程等耗时操作，在主线程 runBlocking 会卡 ANR，
        // 放到后台线程执行（进程退出时系统回收，不保证执行完）。
        // closeAllDevices 也放在 native 线程内：销毁期间 MainActivity 的协程
        // 可能仍在 nativeDispatcher 上执行 bindDevice，map 写必须同线程串行
        Thread {
            UsbIpNative.runOnNativeThread {
                if (UsbIpNative.isServerRunning()) {
                    UsbIpNative.stopServer()
                }
                // 这里调的是 native 的 stopServer（external），不会清理
                // Kotlin 层连接，closeAllDevices 只执行一次，无重复
                closeAllDevices()
                UsbIpNative.release()
            }
        }.start()
        scope.cancel()
    }

    suspend fun startServer(port: Int): Boolean {
        if (serverRunning) return true

        return withContext(UsbIpNative.nativeDispatcher) {
            val success = UsbIpNative.startServer(port)
            if (success) {
                this@UsbService.port = port
                serverRunning = true
                updateNotification()
            }
            success
        }
    }

    suspend fun stopServer() {
        withContext(UsbIpNative.nativeDispatcher) {
            UsbIpNative.stopServer()
            serverRunning = false
            // 关设备也在 native 线程内，避免 UI 线程迭代/清空 map 与并发绑定冲突
            closeAllDevices()
        }
        updateNotification()
    }

    suspend fun bindDevice(usbManager: UsbManager, device: UsbDevice): DeviceBindResult {
        // 块内全是同步 JNI 调用、无挂起点，协程取消只会在 withContext 返回后
        // 抛出，不会中断块内的资源清理（connection 的开关都在块内完成）
        val result = withContext(UsbIpNative.nativeDispatcher) {
            // 防御：同一设备已绑定则拒绝，否则覆盖 map 条目导致旧连接泄漏
            if (activeDevices.containsKey(device.deviceName)) {
                return@withContext DeviceBindResult.Failure.DeviceInUse
            }

            val connection = usbManager.openDevice(device)
            if (connection == null) {
                return@withContext DeviceBindResult.Failure.DeviceOpenFailed
            }

            val fd = getFileDescriptorFromConnection(connection)
            if (fd < 0) {
                connection.close()
                return@withContext DeviceBindResult.Failure.DeviceOpenFailed
            }

            val outBusid = arrayOfNulls<String>(1)
            val nativeResult = UsbIpNative.bindUsbDeviceNative(fd, device.vendorId, device.productId, outBusid)

            when (nativeResult) {
                UsbIpNative.ErrorCode.SUCCESS -> {
                    val busid = outBusid[0]!!
                    activeDevices[device.deviceName] = DeviceInfo(connection, fd, busid)
                    Log.i(TAG, "Device bound: ${device.deviceName} -> $busid")
                    DeviceBindResult.Success(busid)
                }
                else -> {
                    connection.close()
                    mapErrorCodeToResult(nativeResult)
                }
            }
        }
        return result
    }

    suspend fun unbindDevice(deviceName: String): DeviceUnbindResult {
        val result = withContext(UsbIpNative.nativeDispatcher) {
            val info = activeDevices[deviceName]
            if (info == null) {
                return@withContext DeviceUnbindResult.Failure.DeviceNotFound
            }

            val nativeResult = UsbIpNative.unbindUsbDeviceNative(info.fd)

            when (nativeResult) {
                UsbIpNative.ErrorCode.SUCCESS -> {
                    activeDevices.remove(deviceName)?.connection?.close()
                    Log.i(TAG, "Device unbound: $deviceName")
                    DeviceUnbindResult.Success
                }
                UsbIpNative.ErrorCode.DEVICE_NOT_FOUND -> {
                    activeDevices.remove(deviceName)?.connection?.close()
                    Log.w(TAG, "Device already gone in native: $deviceName")
                    DeviceUnbindResult.Failure.DeviceNotFound
                }
                UsbIpNative.ErrorCode.DEVICE_IN_USE -> {
                    DeviceUnbindResult.Failure.DeviceInUse
                }
                else -> {
                    Log.e(TAG, "Unknown unbind error: $nativeResult for $deviceName")
                    DeviceUnbindResult.Failure.UnknownError
                }
            }
        }
        return result
    }

    suspend fun handleDeviceDetached(deviceName: String): Boolean {
        // 整个方法体在 nativeDispatcher 内执行：map 的读写都必须与
        // bindDevice/unbindDevice 同线程，否则 UI 线程的 remove 与
        // native 线程的写入并发修改 HashMap
        return withContext(UsbIpNative.nativeDispatcher) {
            val info = activeDevices[deviceName] ?: return@withContext false
            UsbIpNative.notifyDeviceRemovedNative(info.busid)
            activeDevices.remove(deviceName)?.connection?.close()
            Log.i(TAG, "Device detached: $deviceName")
            true
        }
    }

    private fun closeAllDevices() {
        activeDevices.values.forEach { it.connection.close() }
        activeDevices.clear()
        Log.i(TAG, "All devices closed")
    }

    private fun getFileDescriptorFromConnection(connection: UsbDeviceConnection): Int {
        // getFileDescriptor 是隐藏 API，没有公开替代，反射是唯一途径；
        // frameworks 层该实现多年未变，失败时返回 -1 由调用方兜底
        return try {
            val method = connection.javaClass.getDeclaredMethod("getFileDescriptor")
            method.isAccessible = true
            method.invoke(connection) as Int
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get file descriptor", e)
            -1
        }
    }

    private fun mapErrorCodeToResult(errorCode: Int): DeviceBindResult.Failure {
        return when (errorCode) {
            UsbIpNative.ErrorCode.DEVICE_NOT_FOUND -> DeviceBindResult.Failure.DeviceNotFound
            UsbIpNative.ErrorCode.DEVICE_IN_USE -> DeviceBindResult.Failure.DeviceInUse
            UsbIpNative.ErrorCode.DEVICE_OPEN_FAILED -> DeviceBindResult.Failure.DeviceOpenFailed
            UsbIpNative.ErrorCode.GET_DESCRIPTOR_FAILED -> DeviceBindResult.Failure.GetDescriptorFailed
            UsbIpNative.ErrorCode.GET_CONFIG_FAILED -> DeviceBindResult.Failure.GetConfigFailed
            UsbIpNative.ErrorCode.CLAIM_INTERFACE_FAILED -> DeviceBindResult.Failure.ClaimInterfaceFailed
            else -> DeviceBindResult.Failure.UnknownError
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "USB/IP Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.server_running))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification())
    }
}
