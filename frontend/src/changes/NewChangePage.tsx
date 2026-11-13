import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { z } from 'zod'
import { api } from '@/api/client'
import { unwrap } from '@/api/errors'
import { queryKeys, ticketQuery } from '@/api/queries'
import type { CreateChange } from '@/api/types'
import { FieldError } from '@/components/FieldError'
import { NativeSelect } from '@/components/NativeSelect'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { CategoryFields } from '@/tickets/CategoryFields'
import { LEVEL_LABEL } from '@/tickets/labels'
import { applyServerErrors } from '@/tickets/serverErrors'
import { PlanFields } from './PlanFields'
import { checkPlan, planShape } from './planSchema'
import { fromLocalInput } from './window'

const levels = ['LOW', 'MEDIUM', 'HIGH'] as const

const schema = z
  .object({
    title: z.string().trim().min(1, { error: 'Give the change a short title' }).max(200, { error: 'Keep the title under 200 characters' }),
    description: z.string().trim().min(1, { error: 'Say what will change and why' }).max(10_000),
    categoryId: z.string().min(1, { error: 'Choose the service it changes' }),
    subcategoryId: z.string(),
    impact: z.enum(levels),
    urgency: z.enum(levels),
    ...planShape,
  })
  .superRefine(checkPlan)
type Form = z.infer<typeof schema>

/** Support raises a change, optionally as the fix for a problem (?problem=id). The plan can be finished later. */
export function NewChangePage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [searchParams] = useSearchParams()
  const problemId = searchParams.get('problem')
  const problem = useQuery({ ...ticketQuery(problemId ?? ''), enabled: Boolean(problemId) })
  const [formError, setFormError] = useState<string | null>(null)
  const { register, handleSubmit, control, setValue, setError, formState } = useForm<Form>({
    resolver: zodResolver(schema),
    defaultValues: {
      title: '',
      description: '',
      categoryId: '',
      subcategoryId: '',
      impact: 'MEDIUM',
      urgency: 'MEDIUM',
      changeType: 'NORMAL',
      risk: 'MEDIUM',
      implementationPlan: '',
      backoutPlan: '',
      plannedStart: '',
      plannedEnd: '',
    },
  })
  const { errors, isSubmitting } = formState
  const categoryId = useWatch({ control, name: 'categoryId' })
  const changeType = useWatch({ control, name: 'changeType' })
  const clearSubcategory = useCallback(() => setValue('subcategoryId', ''), [setValue])

  const create = useMutation({
    mutationFn: async (body: CreateChange) => unwrap(await api.POST('/api/changes', { body })),
    onSuccess: async (created) => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.tickets })
      void navigate(`/tickets/${created.id}`)
    },
  })

  const onSubmit = handleSubmit(async (form) => {
    setFormError(null)
    try {
      await create.mutateAsync({
        title: form.title,
        description: form.description,
        categoryId: form.categoryId,
        subcategoryId: form.subcategoryId || undefined,
        impact: form.impact,
        urgency: form.urgency,
        changeType: form.changeType,
        risk: form.risk,
        implementationPlan: form.implementationPlan || undefined,
        backoutPlan: form.backoutPlan || undefined,
        plannedStart: fromLocalInput(form.plannedStart),
        plannedEnd: fromLocalInput(form.plannedEnd),
        problemId: problemId ?? undefined,
      })
    } catch (error) {
      setFormError(
        applyServerErrors(error, setError, ['title', 'description', 'categoryId', 'impact', 'urgency', 'implementationPlan', 'backoutPlan'], {
          INVALID_CATEGORY: 'categoryId',
        }),
      )
    }
  })

  return (
    <section className="mx-auto grid max-w-2xl gap-6">
      <div>
        <h1 className="text-2xl font-semibold">Raise a change</h1>
        <p className="mt-1 text-muted-foreground">
          Plan a change to a live service. The plan can be finished later, but must be complete before it is submitted.
        </p>
      </div>
      {problem.isSuccess && (
        <p className="rounded-md border bg-muted p-3 text-sm">
          The permanent fix for{' '}
          <Link to={`/tickets/${problem.data.id}`} className="font-mono underline-offset-4 hover:underline">
            {problem.data.reference}
          </Link>{' '}
          “{problem.data.title}”.
        </p>
      )}
      <form onSubmit={onSubmit} noValidate className="grid gap-5">
        {formError && (
          <Alert variant="destructive" role="alert">
            <AlertDescription>{formError}</AlertDescription>
          </Alert>
        )}
        <div className="grid gap-2">
          <Label htmlFor="title">Title</Label>
          <Input id="title" aria-invalid={errors.title ? true : undefined} aria-describedby={errors.title ? 'title-error' : undefined} {...register('title')} />
          <FieldError id="title-error" message={errors.title?.message} />
        </div>
        <div className="grid gap-2">
          <Label htmlFor="description">What will change, and why?</Label>
          <Textarea
            id="description"
            rows={4}
            aria-invalid={errors.description ? true : undefined}
            aria-describedby={errors.description ? 'description-error' : undefined}
            {...register('description')}
          />
          <FieldError id="description-error" message={errors.description?.message} />
        </div>
        <CategoryFields
          type="CHANGE"
          categoryId={categoryId}
          category={register('categoryId')}
          subcategory={register('subcategoryId')}
          categoryError={errors.categoryId}
          onCategoryChanged={clearSubcategory}
        />
        <div className="grid gap-4 sm:grid-cols-2">
          <div className="grid gap-2">
            <Label htmlFor="impact">Impact</Label>
            <NativeSelect id="impact" {...register('impact')}>
              {levels.map((level) => (
                <option key={level} value={level}>
                  {LEVEL_LABEL[level]}
                </option>
              ))}
            </NativeSelect>
          </div>
          <div className="grid gap-2">
            <Label htmlFor="urgency">Urgency</Label>
            <NativeSelect id="urgency" {...register('urgency')}>
              {levels.map((level) => (
                <option key={level} value={level}>
                  {LEVEL_LABEL[level]}
                </option>
              ))}
            </NativeSelect>
          </div>
        </div>
        <PlanFields register={register} errors={errors} changeType={changeType} />
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={() => void navigate(-1)}>
            Cancel
          </Button>
          <Button type="submit" disabled={isSubmitting}>
            {isSubmitting ? 'Raising…' : 'Raise change'}
          </Button>
        </div>
      </form>
    </section>
  )
}
