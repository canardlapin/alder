# Core concepts

Alder's types record facts that ordinary machine-learning pipelines often leave
implicit: why data exists, which target information a stage observed, whether
prepared rows may train another stage, and which stage produced a failure.

You do not need these details before
[fitting your first workflow](../getting-started.md). Use them when you want to
understand why a composition is accepted or rejected:

1. [Data roles and preparation scope](data-roles.md) explains the `Use` and
   `Preparation` hierarchies.
2. [Fitting and composition](fitting-and-composition.md) explains components,
   `Pipe`, `FitResult`, and audit construction.

For task-oriented examples, see [Guides](../guides/README.md).
