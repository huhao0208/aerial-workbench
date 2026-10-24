import java.util.Properties

plugins { id("com.android.application") }

android {
    namespace = "app.hzf.keybridge"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "com.tcl.tshop"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    val props = rootProject.file("local/signing.properties")
    signingConfigs {
        if (props.exists()) {
            val p = Properties().apply { props.inputStream().use { load(it) } }
            create("workbench") {
                storeFile = rootProject.file("local/" + p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.findByName("workbench") ?: signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}
