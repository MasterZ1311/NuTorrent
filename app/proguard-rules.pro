# Proguard rules for NuTorrent release builds

# Preserve line numbers and source files for Google Play Console deobfuscation
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable,EnclosingMethod,InnerClasses,Signature,*Annotation*

# Room Database persistence
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>();
}
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
-keep class com.nutorrent.data.local.entity.** { *; }

# Torrent Metainfo and protocol models
-keep class com.nutorrent.torrent.metainfo.** { *; }
-keep class com.nutorrent.torrent.model.** { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
