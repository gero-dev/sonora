# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class app.sonora.** {
    *** Companion;
}
-keepclasseswithmembers class app.sonora.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.sonora.**$$serializer { *; }
