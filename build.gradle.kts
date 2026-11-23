plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "sk.arvyx"
version = "1.1.3"

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.259-stable")
    implementation("redis.clients:jedis:5.2.0") {
        exclude(group = "com.google.code.gson", module = "gson")
        exclude(group = "org.slf4j", module = "slf4j-api")
    }

    testImplementation("io.papermc.paper:paper-api:26.3.build.259-stable")
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName.set("ArvyxCore")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("")
    relocate("redis.clients.jedis", "sk.arvyx.core.libs.jedis")
    relocate("org.apache.commons.pool2", "sk.arvyx.core.libs.pool2")
    relocate("org.json", "sk.arvyx.core.libs.json")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.jar {
    archiveClassifier.set("thin")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
