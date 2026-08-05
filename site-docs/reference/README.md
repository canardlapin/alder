# Reference

- [Modules](modules.md) lists artifact responsibilities and dependency
  direction.
- [Compatibility](compatibility.md) states the Scala, platform, and release
  policy.
- [Current release status](release-status.md) records dated local evidence and
  unresolved external publication evidence.
- [Build Alder from source](building-from-source.md) covers the current
  pre-release checkout layout and repository gates.

Generate the current module-specific API reference with:

```text
sbt -J-Xmx4G apiDocs
```

This checkout does not contain a hosted API URL or a documentation deployment
workflow. This site will link to published Scaladoc only after an immutable
0.1.0 artifact exists and the hosted result is verified.
