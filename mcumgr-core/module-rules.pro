# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

############################################################
## Jackson
############################################################
-keepattributes Signature,Annotation,EnclosingMethod,InnerClasses

-dontwarn com.fasterxml.jackson.**
-keep class com.fasterxml.jackson.** { *; }

-keepclassmembers class * {
     @com.fasterxml.jackson.annotation.JsonCreator *;
     @com.fasterxml.jackson.annotation.JsonProperty *;
}

# Keep all Response classes as they are used reflectively by Jackson.
-keep class no.nordicsemi.android.mcumgr.response.** { *; }

# SLF4J
-dontwarn org.slf4j.impl.StaticLoggerBinder
