# Keep rules for the OpenVPN core. The engine calls back into Java/Kotlin from
# native code and loads the notification implementation by class name, so class
# and member names must survive shrinking.
-keep class com.tim.** { *; }
-keep class net.openvpn.ovpn3.** { *; }
-keep class com.mdc.vpn.vpn.MdcVpnNotification { *; }

-keepclasseswithmembernames class * {
    native <methods>;
}

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

-dontwarn org.slf4j.**
-dontwarn org.apache.**
-dontwarn kotlinx.coroutines.debug.**
