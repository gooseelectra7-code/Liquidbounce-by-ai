// Test which classes are available for Gradle wrapper
import java.util.*;

public class test_gradle_classes {
    public static void main(String[] args) {
        System.out.println("Testing available Gradle classes...\n");
        
        // Test class loaders to understand classpath
        System.out.println("Classpath from system property:");
        System.out.println(System.getProperty("java.class.path"));
        System.out.println();
        
        // Try to load various gradle-related classes
        String[] classesToTest = {
            "org.gradle.wrapper.GradleWrapperMain",
            "org.gradle.launcher.GradleMain",
            "org.gradle.wrapper.GradleWrapperSupport",
            "org.gradle.launcher.launcher.GradleLauncher",
            "org.gradle.api.Gradle",
            "org.gradle.api.initialization.Settings",
            "org.gradle.cli.CliLoader",
            "groovy.lang.GroovyObject"
        };
        
        for (String className : classesToTest) {
            try {
                Class<?> clazz = Class.forName(className);
                System.out.println("FOUND: " + className);
            } catch (ClassNotFoundException e) {
                System.out.println("NOT FOUND: " + className);
            }
        }
        
        // List all classes in gradle-wrapper jar if we can find it
        System.out.println("\n--- Searching for wrapper-related classes ---");
        try {
            String cp = System.getProperty("java.class.path");
            String[] paths = cp.split("[\n:]", -1); // Split on newlines and colons
            
            for (String path : paths) {
                if (path.contains("gradle-wrapper") && path.endsWith(".jar")) {
                    System.out.println("Wrapper jar in classpath: " + path);
                }
            }
        } catch (Exception e) {
            System.out.println("Error parsing classpath: " + e.getMessage());
        }
    }
}