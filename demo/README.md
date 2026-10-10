Discovery demo
==============

A project that depends on `build.jenesis:build.jenesis:0.15.5` and on the newest
`build.jenesis:build.jenesis.launcher` and names no Maven repository that holds either: its POM replaces
`central` with the empty folder `empty/`. Only `jenesis.build`, the domain the group is named after, can answer.

Run it
------

Install the extension from the folder above, then list the dependencies from here:

    (cd .. && mvn install -DskipTests)
    mvn dependency:list

which prints where each file came from:

    [INFO] Downloading from jenesis.build: https://github.com/jenesis/jenesis/releases/download/v0.15.5/build.jenesis-0.15.5.pom
    [INFO] Downloading from jenesis.build: https://github.com/jenesis/jenesis-launcher/releases/download/v0.5.3/build.jenesis.launcher-0.5.3.pom
    [INFO] Downloading from jenesis.build: https://github.com/jenesis/jenesis/releases/download/v0.15.5/build.jenesis-0.15.5.jar
    [INFO] Downloading from jenesis.build: https://github.com/jenesis/jenesis-launcher/releases/download/v0.5.3/build.jenesis.launcher-0.5.3.jar
    ...
    [INFO]    build.jenesis:build.jenesis:jar:0.15.5:compile
    [INFO]    build.jenesis:build.jenesis.launcher:jar:0.5.3:compile

The launcher is declared as `[0.1,)`. Its domain names a template, which lists no versions, and a link to the
newest release, which GitHub redirects to the release it is; that version is what the range resolves to. The
version you see is whatever release is newest when you run it.

Once downloaded, both artifacts are reused from the local repository without asking anyone. Delete
`build/jenesis/` from your local repository to watch the download again, and run with
`-Dwellknown.discovery=false` to see the build fail without the domain.

Layout
------

    demo
    |-- .mvn/extensions.xml   the extension
    |-- empty/                a Maven repository holding nothing, in place of central
    `-- pom.xml               depends on build.jenesis and build.jenesis.launcher
