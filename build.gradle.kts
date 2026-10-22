plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

// 프로젝트가 exFAT(외장하드 M)에 있으면 macOS 가 만드는 `._*` 파일 때문에 빌드 산출물이 깨진다.
// NRM_BUILD_ROOT 환경변수가 있으면 build 폴더를 그 경로(M 드라이브 안의 APFS 이미지)로 옮긴다.
providers.environmentVariable("NRM_BUILD_ROOT").orNull?.let { root ->
    allprojects {
        layout.buildDirectory.set(file("$root/${rootProject.name}/${project.name}"))
    }
}
