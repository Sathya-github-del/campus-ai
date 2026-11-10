# StudyApp release shrinking rules.
# kotlinx.serialization and ML Kit ship their own consumer rules; nothing extra needed yet.

# Stack traces in crash reports stay readable (mapping file is kept with the build).
-keepattributes SourceFile,LineNumberTable
