# OkHttp keeps optional Conscrypt/BouncyCastle hooks it probes reflectively.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# kotlinx.serialization writes its own serializers; the JSON tree model is
# reflective only at the `JsonElement` level, which the library ships rules for.
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class kotlinx.serialization.json.** { *; }
