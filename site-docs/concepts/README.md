# Core concepts

Alder's types record facts that ordinary machine-learning pipelines often leave
implicit: why data exists, which target information a stage observed, whether
prepared rows may train another stage, and which stage produced a failure.

Read these pages in order:

1. [Data roles and preparation scope](data-roles.md) explains the `Use` and
   `Preparation` hierarchies.
2. [Fitting and composition](fitting-and-composition.md) explains components,
   `Pipe`, `FitResult`, and audit construction.

Afterward, choose a task in [Guides](../guides/README.md).
