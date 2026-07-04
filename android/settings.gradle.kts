buildscript {
    repositories {
        maven { url = uri("file:///opt/local-maven-repo") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.2.2")
    }
}

rootProject.name = "MusicFlow"
include(":app")
