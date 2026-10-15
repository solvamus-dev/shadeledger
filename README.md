# ShadeLedger

Status: early prototype, not usable as a plugin yet.

## What this is

When Maven Shade merges a bunch of libraries into one JAR, it can rename
packages, drop unused classes, and merge resources. Once that's done, the
JAR itself doesn't tell you much about what's actually inside it anymore.
If a CVE gets published for a library you shaded in, you're often left
guessing whether it's actually in your artifact, and under what name.

ShadeLedger is an attempt at answering that question directly, using the
same inputs Maven Shade itself used: the original dependency JARs and the
relocation/filter rules from the build. Instead of scanning the finished
JAR and guessing, it takes the original class files, applies the exact
same rule the build applied, and checks whether the result matches the
real output byte for byte. If it does, that's not a guess, it's a proof.

This repo is the first real test of that idea. It's not the plugin
described in the project write-up yet, just enough code to check whether
the core matching approach actually works before building anything more
permanent on top of it.

## Layout

```
fixtures/demo-relocation/         shades commons-text 1.9 with a package relocation
fixtures/demo-plain-merge/        the same dependency merged in unchanged
fixtures/demo-exclude/            the same dependency merged with part of it filtered out
fixtures/demo-relocation-log4j/   a second, much bigger library (log4j-core) with a relocation
matcher/                          the tool that does the actual comparison
reports/                          JSON evidence files from running the matcher on each fixture
```

commons-text 1.9 was picked on purpose. It carries CVE-2022-42889 (the
"Text4Shell" vulnerability), so the fixtures aren't testing against some
made up dependency, they use a real library with a real, serious,
published CVE. log4j-core 2.14.1 was added for the same reason and to
check the approach against a library with a very different shape:
CVE-2021-44228, "Log4Shell", roughly 8 times the class count of
commons-text, and a much deeper, plugin-annotated package structure.

## Building it

You need a JDK (17+) and Maven. From the repo root:

```
mvn package
```

This builds all four fixture JARs and the matcher.

## Running the matcher

Relocation case:

```
java -jar matcher/target/matcher.jar \
  --mode relocation \
  --original ~/.m2/repository/org/apache/commons/commons-text/1.9/commons-text-1.9.jar \
  --output fixtures/demo-relocation/target/demo-relocation.jar \
  --pattern org.apache.commons.text \
  --shaded-pattern com.acme.shaded.commons.text \
  --group org.apache.commons --artifact commons-text --version 1.9 \
  --report reports/relocation-report.json
```

Plain merge and exclude cases use `--mode identity` instead (no rename
happened, so there's nothing to remap, just a direct comparison at the
same path). See `reports/` for the exact commands used to produce each
report.

## What came out of it

| scenario | classes checked | result |
|---|---|---|
| relocation (commons-text) | 135 | all 135 matched exactly after applying the relocation rule |
| plain merge | 135 | all 135 matched exactly, unchanged |
| exclude | 135 | 110 matched, 25 correctly reported as missing (the filtered subpackage) |
| relocation (log4j-core) | 1092 | all 1092 matched exactly after applying the relocation rule |

There's also a negative control: running the matcher with the wrong
input version (1.10.0 instead of 1.9) against the same commons-text
output. It did not report a clean match. 37 classes happened to be byte
identical across versions, 98 were correctly flagged as different, and
19 were correctly flagged as not present. That's the check that actually
matters: if the tool said "match" no matter what you fed it, none of the
results above would mean anything.

The log4j-core run didn't pass cleanly on the first try, and that's
worth writing down rather than hiding. The first pass came back with
1086 matched and 6 flagged ambiguous. Looking at those 6 classes
(ClockFactory, PluginManager, and a few others that look up
implementations by name at runtime), the shaded jar had rewritten string
constants like `"org.apache.logging.log4j.core.util.CachedClock"` to the
new package name, on top of the usual bytecode symbol renaming. Maven
Shade does this deliberately, so that code doing `Class.forName(name)`
or similar still works after relocation, but a plain ASM class remapper
doesn't touch string constants by default, it only rewrites actual type
references. `RelocationRemapper` was extended to also rewrite string
constants that match the old package name (both the "used as a prefix"
and the "is the whole string" case, the second one only showed up once
the first fix was in). After that, log4j-core matched 1092 out of 1092.
commons-text was re-run after each change and stayed at 135 out of 135,
so this wasn't a case of fixing one library by breaking the other.

## How this compares to what's already out there

Before writing any of this, two existing tools were tried against the
same relocated JAR (fixtures/demo-relocation). Both are early-stage
projects and this is not a full review of either one, just a record of
what happened with the specific commands below.

**Unshade** (github.com/stschott/unshade, commit b39cc3a, 2026-03-07).
Built locally and run against demo-relocation.jar in two ways:

* `import-gav -g org.apache.commons -a commons-text -v 1.9` followed by
  `scan-jar -p demo-relocation.jar`: correctly reported commons-text 1.9
  as repackaged.
* `scan-jar -p demo-relocation.jar` on its own, with nothing imported
  first: reported "No rebundled or repackaged dependencies found."

So for this test, the `scan-jar` command found the relocated dependency
only when it had already been told what to look for via `import-gav`.
Other Unshade commands (`import-osv`, and running it against a
pre-populated database) weren't tried.

**Waybill** (github.com/kusari-oss/waybill, release v0.10.0-alpha.1,
Windows build). Run with `waybill sbom scan --path` against the same jar,
twice:

* With the `META-INF/maven/**` files that Maven Shade includes by
  default still in the jar: found both commons-text and commons-lang3,
  tagged in the report as `"technique": "manifest-analysis"`.
* With those files stripped out via a shade `<filter>` (something a
  build is free to do, for example to shrink jar size): `sbom scan`
  reported zero components for the jar.

That's `sbom scan` specifically. Waybill also ships `fingerprints` and an
experimental Linux-only `trace` command that weren't part of this test,
so this isn't a claim about everything the tool can do, just about this
one command on this one jar.

Both results point at the same underlying difference: these tools work
from the finished jar, with no access to how it was built, so they can
only go on what the jar happens to expose (a name you already gave it,
or metadata a build chose not to strip). ShadeLedger's premise is that
if you're the one doing the packaging, you have the original jars and
the build rules right there, so there's nothing left to guess.

## What's not here yet

This is a spike, so a lot is missing on purpose:

* No actual Maven plugin, the matcher is a standalone jar you run by hand
* No CycloneDX/SBOM output, just the raw evidence JSON
* Two dependencies tested so far (commons-text, log4j-core), both with a
  single relocation rule. Custom bytecode transformers, multiple
  simultaneous relocations, and most other shade plugin options haven't
  been touched
* No handling of resource files (only `.class` entries are compared)
* String-constant relocation is handled for plain literals now, but not
  for constants built at runtime (concatenation, `String.format`, and
  so on), since those aren't visible as a single constant pool entry

## License

Apache 2.0, see LICENSE.
