// The fixture server the test app's gRPC, WebSocket and HLS screens talk to (scripts/protocols-check.sh): gRPC-java's own
// server (Netty) and OkHttp's MockWebServer, so what the app meets is a real implementation of each protocol, not apksim's.
// `./gradlew :fixture:installDist`, then fixture/build/install/fixture/bin/fixture --help.
plugins {
  application
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
}

dependencies {
  implementation(libs.grpc.netty.shaded)
  implementation(libs.grpc.stub)
  implementation(libs.okhttp.mockwebserver3)
  implementation(libs.okhttp.tls)
}

application {
  mainClass.set("dev.apksim.fixture.ProtoFixture")
}
