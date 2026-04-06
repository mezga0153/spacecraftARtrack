# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keepclassmembers class * {
    @javax.inject.Inject <fields>;
    @javax.inject.Inject <init>(...);
}
# Ktor
-keep class io.ktor.** { *; }
-keep class kotlinx.serialization.** { *; }
