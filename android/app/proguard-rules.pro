# SafeBank AI ProGuard Rules

# TensorFlow Lite
-keep class org.tensorflow.lite.** { *; }

# Retrofit & Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.safebank.ai.data.model.** { *; }

# Credential Manager
-keep class androidx.credentials.** { *; }
