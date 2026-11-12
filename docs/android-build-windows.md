# Windows Android build

Inspected configuration: AGP 8.7.3, Gradle 8.10.2, Kotlin 2.0.21,
Java/Kotlin target 21, compile/target SDK 35. The installed Oracle JDK is 26.0.2;
Android Studio bundles Java 25.0.4. Neither is suitable for this Gradle version.

JDK 21 is now installed at `C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot`.
The build script automatically finds installed Temurin 21 when `.jdk21` is absent.
`assembleDebug` succeeded with this JDK; the APK is at the output path below.
`testDebugUnitTest` also passed: 66 tests, zero failures, errors, or skips.
Gradle installed SDK Platform 35 and Build Tools 34 using existing accepted licenses.

Download **Eclipse Temurin 21, Windows, x64, JDK, ZIP**:
https://adoptium.net/temurin/releases/?version=21&os=windows&arch=x64&package=jdk

Extract the ZIP. Move the contents of its versioned JDK directory into
`android\.jdk21` so that `android\.jdk21\bin\java.exe` and
`android\.jdk21\bin\javac.exe` exist. This directory is ignored by Git.
No installer, system PATH change, or system JAVA_HOME change is needed.

From the project directory:

```powershell
& '.\android\.jdk21\bin\java.exe' -version
.\android\build-debug.ps1
```

Alternatively, keep the extracted JDK elsewhere and use:

```powershell
.\android\build-debug.ps1 -JdkHome 'C:\path\to\jdk-21'
```

The script validates JDK 21, temporarily sets process-local JAVA_HOME, explicitly
selects the Gradle daemon JVM, runs assembleDebug and testDebugUnitTest, and
restores the previous environment even on failure. It does not modify execution
policy or install software. Kotlin and Java compilation passed with JDK 21.

In Android Studio, open the existing `android` project, then Settings > Build,
Execution, Deployment > Build Tools > Gradle > Gradle JDK. Add/select the same
JDK 21 directory. This is a project selection; keep Java 25 installed.

Expected successful output: `android\app\build\outputs\apk\debug\app-debug.apk`.
