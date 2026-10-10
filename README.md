Well-known Maven extension
==========================

A Maven core extension that lets the owner of a domain say where the Maven artifacts named after that domain
are published. A groupId is a reversed domain, so `build.jenesis` belongs to `jenesis.build`, and before Maven
downloads an artifact of that group from any repository, the extension reads

    https://jenesis.build/.well-known/java-repository.properties

and, where that file names a location for the artifact, downloads it from there. This is the file
[Jenesis](https://github.com/jenesis/jenesis) reads with `-Djenesis.repository.discovery=true`, read by the same
rules, so a domain that publishes it serves Jenesis and Maven alike.

Quickstart
----------

**Requirements.** Maven 3.9 or newer, running on JDK 17 or newer. The extension is compiled for Java 17, so a
Maven that runs on Java 8 or 11 cannot load it, whatever release your project compiles for. It has been verified
on Maven 3.9.11 and 4.0.0-rc-7.

**1. Make the extension available.** No release is published yet, so build and install it into your local
repository once:

    git clone https://github.com/raphw/wellknown-maven-extension.git
    cd wellknown-maven-extension
    mvn install

**2. Declare it in your project.** Create `.mvn/extensions.xml` beside the root `pom.xml`:

    <extensions>
        <extension>
            <groupId>codes.rafael.wellknown</groupId>
            <artifactId>wellknown-maven-extension</artifactId>
            <version>1.0.0-SNAPSHOT</version>
        </extension>
    </extensions>

It has to be this file: a core extension is loaded before the first POM is read, which is what lets it serve
parent POMs and plugins as well. Declaring it in a POM's `<build><extensions>` does not work.

**3. Build as usual.** `mvn verify` or any other goal. Every dependency, plugin, parent POM and version range is
resolved as before, except that a group whose domain publishes a location is asked there first. A group whose
domain publishes nothing resolves exactly as it would without the extension.

**4. See it work.** A file a domain served is reported under the domain's name instead of a repository's:

    [INFO] Downloading from jenesis.build: https://github.com/jenesis/jenesis/releases/download/v0.15.5/build.jenesis-0.15.5.jar

Only downloads are discovered, so an artifact already in your local repository is not fetched again; delete it
there, or run with `-U`, to watch it come through the domain. `-X` adds a debug line for every domain asked,
saying whether it published a file and how long it took. `demo/` is a complete project that names no Maven
repository holding its dependencies and still resolves them; its README walks through it.

**Switching it off.** `-Dwellknown.discovery=false` leaves every request to the declared repositories for one
build, as if the extension were not installed; put it in `.mvn/maven.config` to switch it off for every build of
the project without removing the declaration. Offline builds (`-o`) never ask a domain.

The file
--------

A domain describes what it publishes as a `java.util.Properties` file in UTF-8. `jenesis.build` publishes, among
the keys Jenesis reads for its modules:

    maven[build.jenesis]=https://github.com/jenesis/jenesis/releases/download/v{version}/{artifactId}-{version}{-classifier}.{type}
    maven[build.jenesis].latest=https://github.com/jenesis/jenesis/releases/latest/download/{artifactId}.pom
    maven[build.jenesis].suffixes=none

The rules, all of them as Jenesis applies them:

  * **Which file.** For `net.bytebuddy.agent` the file of `bytebuddy.net` is read, and the file of
    `agent.bytebuddy.net` only where `bytebuddy.net` publishes none. The first file found speaks for every group
    below its domain, so a key it does not hold is absent rather than asked of a subdomain, unless it says
    `delegate=true`, which lets the files of its subdomains answer first while its own entries stand for the
    subdomains that hold none. Each domain is asked once per build.
  * **Which key.** `maven` answers for every artifact below the domain, `maven[<artifactId>]` for one artifact,
    and `maven[<prefix>*]` for every artifact ID starting with the prefix. The exact name wins over the longest
    prefix and either over the key for all, and the keys beside a selected key carry its selector.
  * **A root** is a location without placeholders, a Maven repository such as `https://maven.example.com/releases/`.
    It is read through Maven's own connector and transport, checksums and all, and its version listing is merged
    with that of the repositories the build declares, so a version range sees both.
  * **A template** is a location with placeholders, naming each file: `{groupId}`, `{groupPath}`, `{artifactId}`,
    `{version}`, `{-classifier}` (`-sources` or nothing) and `{type}`, which is the extension (`jar`, `pom`,
    `jar.asc`). A template without `{-classifier}` or `{type}` serves only the plain jar. A downloaded file is
    checked against the first of `.sha512`, `.sha256` and `.sha1` published beside it.
  * **The newest version.** A template lists no versions. `maven.latest` names a link whose `HEAD` response
    names the newest one, in a `Jenesis-MavenVersion` header or by where it redirects, matched against the
    template up to the path segment holding `{version}`, as GitHub's `releases/latest/download/<file>` redirects
    to the newest release. A link ending in `/maven-metadata.xml` is read as that file instead. Either is merged
    into the version listing of the declared repositories, so `RELEASE`, `LATEST` and version ranges see it.
  * **Which versions.** `maven.since=<version>` serves that version and later ones, ordered as Maven orders
    versions, and `maven.suffixes=none,rc` only the qualifiers named, `none` being a version without one. A version
    an entry does not admit is left to the declared repositories.
  * **What fails.** A key without a value, a suffix that is not a word, an unknown placeholder, a value that is
    not a location, a `.latest` beside a root, a location not read over `https` and a checksum that does not match
    each fail the download with the file named. An unknown key, such as Jenesis' `module`, `moduletomaven` and
    `sources`, is ignored. A file that cannot be fetched counts as absent, save where the domain's certificate
    does not verify.

Publishing a file for your own group
------------------------------------

To let Maven and Jenesis users resolve your artifacts from where you publish them, without a Maven repository of
their own declaring it:

**1. Pick the domain.** A groupId names a domain read backwards, and the shortest one that publishes a file
answers: for `com.example.tools`, `example.com` is asked first and `tools.example.com` only where `example.com`
publishes none. Publish at the domain you control that your groupIds reverse to, usually the apex. If several
teams own subdomains, publish `delegate=true` at the apex and let each subdomain publish its own file.

**2. Write the file.** Where your artifacts already sit in a Maven repository, a root is all it takes:

    maven=https://maven.example.com/releases/

Where they are attached to GitHub releases, a template names each file, and a link to the newest release lets
version ranges and `RELEASE` find it:

    maven[example-tools]=https://github.com/example/tools/releases/download/v{version}/{artifactId}-{version}{-classifier}.{type}
    maven[example-tools].latest=https://github.com/example/tools/releases/latest/download/{artifactId}.pom
    maven[example-tools].suffixes=none

With a template, upload each file under exactly the name it expands to: `example-tools-1.2.0.jar`,
`example-tools-1.2.0.pom`, `example-tools-1.2.0-sources.jar` and so on. Attach the POM, or Maven warns that the
POM is missing and resolves the jar without its dependencies. Attach a `.sha256` or `.sha512` beside each file
and every download is checked against it. The `.latest` link downloads nothing: GitHub redirects
`releases/latest/download/<name>` to the newest release whatever `<name>` is, and the version is read from where it
redirects. `suffixes=none` keeps snapshots and other qualified versions away from a location that holds only releases.

**3. Serve it** at `https://<domain>/.well-known/java-repository.properties` over `https` with a certificate that
verifies, as a plain UTF-8 file. GitHub Pages skips folders starting with a dot unless the site has a `.nojekyll`
file or `include: [".well-known"]` in its `_config.yml`.

**4. Check it.** `curl https://example.com/.well-known/java-repository.properties` should print the file. Then
resolve one of your artifacts from a project like `demo/`, which replaces `central` with an empty folder so that
only your domain can answer, with `-U` so that an answer cached earlier is not reused. A mistake in the file, such
as a key without a value or an unknown placeholder, fails that build with the file and the key named.

How it hooks into Maven
-----------------------

The extension is one Maven Resolver component, `WellKnownConnectorFactory`, a `RepositoryConnectorFactory` with a
priority above the stock one. For every remote repository the build talks to, it asks the next factory in line
for the usual connector and wraps it:

  * an **artifact download** is first looked up by its groupId and artifactId. If a location answers and admits
    the version, the file is fetched from there and the declared repository is never asked; if the location does
    not hold the file, the request continues to the repository as if nothing had happened;
  * a **version listing** (`maven-metadata.xml` of a groupId and artifactId) is fetched from the repository as
    usual and the versions the domain names are merged into it, or stand alone where the repository has none;
  * uploads, snapshot metadata and everything else pass through untouched.

Wrapping the connector rather than adding a repository is what keeps the order Jenesis has: the domain answers
before every declared repository, whatever order the POM, its parents and `settings.xml` declare them in, and it
covers parent POMs and plugins as well as dependencies. A core extension is loaded before the first project is
read, which is why the extension belongs in `.mvn/extensions.xml` and not in a POM's `<build><extensions>`.

A download through a domain is reported like any other:

    [INFO] Downloading from jenesis.build: https://github.com/jenesis/jenesis/releases/download/v0.15.5/build.jenesis-0.15.5.jar
    [INFO] Downloaded from jenesis.build: https://github.com/jenesis/jenesis/releases/download/v0.15.5/build.jenesis-0.15.5.jar (1.4 MB at 1.6 MB/s)

The local repository records the file under the repository whose request it answered, so it is reused offline
and by later builds exactly as if that repository had served it.

Settings
--------

Every setting is a `-D` property, on the command line or in `.mvn/maven.config`:

| Property                         | Default                                                 | Meaning                                                                                                     |
|----------------------------------|---------------------------------------------------------|-------------------------------------------------------------------------------------------------------------|
| `wellknown.discovery`            | `true`                                                  | `false` leaves every request to the declared repositories, as if the extension were not installed.          |
| `wellknown.location`             | `https://{domain}/.well-known/java-repository.properties` | Where a domain's file is read, for a mirror of the files or a test server.                                  |
| `wellknown.insecure`             | `false`                                                 | Allows plain `http` for the file and the locations it names; for testing only.                              |
| `wellknown.timeout`              | `5000`                                                  | Milliseconds a domain has to answer for its file before it counts as publishing none; `0` waits forever.    |
| `wellknown.ttl`                  | `86400`                                                 | Seconds a domain's answer is reused from `<local repository>/.wellknown/`; `0` asks every build, as does `-U`. |
| `aether.connector.connectTimeout` | `10000`                                                | Maven's own connect timeout, used for every request the extension makes.                                    |
| `aether.connector.requestTimeout` | `1800000`                                              | Maven's own request timeout, used for the files a domain names.                                             |

Proxies configured in `settings.xml` apply to every request, and the JVM's proxy settings where `settings.xml`
names none. A flag that is neither `true` nor `false`, or a number that is not one, fails the build.

Design notes
------------

**What carries over from Jenesis, and what does not.** Only the `maven` key has a meaning in Maven, which resolves
no modules: `module` and `moduletomaven` describe how a Jenesis module build finds a module, and `sources` feeds
the SBOM Jenesis writes. All three are ignored rather than refused, so one file serves both tools. Lookup,
selection, `delegate`, `since`, `suffixes`, `latest`, templates, roots and checksum validation are ported from
`build.jenesis.Discovery`, `DiscoveredLocation` and `DiscoveryMavenRepository` with their messages, and the
overlay of version listings follows `MavenRepository.overlay`.

**Who asks how often.** Jenesis consults the domain whenever it resolves; this extension only does when Maven
downloads, so a build whose artifacts are all in the local repository asks no domain. A cold build is different:
every group of every transitive dependency and plugin is looked up, which in practice means dozens of domains,
many of them long gone (`codehaus.org`) or slow to fail (`sonatype.org` resets the TLS handshake after seconds).
Three choices keep that affordable, and they are where the extension departs from Jenesis:

  * the file has its own short timeout, `wellknown.timeout`, rather than Maven's thirty-minute request timeout;
  * a domain's answer, the file or its absence, is kept for `wellknown.ttl` in the local repository's
    `.wellknown/` folder, which makes a second cold build cost what it costs without the extension; `-U` asks
    again, as it re-checks everything else;
  * only a certificate that does not verify fails the build. Jenesis fails on any TLS error, but a reset
    handshake says nothing about who owns a domain, and with every transitive group being looked up it made
    builds fail on domains that have nothing to do with the project.

Measured on a project with one dependency and the stock plugins, from an empty local repository: about 11 s
without the extension, about 34 s with it on the first build (21 domains asked, three of them hitting the
timeout), and 11 to 13 s with the domains' answers kept.

**What a domain can and cannot do.** The file is read over `https` only and every location it names likewise, so
the domain's certificate vouches for it. A template download is checked against the checksum published beside
it, which proves integrity of the transfer but not authenticity, since both come from the same place. Jenesis
additionally checks every download against the pins a project commits, which plain Maven has no equivalent
for; a project that wants the same guarantee enables Maven Resolver's trusted checksums
(`aether.trustedChecksumsSource.summaryFile=true`) or verifies signatures with a plugin. Installing the extension
is the opt-in, as `jenesis.repository.discovery=true` is for Jenesis.

**Interaction with the rest of Maven.**

  * Mirrors in `settings.xml` are not applied to locations a domain names. An organisation that requires all
    traffic to pass a repository manager switches discovery off with `wellknown.discovery=false`.
  * Resolver's prefix filter (`aether.remoteRepositoryFilter.prefixes=true`) drops a request before it reaches a
    connector, so a group the filtered repository does not list is never discovered through it.
  * A miss that Maven recorded before the extension was installed is kept in a `.lastUpdated` file until its
    update interval passes; `-U` asks again.
  * Snapshots are served only from roots, as a template cannot list timestamped versions.

**Compatibility.** Built against Maven Resolver 1.9 with Java 17, and verified end to end on Maven 3.9.11 and
4.0.0-rc-7 (Resolver 2.0.23). The extension depends on nothing beyond what Maven exports to core extensions.

Building
--------

    mvn verify

runs the unit tests, which serve discovery files, templates and checksums from a local HTTP server. To try the
extension in `demo/`, install it first with `mvn install` and follow `demo/README.md`.
