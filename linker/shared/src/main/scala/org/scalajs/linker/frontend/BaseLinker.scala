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

package org.scalajs.linker.frontend

import scala.collection.mutable
import scala.concurrent._
import scala.util.Try

import org.scalajs.logging._

import org.scalajs.linker.interface._
import org.scalajs.linker.interface.unstable._
import org.scalajs.linker.standard._
import org.scalajs.linker.checker._
import org.scalajs.linker.analyzer._
import org.scalajs.linker.CollectionsCompat.MutableMapCompatOps

import org.scalajs.ir
import ir.Trees.{ClassDef, MethodDef}
import ir.Hashers

import Analysis._

/** Links the information from [[interface.IRFile IRFile]]s into
 *  [[standard.LinkedClass LinkedClass]]es. Does a dead code elimination pass.
 */
final class BaseLinker(config: CommonPhaseConfig) {
  import BaseLinker._

  private val inputProvider = new InputProvider
  private val methodSynthesizer = new MethodSynthesizer(inputProvider)

  def link(irInput: Seq[IRFile],
      moduleInitializers: Seq[ModuleInitializer], logger: Logger,
      symbolRequirements: SymbolRequirement, checkIR: Boolean)(
      implicit ec: ExecutionContext): Future[LinkingUnit] = {

    val allSymbolRequirements = {
      symbolRequirements ++
      SymbolRequirement.fromModuleInitializer(moduleInitializers)
    }

    val result = for {
      _ <- inputProvider.update(irInput)
      analysis <- logger.timeFuture("Linker: Compute reachability") {
        analyze(allSymbolRequirements, logger)
      }
      linkResult <- logger.timeFuture("Linker: Assemble LinkedClasses") {
        assemble(moduleInitializers, analysis)
      }
    } yield {
      if (checkIR) {
        logger.time("Linker: Check IR") {
          val errorCount = IRChecker.check(linkResult, logger)
          if (errorCount != 0) {
            throw new LinkingException(
                s"There were $errorCount IR checking errors.")
          }
        }
      }

      linkResult
    }

    result.andThen { case _ => inputProvider.cleanAfterRun() }
  }

  private def analyze(symbolRequirements: SymbolRequirement, logger: Logger)(
      implicit ec: ExecutionContext): Future[Analysis] = {
    def reportErrors(errors: scala.collection.Seq[Analysis.Error]) = {
      require(errors.nonEmpty)

      val maxDisplayErrors = {
        val propName = "org.scalajs.linker.maxlinkingerrors"
        Try(System.getProperty(propName, "20").toInt).getOrElse(20).max(1)
      }

      errors
        .take(maxDisplayErrors)
        .foreach(logError(_, logger, Level.Error))

      val skipped = errors.size - maxDisplayErrors
      if (skipped > 0)
        logger.log(Level.Error, s"Not showing $skipped more linking errors")

      throw new LinkingException("There were linking errors")
    }

    for {
      analysis <- Analyzer.computeReachability(config, symbolRequirements,
          allowAddingSyntheticMethods = true, inputProvider)
    } yield {
      if (analysis.errors.nonEmpty) {
        reportErrors(analysis.errors)
      }

      analysis
    }
  }

  private def assemble(moduleInitializers: Seq[ModuleInitializer],
      analysis: Analysis)(implicit ec: ExecutionContext): Future[LinkingUnit] = {
    def assembleClass(info: ClassInfo) = {
      val classAndVersion = inputProvider.loadClassDefAndVersion(info.encodedName)
      val syntheticMethods = methodSynthesizer.synthesizeMembers(info, analysis)

      for {
        (classDef, version) <- classAndVersion
        syntheticMethods <- syntheticMethods
      } yield {
        linkedClassDef(classDef, version, syntheticMethods, info, analysis)
      }
    }

    for {
      linkedClassDefs <- Future.traverse(analysis.classInfos.values)(assembleClass)
    } yield {
      new LinkingUnit(config.coreSpec, linkedClassDefs.toList,
          moduleInitializers.toList)
    }
  }

