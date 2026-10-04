import java.io.FileInputStream
import java.util.Properties
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// --- FoxyVPN engine: hev-socks5-tunnel native build (fetched at build time) ---
val hevSocks5TunnelVersion = "2.17.1"
val hevSocks5TunnelDir = file("src/main/foxyjni")

abstract class FetchHevSocks5TunnelTask @Inject constructor(
    private val execOps: ExecOperations,
) : DefaultTask() {
    @get:OutputDirectory
    abstract val targetDir: DirectoryProperty

    @get:Input
    abstract val version: Property<String>

    @TaskAction
    fun fetch() {
        val dir = targetDir.get().asFile
        val marker = File(dir, "Android.mk")
        if (marker.exists()) return
        dir.mkdirs()
        execOps.exec {
            commandLine(
                "git", "clone",
                "--branch", version.get(),
                "--depth", "1",
                "--recursive",
                "--shallow-submodules",
                "https://github.com/heiher/hev-socks5-tunnel.git",
                dir.absolutePath,
            )
        }
    }
}

val fetchHevSocks5Tunnel = tasks.register<FetchHevSocks5TunnelTask>("fetchHevSocks5Tunnel") {
    group = "foxy"
    description = "Clones hev-socks5-tunnel $hevSocks5TunnelVersion for the FoxyVPN tunnel"
    targetDir.set(hevSocks5TunnelDir)
    version.set(hevSocks5TunnelVersion)
    outputs.upToDateWhen { File(hevSocks5TunnelDir, "Android.mk").exists() }
    onlyIf { !File(hevSocks5TunnelDir, "Android.mk").exists() }
}

tasks.matching { it.name.startsWith("externalNativeBuild") || it.name.contains("NdkBuild") }
    .configureEach { dependsOn(fetchHevSocks5Tunnel) }
tasks.named("preBuild").configure { dependsOn(fetchHevSocks5Tunnel) }

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}
val hasReleaseKeystore = keystorePropertiesFile.exists()

android {
    namespace = "dev.cluvex.zedsecure"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.rdx.vpn"
        minSdk = 24
        targetSdk = 36
        versionCode = (project.property("zedsecure.versionCode") as String).toInt()
        versionName = project.property("zedsecure.versionName") as String

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets.getByName("main").assets.srcDir(
        rootProject.file("shared/build/composeResForAndroid"),
    )

    val buildingBundle = gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }
    splits {
        abi {
            isEnable = !buildingBundle
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true

            excludes += "**/x86/*.so"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

configurations.configureEach {
    resolutionStrategy {
        force(
            "org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.get()}",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk7:${libs.versions.kotlin.get()}",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk8:${libs.versions.kotlin.get()}",
        )
    }
}

tasks.matching {
    it.name.matches(Regex("merge.*Assets")) || it.name.contains("lint", ignoreCase = true)
}.configureEach {
    dependsOn(":shared:copyComposeResForAndroid")
}

dependencies {
    implementation(project(":shared"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.graphics.shapes)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)

    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bouncycastle)

    implementation(files("libs/zedcore.aar"))

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
}
