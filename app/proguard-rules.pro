# Hermie ProGuard rules

# Keep JNI methods (llama.cpp native bridge)
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep kotlinllamacpp classes (JNI + event classes)
-keep class org.nehuatl.llamacpp.** { *; }

# Keep sherpa-onnx classes (JNI)
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Keep notification listener service
-keep class com.hermie.assistant.modules.notifications.HermieNotificationListener { *; }
