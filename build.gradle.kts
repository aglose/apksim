plugins { java }
repositories { mavenCentral(); google() }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
sourceSets { main { java.srcDirs("runtime/src/main/java") } }
// For the arm64 Linux image only: android-all 16-robolectric-13921718-i7 (below) has no linux/aarch64
// libandroid_runtime, which Android 15+ frameworks run on. Its respin -2-i7 adds one and is otherwise the same native
// libraries byte for byte (its classes differ only in how Robolectric instrumented them), so the image takes that one
// library from it (`gradle linuxArm64Runtime`, run by scripts/docker-build.sh --platform linux/arm64).
val linuxArm64Runtime: Configuration by configurations.creating
dependencies {
    implementation("org.robolectric:robolectric:4.17")
    implementation("junit:junit:4.13.2")
    compileOnly("org.robolectric:android-all:16-robolectric-13921718")
    runtimeOnly("org.robolectric:android-all-instrumented:16-robolectric-13921718-i7")
    // Robolectric 4.17 brings 1.0.19; 1.0.20 is the same files plus native/linux/aarch64 (the arm64 Linux image).
    runtimeOnly("org.robolectric:nativeruntime-dist-compat:1.0.20")
    linuxArm64Runtime("org.robolectric:android-all-instrumented:16-robolectric-13921718-2-i7") { isTransitive = false }
}
tasks.register<Copy>("deps") { from(configurations.runtimeClasspath); into("work/lib") }
tasks.register<Copy>("linuxArm64Runtime") {
    from({ linuxArm64Runtime.map { zipTree(it) } }) { include("native/linux/aarch64/*.so"); eachFile { path = name } }
    includeEmptyDirs = false
    into("work/linux-arm64")
}
