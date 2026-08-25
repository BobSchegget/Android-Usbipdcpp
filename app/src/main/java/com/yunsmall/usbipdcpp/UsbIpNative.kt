package com.yunsmall.usbipdcpp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

object UsbIpNative {
    private const val TAG = "UsbIpNative"

    // 单线程 Dispatcher，确保所有 LibusbServer 调用在同一线程
    val nativeDispatcher = Dispatchers.IO.limitedParallelism(1)

    // 错误码定义 - 必须与 JNI 层 usbipd_jni.cpp 中的 ErrorCode 命名空间保持一致
    object ErrorCode {
        const val SUCCESS = 0
        const val DEVICE_NOT_FOUND = 1
        const val DEVICE_IN_USE = 2
        const val DEVICE_OPEN_FAILED = 3
        const val GET_DESCRIPTOR_FAILED = 4
        const val GET_CONFIG_FAILED = 5
        const val CLAIM_INTERFACE_FAILED = 6
        const val UNKNOWN_ERROR = 99
    }

    external fun nativeInit(): Boolean
    // callback 传 null 表示清除回调（释放 JNI 全局引用），UI 销毁时调用
    external fun setLogCallback(callback: LogCallback?)
    external fun bindUsbDeviceNative(fd: Int, vendorId: Int, productId: Int, outBusid: Array<String?>): Int
    external fun unbindUsbDeviceNative(fd: Int): Int
    external fun notifyDeviceRemovedNative(busid: String)
    external fun startServer(port: Int): Boolean
    external fun stopServer()
    external fun isServerRunning(): Boolean
    external fun release()

    /**
     * 在 native 线程同步执行代码块。
     * 注意：runBlocking 会阻塞当前线程，禁止在主线程调用（会 ANR）
     */
    @androidx.annotation.WorkerThread
    fun runOnNativeThread(block: () -> Unit) {
        runBlocking {
            withContext(nativeDispatcher) {
                block()
            }
        }
    }

    fun init(): Boolean {
        return try {
            // 放这里而非 init 块：对象初始化时抛 UnsatisfiedLinkError 会污染类初始化，
            // 后续任何访问都抛 ExceptionInInitializerError，本函数的 try-catch 也救不回
            System.loadLibrary("usbipdcpp_native")
            nativeInit()
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library", e)
            false
        }
    }
}
