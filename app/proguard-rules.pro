# R8 is shrink-only (build.gradle sets -dontobfuscate implicitly via this file below);
# names are preserved so reflection/native-by-name keeps working.
-dontobfuscate

# Native Minima API classes are delivered by the bundled AAR.
-keep class org.minimarex.minimaapi.** { *; }

# JNA + lazysodium: heavy reflection + native binding — keep everything.
-keep class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-keep class com.goterl.lazysodium.** { *; }
-keep interface com.goterl.lazysodium.** { *; }
-dontwarn com.goterl.lazysodium.**

# JSch fork (SFTP) — reflection for KEX/cipher classes.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# ZXing (QR) and org.json.
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
-keep class org.json.** { *; }

# The exported broadcast receiver is referenced from the manifest.
-keep class com.eurobuddha.salon.SalonNotifyReceiver { *; }
