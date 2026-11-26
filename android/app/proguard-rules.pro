# Keep Kotlinx Serialization generated serializers & Retrofit API interfaces
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.pillscope.app.data.remote.dto.**$$serializer { *; }
-keepclassmembers class com.pillscope.app.data.remote.dto.** { *** Companion; }
-keep class com.pillscope.app.data.remote.dto.** { *; }

# Keep Retrofit service interfaces
-keep interface com.pillscope.app.data.remote.PillScopeApi { *; }

# Keep Room entities & DAOs
-keep class com.pillscope.app.data.local.** { *; }

# Keep LiteRT / ML Kit JNI classes
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn com.google.ai.edge.litert.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
