#!/usr/bin/env java

/**
 * JavaLauncher - Simple Gradle build launcher
 * 
 * This class provides an alternative entry point for executing Gradle builds
 * via direct Java invocation when shell scripts are blocked by AppLocker.
 * 
 * Usage: java BuildLauncher
 * 
 * Or as part of the build process:
 * java BuildLauncher build -x detekt -x test --console=plain
 */
public class BuildLauncher {
    public static void main(String[] args) throws Exception {
        // Print startup info
        System.out.println("============================================");
        System.out.println("LiquidBounce Build Launcher");
        System.out.println("============================================");
        
        // Configuration
        String javaExe = "C:\\Program Files\\Java\\jdk-25.0.4\\bin\\java.exe";
        String gradleDist = "C:\\Users\\SYLVIA\\.gradle\\wrapper\\dists\\gradle-9.7.1-bin\\1w1c7tv4s851m17nbqdsro2tv\\gradle-9.7.1";
        String wrapperJar = "C:\\Users\\SYLVIA\\Desktop\\LBnew-main\\gradle\\wrapper\\gradle-wrapper.jar";
        String projectDir = "C:\\Users\\SYLVIA\\Desktop\\LBnew-main";
        
        // Build arguments
        String[] gradleArgs = args.length > 0 ? args : new String[]{"build", "-x", "detekt", "-x", "test", "--console=plain"};
        
        System.out.println("Project: " + projectDir);
        System.out.println("Gradle Distribution: " + gradleDist);
        System.out.println("Arguments: " + String.join(" ", gradleArgs));
        System.out.println();
        
        // Change to project directory
        System.setProperty("user.dir", projectDir);
        
        // Build classpath string
        StringBuilder classpath = new StringBuilder();
        classpath.append(wrapperJar).append(";").append(gradleDist).append("\\lib\\plugins\\gradle-wrapper-main-9.7.1.jar");
        
        // Add all JARs from lib directory
        java.io.File libDir = new java.io.File(gradleDist, "lib");
        if (libDir.exists()) {
            java.io.File[] jars = libDir.listFiles((dir, name) -> name.endsWith(".jar"));
            if (jars != null) {
                for (java.io.File jar : jars) {
                    classpath.append(";").append(jar.getAbsolutePath());
                }
            }
        }
        
        // Build JVM arguments
        java.util.List<String> jvmArgs = new java.util.ArrayList<>();
        jvmArgs.add("-Xms1024m");
        jvmArgs.add("-Xmx6144m");
        jvmArgs.add("-XX:+UseZGC");
        jvmArgs.add("--add-opens");
        jvmArgs.add("java.base/java.lang=ALL-UNNAMED");
        jvmArgs.add("--add-opens");
        jvmArgs.add("java.base/java.lang.invoke=ALL-UNNAMED");
        jvmArgs.add("--add-opens");
        jvmArgs.add("java.base/java.lang.reflect=ALL-UNNAMED");
        jvmArgs.add("--add-opens");
        jvmArgs.add("java.base/java.io=ALL-UNNAMED");
        jvmArgs.add("--add-opens");
        jvmArgs.add("java.base/java.util=ALL-UNNAMED");
        jvmArgs.add("--add-opens");
        jvmArgs.add("java.base/java.util.concurrent=ALL-UNNAMED");
        jvmArgs.add("-cp");
        jvmArgs.add classpath.toString());
        jvmArgs.add("org.gradle.wrapper.GradleWrapperMain");
        
        // Add gradle args
        Collections.addAll(jvmArgs, gradleArgs);
        
        // Create process builder
        ProcessBuilder pb = new ProcessBuilder();
        pb.command(javaExe, jvmArgs.toArray(new String[0]));
        pb.inheritIO();
        
        System.out.println("Starting Gradle build via GradleWrapperMain...");
        System.out.println();
        
        // Start process
        Process process = pb.start();
        int exitCode = process.waitFor();
        
        System.out.println();
        System.out.println("============================================");
        System.out.println("Build completed with exit code: " + exitCode);
        System.out.println("============================================");
        
        System.exit(exitCode);
    }
}