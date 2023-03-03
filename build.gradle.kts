/*
 * This file was generated by the Gradle 'init' task.
 *
 * This generated file contains a sample Kotlin application project to get you started.
 */

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import io.papermc.paperweight.userdev.PaperweightUser
import io.papermc.paperweight.tasks.RemapJar

// plugin versioning
version = "0.0.2"

// base of output jar name
val OUTPUT_JAR_NAME = "xc"

// target will be set to minecraft version by cli input parameter
var target = ""

plugins {
    // Apply the Kotlin JVM plugin to add support for Kotlin.
    id("org.jetbrains.kotlin.jvm") version "1.6.10"
    id("com.github.johnrengelman.shadow") version "7.1.2"
    // maven() // no longer needed in gradle 7
    
    // include paperweight, but DO NOT APPLY BY DEFAULT...
    // we need imports, but only conditionally apply it for 1.17+ builds
    // for 1.16.5, we don't want to apply because not supported, it
    // does not allow building unless a bundle version is applied,
    // which does not exist for 1.16.5, so its impossible to build with
    // paperweight plugin enabled on 1.16.5
    // https://stackoverflow.com/questions/62579114/how-to-optionally-apply-some-plugins-using-kotlin-dsl-and-plugins-block-with-gra

    // i fucking hate gradle and cant configure this
    // just manually uncomment

    // USE FOR 1.16.5, UNCOMMENT WHEN NEEDED :^(
    // id("io.papermc.paperweight.userdev") version "1.3.8" apply false
    
    // USE FOR 1.18.2 (DEFAULT)
    id("io.papermc.paperweight.userdev") version "1.3.8"

    // Apply the application plugin to add support for building a CLI application.
    application
}

repositories {
    // Use jcenter for resolving dependencies.
    // You can declare any Maven/Ivy/file repository here.
    jcenter()
    
    maven { // paper
        url = uri("https://papermc.io/repo/repository/maven-public/")
    }
    maven { // worldguard
        url = uri("https://maven.enginehub.org/repo/")
    }
}

configurations {
    create("resolvableImplementation") {
        isCanBeResolved = true
        isCanBeConsumed = true
    }

    // Special configuration with priority over compileOnly.
    // Required so we can include NMS and bukkit as dependency,
    // without overriding the paper API (which has slight differences
    // in api). See:
    // https://github.com/gradle/gradle/issues/10502
    // https://stackoverflow.com/questions/31698510/can-i-force-the-order-of-dependencies-in-my-classpath-with-gradle/47953373#47953373
    create("compileOnlyPriority") {
        isCanBeResolved = true
        isCanBeConsumed = true
        sourceSets["main"].compileClasspath = configurations["compileOnlyPriority"] + sourceSets["main"].compileClasspath
    }
}


dependencies {
    // Align versions of all Kotlin components
    compileOnly(platform("org.jetbrains.kotlin:kotlin-bom"))

    // Use the Kotlin JDK 8 standard library.
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    // uncomment to shadow into jar
    // configurations["resolvableImplementation"]("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    // Kotlin reflect api
    compileOnly("org.jetbrains.kotlin:kotlin-reflect")
    // uncomment to shadow into jar
    // configurations["resolvableImplementation"]("org.jetbrains.kotlin:kotlin-reflect")
    
    // toml parsing library
    compileOnly("org.tomlj:tomlj:1.1.0")
    configurations["resolvableImplementation"]("org.tomlj:tomlj:1.1.0")

    // paper api
    // api("com.destroystokyo.paper:paper-api:1.16.5-R0.1-SNAPSHOT")
    
    // world guard region protection
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.7")

    // Use the Kotlin test library.
    testImplementation("org.jetbrains.kotlin:kotlin-test")

    // Use the Kotlin JUnit integration.
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")

    // MINECRAFT VERSION SPECIFIC BUILD CONFIGURATION
    if ( project.hasProperty("1.16") == true ) {
        java.toolchain.languageVersion.set(JavaLanguageVersion.of(16)) // need java==16 for 1.16.5
        sourceSets["main"].java.srcDir("src/nms/v1_16_R3")
        // compileOnly(files("./lib/craftbukkit-1.16.5.jar"))
        compileOnly(files("./lib/spigot-1.16.5.jar"))
        configurations["compileOnlyPriority"]("com.destroystokyo.paper:paper-api:1.16.5-R0.1-SNAPSHOT")
        target = "1.16.5"
    }
    else if ( project.hasProperty("1.18") == true ) {
        java.toolchain.languageVersion.set(JavaLanguageVersion.of(17)) // need java==17 for 1.18.2
        sourceSets["main"].java.srcDir("src/nms/v1_18_R2")
        // apply<PaperweightUser>() // applies the paper weight plugin for minecraft nms classes
        paperDevBundle("1.18.2-R0.1-SNAPSHOT") // contains 1.18.2 nms classes
        compileOnly("io.papermc.paper:paper-api:1.18.2-R0.1-SNAPSHOT")
        target = "1.18.2"

        tasks {
            assemble {
                // must write it like below because in 1.16 config, reobfJar does not exist
                // so the simpler definition below wont compile
                // dependsOn(reobfJar) // won't compile :^(
                dependsOn(project.tasks.first { it.name.contains("reobfJar") })
            }
        }

        tasks.named("reobfJar") {
            base.archivesBaseName = "${OUTPUT_JAR_NAME}-${target}-SNAPSHOT"
        }
    }
}

application {
    // Define the main class for the application.
    mainClassName = "phonon.xc.XCPluginKt"
}

tasks.withType<KotlinCompile> {
    kotlinOptions.freeCompilerArgs = listOf("-Xallow-result-return-type")
}

tasks {
    named<ShadowJar>("shadowJar") {
        // verify valid target minecraft version
        doFirst {
            val supportedMinecraftVersions = setOf("1.16.5", "1.18.2")
            if ( !supportedMinecraftVersions.contains(target) ) {
                throw Exception("Invalid Minecraft version! Supported versions are: 1.16, 1.18")
            }
        }

        classifier = ""
        configurations = mutableListOf(project.configurations.named("resolvableImplementation").get()) as List<FileCollection>
    }
}

tasks {
    build {
        dependsOn(shadowJar)
    }
    
    test {
        testLogging.showStandardStreams = true
    }
}

gradle.taskGraph.whenReady {
    tasks {
        named<ShadowJar>("shadowJar") {
            if ( hasTask(":release") ) {
                baseName = "${OUTPUT_JAR_NAME}-${target}"
                // minimize() // FOR PRODUCTION USE MINIMIZE
            }
            else {
                baseName = "${OUTPUT_JAR_NAME}-${target}-SNAPSHOT"
                // minimize() // FOR PRODUCTION USE MINIMIZE
            }
        }
    }
}