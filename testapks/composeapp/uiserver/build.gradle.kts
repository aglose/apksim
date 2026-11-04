// A tool's on-device server in miniature, as the Android CLI's layout server is built: an APK that holds only an
// instrumentation targeting itself, started with `am instrument`, reading the screen through UiAutomation and
// answering on a local socket that adb forwards. apksim's CI installs it over adb into a running instance
// (scripts/ci-smoke.sh), and its own copy of AndroidX Test must stay apart from the app's.
plugins {
  alias(libs.plugins.android.application)
}

android {
  namespace = "dev.apksim.uiserver"
  compileSdk = 36
  defaultConfig {
    applicationId = "dev.apksim.uiserver"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

dependencies {
  implementation(libs.androidx.test.runner)
}
