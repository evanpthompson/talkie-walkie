# Concentus (vendored)

`src/main/java/org/concentus/` is vendored, unmodified source from
[lostromb/concentus](https://github.com/lostromb/concentus), tag
`v1.0-java` (commit `0043b77835c7e9d75aacb426fdb39f4ce5c5cc89`) — a
pure-Java, dependency-free port of the Opus audio codec (originally
transpiled from Concentus's own C# port of the reference `libopus`).

## Why vendored instead of a Gradle dependency

The upstream repo has no build file at its root (its `pom.xml` lives at
`Java/Concentus/pom.xml`), so JitPack cannot build
`com.github.lostromb:concentus` — every coordinate on that group/artifact
404s. There is no published Maven Central artifact either. Rather than
depend on an unofficial, unverified third-party fork that happens to add a
working `jitpack.yml`, the 124 source files (all pure `java.*`, zero
external imports — verified) are copied directly into this module.

## License

`LICENSE` in this directory is the upstream file, copied unmodified, per
its terms (BSD-style, requires retaining copyright/license text in
redistributions — see the file itself for the full holder list: Skype,
Xiph.Org, CSIRO, Microsoft, and named individual contributors).

## Updating

To pull a newer upstream commit, replace the contents of
`src/main/java/org/concentus/` from the desired tag/commit of
`Java/Concentus/src/main/java/org/concentus/` in the upstream repo, and
update the tag/commit noted above.
