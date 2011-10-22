/*
 * Copyright 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.plugins.ide.eclipse

import org.gradle.integtests.fixtures.TestResources
import org.junit.Rule
import org.junit.Test
import spock.lang.Issue

/**
 * @author Szczepan Faber
 */
class EclipseClasspathIntegrationTest extends AbstractEclipseIntegrationTest {

    @Rule
    public final TestResources testResources = new TestResources()

    String content

    @Test
    void "classpath contains library entries for external and file dependencies"() {
        //given
        def module = mavenRepo.module('coolGroup', 'niceArtifact', '1.0')
        module.artifact(classifier: 'sources')
        module.artifact(classifier: 'javadoc')
        module.publish()
        def jar = module.artifactFile
        def srcJar = module.artifactFile(classifier: 'sources')

        //when
        runEclipseTask """
apply plugin: 'java'
apply plugin: 'eclipse'

repositories {
    maven { url "${mavenRepo.uri}" }
    mavenCentral()
}

dependencies {
    compile 'coolGroup:niceArtifact:1.0'
    compile 'commons-lang:commons-lang:2.6'
    compile files('lib/dep.jar')
}
"""

        //then
        def libraries = classpath.libs
        assert libraries.size() == 3
        libraries[0].assertHasJar(jar)
        libraries[0].assertHasSource(srcJar)
        libraries[0].assertHasNoJavadoc()
        libraries[1].assertHasCachedJar('commons-lang', 'commons-lang', '2.6')
        libraries[1].assertHasCachedSource('commons-lang', 'commons-lang', '2.6')
        libraries[1].assertHasNoJavadoc()
        libraries[2].assertHasJar(file('lib/dep.jar'))
        libraries[2].assertHasNoSource()
        libraries[2].assertHasNoJavadoc()
    }

    @Test
    @Issue("GRADLE-1622")
    void "classpath contains entries for dependencies that only differ by classifier"() {
        given:
        def module = mavenRepo.module('coolGroup', 'niceArtifact', '1.0')
        module.artifact(classifier: 'extra')
        module.artifact(classifier: 'tests')
        module.publish()
        def baseJar = module.artifactFile
        def extraJar = module.artifactFile(classifier: 'extra')
        def testsJar = module.artifactFile(classifier: 'tests')
        def anotherJar = mavenRepo.module('coolGroup', 'another', '1.0').publish().artifactFile

        when:
        runEclipseTask """
apply plugin: 'java'
apply plugin: 'eclipse'

repositories {
    maven { url "${mavenRepo.uri}" }
}

dependencies {
    compile 'coolGroup:niceArtifact:1.0'
    compile 'coolGroup:niceArtifact:1.0:extra'
    testCompile 'coolGroup:another:1.0'
    testCompile 'coolGroup:niceArtifact:1.0:tests'
}
"""

        then:
        def libraries = classpath.libs
        assert libraries.size() == 4
        libraries[0].assertHasJar(anotherJar)
        libraries[1].assertHasJar(baseJar)
        libraries[2].assertHasJar(extraJar)
        libraries[3].assertHasJar(testsJar)
    }

    @Test
    void "substitutes path variables into library paths"() {
        //given
        def module = mavenRepo.module('coolGroup', 'niceArtifact', '1.0')
        module.artifact(classifier: 'sources')
        module.artifact(classifier: 'javadoc')
        module.publish()

        //when
        runEclipseTask """
apply plugin: 'java'
apply plugin: 'eclipse'

repositories {
    maven { url "${mavenRepo.uri}" }
}

dependencies {
    compile 'coolGroup:niceArtifact:1.0'
    compile files('lib/dep.jar')
}

eclipse {
    pathVariables REPO_DIR: file('${mavenRepo.uri}')
    pathVariables LIB_DIR: file('lib')
    classpath.downloadJavadoc = true
}
"""

        //then
        def libraries = classpath.vars
        assert libraries.size() == 2
        libraries[0].assertHasJar('REPO_DIR/coolGroup/niceArtifact/1.0/niceArtifact-1.0.jar')
        libraries[0].assertHasSource('REPO_DIR/coolGroup/niceArtifact/1.0/niceArtifact-1.0-sources.jar')
        libraries[0].assertHasJavadoc('REPO_DIR/coolGroup/niceArtifact/1.0/niceArtifact-1.0-javadoc.jar')
        libraries[1].assertHasJar('LIB_DIR/dep.jar')
    }

