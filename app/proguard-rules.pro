# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
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

# CRITICAL FOR RELEASE BUILDS: Preserve Firebase/ML Kit ComponentRegistrars loaded via Manifest reflection!
-keep class com.google.firebase.components.** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }
-keep class com.google.android.libraries.vision.** { *; }
-keep class com.google.android.odml.** { *; }
-keep class com.google.barhopper.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**
-dontwarn com.google.android.libraries.vision.**
-dontwarn javax.annotation.**

# Tesseract4Android Native JNI OCR
-keep class com.googlecode.tesseract.** { *; }
-keep class com.googlecode.leptonica.** { *; }
-dontwarn com.googlecode.tesseract.**

# Custom JNI Bridges
-keep class com.offline.translate.engine.asr.jni.** { *; }
-keep class com.offline.translate.engine.mt.jni.** { *; }

# Accessibility & Overlay Services
-keep class com.offline.translate.app.ScreenTextAccessibilityService { *; }
-keep class com.offline.translate.app.FloatingTranslatorOverlayService { *; }

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
