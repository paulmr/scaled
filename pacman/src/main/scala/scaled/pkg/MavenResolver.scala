//
// Scaled - a scalable editor extensible via JVM languages
// http://github.com/samskivert/scaled/blob/master/LICENSE

package scaled.pkg

import java.net.{URL, URLClassLoader}
import java.nio.file.Paths
import pomutil.{Dependency, DependResolver, POM}
import scala.collection.mutable.{Map => MMap}

/** Handles dependencies from the local Maven repository. */
class MavenResolver (mgr :PackageManager) {

  private val loaders = MMap[Dependency,PackageLoader]()

  /** Resolves the supplied Maven dependency (and its transitive dependencies) and returns a
    * classloader which can deliver classes therefrom.
    */
  def resolveDepend (depend :Depend) :Option[ClassLoader] =
    resolve(Dependency(depend.groupId, depend.artifactId, depend.version, depend.kind))

  private def resolve (dep :Dependency) :Option[ClassLoader] = synchronized {
    val loader = loaders.get(dep)
    if (loader.isDefined) loader
    else {
      val depdeps = dep.localPOM match {
        case None        => mgr.warn(s"Unable to resolve POM for $dep") ; Seq()
        case Some(pfile) => POM.fromFile(pfile) match {
          case None      => mgr.warn(s"Unable to load POM from $pfile") ; Seq()
          case Some(pom) => new DependResolver(pom) {
            // strictly speaking we should only propagate compile/runtime here, but we need system
            // depends because we have stuff that uses tools.jar which for better or worse is
            // generally expressed as a system depend in Maven; maybe I'll eventually make a fake
            // JVM tools project and handle it specially...
            override def rootDepends (forTest :Boolean) =
              if (forTest) super.rootDepends(forTest)
              else pom.depends filter(d => DepScopes(d.scope))
          }.resolve(false)
        }
      }
      val loader = new PackageLoader(dep.id, toURL(dep)) {
        // TODO: there are issues with this approach: we transitively resolve all depends, but each
        // dependent classloader then resolves its own depends; this means that a situation like:
        //
        // A -> B, C1
        // B -> C2
        //
        // results in A asking B for some class in Cn, but B then recursively checks its own
        // dependencies, finding the class in C2, when we really want A to see C1's classes; the
        // proper solution to this is going to be complicated; we will need to cache classloaders
        // for fully resolved dependency sets, such that if A depends on B with C2 overridden to C1,
        // and D depends on B with C2 overridden to C3, we maintain two different class loaders for
        // B; TODO!
        protected def resolveDependLoaders = depdeps.flatMap(resolve).toList
      }
      // println(s"Created Maven loader for $dep (${toURL(dep)})")
      loaders.put(dep, loader)
      Some(loader)
    }
  }

  private val DepScopes = Set("compile", "system", "runtime")

  private def toURL (dep :Dependency) :URL = (dep.systemPath match {
    case Some(sp) => Paths.get(sp)
    case None     => (m2repo /: (dep.repositoryPath :+ dep.artifactName))(_ resolve _)
  }).toUri.toURL
  private val m2repo = Paths.get(System.getProperty("user.home"), ".m2", "repository")
}