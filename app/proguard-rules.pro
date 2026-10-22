# sherpa-onnx is reached from native code (JNI): keep its classes and members as they are
-keep class com.k2fsa.sherpa.onnx.** { *; }

# the swipe models' key mask: ExecuTorch's native code reads it through Tensor's methods by name
-keep class com.ryderdavid.voicebar.swipe.BoolTensor { *; }

# fbjni (under ExecuTorch) ships no keep rules, but its native side finds its Java classes by name
# when ExecuTorch loads (HybridData's destructor, exceptions, threads): stripped, the keyboard
# process aborted the moment the swipe models started loading
-keep class com.facebook.jni.** { *; }
-keep @com.facebook.jni.annotations.DoNotStrip class * { *; }
-keepclassmembers class * { @com.facebook.jni.annotations.DoNotStrip *; }
-keep class com.facebook.soloader.** { *; }