    @Test
    void "can customise the classpath model"() {
        //when
        runEclipseTask """
apply plugin: 'java'
apply plugin: 'eclipse'

sourceSets.main.java.srcDirs.each { it.mkdirs() }
sourceSets.main.resources.srcDirs.each { it.mkdirs() }

configurations {
  someConfig
}

dependencies {
  someConfig files('foo.txt')
}

eclipse {

  pathVariables fooPathVariable: projectDir

  classpath {
    sourceSets = []

    plusConfigurations += configurations.someConfig

    containers 'someFriendlyContainer', 'andYetAnotherContainer'

    defaultOutputDir = file('build-eclipse')

    downloadSources = false
    downloadJavadoc = true

    file {
      withXml { it.asNode().appendNode('message', 'be cool') }
    }
  }
}
"""

        //then
        def vars = classpath.vars
        assert vars.size() == 1
        vars[0].assertHasJar("fooPathVariable/foo.txt")

        def containers = classpath.containers
        assert containers.size() == 3
        assert containers[1] == 'someFriendlyContainer'
        assert containers[2] == 'andYetAnotherContainer'

        assert classpath.output == 'build-eclipse'

        assert classpath.classpath.message[0].text() == 'be cool'
    }

    @Test
    @Issue("GRADLE-1487")
    void "handles plus minus configurations for self resolving deps"() {
        //when
        runEclipseTask """
apply plugin: 'java'
apply plugin: 'eclipse'

configurations {
  someConfig
  someOtherConfig
}

dependencies {
  someConfig files('foo.txt', 'bar.txt', 'unwanted.txt')
  someOtherConfig files('unwanted.txt')
}

eclipse.classpath {
    plusConfigurations += configurations.someConfig
    minusConfigurations += configurations.someOtherConfig
}
"""

        //then
        def libraries = classpath.libs
        assert libraries.size() == 2
        libraries[0].assertHasJar(file("foo.txt"))
        libraries[1].assertHasJar(file("bar.txt"))
    }

    @Test
    void "handles plus minus configurations for project deps"() {
        //when
        runEclipseTask "include 'foo', 'bar', 'unwanted'",
                """
allprojects {
  apply plugin: 'java'
  apply plugin: 'eclipse'
}

configurations {
  someConfig
  someOtherConfig
}

dependencies {
  someConfig project(':foo')
  someConfig project(':bar')
  someConfig project(':unwanted')
  someOtherConfig project(':unwanted')
}

eclipse.classpath {
    plusConfigurations += configurations.someConfig
    minusConfigurations += configurations.someOtherConfig
}
"""

        //then
        assert classpath.projects == ['/foo', '/bar']
    }

    @Test
    void "handles plus minus configurations for external deps"() {
        //given
        def jar = mavenRepo.module('coolGroup', 'coolArtifact', '1.0').dependsOn('coolGroup', 'unwantedArtifact', '1.0').publish().artifactFile
        mavenRepo.module('coolGroup', 'unwantedArtifact', '1.0').publish()

        //when
        runEclipseTask """
apply plugin: 'java'
apply plugin: 'eclipse'

configurations {
  someConfig
  someOtherConfig
}

repositories {
    maven { url "${mavenRepo.uri}" }
}

dependencies {
  someConfig 'coolGroup:coolArtifact:1.0'
  someOtherConfig 'coolGroup:unwantedArtifact:1.0'
}

eclipse.classpath {
    plusConfigurations += configurations.someConfig
    minusConfigurations += configurations.someOtherConfig
}
"""

        //then
        def libraries = classpath.libs
        assert libraries.size() == 1
        libraries[0].assertHasJar(jar)
    }

