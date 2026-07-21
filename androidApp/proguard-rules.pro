# 保留崩溃堆栈行号
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.lhstack.suxi.dns.**$$serializer { *; }
-keepclassmembers class com.lhstack.suxi.dns.** {
    *** Companion;
}
-keepclasseswithmembers class com.lhstack.suxi.dns.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Cronet（HTTP/3）
-keep class org.chromium.net.** { *; }
-dontwarn org.chromium.net.**

# Compose / AndroidX 由依赖自带 consumer rules 处理
