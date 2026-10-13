plugins {
    id("com.android.application")
    // AGP 9 自带 Kotlin 编译；这里只需要 Compose 编译器插件。
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.pocket.opencode"
    compileSdk { version = release(37) }

    defaultConfig {
        applicationId = "dev.pocket.opencode"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        // 只支持 arm64：内置的 proot 运行时与 Ubuntu rootfs 都是 aarch64。
        ndk { abiFilters += "arm64-v8a" }
    }

    androidResources {
        localeFilters += listOf("zh", "en")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // 个人项目：默认用 debug 签名，方便直接出正式包；接 CI 时可换成自己的 keystore。
            signingConfig = signingConfigs.getByName("debug")
            // 需要更小的包时：./gradlew assembleRelease -Ppocket.minify=true（R8 会重写代码，务必真机回归）
            val minify = (project.findProperty("pocket.minify") as String?)?.toBoolean() ?: false
            isMinifyEnabled = minify
            isShrinkResources = minify
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        jniLibs {
            // proot 只能从 nativeLibraryDir 执行（Android 10+ W^X），所以必须解压出来
            useLegacyPackaging = true
            // 内置的 ELF 不能被 strip 改写：proot 的 entry point 与 loader 的固定装载地址都依赖原始字节
            keepDebugSymbols += setOf(
                "**/libproot.so",
                "**/libprootloader.so",
                "**/libtalloc.so",
                "**/libandroidshmem.so",
            )
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/versions/**",
                "META-INF/*.kotlin_module",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        aidl = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-service:2.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.10")

    // 设备 Shell 桥：通过 Shizuku 拿到 shell/root 身份，让容器里的 agent 能操作手机本体
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // 纯逻辑的 JVM 单元测试（解析、配置生成、版本比较、Markdown）
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
