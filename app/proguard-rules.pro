# Android XR compile-only extension classes are supplied on-device at runtime.
# :xr_extensions_stubs provides SAM interfaces at compile time so R8 does not
# strip androidx.xr callbacks into empty classes (wmr-companion ONY-758).
-dontwarn com.android.extensions.xr.**
-dontwarn android.extensions.xr.**
-dontwarn com.android.internal.annotations.GuardedBy

# Platform DisplayManager shadow stubs compatibility (wmr-companion ONY-557).
-keep class android.hardware.display.** { *; }

# JNI entry points called from libhelix_moonlight.so (helix_moonlight_jni.cpp)
-keep class com.nightcinder.helix.stream.MoonlightNativeBridge {
    public <methods>;
    native <methods>;
}
-keep class com.nightcinder.helix.core.model.StreamTelemetry {
    public <init>(...);
    <fields>;
}
-keep class com.nightcinder.helix.core.model.StreamTransportMode { *; }
-keep class com.nightcinder.helix.core.model.StreamCodec { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.nightcinder.helix.**$$serializer { *; }
-keepclassmembers class com.nightcinder.helix.** {
    *** Companion;
}
-keepclasseswithmembers class com.nightcinder.helix.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Tink / Conscrypt / OkHttp
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
