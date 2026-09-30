# R8 rules for the release build.
#
# Each rule here exists because shrinking would otherwise remove something reached by reflection,
# and the failure would appear only in a release build on the headset — the worst place to find it.

# kotlinx.serialization keeps its generated serializers by reflection on the @Serializable class.
-keepclassmembers class ai.passioncode.fabricvr.** {
    *** Companion;
}
-keepclasseswithmembers class ai.passioncode.fabricvr.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class ai.passioncode.fabricvr.assistant.**$$serializer { *; }

# Room instantiates the generated implementation by name.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# The JNI bridge is called from C++ by name; nothing in Kotlin references these from Kotlin.
-keep class ai.passioncode.fabricvr.stt.WhisperNative { *; }

# Meta Spatial SDK loads scene components and panel registrations reflectively.
-keep class com.meta.spatial.** { *; }
-dontwarn com.meta.spatial.**

# OkHttp and Okio ship rules of their own; these two only silence platform-optional classes.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
