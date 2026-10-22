# Release builds carry no logging (Phase 2, channel 6).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
}

# Stack traces in Play vitals stay deobfuscatable via the uploaded mapping file.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# No other rules: manifest components are kept automatically and nothing uses reflection
# (Gate C bans Class.forName). Intel data is read as Java resources, which R8 keeps.
