# R-21: release keep rules. R8 aggressively strips reflection-driven code;
# the two reflection boundaries in this app are UniFFI/JNA (Rust FFI) and
# sherpa-onnx JNI.

# --- UniFFI / JNA: entry points are looked up by name from Kotlin ---
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn com.sun.jna.**

# UniFFI-generated bindings (dev.vynkor.agent.*): uniffi's Kotlin runtime
# resolves interfaces/records via JNA and cleans pointers via
# `destroy()`/`uniffiClonePointer()` — keep the surface intact.
-keep class uniffi.** { *; }
-keep class dev.vynkor.agent.Agent { *; }
-keep class dev.vynkor.agent.AgentConfig { *; }
-keep class dev.vynkor.agent.AgentHolder { *; }
-keep class dev.vynkor.agent.ActionReply* { *; }
-keep class dev.vynkor.agent.Location { *; }
-keep interface dev.vynkor.agent.AgentObserver { *; }
-keep interface dev.vynkor.agent.BatteryProvider { *; }
-keep interface dev.vynkor.agent.ContactsProvider { *; }
-keep interface dev.vynkor.agent.ClipboardProvider { *; }
-keep interface dev.vynkor.agent.LocationProvider { *; }
-keep interface dev.vynkor.agent.SpeakerSink { *; }

# Implementations registered over the FFI boundary are called from native.
-keep class dev.vynkor.agent.caps.** { *; }
-keep class dev.vynkor.agent.notifications.** { *; }

# --- sherpa-onnx JNI: native code resolves Java methods by name ---
-keep class com.k2fsa.sherpa.onnx.** { *; }
