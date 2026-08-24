# --- Ignite: reglas R8 para release ---

# Stack traces útiles en crashes de release
-keepattributes SourceFile,LineNumberTable,RuntimeVisibleAnnotations,AnnotationDefault

# ================= kotlinx.serialization =================
# Serializadores generados en compilación + lookup defensivo por reflexión
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.andyl.ignite.**$$serializer { *; }
-keepclassmembers class com.andyl.ignite.** { *** Companion; }
-keepclasseswithmembers class com.andyl.ignite.** { kotlinx.serialization.KSerializer serializer(...); }

# ================= Ktor (cliente CIO + servidor CIO) =================
# Los engines se resuelven vía ServiceLoader: conservar containers y motores.
-keep class io.ktor.server.cio.** { *; }
-keep class io.ktor.client.engine.cio.** { *; }
-keep class * extends io.ktor.client.HttpClientEngineContainer { *; }
-keep class * implements io.ktor.server.engine.ApplicationEngineFactory { *; }
-keepnames class io.ktor.** { volatile <fields>; }
-dontwarn org.slf4j.**
-dontwarn org.fusesource.jansi.**
-dontwarn io.ktor.**

# ================= zxing / escáner QR =================
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }

# ================= Koin =================
# Definiciones programáticas (sin reflection de nombres), pero conservamos
# las anotaciones por si Koin las usa internamente.
-keep class org.koin.** { *; }
-dontwarn org.koin.**

# ================= FileKit =================
-dontwarn io.github.vinceglb.filekit.**

# Coroutines/Compose traen sus propias reglas embebidas en los artefactos.
