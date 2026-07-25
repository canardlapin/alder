package alder.kernel

/** The role of a dataset. Phantom: no values exist; only the type parameter of
  * [[Data]] carries it. Ordinary fitting accepts `U <: Use.Fit`; tuning accepts
  * `Use.Train` only; evaluation accepts `Use.Evaluation`. There is no public
  * retagging — promotion to `Refit` happens through narrow audited constructors
  * (PRD dataProtocol.useHierarchy, D9).
  */
sealed trait Use

object Use:
  sealed trait Unsplit extends Use
  sealed trait Fit extends Use
  sealed trait Train extends Fit
  sealed trait Refit extends Fit
  sealed trait Evaluation extends Use
  sealed trait Validation extends Evaluation
  sealed trait Test extends Evaluation
