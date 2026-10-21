import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.pportilla.upmmail"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.pportilla.upmmail"
        minSdk = 26
        targetSdk = 36
        // CI builds count up with the workflow run, so Android refuses to install an older build
        // over a newer one (INSTALL_FAILED_VERSION_DOWNGRADE) instead of letting the app wipe its
        // database in onDowngrade. A local build (versionCode 1) therefore cannot be installed over
        // a CI build; forcing it with `adb install -d` wipes the local data (drafts, outbox and
        // changes not yet sent) when its database version is older.
        versionCode = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull()?.let { it + 100 } ?: 1
        versionName = "1.0.0"
    }

    signingConfigs {
        // A committed, well-known debug key so every CI build can be installed as an update
        // over the previous one. It is NOT a secret and must not be used for store releases.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Jakarta Mail discovers its providers reflectively; shrinking buys little here.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
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

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/LICENSE.md",
                "/META-INF/NOTICE.md",
                "/META-INF/versions/*/module-info.class",
            )
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// sqlite-jdbc 3.18.0, the SQLite of Android 8, kept apart from the test classpath: Sqlite318CompatTest
// loads it in its own class loader from the path in -Dsqlite318.jar.
val sqlite318: Configuration by configurations.creating { isTransitive = false }

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.jakarta.mail.api)
    implementation(libs.angus.mail)
    implementation(libs.jsoup)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.greenmail)
    testImplementation(libs.sqlite.jdbc)
    sqlite318(libs.sqlite318.jdbc)
}

tasks.withType<Test>().configureEach {
    val jar: FileCollection = sqlite318
    inputs.files(jar).withPropertyName("sqlite318Jar").withNormalizer(ClasspathNormalizer::class.java)
    jvmArgumentProviders += object : CommandLineArgumentProvider {
        override fun asArguments(): Iterable<String> = listOf("-Dsqlite318.jar=${jar.singleFile.absolutePath}")
    }
}
