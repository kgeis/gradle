
Currently, the JVM language plugins assume that a given set of source files is assembled into a single
output. For example, the `main` Java source is compiled and assembled into a JAR file. However, this is not
always a reasonable assumption. Here are some examples:

* When building for multiple runtimes, such as Scala 2.10 and Scala 2.11.
* When building multiple variants composed from various source sets, such as an Android application.
* When packaging the output in various different ways, such as in a JAR and a fat JAR.

By making this assumption, the language plugins force the build author to implement these cases in ways that
are not understood by other plugins that extend the JVM language plugins, such as the code quality and IDE
plugins.

This problem is also evident in non-JVM languages such as C++, where a given source file may be compiled and
linked into more than one binaries.

This spec describes some work to allow plugins to define the kinds of JVM components that they produce and consume,
and to allow plugins to define their own custom JVM based components.

# Use cases

## Multiple build types for Android applications

An Android application is assembled in to multiple _build types_, such as 'debug' or 'release'.

## Build a library for multiple Scala or Groovy runtimes

A library is compiled and published for multiple Scala or Groovy runtimes, or for multiple JVM runtimes.

# Build different variants of an application

An application is tailored for various purposes, with each purpose represented as a separate variant. For
each variant, some common source files and some variant specific source files are jointly compiled to
produce the application.

For example, when building against the Java 5 APIs do not include the Java 6 or Java 7 specific source files.

# Compose a library from source files compiled in different ways

For example, some source files are compiled using the aspectj compiler and some source files are
compiled using the javac compiler. The resulting class files are assembled into the library.

# Implement a library using multiple languages

A library is implemented using a mix of Java, Scala and Groovy and these source files are jointly compiled
to produce the library.

## Package a library in multiple ways

A library may be packaged as a classes directory, or a set of directories, or a single jar file, or a
far jar, or an API jar and an implementation jar.

## A note on terminology

There is currently a disconnect in the terminology used for the dependency management component model, and that used
for the component model provided by the native plugins.

The dependency management model uses the term `component instance` or `component` to refer to what is known as a `binary`
in the native model. A `component` in the native model doesn't really have a corresponding concept in the dependency
management model (a `module` is the closest we have, and this is not the same thing).

Part of the work for this spec is to unify the terminology. This is yet to be defined.

For now, this spec uses the terminology from the native component model, using `binary` to refer to what is also
known as a `component instance` or `variant`.

# Features

## Feature: Build author creates a JVM library with Java sources

### Story: Build author defines JVM library

#### DSL

Project defining single jvm libraries

    apply plugin: 'jvm-component'

    jvm {
        libraries {
            main
        }
    }

Project defining multiple jvm libraries

    apply plugin: 'jvm-component'

    jvm {
        libraries {
            main
            extra
        }
    }

Combining native and jvm libraries in single project

    apply plugin: 'jvm-component'
    apply plugin: 'native-component'

    jvm {
        libraries {
            myJvmLib
        }
    }
    nativeRuntime {
        libraries {
            myNativeLib
        }
    }

#### Implementation plan

- Introduce `org.gradle.jvm.JvmLibrary`
- Rename `org.gradle.nativebinaries.Library` to `org.gradle.nativebinaries.NativeLibrary`
    - Similar renames for `Executable`, `TestSuite` and all related classes
- Introduce a common superclass for `Library`.
- Extract `org.gradle.nativebinaries.LibraryContainer` out of `nativebinaries` project into `language-base` project,
  and make it an extensible polymorphic container.
    - Different 'library' plugins will register a factory for library types.
- Add a `jvm-component` plugin, that:
    - Registers support for `JvmLibrary`.
    - Adds a single `JvmLibraryBinary` instance to the `binaries` container for every `JvmLibrary`
    - Creates a binary lifecycle task for generating this `JvmLibraryBinary`
    - Wires the binary lifecycle task into the main `assemble` task.
