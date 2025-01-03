/*
 * This file was generated by the Gradle 'init' task.
 *
 * The settings file is used to specify which projects to include in your build.
 *
 * Detailed information about configuring a multi-project build in Gradle can be found
 * in the user manual at https://docs.gradle.org/6.0.1/userguide/multi_project_builds.html
 */
pluginManagement {
    pluginManagement {
        repositories {
            gradlePluginPortal()
            maven("https://repo.papermc.io/repository/maven-public/")
        }
    }
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
        id("io.papermc.paperweight.userdev") version "2.0.0-beta.11"

        // maven() // no longer needed in gradle 7
    }
}

rootProject.name = "xv"
include(":xc")
project(":xc").projectDir = File("../xc")