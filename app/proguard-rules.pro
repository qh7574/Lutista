# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep libgphoto2 JNI
-keep class cn.alittlecookie.lutista.jni.** { *; }

# Keep Room entities
-keep class cn.alittlecookie.lutista.data.database.entity.** { *; }

# Keep Vulkan GPU engine JNI (R8 would strip/rename native methods)
-keep class cn.alittlecookie.lutista.gpu.** { *; }
