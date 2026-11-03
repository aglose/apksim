plugins { java }
repositories { mavenCentral(); google() }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
sourceSets { main { java.srcDirs("runtime/src/main/java") } }
dependencies {
    implementation("org.robolectric:robolectric:4.17")
    implementation("junit:junit:4.13.2")
    compileOnly("org.robolectric:android-all:16-robolectric-13921718")
    runtimeOnly("org.robolectric:android-all-instrumented:16-robolectric-13921718-i7")
}
tasks.register<Copy>("deps") { from(configurations.runtimeClasspath); into("work/lib") }
