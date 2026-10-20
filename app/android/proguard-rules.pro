# Keep JNI bridge methods in MoshUdpEngine / Rust native library
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.toxicbakery.app.bison.ssh.** { *; }
-keep class com.toxicbakery.app.bison.protocol.** { *; }
-keep class com.toxicbakery.app.bison.model.** { *; }