  /** Takes a ClassDef and DCE infos to construct a stripped down LinkedClass.
   */
  private def linkedClassDef(classDef: ClassDef, version: Option[String],
      syntheticMethodDefs: Iterator[MethodDef],
      analyzerInfo: ClassInfo, analysis: Analysis): LinkedClass = {
    import ir.Trees._

    val fields = mutable.Buffer.empty[AnyFieldDef]
    val methods = mutable.Buffer.empty[Versioned[MethodDef]]
    val exportedMembers = mutable.Buffer.empty[Versioned[JSMethodPropDef]]

    def linkedMethod(m: MethodDef) = {
      val version = m.hash.map(Hashers.hashAsVersion(_))
      new Versioned(m, version)
    }

    classDef.memberDefs.foreach {
      case field: FieldDef =>
        val isNeeded = {
          if (field.flags.namespace.isStatic) analyzerInfo.isAnyStaticFieldUsed
          else if (classDef.kind.isJSType) analyzerInfo.isAnyPrivateJSFieldUsed
          else analyzerInfo.isAnySubclassInstantiated
        }
        if (isNeeded)
          fields += field

      case field: JSFieldDef =>
        if (analyzerInfo.isAnySubclassInstantiated)
          fields += field

      case m: MethodDef =>
        val methodInfo =
          analyzerInfo.methodInfos(m.flags.namespace)(m.encodedName)

        if (methodInfo.isReachable) {
          assert(m.body.isDefined,
              s"The abstract method ${classDef.name.name}.${m.encodedName} " +
              "is reachable.")
          methods += linkedMethod(m)
        }

      case m: JSMethodDef =>
        if (analyzerInfo.isAnySubclassInstantiated) {
          val version = m.hash.map(Hashers.hashAsVersion(_))
          exportedMembers += new Versioned(m, version)
        }

      case m: JSPropertyDef =>
        if (analyzerInfo.isAnySubclassInstantiated)
          exportedMembers += new Versioned(m, None)
    }

    methods ++= syntheticMethodDefs.map(linkedMethod)

    val topLevelExports =
      classDef.topLevelExportDefs.map(new Versioned(_, version))

    val kind =
      if (analyzerInfo.isModuleAccessed) classDef.kind
      else classDef.kind.withoutModuleAccessor

    val ancestors = analyzerInfo.ancestors.map(_.encodedName)

    new LinkedClass(
        classDef.name,
        kind,
        classDef.jsClassCaptures,
        classDef.superClass,
        classDef.interfaces,
        classDef.jsSuperClass,
        classDef.jsNativeLoadSpec,
        fields.toList,
        methods.toList,
        exportedMembers.toList,
        topLevelExports,
        classDef.optimizerHints,
        classDef.pos,
        ancestors.toList,
        hasInstances = analyzerInfo.isAnySubclassInstantiated,
        hasInstanceTests = analyzerInfo.areInstanceTestsUsed,
        hasRuntimeTypeInfo = analyzerInfo.isDataAccessed,
        version)
  }
}

private object BaseLinker {
  private class InputProvider extends Analyzer.InputProvider with MethodSynthesizer.InputProvider {
    private var encodedNameToFile: collection.Map[String, IRFileImpl] = _
    private var entryPoints: collection.Set[String] = _
    private val cache = mutable.Map.empty[String, ClassDefAndInfoCache]

    def update(irInput: Seq[IRFile])(implicit ec: ExecutionContext): Future[Unit] = {
      Future.traverse(irInput)(i => IRFileImpl.fromIRFile(i).entryPointsInfo).map { infos =>
        val encodedNameToFile = mutable.Map.empty[String, IRFileImpl]
        val entryPoints = mutable.Set.empty[String]

        for ((input, info) <- irInput.zip(infos)) {
          // Remove duplicates. Just like the JVM
          if (!encodedNameToFile.contains(info.encodedName))
            encodedNameToFile += info.encodedName -> IRFileImpl.fromIRFile(input)

          if (info.hasEntryPoint)
            entryPoints += info.encodedName
        }

        this.encodedNameToFile = encodedNameToFile
        this.entryPoints = entryPoints
      }
    }

    def classesWithEntryPoints(): Iterable[String] = entryPoints

    def loadInfo(encodedName: String)(
        implicit ec: ExecutionContext): Option[Future[Infos.ClassInfo]] =
      getCache(encodedName).map(_.loadInfo(encodedNameToFile(encodedName)))

    def loadClassDefAndVersion(encodedName: String)(
        implicit ec: ExecutionContext): Future[(ClassDef, Option[String])] = {
      val fileCache = getCache(encodedName).getOrElse {
        throw new AssertionError(s"Cannot load file for class $encodedName")
      }
      fileCache.loadClassDefAndVersion(encodedNameToFile(encodedName))
    }

    def loadClassDef(encodedName: String)(
        implicit ec: ExecutionContext): Future[ClassDef] = {
      loadClassDefAndVersion(encodedName).map(_._1)
    }

    private def getCache(encodedName: String): Option[ClassDefAndInfoCache] = {
      cache.get(encodedName).orElse {
        if (encodedNameToFile.contains(encodedName)) {
          val fileCache = new ClassDefAndInfoCache
          cache += encodedName -> fileCache
          Some(fileCache)
        } else {
          None
        }
      }
    }

    def cleanAfterRun(): Unit = {
      encodedNameToFile = null
      entryPoints = null
      cache.filterInPlace((_, fileCache) => fileCache.cleanAfterRun())
    }
  }

  private final class ClassDefAndInfoCache {
    private var cacheUsed: Boolean = false
    private var version: Option[String] = None
    private var cacheUpdate: Future[(ClassDef, Infos.ClassInfo)] = _

    def loadInfo(irFile: IRFileImpl)(
        implicit ec: ExecutionContext): Future[Infos.ClassInfo] = {
      update(irFile).map(_._2)
    }

    def loadClassDefAndVersion(irFile: IRFileImpl)(
        implicit ec: ExecutionContext): Future[(ClassDef, Option[String])] = {
      update(irFile).map(s => (s._1, version))
    }

    private def update(irFile: IRFileImpl)(
        implicit ec: ExecutionContext): Future[(ClassDef, Infos.ClassInfo)] = synchronized {
      /* If the cache was already used in this run, the classDef and info are
       * already correct, no matter what the versions say.
       */
      if (!cacheUsed) {
        cacheUsed = true

        val newVersion = irFile.version
        if (version.isEmpty || newVersion.isEmpty ||
            version.get != newVersion.get) {
          version = newVersion
          cacheUpdate = irFile.tree.map(t => (t, Infos.generateClassInfo(t)))
        }
      }

      cacheUpdate
    }

    /** Returns true if the cache has been used and should be kept. */
    def cleanAfterRun(): Boolean = synchronized {
      val result = cacheUsed
      cacheUsed = false
      result
    }
  }
}
