/*
 * Scala.js (https://www.scala-js.org/)
 *
 * Copyright EPFL.
 *
 * Licensed under Apache License 2.0
 * (https://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package org.scalajs.linker

import scala.concurrent._

import org.junit.Test
import org.junit.Assert._

import org.scalajs.ir
import org.scalajs.ir.ClassKind
import org.scalajs.ir.Definitions._
import org.scalajs.ir.Trees._
import org.scalajs.ir.Types._

import org.scalajs.junit.async._

import org.scalajs.logging.NullLogger
import org.scalajs.linker._
import org.scalajs.linker.analyzer._
import org.scalajs.linker.standard._

import Analysis._

import org.scalajs.linker.testutils._
import org.scalajs.linker.testutils.TestIRBuilder._

class AnalyzerTest {
  import scala.concurrent.ExecutionContext.Implicits.global
  import AnalyzerTest._

  @Test
  def trivialOK(): AsyncResult = await {
    val analysis = computeAnalysis(Nil)
    assertNoError(analysis)
  }

  @Test
  def missingJavaLangObject(): AsyncResult = await {
    val analysis = computeAnalysis(Nil, stdlib = None)
    assertExactErrors(analysis, MissingJavaLangObjectClass(fromAnalyzer))
  }

  @Test
  def invalidJavaLangObject(): AsyncResult = await {
    val invalidJLObjectDefs = Seq(
        // j.l.Object cannot have a super class
        classDef(ObjectClass, superClass = Some("Lparent")),
        // j.l.Object must have kind == ClassKind.Class
        classDef(ObjectClass, kind = ClassKind.ModuleClass),
        // j.l.Object cannot extend any interface
        classDef(ObjectClass, interfaces = List("Lparent"))
    )

    Future.traverse(invalidJLObjectDefs) { jlObjectDef =>
      val analysis = computeAnalysis(Seq(jlObjectDef), stdlib = None)
      assertExactErrors(analysis,
          InvalidJavaLangObjectClass(fromAnalyzer))
    }
  }

  @Test
  def cycleInInheritanceChainThroughParentClasses(): AsyncResult = await {
    val classDefs = Seq(
        classDef("LA", superClass = Some("LB")),
        classDef("LB", superClass = Some("LA"))
    )

    val analysis = computeAnalysis(classDefs, reqsFactory.classData("LA"))

    assertContainsError("CycleInInheritanceChain(LA, LB)", analysis) {
      case CycleInInheritanceChain(List("LA", "LB"), `fromAnalyzer`) => true
    }
  }

  @Test
  def cycleInInheritanceChainThroughInterfaces(): AsyncResult = await {
    val classDefs = Seq(
        classDef("LA", superClass = Some("LB")),
        classDef("LB", superClass = Some(ObjectClass), interfaces = List("LA"))
    )

    val analysis = computeAnalysis(classDefs, reqsFactory.classData("LA"))

    assertContainsError("CycleInInheritanceChain(LA, LB)", analysis) {
      case CycleInInheritanceChain(List("LA", "LB"), `fromAnalyzer`) => true
    }
  }

  @Test
  def bigCycleInInheritanceChain(): AsyncResult = await {
    val classDefs = Seq(
        classDef("LA", superClass = Some("LB")),
        classDef("LB", superClass = Some("LC")),

        // Start of cycle.
        classDef("LC", superClass = Some("LD")),
        classDef("LD", superClass = Some("LE")),
        classDef("LE", superClass = Some("LC"))
    )

    val analysis = computeAnalysis(classDefs, reqsFactory.classData("LA"))

    assertContainsError("CycleInInheritanceChain(LB, LC, LD)", analysis) {
      case CycleInInheritanceChain(List("LC", "LD", "LE"), `fromAnalyzer`) => true
    }
  }

  @Test
  def missingClassDirect(): AsyncResult = await {
    val analysis = computeAnalysis(Nil, reqsFactory.classData("LA"))

    assertContainsError("MissingClass(LA)", analysis) {
      case MissingClass(ClsInfo("LA"), `fromUnitTest`) => true
    }
  }

  @Test
  def missingClassParent(): AsyncResult = await {
    val classDefs = Seq(
        classDef("LA", superClass = Some("LB"))
    )

    val analysis = computeAnalysis(classDefs, reqsFactory.classData("LA"))

    assertContainsError("MissingClass(LB)", analysis) {
      case MissingClass(ClsInfo("LB"), FromClass(ClsInfo("LA"))) => true
    }
  }

  @Test
  def missingSuperClass(): AsyncResult = await {
    val kinds = Seq(
        ClassKind.Class,
        ClassKind.ModuleClass,
        ClassKind.HijackedClass,
        ClassKind.JSClass,
        ClassKind.JSModuleClass,
        ClassKind.NativeJSClass,
        ClassKind.NativeJSModuleClass
    )

    Future.traverse(kinds) { kind =>
      val classDefs = Seq(
          classDef("LA", kind = kind, memberDefs = List(trivialCtor("LA")))
      )

      val analysis = computeAnalysis(classDefs,
          reqsFactory.instantiateClass("LA", "init___"))

      assertContainsError("MissingSuperClass(LA)", analysis) {
        case MissingSuperClass(ClsInfo("LA"), FromClass(ClsInfo("LA"))) => true
      }
    }
  }

  @Test
  def invalidSuperClass(): AsyncResult = await {
    val kindsSub = Seq(
        ClassKind.Class,
        ClassKind.ModuleClass,
        ClassKind.HijackedClass,
        ClassKind.Interface,
        ClassKind.JSClass,
        ClassKind.JSModuleClass,
        ClassKind.NativeJSClass,
        ClassKind.NativeJSModuleClass,
        ClassKind.AbstractJSType
    )

    def kindsBaseFor(kindSub: ClassKind): Seq[ClassKind] = {
      import ClassKind._
      kindSub match {
        case Class | ModuleClass | HijackedClass =>
          Seq(Interface, ModuleClass, JSClass, NativeJSClass)
        case Interface =>
          Seq(Class, Interface)
        case JSClass | JSModuleClass | NativeJSClass | NativeJSModuleClass |
            AbstractJSType =>
          Seq(Class, Interface, AbstractJSType, JSModuleClass)
      }
    }

    Future.traverse(kindsSub) { kindSub =>
      Future.traverse(kindsBaseFor(kindSub)) { kindBase =>

        val classDefs = Seq(
            classDef("LA", kind = kindSub, superClass = Some("LB")),
            classDef("LB", kind = kindBase,
                superClass = validParentForKind(kindBase))
        )

        val analysis = computeAnalysis(classDefs,
            reqsFactory.instantiateClass("LA", "init___"))

        assertContainsError("InvalidSuperClass(LB, LA)", analysis) {
          case InvalidSuperClass(ClsInfo("LB"), ClsInfo("LA"),
              FromClass(ClsInfo("LA"))) =>
            true
        }
      }
    }
  }

  @Test
  def invalidImplementedInterface(): AsyncResult = await {
    val kindsCls = Seq(
        ClassKind.Class,
        ClassKind.ModuleClass,
        ClassKind.HijackedClass,
        ClassKind.Interface,
        ClassKind.JSClass,
        ClassKind.JSModuleClass,
        ClassKind.NativeJSClass,
        ClassKind.NativeJSModuleClass,
        ClassKind.AbstractJSType
    )

    def kindsIntfFor(kindCls: ClassKind): Seq[ClassKind] = {
      import ClassKind._
      kindCls match {
        case Class | ModuleClass | HijackedClass | Interface =>
          Seq(Class, ModuleClass, JSClass, NativeJSClass, AbstractJSType)
        case JSClass | JSModuleClass | NativeJSClass | NativeJSModuleClass |
            AbstractJSType =>
          Seq(Class, ModuleClass, HijackedClass, Interface, JSClass,
              JSModuleClass, NativeJSClass, NativeJSModuleClass)
      }
    }

    Future.traverse(kindsCls) { kindCls =>
      Future.traverse(kindsIntfFor(kindCls)) { kindIntf =>
        val classDefs = Seq(
            classDef("LA", kind = kindCls,
                superClass = validParentForKind(kindCls),
                interfaces = List("LB")),
            classDef("LB", kind = kindIntf,
                superClass = validParentForKind(kindIntf))
        )

        val analysis = computeAnalysis(classDefs,
            reqsFactory.instantiateClass("LA", "init___"))

        assertContainsError("InvalidImplementedInterface(LB, LA)", analysis) {
          case InvalidImplementedInterface(ClsInfo("LB"), ClsInfo("LA"),
              FromClass(ClsInfo("LA"))) =>
            true
        }
      }
    }
  }

  @Test
  def notAModule(): AsyncResult = await {
    val classDefs = Seq(
        classDef("LA", superClass = Some(ObjectClass),
            memberDefs = List(trivialCtor("LA")))
    )

    val analysis = computeAnalysis(classDefs, reqsFactory.accessModule("LA"))

    assertContainsError("NotAModule(LA)", analysis) {
      case NotAModule(ClsInfo("LA"), `fromUnitTest`) => true
    }
  }

  @Test
  def missingMethod(): AsyncResult = await {
    val classDefs = Seq(
        classDef("LA", superClass = Some(ObjectClass),
            memberDefs = List(trivialCtor("LA")))
    )

    val analysis = computeAnalysis(classDefs,
        reqsFactory.instantiateClass("LA", "init___") ++
        reqsFactory.callMethod("LA", "foo__V"))

    assertContainsError("MissingMethod(LA.foo__V)", analysis) {
      case MissingMethod(MethInfo("LA", "foo__V"), `fromUnitTest`) => true
    }
  }

  @Test
  def conflictingDefaultMethods(): AsyncResult = await {
    val defaultMethodDef = MethodDef(EMF, "foo__V", Nil,
        NoType, Some(Skip()))(EOH, None)
    val classDefs = Seq(
        classDef("LI1", kind = ClassKind.Interface,
            memberDefs = List(defaultMethodDef)),
        classDef("LI2", kind = ClassKind.Interface,
            memberDefs = List(defaultMethodDef)),
        classDef("LA", superClass = Some(ObjectClass),
            interfaces = List("LI1", "LI2"),
            memberDefs = List(trivialCtor("LA")))
    )

    val analysis = computeAnalysis(classDefs,
        reqsFactory.instantiateClass("LA", "init___") ++
        reqsFactory.callMethod("LA", "foo__V"))

    assertContainsError("ConflictingDefaultMethods(LI1.foo__V, LI2.foo__V)", analysis) {
      case ConflictingDefaultMethods(
          List(MethInfo("LI1", "foo__V"), MethInfo("LI2", "foo__V")),
          `fromAnalyzer`) =>
        true
      case ConflictingDefaultMethods(
          List(MethInfo("LI2", "foo__V"), MethInfo("LI1", "foo__V")),
          `fromAnalyzer`) =>
        true
    }
  }

  @Test
  def conflictingTopLevelExports(): AsyncResult = await {
    def singleDef(name: String) = {
      classDef(name,
          kind = ClassKind.ModuleClass, superClass = Some(ObjectClass),
          memberDefs = List(trivialCtor(name)),
          topLevelExportDefs = List(TopLevelModuleExportDef("foo")))
    }

    val classDefs = Seq(singleDef("LA"), singleDef("LB"))
    val analysis = computeAnalysis(classDefs)

    assertContainsError("ConflictingTopLevelExport(foo, LA, LB)", analysis) {
      case ConflictingTopLevelExport("foo", List(ClsInfo("LA"), ClsInfo("LB"))) =>
        true
      case ConflictingTopLevelExport("foo", List(ClsInfo("LB"), ClsInfo("LA"))) =>
        true
    }
  }

  @Test
  def degenerateConflictingTopLevelExports(): AsyncResult = await {
    val classDefs = Seq(classDef("LA",
        kind = ClassKind.ModuleClass, superClass = Some(ObjectClass),
        memberDefs = List(trivialCtor("LA")),
        topLevelExportDefs = List(
            TopLevelModuleExportDef("foo"),
            TopLevelModuleExportDef("foo"))))

    val analysis = computeAnalysis(classDefs)
    assertContainsError("ConflictingTopLevelExport(foo, <degenerate>)", analysis) {
      case ConflictingTopLevelExport("foo", _) => true
    }
  }

  @Test
  def juPropertiesNotReachableWhenUsingGetSetClearProperty(): AsyncResult = await {
    val systemMod = LoadModule(ClassRef("jl_System$"))
    val emptyStr = StringLiteral("")
    val StringType = ClassType(BoxedStringClass)

    val classDefs = Seq(
        classDef("LA", superClass = Some(ObjectClass), memberDefs = List(
            trivialCtor("LA"),
            MethodDef(EMF, "test__V", Nil, NoType, Some(Block(
                Apply(EAF, systemMod, "getProperty__T__T", List(emptyStr))(StringType),
                Apply(EAF, systemMod, "getProperty__T__T__T", List(emptyStr))(StringType),
                Apply(EAF, systemMod, "setProperty__T__T__T", List(emptyStr))(StringType),
                Apply(EAF, systemMod, "clearProperty__T__T", List(emptyStr))(StringType)
            )))(EOH, None)
        ))
    )

    for {
      analysis <- computeAnalysis(classDefs,
          reqsFactory.instantiateClass("LA", "init___") ++
          reqsFactory.callMethod("LA", "test__V"),
          stdlib = Some(TestIRRepo.fulllib))
    } yield {
      assertNoError(analysis)

      val juPropertiesClass = analysis.classInfos("ju_Properties")
      assertFalse(juPropertiesClass.isAnySubclassInstantiated)
      assertFalse(juPropertiesClass.areInstanceTestsUsed)
      assertFalse(juPropertiesClass.isDataAccessed)
    }
  }

  @Test  // #3571
  def specificReflectiveProxy(): AsyncResult = await {
    val classDefs = Seq(
        classDef("LA", superClass = Some(ObjectClass)),
        classDef("LB", superClass = Some("LA")),
        classDef("LX", superClass = Some(ObjectClass),
            memberDefs = List(
                trivialCtor("LX"),
                MethodDef(EMF, "foo__LA", Nil, ClassType("LA"),
                    Some(Null()))(EOH, None),
                MethodDef(EMF, "foo__LB", Nil, ClassType("LB"),
                    Some(Null()))(EOH, None)
            )
        )
    )

    for {
      analysis <- computeAnalysis(classDefs,
          reqsFactory.instantiateClass("LX", "init___") ++
          reqsFactory.callMethod("LX", "foo__"))
    } yield {
      assertNoError(analysis)

      val MethodSyntheticKind.ReflectiveProxy(target) = {
        analysis.classInfos("LX")
          .methodInfos(MemberNamespace.Public)("foo__")
          .syntheticKind
      }

      assertEquals("foo__LB", target)
    }
  }
}

object AnalyzerTest {
  private val reqsFactory = SymbolRequirement.factory("unit test")

  private val fromAnalyzer = FromCore("analyzer")
  private val fromUnitTest = FromCore("unit test")

  private def validParentForKind(kind: ClassKind): Option[String] = {
    import ClassKind._
    kind match {
      case Class | ModuleClass | HijackedClass | NativeJSClass |
          NativeJSModuleClass =>
        Some(ObjectClass)
      case JSClass | JSModuleClass =>
        Some("sjs_js_Object")
      case Interface | AbstractJSType =>
        None
    }
  }

  private def computeAnalysis(classDefs: Seq[ClassDef],
      symbolRequirements: SymbolRequirement = reqsFactory.none(),
      stdlib: Option[TestIRRepo] = Some(TestIRRepo.minilib))(
      implicit ec: ExecutionContext): Future[Analysis] = {

    val classesWithEntryPoints0 = classDefs
      .map(ir.EntryPointsInfo.forClassDef)
      .withFilter(_.hasEntryPoint)
      .map(_.encodedName)

    val encodedNameToInfo =
      classDefs.map(c => c.name.name -> Infos.generateClassInfo(c)).toMap

    def inputProvider(loader: Option[TestIRRepo.InfoLoader]) = new Analyzer.InputProvider {
      def classesWithEntryPoints(): Iterable[String] = classesWithEntryPoints0

      def loadInfo(encodedName: String)(implicit ec: ExecutionContext): Option[Future[Infos.ClassInfo]] = {
        /* Note: We could use Future.successful here to complete the future
         * immediately. However, in order to exercise as much asynchronizity as
         * possible, we don't.
         */
        val own = encodedNameToInfo.get(encodedName).map(Future(_))
        own.orElse(loader.flatMap(_.loadInfo(encodedName)))
      }
    }

    for {
      loader <- Future.traverse(stdlib.toList)(_.loader).map(_.headOption)
      analysis <- Analyzer.computeReachability(CommonPhaseConfig(),
          symbolRequirements, allowAddingSyntheticMethods = true,
          inputProvider(loader))
    } yield {
      analysis
    }
  }

  private def assertNoError(analysis: Future[Analysis])(
      implicit ec: ExecutionContext): Future[Unit] = {
    assertExactErrors(analysis)
  }

  private def assertNoError(analysis: Analysis): Unit =
    assertExactErrors(analysis)

  private def assertExactErrors(analysis: Future[Analysis],
      expectedErrors: Error*)(implicit ec: ExecutionContext): Future[Unit] = {
    analysis.map(assertExactErrors(_, expectedErrors: _*))
  }

  private def assertExactErrors(analysis: Analysis,
      expectedErrors: Error*): Unit = {
    val actualErrors = analysis.errors

    for (expectedError <- expectedErrors) {
      assertTrue(s"Missing expected error: $expectedError",
          actualErrors.contains(expectedError))
    }

    if (actualErrors.size != expectedErrors.size) {
      for (actualError <- actualErrors) {
        assertTrue(s"Unexpected error: $actualError",
            expectedErrors.contains(actualError))
      }
    }
  }

  private def assertContainsError(msg: String, analysis: Future[Analysis])(
      pf: PartialFunction[Error, Boolean])(
      implicit ec: ExecutionContext): Future[Unit] = {
    analysis.map(assertContainsError(msg, _)(pf))
  }

  private def assertContainsError(msg: String, analysis: Analysis)(
      pf: PartialFunction[Error, Boolean]): Unit = {
    val fullMessage = s"Expected $msg, got ${analysis.errors}"
    assertTrue(fullMessage, analysis.errors.exists {
      e => pf.applyOrElse(e, (_: Error) => false)
    })
  }

  object ClsInfo {
    def unapply(classInfo: Analysis.ClassInfo): Some[String] =
      Some(classInfo.encodedName)
  }

  object MethInfo {
    def unapply(methodInfo: Analysis.MethodInfo): Some[(String, String)] =
      Some((methodInfo.owner.encodedName, methodInfo.encodedName))
  }
}
