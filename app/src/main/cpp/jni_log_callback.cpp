#include "jni_log_callback.h"
#include <android/log.h>
#include <mutex>

namespace jni_log {

namespace {
    JavaVM* g_jvm = nullptr;
    jobject g_callback_obj = nullptr;
    jmethodID g_log_method = nullptr;
    // 保护 g_callback_obj 等全局：cleanup 删除全局引用时，log_callback
    // 可能正由 usbipdcpp 的日志线程执行，不用锁会访问已释放的引用
    std::mutex g_mutex;
}

void init(JNIEnv* env, jobject callback_obj, jmethodID log_method) {
    // MainActivity 的 DisposableEffect 每次重建都会调用 setLogCallback，
    // 不释放旧引用会导致全局引用永久泄漏
    std::lock_guard lock(g_mutex);
    if (g_callback_obj) {
        env->DeleteGlobalRef(g_callback_obj);
    }
    env->GetJavaVM(&g_jvm);
    g_callback_obj = callback_obj;
    g_log_method = log_method;
}

void cleanup(JNIEnv* env) {
    std::lock_guard lock(g_mutex);
    if (g_callback_obj) {
        env->DeleteGlobalRef(g_callback_obj);
        g_callback_obj = nullptr;
    }
    g_jvm = nullptr;
    g_log_method = nullptr;
}

void log_callback(spdlog::level::level_enum level, const std::string& message) {
    // 锁内只派生本地引用并拷贝所需值，JNI 调用放锁外：
    // - 本地引用独立引用计数，锁外调用期间 cleanup 删除全局引用对象仍存活
    //   （快照裸指针方案在这里是 use-after-free，不能用于缩小锁范围）
    // - 锁外执行用户代码，Kotlin 回调未来即使同步触发 native 日志也不会死锁
    JavaVM* jvm = nullptr;
    JNIEnv* env = nullptr;
    jobject callback = nullptr;
    jmethodID method = nullptr;
    bool need_detach = false;
    {
        std::lock_guard lock(g_mutex);
        if (!g_jvm || !g_callback_obj || !g_log_method) return;
        jvm = g_jvm;

        // attach 放锁内执行：JVM 的 attach 只建线程局部表、不执行 Java 代码，
        // 不会回调用户逻辑；需要 GetEnv 后才能在锁内派生本地引用
        int get_env_result = jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (get_env_result == JNI_EDETACHED) {
            if (jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                need_detach = true;
            } else {
                return;
            }
        } else if (get_env_result != JNI_OK) {
            return;
        }

        callback = env->NewLocalRef(g_callback_obj);
        method = g_log_method;
    }
    if (!env || !callback || !method) return;

    jstring jmessage = env->NewStringUTF(message.c_str());
    if (!jmessage) {
        // OOM 时 NewStringUTF 返回 null 并挂 OutOfMemoryError，清掉避免残留
        env->ExceptionClear();
        env->DeleteLocalRef(callback);
        if (need_detach) {
            jvm->DetachCurrentThread();
        }
        return;
    }

    jint jlevel = static_cast<jint>(level);
    env->CallVoidMethod(callback, method, jlevel, jmessage);
    env->DeleteLocalRef(jmessage);
    env->DeleteLocalRef(callback);

    // Kotlin 回调抛异常时清除，避免异常残留在 JNI 栈上破坏后续调用
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }

    if (need_detach) {
        jvm->DetachCurrentThread();
    }
}

} // namespace jni_log