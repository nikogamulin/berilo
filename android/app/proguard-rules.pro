# Add project specific ProGuard rules here.
# S2.8: release build has isMinifyEnabled = true; keep rules below cover the
# reflection-heavy libraries in use (Readium, Room, kotlinx-serialization).

# --- kotlinx.serialization -------------------------------------------------
# Standard recipe (https://github.com/Kotlin/kotlinx.serialization#android):
# serializers are looked up via generated `Companion.serializer()` methods
# and `$$serializer` classes, both invisible to R8's shrinker without help.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.berilo.reader.**$$serializer { *; }
-keepclassmembers class app.berilo.reader.** {
    *** Companion;
}
-keepclasseswithmembers class app.berilo.reader.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Room -------------------------------------------------------------------
# Entities/DAOs are referenced from generated *_Impl classes via reflection;
# Room's own AAR consumer rules cover most of this, these are the app-level
# additions for our @Entity/@Database types.
-keep class app.berilo.reader.**.data.db.** { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# --- Clerk (S3.2) -----------------------------------------------------------
# The Clerk AAR ships its own consumer rules, which already keep its serializable
# models and Retrofit interfaces, so nothing is repeated here.
#
# What is needed is these -dontwarn entries. app/build.gradle.kts deliberately excludes
# Clerk's Play-Services dependencies (Google One Tap, Play Integrity): Boox e-ink tablets
# frequently ship without Play Services, and this app signs in with email codes and
# passwords only, so those code paths are unreachable. R8 still sees the references from
# Clerk's compiled SSO/attestation classes and fails the build over classes that are
# absent by design — these silence exactly that, and nothing else.
-dontwarn com.google.android.gms.tasks.**
-dontwarn com.google.android.libraries.identity.googleid.**
-dontwarn com.google.android.play.core.integrity.**

# --- Readium (shared/streamer/navigator) ------------------------------------
# The toolkit parses OPF/NCX/media-type metadata and (de)serializes its model
# classes reflectively; keep the whole package rather than chase individual
# R8 warnings across three modules we don't control.
-keep class org.readium.r2.** { *; }
-keepclassmembers class org.readium.r2.** { *; }
-dontwarn org.readium.r2.**
-dontwarn com.google.gson.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.joda.time.**
-dontwarn org.jsoup.**
-dontwarn org.osgi.framework.**
