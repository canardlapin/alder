# Compatibility

## Scala

Scala 3.7.4 is Alder 0.1's publication baseline. Alder does not promise that a
Scala 3.3.x compiler can consume artifacts published from 3.7.4.

The development build can consume older Scala 3.3.8 TASTy from current Gale and
linop4s artifacts. That compatibility direction is not symmetric.

## Platforms

Pure modules cross-build for:

- JVM;
- Scala.js; and
- Scala Native.

Backend adapters follow their solver libraries. The Gale adapter supports JVM
and Scala.js. The linop4s adapter supports all three platforms.

The executable guide site uses the JVM projection. Platform claims come from
the aggregate test gate, not from mdoc.

## Compatibility checks

The first `0.1.0` release has no previous Alder artifact. MiMa and TASTy-MiMa
therefore run against an explicitly empty baseline:

```text
sbt -J-Xmx4G compatibilityCheck
```

After 0.1.0 is published, select that immutable baseline:

```text
sbt -J-Xmx4G -Dalder.compatibility.previous=0.1.0 compatibilityCheck
```

MiMa checks JVM classfile compatibility. TASTy-MiMa checks the Scala 3 public
representation. Neither task replaces source compilation of downstream
consumers.

## Release status

The current checkout is pre-release. It uses source composites for Tessera,
Gale, and linop4s. Stable Alder artifacts must use published, non-snapshot
dependencies and must be verified from their generated POMs.