- Rename `NativeBinariesPlugin` to `NativeComponentPlugin` with id `native-component`.
- Move `Binary` and `ClassDirectoryBinary` to live with the runtime support classes (and not the language support classes)
- Extract a common supertype `Application` for `NativeExecutable`, and a common supertype `Component` for `Library` and `Application`
- Introduce a 'filtered' view of the ExtensiblePolymorphicDomainObjectContainer, such that only elements of a particular type are returned
  and any element created is given that type.
    - Add a backing `projectComponents` container extension that contains all Library and Application elements
        - Will later be merged with `project.components`.
    - Add 'jvm' and 'nativeRuntime' extensions for namespacing different library containers
    - Add 'nativeRuntime.libraries' and 'jvm.libraries' as filtered containers on 'components', with appropriate library type
    - Add 'nativeRuntime.executables' as filtered view on 'components
    - Use the 'projectComponents' container in native code where currently must iterate separately over 'libraries' and 'executables'

#### Test cases

- Can apply `jvm-component` plugin without defining library
    - No binaries defined
    - No lifecycle task added
- Define a jvm library component
    - `JvmLibraryBinary` added to binaries container
    - Lifecycle task available to build binary: skipped when no sources for binary
    - Binary is buildable: can add dependent tasks which are executed when building binary
- Define and build multiple java libraries in the same project
  - Build library jars individually using binary lifecycle task
  - `gradle assemble` builds single jar for each library
- Can combine native and JVM libraries in the same project
  - `gradle assemble` executes lifecycle tasks for each native library and each jvm library

#### Open issues

