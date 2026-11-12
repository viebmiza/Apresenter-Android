# Keep kotlinx.serialization & OkHttp models intact
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
