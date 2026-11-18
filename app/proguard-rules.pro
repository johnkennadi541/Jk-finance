# SkyPlus Finance ProGuard / R8 Rules
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# SQLCipher native bridge
-keep class net.zetetic.** { *; }
-keepclassmembers class net.zetetic.** { *; }

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.skyplus.finance.**$$serializer { *; }
-keepclassmembers class com.skyplus.finance.** {
    *** Companion;
}
-keepclasseswithmembers class com.skyplus.finance.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Firebase Firestore & Google Credential / Auth models
-keepclassmembers class com.skyplus.finance.data.cloud.dto.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod
-keep @interface androidx.annotation.Keep
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep <fields>;
    @androidx.annotation.Keep <methods>;
}
