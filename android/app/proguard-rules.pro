# Keep Kotlinx Serialization generated serializers & Retrofit API interfaces
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.pillscope.app.data.remote.dto.**$$serializer { *; }
-keepclassmembers class com.pillscope.app.data.remote.dto.** { *** Companion; }
