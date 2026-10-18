# AndroidJUnitRunner discovers JUnit classes, methods and rules by reflection.
# The generator and QA scenario names must also remain usable with -e class.
# Error Prone IncompatibleModifiers is CLASS-retained annotation metadata. Its
# deprecated value() references the JDK compiler enum, absent from Android and
# never used by the test runtime. Keep this suppression limited to that enum.
-dontwarn javax.lang.model.element.Modifier
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class org.isoron.uhabits.benchmark.** { *; }
-keep class androidx.test.runner.AndroidJUnitRunner { *; }
-keep class * extends org.junit.runner.Runner { *; }
-keepclassmembers class * {
    @org.junit.Test <methods>;
    @org.junit.Before <methods>;
    @org.junit.After <methods>;
    @org.junit.BeforeClass <methods>;
    @org.junit.AfterClass <methods>;
    @org.junit.Rule <fields>;
    @org.junit.Rule <methods>;
    @org.junit.ClassRule <fields>;
    @org.junit.ClassRule <methods>;
}
