# Hermie ProGuard rules

# Keep JNI methods (llama.cpp native bridge)
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep llama.cpp JNI bridge classes
-keep class com.hermie.llamacpp.** { *; }

# Keep sherpa-onnx classes (JNI)
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Keep notification listener service
-keep class com.hermie.assistant.modules.notifications.HermieNotificationListener { *; }
