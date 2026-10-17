plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.wongnd.jamkkan"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.wongnd.jamkkan"
        minSdk = 26
        // 34: 안드로이드 15의 '화면 끝까지 그리기' 강제를 피해서 웹 화면이 상태 표시줄에 가려지지 않게 함
        targetSdk = 34
        // GitHub Actions 실행 번호를 버전 코드로 써서, 새로 받은 APK가 항상 '업데이트'로 설치되게 함
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.7.0"
    }

    // 개인 테스트용 서명 키 (저장소에 포함). 다른 사람에게 배포하기 전에는 비공개 키로 바꿔야 함
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    testImplementation("junit:junit:4.13.2")
}

// 웹 화면(저장소의 app/index.html)과 아이콘을 빌드할 때마다 앱 안에 복사.
// 화면 코드는 웹과 앱이 같은 파일 하나를 씀.
val webAssetsDir = layout.buildDirectory.dir("generated/webassets").get().asFile
val syncWeb by tasks.registering(Copy::class) {
    from(rootProject.file("../app/index.html")) { into("web/app") }
    from(rootProject.file("../icons")) { into("web/icons") }
    into(webAssetsDir)
}
android.sourceSets.getByName("main").assets.srcDir(webAssetsDir)
tasks.named("preBuild") { dependsOn(syncWeb) }
