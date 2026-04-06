# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keepclassmembers class * {
    @javax.inject.Inject <fields>;
    @javax.inject.Inject <init>(...);
}
# Ktor
-keep class io.ktor.** { *; }
-keep class kotlinx.serialization.** { *; }
# Suppress R8 warnings about JVM-only classes used in Ktor's debug utilities
-dontwarn java.lang.management.**
-dontwarn io.ktor.util.debug.**
