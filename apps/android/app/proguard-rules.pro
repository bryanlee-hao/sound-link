# Diagnostics record exception class names via getSimpleName(); keep them readable.
-keepnames class app.soundlink.android.** extends java.lang.Throwable

# Keep line numbers so crash stack traces can be retraced with the build's mapping.txt.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
