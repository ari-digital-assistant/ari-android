# Keep enough of a stack trace to be worth reading. Play's crash reports are
# the only view we get of a release build going wrong on someone else's phone.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# UniFFI talks to the Rust engine through JNA, which maps Kotlin classes onto C
# layouts by field name and instantiates callback interfaces reflectively.
# Rename any of it and the FFI breaks at runtime with no build-time warning.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keep class * implements com.sun.jna.Library { *; }
-keepclassmembers class * extends com.sun.jna.Structure { *; }
-keep class uniffi.** { *; }

# JNA is a desktop library first; on Android its AWT references resolve to
# nothing and R8 would otherwise refuse to build over the missing classes.
-dontwarn java.awt.**

# sherpa-onnx's JNI reads its config objects field by field with GetFieldID,
# and the AAR ships an empty proguard.txt — nothing else keeps these.
-keep class com.k2fsa.sherpa.onnx.** { *; }
