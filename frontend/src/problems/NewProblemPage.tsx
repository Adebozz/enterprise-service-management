import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { z } from 'zod'
import { api } from '@/api/client'
import { unwrap } from '@/api/errors'
import { queryKeys, ticketQuery } from '@/api/queries'
import type { CreateProblem } from '@/api/types'
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

const levels = ['LOW', 'MEDIUM', 'HIGH'] as const

const schema = z.object({
  title: z.string().trim().min(1, { error: 'Give the problem a short title' }).max(200, { error: 'Keep the title under 200 characters' }),
  description: z.string().trim().min(1, { error: 'Describe what keeps happening' }).max(10_000),
  categoryId: z.string().min(1, { error: 'Choose a category' }),
  subcategoryId: z.string(),
  impact: z.enum(levels),
  urgency: z.enum(levels),
})
type Form = z.infer<typeof schema>

/** Support raises a problem, optionally from the incident that prompted it (?incident=id), which is linked. */
export function NewProblemPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [searchParams] = useSearchParams()
  const incidentId = searchParams.get('incident')
  const incident = useQuery({ ...ticketQuery(incidentId ?? ''), enabled: Boolean(incidentId) })
  const [formError, setFormError] = useState<string | null>(null)
  const { register, handleSubmit, control, setValue, setError, formState } = useForm<Form>({
    resolver: zodResolver(schema),
    defaultValues: { title: '', description: '', categoryId: '', subcategoryId: '', impact: 'MEDIUM', urgency: 'MEDIUM' },
  })
  const { errors, isSubmitting } = formState
  const categoryId = useWatch({ control, name: 'categoryId' })
  const clearSubcategory = useCallback(() => setValue('subcategoryId', ''), [setValue])

  const create = useMutation({
    mutationFn: async (body: CreateProblem) => unwrap(await api.POST('/api/problems', { body })),
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
        incidentId: incidentId ?? undefined,
      })
    } catch (error) {
      setFormError(
        applyServerErrors(error, setError, ['title', 'description', 'categoryId', 'impact', 'urgency'], { INVALID_CATEGORY: 'categoryId' }),
      )
    }
  })

  return (
    <section className="mx-auto grid max-w-2xl gap-6">
      <div>
        <h1 className="text-2xl font-semibold">Raise a problem</h1>
        <p className="mt-1 text-muted-foreground">
          For incidents that keep coming back: the team investigates the cause while incidents keep people working.
        </p>
      </div>
      {incident.isSuccess && (
        <p className="rounded-md border bg-muted p-3 text-sm">
          Raised from{' '}
          <Link to={`/tickets/${incident.data.id}`} className="font-mono underline-offset-4 hover:underline">
            {incident.data.reference}
          </Link>{' '}
          “{incident.data.title}”, which will be linked to it.
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
          <Label htmlFor="description">What keeps happening?</Label>
          <Textarea
            id="description"
            rows={6}
            aria-invalid={errors.description ? true : undefined}
            aria-describedby={errors.description ? 'description-error' : undefined}
            {...register('description')}
          />
          <FieldError id="description-error" message={errors.description?.message} />
        </div>
        <CategoryFields
          type="PROBLEM"
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
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={() => void navigate(-1)}>
            Cancel
          </Button>
          <Button type="submit" disabled={isSubmitting}>
            {isSubmitting ? 'Raising…' : 'Raise problem'}
          </Button>
        </div>
      </form>
    </section>
  )
}
