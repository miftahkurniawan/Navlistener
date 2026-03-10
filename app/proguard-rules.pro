# NavListener ProGuard Rules

# Keep HiveMQ MQTT client
-keep class com.hivemq.** { *; }
-dontwarn com.hivemq.**

# Keep Notification Listener
-keep class com.navlistener.NavListenerService { *; }
-keep class com.navlistener.NavState { *; }

# Keep JSON
-keep class org.json.** { *; }
