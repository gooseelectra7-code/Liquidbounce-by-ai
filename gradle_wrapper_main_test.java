// Test class to verify Gradle wrapper classes are accessible
// Compile with: javac gradle_wrapper_main_test.java
// Run with: java gradle_wrapper_main_test
// Classpath should be: gradle-dist/lib/*;gradle-dist/lib/plugins/gradle-wrapper-main-9.7.1.jar;.

public class gradle_wrapper_main_test {
    public static void main(String[] args) {
        System.out.println("Testing GradleWrapperMain accessibility...");
        
        // Try to load the class
        try {
            Class<?> clazz = Class.forName("org.gradle.wrapper.GradleWrapperMain");
            System.out.println("SUCCESS: GradleWrapperMain class found!");
            System.out.println("Class: " + clazz.getName());
            System.out.println("Location: " + clazz.getProtectionDomain().getCodeSource().getLocation());
        } catch (ClassNotFoundException e) {
            System.out.println("FAILED: GradleWrapperMain class not found!");
            System.out.println("Error: " + e.getMessage());
            
            // Try alternative class name
            try {
                Class<?> clazz = Class.forName("org.gradle.launcher.GradleMain");
                System.out.println("Alternative found: GradleMain!");
            } catch (ClassNotFoundException e2) {
                System.out.println("GradleMain also not found");
            }
        }
        
        // List available main classes
        System.out.println("\nAvailable classes containing 'wrapper' in name:");
        try {
            for (Package pkg : java.lang.Package.getPackages()) {
                if (pkg.getName().contains("gradle")) {
                    System.out.println("Package: " + pkg.getName());
                }
            }
        } catch (Exception e) {
            // ignore
        }
    }
}