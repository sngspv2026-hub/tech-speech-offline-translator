# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.offline.translate.**$$serializer { *; }
-keepclassmembers class com.offline.translate.** {
    *** Companion;
}
-keepclasseswithmembers class com.offline.translate.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# JNI Bridges
-keep class com.offline.translate.engine.asr.jni.** { *; }
-keep class com.offline.translate.engine.mt.jni.** { *; }

# Google ML Kit Translate, Language ID & Text Recognition
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn javax.annotation.**

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
