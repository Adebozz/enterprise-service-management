import { z } from 'zod'
import type { ChangeRisk, ChangeType } from '@/api/types'
import { CHANGE_TYPE_LABEL, RISK_LABEL } from '@/tickets/labels'

export const CHANGE_TYPES = Object.keys(CHANGE_TYPE_LABEL) as [ChangeType, ...ChangeType[]]
export const RISKS = Object.keys(RISK_LABEL) as [ChangeRisk, ...ChangeRisk[]]

/**
 * The plan's fields and the rules that hold at all times (the server checks them too). What must be
 * complete before submitting is left to the server, which says exactly what is missing.
 */
export const planShape = {
  changeType: z.enum(CHANGE_TYPES),
  risk: z.enum(RISKS),
  implementationPlan: z.string().max(10_000, { error: 'Keep it under 10,000 characters' }),
  backoutPlan: z.string().max(10_000, { error: 'Keep it under 10,000 characters' }),
  plannedStart: z.string(),
  plannedEnd: z.string(),
}

export interface PlanForm {
  changeType: ChangeType
  risk: ChangeRisk
  implementationPlan: string
  backoutPlan: string
  plannedStart: string
  plannedEnd: string
}

export function checkPlan(form: PlanForm, ctx: z.RefinementCtx<PlanForm>) {
  if (form.changeType === 'STANDARD' && form.risk !== 'LOW') {
    ctx.addIssue({ code: 'custom', path: ['risk'], message: 'A standard change is low risk; choose a normal change for this risk' })
  }
  if (form.plannedStart && form.plannedEnd && form.plannedEnd <= form.plannedStart) {
    ctx.addIssue({ code: 'custom', path: ['plannedEnd'], message: 'The window must end after it starts' })
  }
  if (Boolean(form.plannedStart) !== Boolean(form.plannedEnd)) {
    ctx.addIssue({ code: 'custom', path: [form.plannedStart ? 'plannedEnd' : 'plannedStart'], message: 'Give both ends of the window' })
  }
}
