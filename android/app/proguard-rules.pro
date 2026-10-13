# R8 / ProGuard rules for the release build.
#
# The default Android rules plus Compose's own consumer rules cover almost everything here.
# What is below is only what R8 genuinely cannot see, and each entry says why it is needed so
# that a future change does not quietly delete it and ship a broken build.

# --- org.json -----------------------------------------------------------
# The app's whole storage format. R8 sees these calls in the code, but the android.jar it
# links against has org.json stubbed, so it warns that the classes are missing. They are not:
# the platform provides them at runtime. Without this the build prints a wall of warnings that
# people learn to ignore, and one real warning along with them.
-dontwarn org.json.**

# --- Fabricated / reflective -------------------------------------------
# Nothing here is loaded by name at runtime. Stated so that a future feature that does need it
# knows this is where the rule belongs, rather than discovering a ClassNotFoundException on a
# surveyor's phone.
-dontwarn javax.xml.parsers.**
-dontwarn org.xml.sax.**

# --- Line numbers -------------------------------------------------------
# Kept because a stack trace from a release build is the only trace there will ever be, and a
# release-only crash with no line numbers is close to undebuggable on a device that is not
# attached to a machine.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile