-keepattributes *Annotation*
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}
-keep class com.offline.translate.engine.asr.jni.** { *; }
-keep class com.offline.translate.engine.mt.jni.** { *; }
