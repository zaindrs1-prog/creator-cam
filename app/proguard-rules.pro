# CreatorCam release shrink rules.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
# CameraX is accessed partly via reflection / Camera2 interop keys.
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**
# Media3 ExoPlayer renderers are reflectively instantiated in some paths.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
# DataStore serializer + Compose navigation args survive shrinking.
-keep class com.creatorcam.app.** { *; }