- Come up with a better name for `JvmLibraryBinary`, or perhaps add a `JarBinary` subtype.
- Validation of component, binary and source set names (e.g. don't include ':' and reserved filesystem characters, or limit to valid Java identifiers).

### Story: Build author creates JVM library jar from Java sources

When a JVM library is defined with Java language support, then binary is built from conventional source set locations:

- Has a single Java source set hardcoded to `src/myLib/java`
- Has a single resources source set hardcoded to `src/myLib/resources`

#### DSL

Java library using conventional source locations

    apply plugin: 'jvm-component'
    apply plugin: 'java-lang'

    jvm {
        libraries {
            myLib
        }
    }


Combining jvm-java and native (multi-lang) libraries in single project

    apply plugin: 'jvm-component'
    apply plugin: 'java-lang'

    apply plugin: 'native-component'
    apply plugin: 'cpp-lang'
    apply plugin: 'c-lang'

    jvm {
        libraries {
            myJvmLib
        }
    }
    nativeRuntime {
        libraries {
            myNativeLib
        }
    }

#### Implementation plan

- Replace the current `java-lang` plugin with a simpler one that does not know about legacy conventions
- For each `JvmLibrary`:
    - Adds a single `ResourceSet` for `src/${component}/resources`
    - Adds a single `JavaSourceSet` for `src/${component}/java`
- Each created `JvmLibraryBinary` has the source sets of its `JvmLibrary`
- Create a `ProcessResources` task for each `ResourceSet` for a `JvmLibraryBinary`
    - copy resources to `build/classes/${binaryName}`
- Create a `CompileJava` task for each `JavaSourceSet` for a `JvmLibraryBinary`
    - compile classes to `build/classes/${binaryName}`
- Create a `Jar` task for each `JvmLibraryBinary`
    - produce jar file at `build/${binaryType}/${binaryName}/${componentName}.jar`
- Rejig the native language plugins so that '*-lang' + 'native-components' is sufficient to apply language support
    - Existing 'cpp', 'c', etc plugins will simply apply '*-lang' and 'native-components'

#### Test cases

- Define and build the jar for a java library (assert jar contents for each case)
    - With no sources or resources
    - With sources but no resources
    - With resources but no sources
    - With both sources and resources
- Reports failure to compile source
- Compiled sources and resources are available in a common directory
- Incremental build for java library
    - Tasks skipped when all sources up-to-date
    - Class file is removed when source is removed
    - Copied resource is removed when resource is removed
- Can build native and JVM libraries in the same project
  - `gradle assemble` builds each native library and each jvm library

#### Open issues

- Don't attach Java source sets to native components.
- Don't attach native language source sets to jvm components.
- Don't build a jar when there is no source, mark the binary as not buildable.
    - Should do a similar thing with native components.
- Need to be able to navigate from a `JvmLibrary` to its binaries.
- Need to be able to navigate from a `JvmLibraryBinary` to its tool chain.
- Possibly deprecate the existing 'cpp', 'c', etc plugins.
- All compiled classes are removed when all java source files are removed.
- Clean up output files for source set that is removed.
- Clean up output files from components and binaries that have been removed or renamed.
- How to model the fact that component is often a prototype for binary: have similar attributes and configuration.
- When to document and announce the new JVM plugins?

## Feature: Custom plugin defines a custom library type

This features allows the development of a custom plugin that can contribute Library, Binary and Task instances to the language domain.

Development of this feature depends on the first 2 stories from the `unified-configuration-and-task-model` spec, namely:

- Story: Plugin declares a top level model to make available
- Story: Plugin configures tasks using model as input

### Story: plugin declares its own library type

Define a sample plugin that declares a custom library type:
    
    interface SampleLibrary extends Library {}

    class MySamplePlugin {
        @Model("mySample")
        SampleExtension createSampleExtension() {
            ...
        }

        @Rule
        void createSampleLibraryComponents(CollectionBuilder<SampleLibrary> sampleLibraries, SampleExtension sampleExtension) {
            ... Register sample libraries based on configured sampleExtension
        }
    }

Libraries are then visible in libraries and components containers:

    // Library is visible in libraries and components containers
    assert projectComponents.withType(SampleLibrary).size() == 2
    assert libraries.withType(SampleLibrary).size() == 2

A custom library type:
- Extends or implements some public base `Library` type.
- Has no dependencies.
- Produces no artifacts.

#### Implementation Plan

- Allow a rule-based plugin to add general rules via the @Rule annotation
- When registering a rule-based plugin, inspect any declared rules for ones that create Library instances via a `CollectionBuilder<? extends Library>`.
- The library-creation rule will be executed when closing the LibraryContainer. This mechanism can be specific to the language-base plugin.

#### Open issues

- Need some public way to easily 'implement' Library and commons subtypes such as `ProjectComponent`. For example, a public default implementation that can
be extended (should have no-args constructor) or generate the implementation from the interface.
- Infer the dependency on the language base plugin.
- Interaction with the `model { }` block.
- Need some way to declare a language domain, without necessarily defining any particular component instances.

### Story: Custom plugin defines binaries for each custom library

Add a binary type to the sample plugin:

    interface SampleBinary extends LibraryBinary {}

    class MySamplePlugin {
        ...

        @Rule
        void createBinariesForSampleLibrary(CollectionBuilder<SampleBinary> binaries, SampleLibrary library) {
            ... Create sample binaries for this library, one for each flavor. Add to the 'binaries' set.
        }
    }

Binaries are now visible in the appropriate containers:

    // Binaries are visible in the appropriate containers
    // (assume 2 libraries & 2 binaries per library)
    assert binaries.withType(SampleBinary).size() == 4
    assert libraries[0].binaries.size() == 2

Running `gradle assemble` will execute lifecycle task for each library binary.

A custom binary:
- Extends or implements some public base `LibraryBinary` type.
- Has some lifecycle task to build its outputs.

#### Implementation Plan

- For a library-producing plugin:
    - Inspect any declared rules that take the created library type as input, and produce LibraryBinary instances
      via a `CollectionBuilder<? extends LibraryBinary> parameter.
- The binary-creation rule will be executed for each library when closing the BinariesContainer.
- For each created binary, create the lifecycle task
- Document in the user guide how to use this. Include some samples.

#### Open issues

- Public mechanism to 'implement' Binary and common subtypes such as ProjectBinary.
- General mechanism to register a model collection and have rules that apply to each element of that collection.
- Validation of binary names

### Story: Custom plugin defines tasks from binaries

Add a rule to the sample plugin:

    class MySamplePlugin {
        ...

        @Rule
        void createTasksForSampleBinary(CollectionBuilder<Task> tasks, SampleBinary binary) {
            ... Add tasks that create this binary. Create additional tasks where signing is required.
        }
    }

Running `gradle assemble` will execute tasks for each library binary.

#### Implementation Plan

- For a library-producing plugin:
    - Inspect any declared rules that take the created binary type as input, and produce Task instances
      via a `CollectionBuilder<Task> parameter.
- The task-creation rule will be executed for each binary when closing the TaskContainer.
- Document in the user guide how to use this. Include some samples.

#### Open issues


### Story: Custom binary is built from Java sources

Change the sample plugin so that it compiles Java source to produce its binaries

- Uses same conventions as a Java library.
- No dependencies.

## Feature: Build author declares that a Java library depends on a Java library produced by another project

### Story: Legacy JVM language plugins declare a jvm library

- Rework the existing `SoftwareComponent` implementations so that they are `Component` implementations instead.
- Expose all native and jvm components through `project.components`.
- Don't need to support publishing yet. Attaching one of these components to a publication can result in a 'this isn't supported yet' exception.


    apply plugin: 'java'

    // The library is visible
    assert jvm.libraries.main instanceof LegacyJvmLibrary
    assert libraries.size() == 1
    assert components.size() == 1

    // The binary is visible
    assert binaries.withType(ClassDirectoryBinary).size() == 1
    assert binaries.withType(JarBinary).size() == 1

#### Test cases

- JVM library with name `main` is defined with any combination of `java`, `groovy` and `scala` plugins applied
- Web application with name `war` is defined when `war` plugin is applied.
- Can build legacy jvm library jar using standard lifecycle task
- Can mix legacy and new jvm libraries in the same project.

#### Open issues

- Expose through the DSL, or just through the APIs?

### Story: Build author declares a dependency on another Java library

For example:

    apply plugin: 'jvm-component'

    jvm {
        libraries {
            myLib {
                dependencies {
                    project 'other-project' // Infer the target library
                    project 'other-project' library 'my-lib'
                }
            }
        }
    }

When the project attribute refers to a project with a component plugin applied:

- Select the target library from the libraries of the project. Assert that there is exactly one matching JVM library.
- At compile time, include the library's jar binary only.
- At runtime time, include the library's jar binary and runtime dependencies.

When the project attribute refers to a project without a component plugin applied:

- At compile and runtime, include the artifacts and dependencies from the `default` configuration.

#### Open issues

- Should be able to depend on a library in the same project.
- Need an API to query the various classpaths.
- Need to be able to configure the resolution strategy for each usage.

## Feature: Build author declares that a Java library depends on an external Java library

For example:

    apply plugin: 'jvm-component'

    jvm {
        libraries {
            myLib {
                dependencies {
                    library "myorg:mylib:2.3"
                }
            }
        }
    }

This makes the jar of `myorg:mylib:2.3` and its dependencies available at both compile time and runtime.

### Open issues

- Using `library "some:thing:1.2"` will conflict with a dependency `library "someLib"` on a library declared in the same project.
Could potentially just assert that component names do not contain ':' (should do this anyway).

## Feature: Build author declares that legacy Java project depends on a Java library produced by another project

For example:

    apply plugin: 'java'

    dependencies {
        compile project: 'other-project'
    }

When the project attribute refers to a project with a component plugin applied:

- Select the target library from the libraries of the project. Assert that there is exactly one JVM library.
- At compile time, include the library's jar binary only.
- At runtime time, include the library's jar binary and runtime dependencies.

### Open issues

- Allow `library` attribute?

## Feature: Build user views the dependencies for the Java libraries of a project

The dependency reports show the dependencies of the Java libraries of a project:

- `dependencies` task
- `dependencyInsight` task
- HTML report

## Feature: Build author declares that a native component depends on a native library

Add the ability to declare dependencies directly on a native component, using a similar DSL as for Java libraries:

    apply plugin: 'cpp'

    libraries {
        myLib {
            dependencies {
                project 'other-project'
                library 'my-prebuilt'
                library 'local-lib' linkage 'api'
            }
        }
    }

Also reuse the dependency DSL at the source set level:

    apply plugin: 'cpp'

    libraries {
        myLib
    }

    sources {
        myLib {
            java {
                dependencies {
                    project 'other-project'
                    library 'my-lib' linkage 'api'
                }
            }
        }
    }

## Feature: Build author declares that the API of a Java library requires some Java library

For example:

    apply plugin: 'new-java'

    libraries {
        myLib {
            dependencies {
                api {
                    project 'other-project' library 'other-lib'
                }
            }
        }
    }

This makes the API of the library 'other-lib' available at compile time, and the runtime artifacts and dependencies of 'other-lib' available at
runtime.

It also exposes the API of the library 'other-lib' as part of the API for 'myLib', so that it is visible at compile time for any other component that
depends on 'myLib'.

The default API of a Java library is its Jar file and no dependencies.

### Open issues

- Add this to native libraries

## Feature: Build author declares that a Java library requires some Java library at runtime

For example:

    apply plugin: 'new-java'

    libraries {
        myLib {
            dependencies {
                runtime {
                    project 'other-project' library 'other-lib'
                }
            }
        }
    }

### Open issues

- Add this to native libraries

## Feature: Build author declares the target JVM for a Java library

For example:

    apply plugin: 'new-java'

    platforms {
        // Java versions are visible here
    }

    libraries {
        myLib {
            buildFor platforms.java7
        }
    }

This declares that the bytecode for the binary should be generated for Java 7, and should be compiled against the Java 7 API.
Assume that the source also uses Java 7 language features.

When a library `a` depends on another library `b`, assert that the target JVM for `b` is compatible with the target JVM for `a` - that is
JVM for `a` is same or newer than the JVM for `b`.

The target JVM for a legacy Java library is the lowest of `sourceCompatibility` and `targetCompatibility`.

### Open issues

- Need to discover or configure the JDK installations.

## Feature: Build author declares a custom target platform for a Java library

For example:

    apply plugin: 'new-java'

    platforms {
        myContainer {
            runsOn platforms.java6
            provides {
                library 'myorg:mylib:1.2'
            }
        }
    }

    libraries {
        myLib {
            buildFor platforms.myContainer
        }
    }

This defines a custom container that requires Java 6 or later, and states that the library should be built for that container.

This includes the API of 'myorg:mylib:1.2' at compile time, but not at runtime. The bytecode for the library is compiled for java 6.

When a library `a` depends on another library `b`, assert that both libraries run on the same platform, or that `b` targets a JVM compatible with
the JVM for the platform of `a`.

### Open issues

- Rework the platform DSL for native component to work the same way.
- Retrofit into legacy java and web plugins.

## Feature: Build author declares dependencies for a Java source set

For example:

    apply plugin: 'new-java'

    libraries {
        myLib {
            source {
                java {
                    runsOn platforms.java7
                    dependencies {
                        project 'some-project'
                        library 'myorg:mylib:1.2'
                        runtime {
                            ...
                        }
                    }
                }
            }
        }
    }

Will have to move source sets live with the library domain object.

### Open issues

- Fail or skip if target platform is not applicable for the the component's platform?

## Feature: Build author declares dependencies for custom library

Change the sample plugin so that it allows Java and custom libraries to be used as dependencies:

    apply plugin: 'my-sample'

    libraries {
        myCustomLib {
            dependencies {
                project 'other-project'
                customUsage {
                    project 'other-project' library 'some-lib'
                }
            }
        }
    }

Allow a plugin to resolve the dependencies for a custom library, via some API. Target library must produce exactly
one binary of the target type.

Move the hard-coded Java library model out of the dependency management engine and have the jvm plugins define the
Java library type.

Resolve dependencies with inline notation:

    def compileClasspath = dependencies.newDependencySet()
                .withType(JvmLibrary.class)
                .withUsage(Usage.COMPILE)
                .forDependencies("org.group:module:1.0", ...) // Any dependency notation, or dependency instances
                .create()

    compileTask.classPath = compileClasspath.files
    assert compileClasspath.files == compileClasspath.artifactResolutionResult.files

Resolve dependencies based on a configuration:

    def testRuntimeUsage = dependencies.newDependencySet()
                .withType(JvmLibrary.class)
                .withUsage(Usage.RUNTIME)
                .forDependencies(configurations.test.incoming.dependencies)
                .create()
    copy {
        from testRuntimeUsage.artifactResolutionResult.artifactFiles
        into "libs"
    }

    testRuntimeUsage.resolutionResult.allDependencies { dep ->
        println dep.requested
    }

Resolve dependencies not added a configuration:

    dependencies {
        def lib1 = create("org.group:mylib:1.+") {
            transitive false
        }
        def projectDep = project(":foo")
    }
    def deps = dependencies.newDependencySet()
                .withType(JvmLibrary)
                .withUsage(Usage.RUNTIME)
                .forDependencies(lib1, projectDep)
                .create()
    deps.files.each {
        println it
    }

### Open issues

- Component type declares usages.
- Binary declares artifacts and dependencies for a given usage.

## Feature: Build user views the dependencies for the custom libraries of a project

Change the `dependencies`, `dependencyInsight` and HTML dependencies report so that it can report
on the dependencies of a custom component, plus whatever binaries the component happens to produce.

## Feature: Build author declares target platform for custom library

Change the sample plugin to allow a target JVM based platform to be declared:

    apply plugin: 'my-sample'

    platforms {
        // Several target platforms are visible here
    }

    libraries {
        myCustomLib {
            minSdk 12 // implies: buildFor platforms.mySdk12
        }
    }

## Feature: Java library produces multiple variants

For example:

    apply plugin: 'new-java'

    libraries {
        myLib {
            buildFor platforms.java6, platforms.java8
        }
    }

Builds a binary for Java 6 and Java 8.

Dependency resolution selects the best binary from each dependency for the target platform.

## Feature: Dependency resolution for native components

## Feature: Build user views the dependencies for the native components of a project

# Open issues and Later work

## Component model

- Should use rules mechanism, so that this DSL lives under `model { ... }`
- Reuse the local component and binary meta-data for publication.
    - Version the meta-data schemas.
    - Source and javadoc artifacts.
- Test suites.
- Libraries declare an API that is used at compile time.
- Java component plugins support variants.
- Gradle runtime defines Gradle plugin as a type of jvm component, and Gradle as a container that runs-on the JVM.
- The `application` plugin should also declare a jvm application.
- The `war` plugin should also declare a j2ee web application.
- The `ear` plugin should also declare a j2ee application.
- Configure `JvmLibrary` and `JvmLibraryBinary`
    - Customise manifest
    - Customise compiler options
    - Customise output locations
    - Customise source directories
        - Handle layout where source and resources are in the same directory - need to filter source files

## Language support

- Support multiple input source sets for a component and binary.
    - Apply conflict resolution across all inputs source sets.
- Support for custom language implementations.
- Java language support
    - Java language level.
    - Source encoding.
- Groovy and Scala language support, including joint compilation.
    - Language level.
- ANTLR language support.
    - Improve the generated source support from the native plugins
    - ANTLR runs on the JVM, but can target other platforms.

## Misc

- Consider splitting up `assemble` into various lifecycle tasks. There are several use cases:
    - As a developer, build me a binary I can play with or test in some way.
    - As part of some workflow, build all binaries that should be possible to build in this specific environment. Fail if a certain binary cannot be built.
      For example, if I'm on Windows build all the Windows variants and fail if the Windows SDK (with 64bit support) is not installed.
      Or, if I'm building for Android, fail if the SDK is not installed.
    - Build everything. Fail if a certain binary cannot be built.
- Expose the source and javadoc artifacts for local binaries.
- Deprecate and remove support for resolution via configurations.
- Add a report that shows the details for the components and binaries produced by a project.
- Bust up the 'plugins' project.
