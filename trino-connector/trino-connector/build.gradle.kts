/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
plugins {
  `maven-publish`
  id("java")
  id("idea")
}

repositories {
  mavenCentral()
}

dependencies {
  implementation(project(":catalogs:catalog-common"))
  implementation(project(":clients:client-java-runtime", configuration = "shadow"))
  implementation(libs.airlift.json)
  implementation(libs.bundles.log4j)
  implementation(libs.commons.collections4)
  implementation(libs.commons.lang3)
  compileOnly(libs.airlift.resolver)
  implementation("io.trino:trino-jdbc:478") {
    exclude("org.apache.logging.log4j")
  }
  compileOnly("io.trino:trino-spi:478") {
    exclude("org.apache.logging.log4j")
  }
  testImplementation(libs.awaitility)
  testImplementation(libs.mockito.core)
  testImplementation(libs.mysql.driver)
  testImplementation("io.trino:trino-memory:478") {
    exclude("org.antlr")
    exclude("org.apache.logging.log4j")
    exclude(group = "org.junit.jupiter")
    exclude(group = "org.junit.platform")
    exclude(group = "org.junit.vintage")
    exclude(group = "junit")
  }
  testImplementation("io.trino:trino-testing:478") {
    exclude("org.apache.logging.log4j")
    exclude(group = "org.junit.jupiter")
    exclude(group = "org.junit.platform")
    exclude(group = "org.junit.vintage")
    exclude(group = "junit")
  }
  testImplementation(libs.junit.jupiter.api)
  testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.named("generateMetadataFileForMavenJavaPublication") {
  dependsOn(":trino-connector:trino-connector:copyDepends")
}

tasks {
  val copyDepends by registering(Copy::class) {
    from(configurations.runtimeClasspath)
    into("build/libs")
  }
  jar {
    finalizedBy(copyDepends)
  }

  register("copyLibs", Copy::class) {
    dependsOn(copyDepends, "build")
    from("build/libs")
    into("$rootDir/distribution/${rootProject.name}-trino-connector")
  }
}
