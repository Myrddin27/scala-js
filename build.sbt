import build.Build

val scalajs = Build.root
val ir = Build.irProject
val irJS = Build.irProjectJS
val compiler = Build.compiler
val logging = Build.logging
val loggingJS = Build.loggingJS
val linkerInterface = Build.linkerInterface
val linkerInterfaceJS = Build.linkerInterfaceJS
val linker = Build.linker
val linkerJS = Build.linkerJS
val jsEnvs = Build.jsEnvs
val jsEnvsTestKit = Build.jsEnvsTestKit
val nodeJSEnv = Build.nodeJSEnv
val testAdapter = Build.testAdapter
val sbtPlugin = Build.plugin
val javalanglib = Build.javalanglib
val javalib = Build.javalib
val scalalib = Build.scalalib
val libraryAux = Build.libraryAux
val library = Build.library
val minilib = Build.minilib
val testInterface = Build.testInterface
val testBridge = Build.testBridge
val jUnitRuntime = Build.jUnitRuntime
val jUnitTestOutputsJS = Build.jUnitTestOutputsJS
val jUnitTestOutputsJVM = Build.jUnitTestOutputsJVM
val jUnitPlugin = Build.jUnitPlugin
val jUnitAsyncJS = Build.jUnitAsyncJS
val jUnitAsyncJVM = Build.jUnitAsyncJVM
val examples = Build.examples
val helloworld = Build.helloworld
val reversi = Build.reversi
val testingExample = Build.testingExample
val testSuite = Build.testSuite
val testSuiteJVM = Build.testSuiteJVM
val testSuiteEx = Build.testSuiteEx
val testSuiteLinker = Build.testSuiteLinker
val partest = Build.partest
val partestSuite = Build.partestSuite
val scalaTestSuite = Build.scalaTestSuite

inThisBuild(Build.thisBuildSettings)
