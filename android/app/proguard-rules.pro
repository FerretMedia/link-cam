# JNI calls these by name.
-keep class cool.linker.cam.uvc.UvcDevice { native <methods>; }
-keep class cool.linker.cam.uvc.UvcException { <init>(java.lang.String); }

# ML Kit finds its parts by reflection, from class names in its manifest, and
# makes each with its no-argument constructor. R8 can't see that and strips
# them, which leaves text recognition (reading the camera's pairing screen)
# without its registrars in a release build.
-keep class com.google.mlkit.**.*Registrar { <init>(); }
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
