# Keep JNI entry points used by the native tun2socks bridge.
-keepclasseswithmembernames class * {
    native <methods>;
}
