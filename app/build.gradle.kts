import java.io.File
import java.io.InputStreamReader
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// ---------------------------------------------------------------------------
// 签名：口令只放在项目根的 keystore.properties（已 gitignore）。
//
// 该文件存在且四项填全时，debug 与 release 都用它签名。统一签名是必须的：
// 设备上装的是 release 签名版，debug 用默认 debug key 会直接报
// INSTALL_FAILED_UPDATE_INCOMPATIBLE（2026-11-19 实测）。
// 文件缺失时退回 Gradle 默认行为，不影响他人克隆后构建。
// 用 UTF-8 读取：storeFile 路径含中文（OneDrive/项目资料），Properties 默认
// ISO-8859-1 会把路径解坏。
// ---------------------------------------------------------------------------
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.isFile) {
        keystorePropsFile.inputStream().use {
            load(InputStreamReader(it, Charsets.UTF_8))
        }
    }
}

fun keystoreValue(key: String): String? =
    keystoreProps.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }

val signingReady = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { keystoreValue(it) != null }

android {
    namespace = "com.ztc1997.timeless"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.ztc1997.timeless"
        minSdk = 37
        targetSdk = 37
        versionCode = 23
        versionName = "0.2.0"
    }

    signingConfigs {
        if (signingReady) {
            create("release") {
                val raw = keystoreValue("storeFile")!!
                storeFile = if (File(raw).isAbsolute) {
                    File(raw)
                } else {
                    rootProject.file(raw)
                }
                storePassword = keystoreValue("storePassword")
                keyAlias = keystoreValue("keyAlias")
                keyPassword = keystoreValue("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // 与设备上已安装的版本同签名，install -r 才能覆盖升级。
            if (signingReady) signingConfig = signingConfigs.getByName("release")
        }
        release {
            if (signingReady) signingConfig = signingConfigs.getByName("release")
            // 完整 R8（full mode）：对整个程序统一优化，体积收益最大。
            //
            // 不要用实验性 packageScope 只优化 androidx/kotlin/kotlinx——
            // 那会把 kotlin.collections 内部类改成包私有，而跨包继承它的
            // 业务/Compose 类不在优化范围内、可见性没被同步放宽，运行时抛
            // IllegalAccessError（实测崩在 ModulePreferences.notifyChanged）。
            // 完整模式统一分析所有类，不存在跨优化边界问题。
            optimization {
                enable = true
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // META-INF/xposed/ 下的 module.prop / scope.list / java_init.list 必须原样进包。
    // 不裁剪这些元数据资源，否则 LSPosed 识别不到这是一个现代 API 模块。
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // material 提供 Theme.Material3.*（AndroidManifest 的 android:theme 用到）
    implementation(libs.material)

    // Compose + Material 3（BOM 统一版本，material3 等不写版本号）
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)

    // -------------------------------------------------------------------
    // 现代 Xposed API（libxposed API 102）
    //
    // api 与 annotation 必须 compileOnly：运行时由框架在目标进程注入这些类。
    // 一旦被打进 APK，LSPosed 会拒绝加载并报
    //   "The Xposed API classes are compiled into the module's APK"
    // service 必须 implementation：它跑在模块自己的进程里，负责与 LSPosed
    // 服务通信（remote preferences 读写、作用域、热重载），会自动合并一个
    // XposedProvider（authority = <applicationId>.XposedService）。
    // -------------------------------------------------------------------
    compileOnly(libs.libxposed.api)
    compileOnly(libs.libxposed.annotation)
    implementation(libs.libxposed.service)

    testImplementation(libs.junit)
}
