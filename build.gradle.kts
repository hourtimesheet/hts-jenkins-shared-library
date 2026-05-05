import org.gradle.api.tasks.wrapper.Wrapper.DistributionType.ALL

plugins {
  id("groovy")
}

group = "com.hourtimesheet"
version = "1.0"

repositories {
  mavenCentral()
  maven(url = "https://repo.jenkins-ci.org/releases/")
  maven(url = "https://repo.jenkins-ci.org/public/")
}

dependencies {
  implementation(libs.groovy)
  implementation(libs.apache.commonlang3)
  implementation(libs.jenkin.core)

  testImplementation(libs.groovy)
  testImplementation(libs.spock.core)
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Jenkins shared-library convention: vars/ holds DSL steps, src/ holds typed
// classes, resources/ holds resource files. Tests live under test/.
sourceSets.main {
  groovy.srcDirs("src", "vars")
  resources.srcDirs("resources")
}

sourceSets.test {
  groovy.srcDirs("test")
  resources.srcDirs("test/resources")
}

java {
  withSourcesJar()
  toolchain {
    languageVersion = JavaLanguageVersion.of(11)
  }
}

tasks.test {
  useJUnitPlatform()
  testLogging {
    events("passed", "skipped", "failed")
    showStandardStreams = false
  }
}

tasks {
  wrapper {
    description = "Update gradle wrapper to specified version."
    gradleVersion = "8.10.2"
    distributionType = ALL
  }
}
