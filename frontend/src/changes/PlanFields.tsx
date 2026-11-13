import type { FieldErrors, Path, UseFormRegister } from 'react-hook-form'
import type { ChangeType } from '@/api/types'
import { FieldError } from '@/components/FieldError'
import { NativeSelect } from '@/components/NativeSelect'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { CHANGE_TYPE_HINT, CHANGE_TYPE_LABEL, RISK_LABEL } from '@/tickets/labels'
import { CHANGE_TYPES, RISKS, type PlanForm } from './planSchema'

/** The plan's inputs, shared by "Raise a change" and editing the plan on the change's page. */
export function PlanFields<T extends PlanForm>({
  register: registerAny,
  errors,
  changeType,
}: {
  register: UseFormRegister<T>
  errors: FieldErrors<PlanForm>
  changeType: ChangeType
}) {
  // Every form using these fields includes the plan's, so its names are valid paths of T.
  const register = (name: keyof PlanForm) => registerAny(name as Path<T>)
  return (
    <>
      <div className="grid gap-4 sm:grid-cols-2">
        <div className="grid gap-2">
          <Label htmlFor="changeType">Change type</Label>
          <NativeSelect id="changeType" aria-describedby="changeType-hint" {...register('changeType')}>
            {CHANGE_TYPES.map((type) => (
              <option key={type} value={type}>
                {CHANGE_TYPE_LABEL[type]}
              </option>
            ))}
          </NativeSelect>
          <p id="changeType-hint" className="text-xs text-muted-foreground">
            {CHANGE_TYPE_HINT[changeType]}
          </p>
        </div>
        <div className="grid gap-2 content-start">
          <Label htmlFor="risk">Risk</Label>
          <NativeSelect
            id="risk"
            aria-invalid={errors.risk ? true : undefined}
            aria-describedby={errors.risk ? 'risk-error' : undefined}
            {...register('risk')}
          >
            {RISKS.map((risk) => (
              <option key={risk} value={risk}>
                {RISK_LABEL[risk]}
              </option>
            ))}
          </NativeSelect>
          <FieldError id="risk-error" message={errors.risk?.message} />
        </div>
      </div>
      <div className="grid gap-2">
        <Label htmlFor="implementationPlan">Implementation plan</Label>
        <Textarea
          id="implementationPlan"
          rows={5}
          placeholder="The steps, in order, and how you'll know it worked."
          aria-invalid={errors.implementationPlan ? true : undefined}
          aria-describedby={errors.implementationPlan ? 'implementationPlan-error' : undefined}
          {...register('implementationPlan')}
        />
        <FieldError id="implementationPlan-error" message={errors.implementationPlan?.message} />
      </div>
      <div className="grid gap-2">
        <Label htmlFor="backoutPlan">Backout plan</Label>
        <Textarea
          id="backoutPlan"
          rows={3}
          placeholder="How to put things back as they were if it goes wrong."
          aria-invalid={errors.backoutPlan ? true : undefined}
          aria-describedby={errors.backoutPlan ? 'backoutPlan-error' : undefined}
          {...register('backoutPlan')}
        />
        <FieldError id="backoutPlan-error" message={errors.backoutPlan?.message} />
      </div>
      <fieldset className="grid gap-4 sm:grid-cols-2">
        <legend className="mb-2 text-sm font-medium">Planned window</legend>
        <div className="grid gap-2">
          <Label htmlFor="plannedStart">Starts</Label>
          <Input
            id="plannedStart"
            type="datetime-local"
            aria-invalid={errors.plannedStart ? true : undefined}
            aria-describedby={errors.plannedStart ? 'plannedStart-error' : undefined}
            {...register('plannedStart')}
          />
          <FieldError id="plannedStart-error" message={errors.plannedStart?.message} />
        </div>
        <div className="grid gap-2">
          <Label htmlFor="plannedEnd">Ends</Label>
          <Input
            id="plannedEnd"
            type="datetime-local"
            aria-invalid={errors.plannedEnd ? true : undefined}
            aria-describedby={errors.plannedEnd ? 'plannedEnd-error' : undefined}
            {...register('plannedEnd')}
          />
          <FieldError id="plannedEnd-error" message={errors.plannedEnd?.message} />
        </div>
      </fieldset>
    </>
  )
}
