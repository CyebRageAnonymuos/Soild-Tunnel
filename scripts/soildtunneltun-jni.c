/*
 * soildtunneltun-jni.c — SoildTunnel's OWN JNI bridge to hev-socks5-tunnel.
 *
 * PERMANENT FIX for the "VPN mode never connects while proxy mode works" bug:
 *
 * The app used to System.loadLibrary("hev-socks5-tunnel") and rely on hev's
 * bundled hev-jni.c (built with -DPKGNAME=...) to register the TProxy*
 * natives onto com.soildtunnel.app.core.TProxyService. That coupled the
 * Kotlin declarations to WHATEVER JNI signatures the upstream default branch
 * happens to use. Upstream then changed TProxyStartService from
 * '(Ljava/lang/String;I)V' to '(Ljava/lang/String;I)Z', RegisterNatives
 * inside its JNI_OnLoad failed with:
 *   NoSuchMethodError: no static or non-static method
 *   "Lcom/soildtunnel/app/core/TProxyService;.TProxyStartService(Ljava/lang/String;I)Z"
 * System.loadLibrary() threw, TProxyService.available stayed false, and VPN
 * mode died with "hev native library unavailable". Proxy mode never loads
 * hev, which is why it kept working.
 *
 * This bridge removes that failure mode CATEGORICALLY:
 *  - It binds ONLY hev's stable public C API (include/hev-main.h):
 *      hev_socks5_tunnel_main / hev_socks5_tunnel_quit / hev_socks5_tunnel_stats
 *    which is verified at LINK time (-Wl,--no-undefined) — if upstream ever
 *    breaks it, the CI build fails loudly instead of shipping a broken APK.
 *  - It exposes conventional Java_* symbols that WE control, matching the
 *    Kotlin declarations in TProxyService.kt exactly.
 *  - It defines its OWN JNI_OnLoad. This is CRITICAL: ART locates JNI_OnLoad
 *    with dlsym() on the loaded library's handle, and dlsym() searches the
 *    library AND its DT_NEEDED dependencies. Without our own JNI_OnLoad,
 *    dlsym() found hev's one inside libhev-socks5-tunnel.so and executed it
 *    anyway; its RegisterNatives then failed on upstream's drifted
 *    'TProxyStopService()Z' signature (observed in the field, round 2).
 *    Ours shadows it (the root object is searched first), and
 *    build-natives.sh ALSO strips hev-jni.c out of the hev build entirely,
 *    so upstream JNI ABI drift can never again break library loading.
 *
 * THREADING (do not "simplify" this): the tunnel event loop MUST run on a
 * native pthread. hev-task-system implements its coroutines by swapping the
 * thread's stack pointer; doing that on an ART-attached Java thread corrupts
 * what the runtime expects of the stack and kills the whole app with a native
 * SIGSEGV shortly after real traffic starts. It must also run IN-PROCESS,
 * because the VpnService TUN fd is only valid inside this process.
 */

#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

/* hev-socks5-tunnel's stable public C API (include/hev-main.h). */
extern int hev_socks5_tunnel_main(const char *config_path, int tun_fd);
extern void hev_socks5_tunnel_quit(void);
extern void hev_socks5_tunnel_stats(size_t *tx_packets, size_t *tx_bytes,
                                    size_t *rx_packets, size_t *rx_bytes);

#define TAG "soildtunnel-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/*
 * Our own JNI_OnLoad — it registers NOTHING and only reports the JNI version,
 * but it MUST exist. ART resolves "JNI_OnLoad" with dlsym() on this library's
 * handle, and dlsym() searches this object FIRST, then its DT_NEEDED
 * dependencies. If this symbol were missing, dlsym() could find a JNI_OnLoad
 * inside a dependency (as happened with hev's hev-jni.c) and run foreign
 * RegisterNatives code with signatures we do not control. Never remove this.
 */
JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void)vm;
    (void)reserved;
    LOGI("libsoildtunnel-jni r3 loaded: conventional Java_* bindings; hev JNI layer unused");
    return JNI_VERSION_1_6;
}

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static bool running = false;

typedef struct {
    char *config_path;
    int tun_fd;
} StartArgs;

static void *
tunnel_thread(void *data)
{
    StartArgs *args = (StartArgs *)data;
    int res;

    LOGI("tunnel loop starting on native pthread (fd=%d)", args->tun_fd);
    res = hev_socks5_tunnel_main(args->config_path, args->tun_fd);
    LOGI("tunnel loop exited (res=%d)", res);

    pthread_mutex_lock(&lock);
    running = false;
    pthread_mutex_unlock(&lock);

    free(args->config_path);
    free(args);
    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_com_soildtunnel_app_core_TProxyService_TProxyStartService(JNIEnv *env,
                                                                jclass clazz,
                                                                jstring config_path,
                                                                jint tun_fd)
{
    const char *path = NULL;
    StartArgs *args = NULL;
    pthread_attr_t attr;
    pthread_t thread;
    int rc;

    (void)clazz;

    pthread_mutex_lock(&lock);
    if (running) {
        pthread_mutex_unlock(&lock);
        LOGI("tunnel already running; start request ignored");
        return JNI_TRUE;
    }
    running = true; /* claimed; rolled back on any failure below */
    pthread_mutex_unlock(&lock);

    path = (*env)->GetStringUTFChars(env, config_path, NULL);
    if (!path)
        goto fail;

    args = (StartArgs *)malloc(sizeof(StartArgs));
    if (!args) {
        (*env)->ReleaseStringUTFChars(env, config_path, path);
        goto fail;
    }
    args->config_path = strdup(path);
    args->tun_fd = (int)tun_fd;
    (*env)->ReleaseStringUTFChars(env, config_path, path);
    if (!args->config_path) {
        free(args);
        goto fail;
    }

    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    rc = pthread_create(&thread, &attr, tunnel_thread, args);
    pthread_attr_destroy(&attr);
    if (rc != 0) {
        LOGE("pthread_create failed: %d", rc);
        free(args->config_path);
        free(args);
        goto fail;
    }
    return JNI_TRUE;

fail:
    pthread_mutex_lock(&lock);
    running = false;
    pthread_mutex_unlock(&lock);
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_soildtunnel_app_core_TProxyService_TProxyStopService(JNIEnv *env, jclass clazz)
{
    (void)env;
    (void)clazz;
    hev_socks5_tunnel_quit();
}

JNIEXPORT jlongArray JNICALL
Java_com_soildtunnel_app_core_TProxyService_TProxyGetStats(JNIEnv *env, jclass clazz)
{
    size_t tx_packets = 0, tx_bytes = 0, rx_packets = 0, rx_bytes = 0;
    jlong values[4];
    jlongArray result;

    (void)clazz;

    hev_socks5_tunnel_stats(&tx_packets, &tx_bytes, &rx_packets, &rx_bytes);

    values[0] = (jlong)tx_packets;
    values[1] = (jlong)tx_bytes;
    values[2] = (jlong)rx_packets;
    values[3] = (jlong)rx_bytes;

    result = (*env)->NewLongArray(env, 4);
    if (!result)
        return NULL;
    (*env)->SetLongArrayRegion(env, result, 0, 4, values);
    return result;
}
