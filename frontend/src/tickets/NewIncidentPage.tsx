import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useCallback, useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { useNavigate } from 'react-router'
import { z } from 'zod'
import { api } from '@/api/client'
import { unwrap } from '@/api/errors'
import { queryKeys } from '@/api/queries'
import type { CreateIncident } from '@/api/types'
import { FieldError } from '@/components/FieldError'
import { NativeSelect } from '@/components/NativeSelect'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { CategoryFields } from './CategoryFields'
import { LEVEL_LABEL } from './labels'
import { applyServerErrors } from './serverErrors'

const levels = ['LOW', 'MEDIUM', 'HIGH'] as const

// Mirrors the server's validation for instant feedback; the server remains the authority.
const schema = z.object({
  title: z.string().trim().min(1, { error: 'Give the problem a short title' }).max(200, { error: 'Keep the title under 200 characters' }),
  description: z.string().trim().min(1, { error: 'Describe what is happening' }).max(10_000),
  categoryId: z.string().min(1, { error: 'Choose a category' }),
  subcategoryId: z.string(),
  impact: z.enum(levels),
  urgency: z.enum(levels),
  affectedService: z.string().trim().max(120),
})
type Form = z.infer<typeof schema>

export function NewIncidentPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const { register, handleSubmit, control, setValue, setError, formState } = useForm<Form>({
    resolver: zodResolver(schema),
    defaultValues: { title: '', description: '', categoryId: '', subcategoryId: '', impact: 'LOW', urgency: 'MEDIUM', affectedService: '' },
  })
  const { errors, isSubmitting } = formState
  const categoryId = useWatch({ control, name: 'categoryId' })

  const create = useMutation({
    mutationFn: async (body: CreateIncident) => unwrap(await api.POST('/api/incidents', { body })),
    onSuccess: async (created) => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.tickets })
      void navigate(`/tickets/${created.id}`)
    },
  })

  const clearSubcategory = useCallback(() => setValue('subcategoryId', ''), [setValue])

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
        affectedService: form.affectedService || undefined,
      })
    } catch (error) {
      setFormError(
        applyServerErrors(error, setError, ['title', 'description', 'categoryId', 'impact', 'urgency', 'affectedService'], {
          INVALID_CATEGORY: 'categoryId',
        }),
      )
    }
  })

  return (
    <section className="mx-auto grid max-w-2xl gap-6">
      <div>
        <h1 className="text-2xl font-semibold">Report a problem</h1>
        <p className="mt-1 text-muted-foreground">Something isn’t working. We’ll route it to the right team.</p>
      </div>
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
          <Label htmlFor="description">What’s happening?</Label>
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
          type="INCIDENT"
          categoryId={categoryId}
          category={register('categoryId')}
          subcategory={register('subcategoryId')}
          categoryError={errors.categoryId}
          onCategoryChanged={clearSubcategory}
        />
        <div className="grid gap-4 sm:grid-cols-2">
          <div className="grid gap-2">
            <Label htmlFor="impact">Who is affected?</Label>
            <NativeSelect id="impact" {...register('impact')}>
              <option value="LOW">Just me</option>
              <option value="MEDIUM">My team or department</option>
              <option value="HIGH">Many people / the whole organisation</option>
            </NativeSelect>
          </div>
          <div className="grid gap-2">
            <Label htmlFor="urgency">How urgent is it?</Label>
            <NativeSelect id="urgency" {...register('urgency')}>
              {levels.map((level) => (
                <option key={level} value={level}>
                  {LEVEL_LABEL[level]}
                </option>
              ))}
            </NativeSelect>
          </div>
        </div>
        <div className="grid gap-2">
          <Label htmlFor="affectedService">Affected service (optional)</Label>
          <Input id="affectedService" placeholder="e.g. Office Wi-Fi, Payroll" {...register('affectedService')} />
          <FieldError id="affectedService-error" message={errors.affectedService?.message} />
        </div>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={() => void navigate(-1)}>
            Cancel
          </Button>
          <Button type="submit" disabled={isSubmitting}>
            {isSubmitting ? 'Submitting…' : 'Submit'}
          </Button>
        </div>
      </form>
    </section>
  )
}
