# ponytail: shrink-only. Keep every class except the Material icons pack (37 MB, we use ~40 icons),
# so reflection users (vlcj/JNA, dbus-java, protobuf, kotlinx.serialization, ktor, quickjs) keep working
# untouched. Upgrade path: narrow this keep rule per library to also shrink Compose and friends.
-keep class !androidx.compose.material.icons.**,** { *; }
-keepattributes *
-dontobfuscate
-dontoptimize
-dontnote **
-dontwarn **
-ignorewarnings
