import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm, type Path, type UseFormRegister } from 'react-hook-form'
import { Link, useNavigate, useParams } from 'react-router'
import { api } from '@/api/client'
import { unwrap } from '@/api/errors'
import { catalogueItemQuery, queryKeys } from '@/api/queries'
import type { CatalogueItem, FormField, OrderCatalogueItem, Urgency } from '@/api/types'
import { FieldError } from '@/components/FieldError'
import { NativeSelect } from '@/components/NativeSelect'
import { QueryError } from '@/components/QueryError'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import { Textarea } from '@/components/ui/textarea'
import { applyServerErrors } from '@/tickets/serverErrors'

/** Mirrors the server's limits (CatalogueForm) so most mistakes are caught before sending. */
const MAX_LENGTH: Partial<Record<FormField['type'], number>> = { TEXT: 500, LONG_TEXT: 4000 }

interface OrderForm {
  answers: Record<string, string>
  notes: string
  urgency: Urgency
}

/** One catalogue item: what it is, and a form built from its questions. */
export function OrderItemPage() {
  const { id = '' } = useParams()
  const item = useQuery(catalogueItemQuery(id))

  return (
    <section className="mx-auto grid max-w-2xl gap-6">
      <Link to="/catalogue" className="text-sm text-muted-foreground hover:underline">
        ← All requests
      </Link>
      {item.isPending && <Skeleton className="h-64 w-full" />}
      {item.isError && <QueryError error={item.error} what="This item" />}
      {item.isSuccess && <OrderForm key={`${item.data.id}-${item.data.version}`} item={item.data} />}
    </section>
  )
}

function OrderForm({ item }: { item: CatalogueItem }) {
  const { id: itemId, name, summary, description, fields } = item
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const { register, handleSubmit, setError, formState } = useForm<OrderForm>({
    defaultValues: { answers: Object.fromEntries(fields.map((f) => [f.key, ''])), notes: '', urgency: 'MEDIUM' },
  })
  const { errors, isSubmitting } = formState

  const order = useMutation({
    mutationFn: async (body: OrderCatalogueItem) =>
      unwrap(await api.POST('/api/catalogue/{id}/requests', { params: { path: { id: itemId } }, body })),
    onSuccess: async (created) => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.tickets })
      void navigate(`/tickets/${created.id}`)
    },
  })

  const onSubmit = handleSubmit(async (form) => {
    setFormError(null)
    const answers = Object.fromEntries(Object.entries(form.answers).filter(([, value]) => value.trim() !== ''))
    try {
      await order.mutateAsync({ answers, notes: form.notes.trim() || undefined, urgency: form.urgency })
    } catch (error) {
      const paths = [...fields.map((f) => `answers.${f.key}` as Path<OrderForm>), 'notes' as const]
      setFormError(applyServerErrors(error, setError, paths))
    }
  })

  return (
    <>
      <div>
        <h1 className="text-2xl font-semibold">{name}</h1>
        <p className="mt-1 text-muted-foreground">{summary}</p>
        {description && <p className="mt-3 whitespace-pre-wrap">{description}</p>}
        {item.approvalRequired && (
          <p className="mt-3 text-sm text-muted-foreground">A team lead approves this request before work starts. You can follow it in My tickets.</p>
        )}
      </div>
      <form onSubmit={onSubmit} noValidate className="grid gap-5">
        {formError && (
          <Alert variant="destructive" role="alert">
            <AlertDescription>{formError}</AlertDescription>
          </Alert>
        )}
        {fields.map((field) => (
          <Question key={field.key} field={field} register={register} error={errors.answers?.[field.key]?.message} />
        ))}
        <div className="grid gap-2">
          <Label htmlFor="notes">Anything else? (optional)</Label>
          <Textarea id="notes" rows={3} {...register('notes', { maxLength: { value: 4000, message: 'Keep this under 4,000 characters' } })} />
          <FieldError id="notes-error" message={errors.notes?.message} />
        </div>
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
    </>
  )
}

function Question({ field, register, error }: { field: FormField; register: UseFormRegister<OrderForm>; error?: string }) {
  const id = `answer-${field.key}`
  const describedBy = [field.helpText ? `${id}-help` : null, error ? `${id}-error` : null].filter(Boolean).join(' ') || undefined
  const max = MAX_LENGTH[field.type]
  const registration = register(`answers.${field.key}`, {
    validate: (value: string) => {
      const answer = (value ?? '').trim()
      if (field.required && answer === '') return 'This question needs an answer'
      if (max !== undefined && answer.length > max) return `Keep this under ${max.toLocaleString('en-GB')} characters`
      return true
    },
  })
  const common = { id, 'aria-invalid': error ? true : undefined, 'aria-describedby': describedBy, 'aria-required': field.required || undefined }

  return (
    <div className="grid gap-2">
      <Label htmlFor={id}>
        {field.label}
        {!field.required && <span className="font-normal text-muted-foreground"> (optional)</span>}
      </Label>
      {field.type === 'TEXT' && <Input {...common} {...registration} />}
      {field.type === 'LONG_TEXT' && <Textarea rows={4} {...common} {...registration} />}
      {field.type === 'DATE' && <Input type="date" className="max-w-xs" {...common} {...registration} />}
      {field.type === 'SELECT' && (
        <NativeSelect {...common} {...registration}>
          <option value="">Choose…</option>
          {(field.options ?? []).map((option) => (
            <option key={option} value={option}>
              {option}
            </option>
          ))}
        </NativeSelect>
      )}
      {field.helpText && (
        <p id={`${id}-help`} className="text-sm text-muted-foreground">
          {field.helpText}
        </p>
      )}
      <FieldError id={`${id}-error`} message={error} />
    </div>
  )
}
