# Keep the JSON serializers
-keepclassmembers class * extends kotlinx.serialization.Serializable { *; }
-keep,includedescriptorclasses class dev.pocketbox.**$$serializer { *; }
-keepclassmembers class dev.pocketbox.** { *** Companion; }
-keepclasseswithmembers class dev.pocketbox.** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn org.apache.commons.compress.**
