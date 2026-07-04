allprojects {
    repositories {
        maven { url = uri("file:///opt/local-maven-repo") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        mavenCentral()
    }
}
