plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
}

// -Papksim.targetSdk=28 builds the same app targeting that SDK (docs/conformance.md) into app/build-t28/ (APKs
// app-t28-debug.apk and app-t28-debug-androidTest.apk under outputs/apk/); without the property nothing changes.
// A folder of its own, as AGP deletes the other APKs of an outputs folder when it packages one.
val apksimTargetSdk = (findProperty("apksim.targetSdk") as String?)?.toInt()
if (apksimTargetSdk != null) {
  base.archivesName.set("app-t$apksimTargetSdk")
  layout.buildDirectory.set(layout.projectDirectory.dir("build-t$apksimTargetSdk"))
  // src/t28/AndroidManifest.xml, if there is one for the target, is merged over the main manifest (debug variant).
  if (file("src/t$apksimTargetSdk/AndroidManifest.xml").exists()) {
    extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
      sourceSets.getByName("debug").manifest.srcFile("src/t$apksimTargetSdk/AndroidManifest.xml")
    }
  }
}

android {
  namespace = "dev.apksim.composeapp"
  compileSdk = 36
  defaultConfig {
    applicationId = "dev.apksim.composeapp"
    minSdk = 26
    targetSdk = apksimTargetSdk ?: 36
    versionCode = 1
    versionName = "1.0"
    testInstrumentationRunner = "dev.apksim.composeapp.HarnessRunner"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = false
    shaders = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  // apksim://drawer: a DrawerLayout settles through ViewDragHelper's Scroller, with no animator (issue 215)
  implementation(libs.androidx.drawerlayout)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation("androidx.compose.material:material-icons-core")
  // Window size classes: a navigation rail and a grid once the window is 600 dp wide (apksim: `device tablet`, `size`)
  implementation(libs.androidx.compose.material3.adaptive)
  // Video: the Settings tab's "Play clip" (HEVC + AAC through MediaCodec and AudioTrack)
  implementation(libs.androidx.media3.exoplayer)
  // apksim://camera: a CameraX preview and frame analysis on apksim's fake camera (--camera fake).
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
  // apksim://health, HealthConnectTest: Health Connect through AndroidX's client (apksim's stand-in, docs/health-connect.md)
  implementation(libs.androidx.health.connect.client)
  // Network: NetworkActivity fetches over HTTPS (apksim --network record/replay and conditions, docs/network.md)
  implementation(libs.okhttp)
  // Time zones: Joda-Time up to 2.10 reads user.timezone before the JDK's default (apksim://timezone, WallClockTest;
  // issue 91). 2.12 reads a property of its own instead, so this stays on 2.10.
  implementation(libs.joda.time)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.services.storage)
  // On a device, the Test Services APK serves Test Storage (RecordingListener, RunnerListenersTest); apksim answers it itself.
  androidTestUtil(libs.androidx.test.services)
  androidTestImplementation(libs.androidx.test.espresso.core)
  androidTestImplementation(libs.androidx.test.uiautomator)
  androidTestImplementation(libs.mockito.android)
  // Conformance tests (docs/conformance.md): fragments and navigation as the libraries document them.
  implementation(libs.androidx.fragment.ktx)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.navigation.fragment.ktx)
  implementation(libs.androidx.navigation.compose)
  debugImplementation(libs.androidx.fragment.testing.manifest)
  androidTestImplementation(libs.androidx.fragment.testing)
  // Batch 2: RecyclerView and Espresso's RecyclerViewActions.
  implementation(libs.androidx.recyclerview)
  androidTestImplementation(libs.androidx.test.espresso.contrib)
  // Batch 3: WorkManager (in the app, so its startup initializer and job service are the app's, as on a device),
  // Room (the database is the tests' own, compiled by KSP), DataStore (in the app: in the test APK alone its tracing
  // classes were left out on a device, since the app had an older androidx.tracing) and espresso-intents.
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.androidx.room.runtime)  // WorkManager's own Room, at the tests' version
  androidTestImplementation(libs.androidx.work.testing)
  androidTestImplementation(libs.androidx.room.ktx)
  kspAndroidTest(libs.androidx.room.compiler)
  implementation(libs.androidx.datastore.preferences)  // in the app, as an app that uses it has it
  androidTestImplementation(libs.androidx.test.espresso.intents)

  // Navigation
  
  
  
}
