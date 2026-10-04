import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useCallback, useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { useNavigate } from 'react-router'
import { z } from 'zod'
import { api } from '@/api/client'
import { unwrap } from '@/api/errors'
import { queryKeys } from '@/api/queries'
import type { CreateServiceRequest } from '@/api/types'
import { FieldError } from '@/components/FieldError'
import { NativeSelect } from '@/components/NativeSelect'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { CategoryFields } from './CategoryFields'
import { applyServerErrors } from './serverErrors'

const schema = z.object({
  title: z.string().trim().min(1, { error: 'Say what you need' }).max(200, { error: 'Keep the title under 200 characters' }),
  description: z.string().trim().min(1, { error: 'Add the details we need' }).max(10_000),
  categoryId: z.string().min(1, { error: 'Choose a category' }),
  subcategoryId: z.string(),
  urgency: z.enum(['LOW', 'MEDIUM', 'HIGH']),
})
type Form = z.infer<typeof schema>

export function NewServiceRequestPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const { register, handleSubmit, control, setValue, setError, formState } = useForm<Form>({
    resolver: zodResolver(schema),
    defaultValues: { title: '', description: '', categoryId: '', subcategoryId: '', urgency: 'MEDIUM' },
  })
  const { errors, isSubmitting } = formState
  const categoryId = useWatch({ control, name: 'categoryId' })

  const create = useMutation({
    mutationFn: async (body: CreateServiceRequest) => unwrap(await api.POST('/api/service-requests', { body })),
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
        urgency: form.urgency,
      })
    } catch (error) {
      setFormError(
        applyServerErrors(error, setError, ['title', 'description', 'categoryId', 'urgency'], { INVALID_CATEGORY: 'categoryId' }),
      )
    }
  })

  return (
    <section className="mx-auto grid max-w-2xl gap-6">
      <div>
        <h1 className="text-2xl font-semibold">Request something</h1>
        <p className="mt-1 text-muted-foreground">Equipment, access or software you need.</p>
      </div>
      <form onSubmit={onSubmit} noValidate className="grid gap-5">
        {formError && (
          <Alert variant="destructive" role="alert">
            <AlertDescription>{formError}</AlertDescription>
          </Alert>
        )}
        <div className="grid gap-2">
          <Label htmlFor="title">What do you need?</Label>
          <Input id="title" aria-invalid={errors.title ? true : undefined} aria-describedby={errors.title ? 'title-error' : undefined} {...register('title')} />
          <FieldError id="title-error" message={errors.title?.message} />
        </div>
        <div className="grid gap-2">
          <Label htmlFor="description">Details</Label>
          <Textarea
            id="description"
            rows={5}
            aria-invalid={errors.description ? true : undefined}
            aria-describedby={errors.description ? 'description-error' : undefined}
            {...register('description')}
          />
          <FieldError id="description-error" message={errors.description?.message} />
        </div>
        <CategoryFields
          type="SERVICE_REQUEST"
          categoryId={categoryId}
          category={register('categoryId')}
          subcategory={register('subcategoryId')}
          categoryError={errors.categoryId}
          onCategoryChanged={clearSubcategory}
        />
        <div className="grid max-w-xs gap-2">
          <Label htmlFor="urgency">When do you need it?</Label>
          <NativeSelect id="urgency" {...register('urgency')}>
            <option value="LOW">No rush</option>
            <option value="MEDIUM">Within a few days</option>
            <option value="HIGH">As soon as possible</option>
          </NativeSelect>
        </div>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={() => void navigate(-1)}>
            Cancel
          </Button>
          <Button type="submit" disabled={isSubmitting}>
            {isSubmitting ? 'Submitting…' : 'Submit request'}
          </Button>
        </div>
      </form>
    </section>
  )
}