    @Test
    void "can toggle javadoc and sources on"() {
        //given
        def module = mavenRepo.module('coolGroup', 'niceArtifact', '1.0')
        module.artifact(classifier: 'sources')
        module.artifact(classifier: 'javadoc')
        module.publish()
        def jar = module.artifactFile
        def srcJar = module.artifactFile(classifier: 'sources')
        def javadocJar = module.artifactFile(classifier: 'javadoc')

        //when
        runEclipseTask """
apply plugin: 'java'
apply plugin: 'eclipse'

repositories {
    maven { url "${mavenRepo.uri}" }
}

dependencies {
    compile 'coolGroup:niceArtifact:1.0'
}

eclipse.classpath {
    downloadSources = true
    downloadJavadoc = true
}
"""

        //then
        def libraries = classpath.libs
        assert libraries.size() == 1
        libraries[0].assertHasJar(jar)
        libraries[0].assertHasSource(srcJar)
        libraries[0].assertHasJavadoc(javadocJar)
    }

    @Test
    void "can toggle javadoc and sources off"() {
        //given
        def module = mavenRepo.module('coolGroup', 'niceArtifact', '1.0')
        module.artifact(classifier: 'sources')
        module.artifact(classifier: 'javadoc')
        module.publish()
        def jar = module.artifactFile

        //when
        runEclipseTask """
apply plugin: 'java'
apply plugin: 'eclipse'

repositories {
    maven { url "${mavenRepo.uri}" }
}

dependencies {
    compile 'coolGroup:niceArtifact:1.0'
}

eclipse.classpath {
    downloadSources = false
    downloadJavadoc = false
}
"""

        //then
        def libraries = classpath.libs
        assert libraries.size() == 1
        libraries[0].assertHasJar(jar)
        libraries[0].assertHasNoSource()
        libraries[0].assertHasNoJavadoc()
    }

    @Test
    void "removes dependencies from existing classpath file when merging"() {
        //given
        getClasspathFile() << '''<?xml version="1.0" encoding="UTF-8"?>
<classpath>
	<classpathentry kind="output" path="bin"/>
	<classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER" exported="true"/>
	<classpathentry kind="lib" path="/some/path/someDependency.jar" exported="true"/>
	<classpathentry kind="var" path="SOME_VAR/someVarDependency.jar" exported="true"/>
	<classpathentry kind="src" path="/someProject" exported="true"/>
</classpath>
'''

        //when
        runEclipseTask """
apply plugin: 'java'
apply plugin: 'eclipse'

dependencies {
  compile files('newDependency.jar')
}
"""

        //then
        assert classpath.entries.size() == 3
        def libraries = classpath.libs
        assert libraries.size() == 1
        libraries[0].assertHasJar(file('newDependency.jar'))
    }

    @Test
    void "can access xml model before and after generation"() {
        //given
        def classpath = getClasspathFile([:])
        classpath << '''<?xml version="1.0" encoding="UTF-8"?>
<classpath>
	<classpathentry kind="output" path="bin"/>
	<classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER" exported="true"/>
	<classpathentry kind="lib" path="/some/path/someDependency.jar" exported="true"/>
	<classpathentry kind="var" path="SOME_VAR/someVarDependency.jar" exported="true"/>
</classpath>
'''

        //when
        runEclipseTask """
apply plugin: 'java'
apply plugin: 'eclipse'

def hooks = []

dependencies {
  compile files('newDependency.jar')
}

eclipse {
  classpath {
    file {
      beforeMerged {
        hooks << 'beforeMerged'
        assert it.entries.size() == 4
        assert it.entries.any { it.path.contains('someDependency.jar') }
        assert it.entries.any { it.path.contains('someVarDependency.jar') }
        assert !it.entries.any { it.path.contains('newDependency.jar') }
      }
      whenMerged {
        hooks << 'whenMerged'
        assert it.entries.size() == 3
        assert it.entries.any { it.path.contains('newDependency.jar') }
        assert !it.entries.any { it.path.contains('someDependency.jar') }
        assert !it.entries.any { it.path.contains('someVarDependency.jar') }
      }
    }
  }
}

eclipseClasspath.doLast() {
  assert hooks == ['beforeMerged', 'whenMerged']
}
"""

        //then no exception is thrown
    }

