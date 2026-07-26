# Reference

- [Modules](modules.md) lists artifact responsibilities and dependency
  direction.
- [Compatibility](compatibility.md) states the Scala, platform, and release
  policy.
- [Build Alder from source](building-from-source.md) covers the current
  pre-release checkout layout and repository gates.

Generate the current module-specific API reference with:

```text
sbt -J-Xmx4G apiDocs
```

The first release has no hosted API URL yet. This site will link to published
Scaladoc only after an immutable 0.1.0 artifact exists.
