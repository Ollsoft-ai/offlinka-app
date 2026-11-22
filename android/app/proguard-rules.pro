# JNA + UniFFI rules come from :core (consumer-rules.pro).
# Room entities and DAOs are generated and kept by Room's own rules.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Release builds drop verbose, debug and info logging (no file names or page details in logcat).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
