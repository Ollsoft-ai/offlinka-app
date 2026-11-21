# JNA + UniFFI bindings are reflective: keep them whole.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class cz.offlinka.core.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
