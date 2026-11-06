# JNI entry points called from native code (libservoshell.so):
-keep class org.servo.servoview.** { *; }
-keep class me.ik.argon.MediaFocus { *; }

# kotlinx.serialization model classes (reflection via generated serializers):
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <init>(...);
}
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepnames @kotlinx.serialization.Serializable class *

# Compose runtime safety (standard for R8 + Compose):
-keep class androidx.compose.** { *; }
-dontwarn org.slf4j.**