    @Issue("GRADLE-1502")
    @Test
    void "creates linked resources for source directories which are not under the project directory"() {
        file('someGroovySrc').mkdirs()

        def settingsFile = file('settings.gradle')
        settingsFile << "include 'api'"

        def buildFile = file('build.gradle')
        buildFile << """
allprojects {
  apply plugin: 'java'
  apply plugin: 'eclipse'
  apply plugin: 'groovy'
}

project(':api') {
    sourceSets {
        main {
            groovy.srcDirs = ['../someGroovySrc']
        }
    }
}
"""
        //when
        executer.usingBuildScript(buildFile).usingSettingsFile(settingsFile).withTasks('eclipse').run()

        //then
        def classpath = getClasspathFile(project: 'api').text

        assert !classpath.contains('path="../someGroovySrc"'): "external src folders are not supported by eclipse"
        assert classpath.contains('path="someGroovySrc"'): "external folder should be mapped to linked resource"

        def project = parseProjectFile(project: 'api')

        assert project.linkedResources.link.location.text().contains('someGroovySrc'): 'should contain linked resource for folder that is not beneath the project dir'
    }

    @Issue("GRADLE-1402")
    @Test
    void "should put sourceSet's output dir on classpath"() {
        testFile('build/generated/main/prod.resource').createFile()
        testFile('build/generated/test/test.resource').createFile()

        //when
        runEclipseTask '''
apply plugin: "java"
apply plugin: "eclipse"

sourceSets.main.output.dir "$buildDir/generated/main"
sourceSets.test.output.dir "$buildDir/generated/test"
'''
        //then
        def libraries = classpath.libs
        assert libraries.size() == 2
        libraries[0].assertHasJar(file('build/generated/main'))
        libraries[1].assertHasJar(file('build/generated/test'))
    }

    @Test
    void "the 'buildBy' task be executed"() {
        //when
        def result = runEclipseTask('''
apply plugin: "java"
apply plugin: "eclipse"

sourceSets.main.output.dir "$buildDir/generated/main", buildBy: 'generateForMain'
sourceSets.test.output.dir "$buildDir/generated/test", buildBy: 'generateForTest'

task generateForMain << {}
task generateForTest << {}
''')
        //then
        result.assertTasksExecuted(':generateForMain', ':generateForTest', ':eclipseClasspath', ':eclipseJdt', ':eclipseProject', ':eclipse')
    }

    @Test
    @Issue("GRADLE-1613")
    void "should allow setting non-exported configurations"() {
        //when
        runEclipseTask """
apply plugin: 'java'
apply plugin: 'eclipse'

configurations {
  provided
}

dependencies {
  compile  files('compileDependency.jar')
  provided files('providedDependency.jar')
}

eclipse {
  classpath {
    plusConfigurations += configurations.provided
    noExportConfigurations += configurations.provided
  }
}
"""

        //then
        def libraries = classpath.libs
        assert libraries.size() == 2
        libraries[0].assertHasJar(file('compileDependency.jar'))
        libraries[0].assertExported()
        libraries[1].assertHasJar(file('providedDependency.jar'))
        libraries[1].assertNotExported()
    }

    @Test
    void "does not break when some dependencies cannot be resolved"() {
        //given
        def repoJar = mavenRepo.module('coolGroup', 'niceArtifact', '1.0').publish().artifactFile
        def localJar = file('someDependency.jar').createFile()

        file("settings.gradle") << "include 'someApiProject'\n"

        //when
        runEclipseTask """
allprojects {
    apply plugin: 'java'
    apply plugin: 'eclipse'
}

repositories {
    maven { url "${mavenRepo.uri}" }
}

dependencies {
    compile 'coolGroup:niceArtifact:1.0'
    compile project(':someApiProject')
    compile 'i.dont:Exist:1.0'
    compile files('someDependency.jar')
}
"""

        //then
        def libraries = classpath.libs
        assert libraries.size() == 3
        libraries[0].assertHasJar(repoJar)
        libraries[1].assertHasJar(file('unresolved dependency - i.dont#Exist;1.0'))
        libraries[2].assertHasJar(localJar)
    }
}
