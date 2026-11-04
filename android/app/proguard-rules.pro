# The Go core is called through JNI by name: keep the gomobile runtime and bindings.
-keep class go.** { *; }
-keep class app.iykyk.core.** { *; }
-keepclassmembers class * implements go.Seq$Proxy { *; }
