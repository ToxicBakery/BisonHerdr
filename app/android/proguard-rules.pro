# Keep JNI bridge methods in MoshUdpEngine / Rust native library
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.toxicbakery.app.bison.ssh.** { *; }
-keep class com.toxicbakery.app.bison.protocol.** { *; }
-keep class com.toxicbakery.app.bison.model.** { *; }

# Keep JSch classes loaded reflectively via Class.forName(...) (jce.Random, ciphers, MACs, hashes, KEX, UserAuth, etc.)
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.bouncycastle.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.**
-dontwarn com.sun.jna.**
-dontwarn org.newsclub.net.unix.**
