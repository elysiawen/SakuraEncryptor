# Keep kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.sakura.encryptor.**$$serializer { *; }
-keepclassmembers class com.sakura.encryptor.** {
    *** Companion;
}
-keepclasseswithmembers class com.sakura.encryptor.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Enums are persisted by name (accent, theme mode, download status), so the
# reflective values() / valueOf() pair has to survive shrinking.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Media3 resolves renderers and extractors reflectively.
-dontwarn androidx.media3.**
-keep class androidx.media3.exoplayer.DefaultRenderersFactory { *; }
