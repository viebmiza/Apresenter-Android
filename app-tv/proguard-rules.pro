# Keep serialization and Media3 classes intact
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
