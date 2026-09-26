plugins { base }

val paperApi = "io.papermc.paper:paper-api:26.2.build.123-stable"
val velocityJar: String? = (findProperty("velocityJar") as String?)?.takeIf { it.isNotBlank() }
    ?: System.getenv("VELOCITY_JAR")?.takeIf { it.isNotBlank() }

subprojects {
    apply(plugin = "java")
    group = "io.github.jayden0903"
    version = "1.0.0"
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        // Plugin descriptors are written by hand; no annotation processing.
        options.compilerArgs.add("-proc:none")
    }
    // Tests are plain `main` programs using `assert`; each one becomes a verification task wired into `check`.
    tasks.withType<Test>().configureEach { failOnNoDiscoveredTests.set(false) }
    afterEvaluate {
        val sourceSets = extensions.getByType<SourceSetContainer>()
        val testDir = file("src/test/java")
        val mains = if (testDir.isDirectory) fileTree(testDir) { include("**/*Test.java") }.files
            .map { it.relativeTo(testDir).path.removeSuffix(".java").replace(File.separatorChar, '.') }.sorted() else emptyList()
        val runs = mains.map { main ->
            tasks.register<JavaExec>("verify" + main.substringAfterLast('.')) {
                group = "verification"
                description = "Runs $main with assertions enabled"
                dependsOn("testClasses")
                classpath = sourceSets["test"].runtimeClasspath
                mainClass.set(main)
                enableAssertions = true
                jvmArgs("--enable-native-access=ALL-UNNAMED")
                // Velocity's bundled log4j writes logs/ into the working directory; keep it out of the sources.
                workingDir = temporaryDir
            }
        }
        tasks.named("check") { dependsOn(runs) }
    }
}

// Bundles :common (entry ticket + balancer) into a plugin jar.
fun Project.bundleCommon(jarName: String) {
    dependencies { add("implementation", project(":common")) }
    tasks.named<Jar>("jar") {
        dependsOn(":common:classes")
        from(project(":common").extensions.getByType<SourceSetContainer>()["main"].output)
        archiveFileName.set(jarName)
    }
}

project(":common") {
    tasks.withType<JavaCompile>().configureEach { options.release.set(21) }
}

project(":router") {
    tasks.withType<JavaCompile>().configureEach { options.release.set(25) }
    dependencies {
        add("compileOnly", paperApi)
        add("compileOnly", "com.google.code.gson:gson:2.13.2")
    }
    bundleCommon("IngressRouter.jar")
}

project(":lobby") {
    tasks.withType<JavaCompile>().configureEach { options.release.set(25) }
    dependencies { add("compileOnly", paperApi) }
    tasks.named<Jar>("jar") { archiveFileName.set("FairQueueLobby.jar") }
}

project(":proxy") {
    tasks.withType<JavaCompile>().configureEach { options.release.set(21) }
    if (velocityJar == null) {
        tasks.configureEach {
            if (name == "compileJava" || name == "compileTestJava") doFirst {
                throw GradleException("Set -PvelocityJar=/path/to/velocity.jar (or VELOCITY_JAR); :proxy needs the full Velocity proxy jar")
            }
        }
    } else {
        dependencies {
            add("compileOnly", files(velocityJar))
            add("testImplementation", files(velocityJar))
        }
    }
    bundleCommon("FairQueue.jar")
}
