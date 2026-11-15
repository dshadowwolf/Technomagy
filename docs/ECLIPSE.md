# Eclipse setup

Use Java 21 and import the inner `Technomagy` directory as an **Existing Gradle
Project**. The Eclipse workspace can be its parent `Technomagy 121NEO` directory.
Keep the project name **Technomagy**, including the capital T. Eclipse requires
that name to match the folder when the project is directly inside its workspace.
The Minecraft mod ID remains `technomagy`.

Select the checked-in Gradle wrapper. It pins Gradle 9.7.1 and verifies the
downloaded distribution's SHA-256. A system Gradle installation is not required.

For an existing import, select the project, press **F5**, then use
**Gradle → Refresh Gradle Project**. Synchronization prepares the processed
resources, UTF-8 encoding and normal client/server launch files. Launch the
generated **Client** configuration under **Run Configurations → Java Application**.
It uses the `Technomagy` project and the production classes in `bin/main`.

The same setup can be generated and checked from PowerShell with Java 21:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:GRADLE_USER_HOME = 'D:\path\to\your\gradle-cache'
.\gradlew.bat '-Declipse.application=org.eclipse.jdt.core.JavaCore' verifyEclipseSetup
```

The Eclipse marker enables ModDevGradle's native launch-file generation outside
the IDE. Buildship supplies it during normal Eclipse synchronization.
The setup preserves Buildship's project link and records the Java and cache
locations used by setup, so subsequent refreshes and launches resolve the same
dependency files. Those machine-specific settings are ignored by Git.

GameTests and screenshot probes run through their explicit Gradle tasks or
properties. They are excluded from the normal Eclipse project and client:

```powershell
.\gradlew.bat runGameTestServer
.\gradlew.bat runClient -PdirectorClientCheck=true
```

If this machine needs the guarded GLFW runtime, set `glfwLibrary` in the ignored
`local.properties` file to the absolute path of that DLL, using forward slashes.
Alternatively pass `-PglfwLibrary=C:/path/to/glfw.dll`. The configured file must
exist. This applies to Gradle and generated Eclipse client launches without
adding a machine-specific path to the shared build.
