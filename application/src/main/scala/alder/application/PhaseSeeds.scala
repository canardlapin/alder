package alder.application

import alder.kernel.{PlanFingerprint, Seed}

/** Stable domains for experiment-level randomness. */
enum ExperimentPhase derives CanEqual:
  case Split
  case CandidateFit
  case Validation
  case SelectedRefit
  case Test
  case DeploymentRefit

/** One root seed expanded into stable, plan-scoped lifecycle seeds. */
final class PhaseSeeds private (
    val root: Seed,
    val plan: PlanFingerprint
):
  def apply(phase: ExperimentPhase): Seed =
    ReceiptHash.phaseSeed(root, plan, phase)

  def split: Seed = apply(ExperimentPhase.Split)
  def candidateFit: Seed = apply(ExperimentPhase.CandidateFit)
  def validation: Seed = apply(ExperimentPhase.Validation)
  def selectedRefit: Seed = apply(ExperimentPhase.SelectedRefit)
  def test: Seed = apply(ExperimentPhase.Test)
  def deploymentRefit: Seed = apply(ExperimentPhase.DeploymentRefit)

object PhaseSeeds:
  def apply(root: Seed, plan: PlanFingerprint): PhaseSeeds =
    new PhaseSeeds(root, plan)
