pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "VoterList"
include(":app")

// Kraft Foundation — the shared design system, the core utilities, and the standard that
// says how both are to be used. Local composite build, no publishing.
// See github.com/kedharsairam/kraft-foundation.
//
// Private repo, public dependency: code flows inward only, nothing about the roll leaves.
// Spacing, type, radius, motion and touch targets come from here; the booth's own
// dimensions and its teal-paper identity stay local.
includeBuild("../kraft-foundation") {
    dependencySubstitution {
        substitute(module("com.kraft:kraft-ui")).using(project(":kraft-ui"))
        substitute(module("com.kraft:kraft-core")).using(project(":kraft-core"))
    }
}
