import java.security.MessageDigest
import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.blackai.sensors"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.blackai.sensors"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0"
    }

    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("BLACKAI_KEYSTORE_FILE")
            if (!storeFilePath.isNullOrBlank()) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("BLACKAI_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BLACKAI_KEY_ALIAS")
                keyPassword = System.getenv("BLACKAI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Fetch the verified public YAMNet model and AudioSet labels into app assets at build time.
val downloadYamnetAssets by tasks.registering {
    val assetsDir = layout.projectDirectory.dir("src/main/assets")
    outputs.files(assetsDir.file("yamnet.tflite"), assetsDir.file("yamnet_label_list.txt"))
    doLast {
        val dir = assetsDir.asFile.apply { mkdirs() }
        fun fetch(url: String, name: String, expectedSha256: String) {
            val target = dir.resolve(name)
            if (target.exists() && MessageDigest.getInstance("SHA-256")
                    .digest(target.readBytes()).joinToString("") { "%02x".format(it) } == expectedSha256) return
            val temp = dir.resolve("$name.download")
            val connection = URL(url).openConnection()
            connection.connectTimeout = 20000
            connection.readTimeout = 60000
            connection.getInputStream().use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
            val actual = MessageDigest.getInstance("SHA-256")
                .digest(temp.readBytes()).joinToString("") { "%02x".format(it) }
            if (actual != expectedSha256) {
                temp.delete()
                throw GradleException("SHA-256 verification failed for $name")
            }
            if (!temp.renameTo(target)) throw GradleException("Could not install $name")
        }
        fetch(
            "https://huggingface.co/litert-community/MediaPipe-AudioClassifier-YAMNet/resolve/main/yamnet.tflite?download=true",
            "yamnet.tflite",
            "4d8b4a53282dc83ef04e3e7dbc4fbc98082e34e44ed798e16c3a0cdd4c584faf"
        )
        fetch(
            "https://huggingface.co/litert-community/MediaPipe-AudioClassifier-YAMNet/resolve/main/yamnet_label_list.txt?download=true",
            "yamnet_label_list.txt",
            "8e1267a120c1932b7273c0d0e0c5529edbb9a35512b437b1c8982baa59047051"
        )
    }
}
tasks.named("preBuild").configure { dependsOn(downloadYamnetAssets) }

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:35.2.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-database")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-ai")
    // Debug-only testing support; never ship this debug-provider configuration in a production release.
    debugImplementation("com.google.firebase:firebase-appcheck-debug")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
}
