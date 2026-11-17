@file:Suppress("UnstableApiUsage")

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.lsplugin.apksign)
    id("kotlin-parcelize")
}

val androidCompileSdkVersion = rootProject.extra["androidCompileSdkVersion"] as Int
val androidCompileSdkVersionMinor = rootProject.extra["androidCompileSdkVersionMinor"] as Int
val androidBuildToolsVersion = rootProject.extra["androidBuildToolsVersion"] as String
val androidMinSdkVersion = rootProject.extra["androidMinSdkVersion"] as Int
val androidTargetSdkVersion = rootProject.extra["androidTargetSdkVersion"] as Int
val androidSourceCompatibility = rootProject.extra["androidSourceCompatibility"] as JavaVersion
val androidTargetCompatibility = rootProject.extra["androidTargetCompatibility"] as JavaVersion
val managerVersionCode = rootProject.extra["managerVersionCode"] as Int
val managerVersionName = rootProject.extra["managerVersionName"] as String

apksign {
    storeFileProperty = "KEYSTORE_FILE"
    storePasswordProperty = "KEYSTORE_PASSWORD"
    keyAliasProperty = "KEY_ALIAS"
    keyPasswordProperty = "KEY_PASSWORD"
}

android {
    namespace = "com.example.kernelsustyleuikit"
    val isPrBuild = project.findProperty("IS_PR_BUILD")?.toString()?.toBoolean() ?: false

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            vcsInfo.include = false
            if (isPrBuild) applicationIdSuffix = ".dev"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        dex {
            useLegacyPackaging = true
        }
        jniLibs {
            useLegacyPackaging = true
            excludes += "lib/*/libandroidx.graphics.path.so"
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    androidResources {
        generateLocaleConfig = true
        // Keep unfinished translations in source without advertising or shipping them.
        localeFilters += listOf("en", "zh-rCN", "zh-rTW", "zh-rHK")
    }
    compileSdk {
        version =
            release(androidCompileSdkVersion) {
                minorApiLevel = androidCompileSdkVersionMinor
            }
    }
    buildToolsVersion = androidBuildToolsVersion

    defaultConfig {
        applicationId = "com.TunNet.n520"
        minSdk = androidMinSdkVersion
        targetSdk = androidTargetSdkVersion
        versionCode = managerVersionCode
        versionName = managerVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("boolean", "IS_PR_BUILD", isPrBuild.toString())
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }

    testOptions {
        unitTests.all {
            it.systemProperty("app.resourcesDir", file("src/main/res").absolutePath)
            it.systemProperty("app.localeFilters", androidResources.localeFilters.joinToString(","))
            it.inputs.files(fileTree("src/main/res") { include("values*/strings.xml") })
                .withPropertyName("translationFiles")
        }
    }

    compileOptions {
        sourceCompatibility = androidSourceCompatibility
        targetCompatibility = androidTargetCompatibility
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) {
        // 注意：不能笼统排除 META-INF/**，否则 LSPosed 的模块元数据会被一起删掉，
        // 模块在 LSPosed 里就消失了。这里精确排除签名与构建产物。
        it.packaging.resources.excludes.addAll(
            listOf(
                "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.EC",
                "META-INF/MANIFEST.MF", "META-INF/com/**", "META-INF/*.version",
                "META-INF/*.kotlin_module", "kotlin/**", "**.bin",
            )
        )
    }
}

base {
    archivesName.set(
        "KernelSUStyleUIKit_v${managerVersionName}_${managerVersionCode}"
    )
}

/**
 * LSPosed 用 module.prop 里的 versionCode 判断模块「有没有变过」。
 * 曾经这里硬编码 v6.16 / 255，和 Gradle 的 versionCode 长期不一致，
 * LSPosed 认为模块没更新就不再重新加载，hook 永远不注入。
 * 这里在每次构建前把版本号同步进 module.prop，保持两处永远一致。
 */
val modulePropFile = layout.projectDirectory.file("src/main/resources/META-INF/xposed/module.prop")

val syncModulePropVersion by tasks.registering {
    group = "build"
    description = "把 Gradle 的 managerVersionName / managerVersionCode 同步进 module.prop"

    val source = modulePropFile.asFile
    // 在配置阶段就把值读出来，避免执行阶段访问 project 导致配置缓存失效
    val targetVersionName = managerVersionName
    val targetVersionCode = managerVersionCode

    inputs.property("versionName", targetVersionName)
    inputs.property("versionCode", targetVersionCode)
    inputs.file(source)
    outputs.file(source)

    doLast {
        if (!source.exists()) {
            logger.warn("module.prop 不存在，跳过版本同步: ${source.absolutePath}")
            return@doLast
        }
        val original = source.readText()
        var updated = original
            .replace(Regex("(?m)^version=.*$"), "version=v$targetVersionName")
            .replace(Regex("(?m)^versionCode=.*$"), "versionCode=$targetVersionCode")
        if (updated != original) {
            source.writeText(updated)
            logger.lifecycle("module.prop 已同步: v$targetVersionName ($targetVersionCode)")
        } else {
            logger.info("module.prop 已是 v$targetVersionName ($targetVersionCode)，无需改动")
        }
    }
}

// 所有会把 module.prop 打进 APK 的任务都依赖这个同步任务
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JavaResource") }
    .configureEach { dependsOn(syncModulePropVersion) }
tasks.matching { it.name == "preBuild" }
    .configureEach { dependsOn(syncModulePropVersion) }



dependencies {
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.navigationevent.compose)

    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.autolink)
    implementation(libs.commonmark.ext.task.list.items)

    implementation(libs.androidx.webkit)

    implementation(libs.hiddenapibypass)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.nav)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.blur)
    implementation(libs.dexkit)

    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    implementation(libs.material.kolor)

    // LibXposed —— hook 内核
    // api 必须 compileOnly：Xposed API 由 LSPosed 在运行期提供，
    // 打进 dex 会让 LSPosed 拒绝加载整个模块。
    compileOnly("io.github.libxposed:api:102.0.0")
    // service 是模块 App 侧与框架通信的客户端，需要打进包里
    implementation("io.github.libxposed:service:102.0.0")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)

}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}
