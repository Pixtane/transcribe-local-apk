# sherpa-onnx JNI reads Kotlin config classes by field name and constructs GeneratedAudio.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }

# commons-compress references optional codecs (xz, zstd, brotli, asm) we do not ship.
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.**
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
# PdfBox-Android loads fonts/encodings reflectively and from bundled resources.
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
# jsoup optional regex engine
-dontwarn com.google.re2j.**
-ignorewarnings
