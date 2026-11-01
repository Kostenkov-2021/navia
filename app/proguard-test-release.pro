# Only applied with -PtestRelease (see build.gradle.kts). androidx.test classes in the test APK
# resolve the libraries both APKs share (androidx.tracing, kotlin stdlib, ...) against the app's
# dex, and R8 strips whatever the app itself doesn't use, so keep the app under test unshrunk.
-dontshrink
-dontoptimize
-dontobfuscate
