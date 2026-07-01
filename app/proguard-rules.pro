# Add project specific ProGuard rules here.
-keepattributes Signature
-keepattributes *Annotation*

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Kotlinx Serialization
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep,includedescriptorclasses class com.termuxagent.**$$serializer { *; }
-keepclassmembers class com.termuxagent.** {
    *** Companion;
}
-keepclasseswithmembers class com.termuxagent.** {
    kotlinx.serialization.KSerializer serializer(...);
}
