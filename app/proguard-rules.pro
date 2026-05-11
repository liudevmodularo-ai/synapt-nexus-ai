# ─── Synapt Nexus AI — ProGuard Rules ────────────────────────────────────────

# Keep JNI entry points (llama.cpp bridge)
-keep class com.synapt.nexus.inference.LlamaCppBridge {
    native <methods>;
    public *;
}

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class **$$serializer { *; }
-keep @kotlinx.serialization.Serializable class * { *; }

# Keep Ktor internal classes
-keep class io.ktor.** { *; }
-keep class io.netty.** { *; }
-dontwarn io.netty.**

# Keep BouncyCastle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Keep ONNX Runtime
-keep class ai.onnxruntime.** { *; }

# Keep Room entities
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# Keep data classes used in serialization
-keep class com.synapt.nexus.network.** { *; }
-keep class com.synapt.nexus.security.** { *; }
-keep class com.synapt.nexus.model.** { *; }

# WireGuard
-keep class com.wireguard.** { *; }
-keep class org.jmdns.** { *; }

# General Android
-keepattributes SourceFile,LineNumberTable
-dontwarn okhttp3.**
-dontwarn okio.**
