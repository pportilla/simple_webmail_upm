import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.security.KeyStore
import java.security.PrivateKey

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release numbering is independent of debug CI runs. Increase this for each Play upload.
val releaseVersionCode = providers.gradleProperty("releaseVersionCode")
    .orElse(providers.environmentVariable("UPM_RELEASE_VERSION_CODE"))
    .orElse("8").get().toIntOrNull()?.takeIf { it in 1..2_100_000_000 }
    ?: throw GradleException("releaseVersionCode must be an integer between 1 and 2100000000.")
val releaseVersionName = providers.gradleProperty("releaseVersionName")
    .orElse(providers.environmentVariable("UPM_RELEASE_VERSION_NAME"))
    .orElse("1.1.0").get().also {
        require(it.isNotBlank() && it.length <= 100) { "releaseVersionName must contain 1–100 characters." }
    }
val debugVersionCode = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull
    ?.toIntOrNull()?.let { it + 100 } ?: 1
val unsignedRelease = providers.gradleProperty("unsignedRelease").orNull == "true"
val requireReleaseSigning = providers.gradleProperty("requireReleaseSigning").orNull == "true"
require(!(unsignedRelease && requireReleaseSigning)) { "A required signed release cannot be unsigned." }

// Secrets belong in the environment or an external, owner-readable properties file.
// No release ever falls back to the public debug key.
val uploadProperties = Properties()
if (!unsignedRelease) {
    providers.environmentVariable("UPM_SIGNING_PROPERTIES_FILE").orNull?.let { path ->
        val propertiesFile = File(path)
        require(propertiesFile.isAbsolute && propertiesFile.isFile) {
            "UPM_SIGNING_PROPERTIES_FILE must identify an existing absolute file outside the repository."
        }
        require(!propertiesFile.canonicalFile.toPath().startsWith(rootDir.canonicalFile.toPath())) {
            "Keep signing properties outside the repository."
        }
        propertiesFile.reader(Charsets.UTF_8).use { uploadProperties.load(it) }
    }
}
fun uploadSetting(environmentName: String, propertyName: String): String? =
    if (unsignedRelease) null else providers.environmentVariable(environmentName).orNull
        ?: uploadProperties.getProperty(propertyName)

val uploadStorePath = uploadSetting("UPM_UPLOAD_KEYSTORE", "storeFile")
val uploadStorePassword = uploadSetting("UPM_UPLOAD_STORE_PASSWORD", "storePassword")
val uploadKeyAlias = uploadSetting("UPM_UPLOAD_KEY_ALIAS", "keyAlias")
val uploadKeyPassword = uploadSetting("UPM_UPLOAD_KEY_PASSWORD", "keyPassword")
val uploadSettings = listOf(uploadStorePath, uploadStorePassword, uploadKeyAlias, uploadKeyPassword)
val hasUploadSigning = uploadSettings.all { !it.isNullOrEmpty() }
if (!unsignedRelease && uploadSettings.any { it != null } && !hasUploadSigning) {
    throw GradleException("Incomplete upload signing configuration. All four upload signing settings are required.")
}
if (requireReleaseSigning && !hasUploadSigning) {
    throw GradleException("Upload signing is required. Run scripts/setup-signing.py and set UPM_SIGNING_PROPERTIES_FILE, or supply the four UPM_UPLOAD_* variables.")
}
if (hasUploadSigning) {
    val uploadFile = File(requireNotNull(uploadStorePath))
    require(uploadFile.isAbsolute && uploadFile.isFile &&
        !uploadFile.canonicalFile.toPath().startsWith(rootDir.canonicalFile.toPath())) {
        "The upload keystore must be an existing absolute file outside the repository."
    }
    val uploadStore = try {
        KeyStore.getInstance(uploadFile, requireNotNull(uploadStorePassword).toCharArray())
    } catch (_: Exception) {
        throw GradleException("Cannot open the upload keystore. Check its format and store password.")
    }
    val uploadKey = try {
        uploadStore.getKey(uploadKeyAlias, requireNotNull(uploadKeyPassword).toCharArray())
    } catch (_: Exception) {
        throw GradleException("Cannot unlock the upload key. Check its alias and key password.")
    }
    require(uploadKey is PrivateKey) { "The upload alias must identify a private signing key." }
    val uploadCertificate = requireNotNull(uploadStore.getCertificate(uploadKeyAlias)) {
        "The upload key must have a signing certificate."
    }
    val debugStore = KeyStore.getInstance(file("debug.keystore"), "android".toCharArray())
    require(!uploadCertificate.encoded.contentEquals(debugStore.getCertificate("androiddebugkey").encoded)) {
        "The public debug signing certificate is forbidden for Play releases."
    }
}

android {
    namespace = "io.github.pportilla.upmmail"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.pportilla.upmmail"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = releaseVersionName
    }

    signingConfigs {
        if (hasUploadSigning) {
            create("upload") {
                storeFile = File(requireNotNull(uploadStorePath))
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
            }
        }
        // A committed, well-known debug key keeps sideloaded builds update-compatible.
        // It is public and must never be used for store releases.
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
            // Keep reflective mail entry points in proguard-rules.pro while optimizing the app.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasUploadSigning) signingConfigs.getByName("upload") else null
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

    // The in-app language picker must work offline, including on Android 8–12.
    bundle {
        language {
            enableSplit = false
        }
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

// Keep sideloaded debug APKs on their existing CI version sequence.
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.outputs.forEach { it.versionCode.set(debugVersionCode) }
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
    implementation(libs.play.age.signals)

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
