import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useRef, useState } from 'react'
import { useFieldArray, useForm, useWatch, type Control, type FieldErrors, type Path, type UseFormRegister } from 'react-hook-form'
import { Link, useNavigate, useParams } from 'react-router'
import { api } from '@/api/client'
import { isApiError, unwrap } from '@/api/errors'
import { catalogueItemQuery, categoriesQuery, queryKeys } from '@/api/queries'
import type { CatalogueItem, FieldType } from '@/api/types'
import { FieldError } from '@/components/FieldError'
import { NativeSelect } from '@/components/NativeSelect'
import { QueryError } from '@/components/QueryError'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import { Textarea } from '@/components/ui/textarea'
import { CategoryFields } from '@/tickets/CategoryFields'
import { draftPath, emptyQuestion, FIELD_TYPE_LABEL, toDraft, toFields, type ItemDraft } from './catalogueEditor'

/** Create (/admin/catalogue/new) or edit (/admin/catalogue/:id) a catalogue item and its questions. */
export function CatalogueItemEditor() {
  const { id } = useParams()
  const existing = useQuery({ ...catalogueItemQuery(id ?? ''), enabled: id !== undefined })
  // Loaded before the form renders, so the saved subcategory has its option to select.
  const categories = useQuery(categoriesQuery('SERVICE_REQUEST'))
  const loading = categories.isPending || (id !== undefined && existing.isPending)

  return (
    <section className="mx-auto grid max-w-3xl gap-6">
      <Link to="/admin/catalogue" className="text-sm text-muted-foreground hover:underline">
        ← Service catalogue
      </Link>
      {loading && <Skeleton className="h-96 w-full" />}
      {existing.isError && <QueryError error={existing.error} what="This item" />}
      {categories.isError && <QueryError error={categories.error} what="Categories" />}
      {!loading && categories.isSuccess && id === undefined && <ItemForm />}
      {!loading && categories.isSuccess && existing.isSuccess && <ItemForm key={existing.data.version} item={existing.data} />}
    </section>
  )
}

function ItemForm({ item }: { item?: CatalogueItem }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const { register, handleSubmit, control, getValues, setValue, setError, formState } = useForm<ItemDraft>({ defaultValues: toDraft(item) })
  const { errors, isSubmitting } = formState
  const questions = useFieldArray({ control, name: 'questions' })
  const categoryId = useWatch({ control, name: 'categoryId' })

  // CategoryFields asks to clear the subcategory on every render where the category may have
  // changed, including the first; an item being edited must keep the subcategory it was saved with.
  const shownCategory = useRef(item?.category.id ?? '')
  const clearSubcategory = useCallback(() => {
    const current = getValues('categoryId')
    if (current === shownCategory.current) return
    shownCategory.current = current
    setValue('subcategoryId', '')
  }, [getValues, setValue])

  const save = useMutation({
    mutationFn: async (draft: ItemDraft) => {
      const details = {
        name: draft.name.trim(),
        summary: draft.summary.trim(),
        description: draft.description.trim() || undefined,
        categoryId: draft.categoryId,
        subcategoryId: draft.subcategoryId || undefined,
        fields: toFields(draft.questions),
      }
      return item
        ? unwrap(
            await api.PUT('/api/admin/catalogue/{id}', {
              params: { path: { id: item.id } },
              body: { ...details, active: draft.active, version: item.version },
            }),
          )
        : unwrap(await api.POST('/api/admin/catalogue', { body: details }))
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.catalogue })
      void navigate('/admin/catalogue')
    },
  })

  const onSubmit = handleSubmit(async (draft) => {
    setFormError(null)
    try {
      await save.mutateAsync(draft)
    } catch (error) {
      if (!isApiError(error)) {
        setFormError('Something went wrong. Please try again.')
        return
      }
      if (error.code === 'CONCURRENT_MODIFICATION') {
        setFormError('Someone else changed this item meanwhile. Reload the page to see their version, then make your change again.')
        return
      }
      if (error.code === 'CATALOGUE_ITEM_NAME_ALREADY_EXISTS') {
        setError('name', { message: error.message })
        return
      }
      if (error.code === 'INVALID_CATEGORY') {
        setError('categoryId', { message: error.message })
        return
      }
      const placed = (error.problem?.fieldErrors ?? []).flatMap((fe) => {
        const path = draftPath(fe.field)
        if (!path) return []
        setError(path as Path<ItemDraft>, { message: fe.message })
        return [path]
      })
      if (placed.length === 0) setFormError(error.message || 'Something went wrong. Please try again.')
    }
  })

  return (
    <>
      <h1 className="text-2xl font-semibold">{item ? `Edit ${item.name}` : 'Add a catalogue item'}</h1>
      <form onSubmit={onSubmit} noValidate className="grid gap-6">
        {formError && (
          <Alert variant="destructive" role="alert">
            <AlertDescription>{formError}</AlertDescription>
          </Alert>
        )}
        <div className="grid gap-2">
          <Label htmlFor="name">Name</Label>
          <Input
            id="name"
            aria-invalid={errors.name ? true : undefined}
            aria-describedby={errors.name ? 'name-error' : undefined}
            {...register('name', {
              validate: (v) => (v.trim() ? true : 'Give the item a name'),
              maxLength: { value: 100, message: 'Keep the name under 100 characters' },
            })}
          />
          <FieldError id="name-error" message={errors.name?.message} />
        </div>
        <div className="grid gap-2">
          <Label htmlFor="summary">Summary</Label>
          <Input
            id="summary"
            aria-invalid={errors.summary ? true : undefined}
            aria-describedby="summary-help summary-error"
            {...register('summary', {
              validate: (v) => (v.trim() ? true : 'Add a one-line summary'),
              maxLength: { value: 200, message: 'Keep the summary under 200 characters' },
            })}
          />
          <p id="summary-help" className="text-sm text-muted-foreground">
            One line, shown on the catalogue card.
          </p>
          <FieldError id="summary-error" message={errors.summary?.message} />
        </div>
        <div className="grid gap-2">
          <Label htmlFor="description">What requesters should know (optional)</Label>
          <Textarea
            id="description"
            rows={3}
            {...register('description', { maxLength: { value: 4000, message: 'Keep this under 4,000 characters' } })}
          />
          <FieldError id="description-error" message={errors.description?.message} />
        </div>
        <fieldset className="grid gap-2">
          <legend className="mb-2 font-medium">Routing</legend>
          <CategoryFields
            type="SERVICE_REQUEST"
            categoryId={categoryId}
            category={register('categoryId', { required: 'Choose the category that routes this item' })}
            subcategory={register('subcategoryId')}
            categoryError={errors.categoryId}
            onCategoryChanged={clearSubcategory}
          />
          <p className="text-sm text-muted-foreground">Requests go to the team this category routes to.</p>
        </fieldset>
        {item && (
          <div className="flex items-center gap-2">
            <input id="active" type="checkbox" className="size-4 accent-primary" {...register('active')} />
            <Label htmlFor="active">Available to order</Label>
          </div>
        )}

        <fieldset className="grid gap-4">
          <legend className="mb-1 font-medium">Questions</legend>
          {questions.fields.length === 0 && <p className="text-sm text-muted-foreground">No questions: requesters just add optional notes.</p>}
          {questions.fields.map((question, index) => (
            <QuestionEditor
              key={question.id}
              index={index}
              count={questions.fields.length}
              control={control}
              register={register}
              errors={errors}
              onMove={(to) => questions.move(index, to)}
              onRemove={() => questions.remove(index)}
            />
          ))}
          <div>
            <Button type="button" variant="outline" onClick={() => questions.append(emptyQuestion())} disabled={questions.fields.length >= 20}>
              Add question
            </Button>
          </div>
        </fieldset>

        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={() => void navigate('/admin/catalogue')}>
            Cancel
          </Button>
          <Button type="submit" disabled={isSubmitting}>
            {isSubmitting ? 'Saving…' : item ? 'Save changes' : 'Add item'}
          </Button>
        </div>
      </form>
    </>
  )
}

function QuestionEditor({
  index,
  count,
  control,
  register,
  errors,
  onMove,
  onRemove,
}: {
  index: number
  count: number
  control: Control<ItemDraft>
  register: UseFormRegister<ItemDraft>
  errors: FieldErrors<ItemDraft>
  onMove: (to: number) => void
  onRemove: () => void
}) {
  const type = useWatch({ control, name: `questions.${index}.type` })
  const errs = errors.questions?.[index]
  const id = (part: string) => `q${index}-${part}`
  const number = index + 1

  return (
    <div role="group" aria-label={`Question ${number}`} className="grid gap-3 rounded-lg border p-4">
      <div className="grid gap-3 sm:grid-cols-[2fr_1fr]">
        <div className="grid gap-2">
          <Label htmlFor={id('label')}>Question {number}</Label>
          <Input
            id={id('label')}
            aria-invalid={errs?.label ? true : undefined}
            aria-describedby={errs?.label ? id('label-error') : undefined}
            {...register(`questions.${index}.label`, {
              validate: (v) => (v.trim() ? true : 'Write the question'),
              maxLength: { value: 100, message: 'Keep the question under 100 characters' },
            })}
          />
          <FieldError id={id('label-error')} message={errs?.label?.message} />
        </div>
        <div className="grid gap-2">
          <Label htmlFor={id('type')}>Answer type</Label>
          <NativeSelect id={id('type')} {...register(`questions.${index}.type`)}>
            {(Object.keys(FIELD_TYPE_LABEL) as FieldType[]).map((t) => (
              <option key={t} value={t}>
                {FIELD_TYPE_LABEL[t]}
              </option>
            ))}
          </NativeSelect>
        </div>
      </div>
      {type === 'SELECT' && (
        <div className="grid gap-2">
          <Label htmlFor={id('options')}>Options, one per line</Label>
          <Textarea
            id={id('options')}
            rows={3}
            aria-invalid={errs?.optionsText ? true : undefined}
            aria-describedby={errs?.optionsText ? id('options-error') : undefined}
            {...register(`questions.${index}.optionsText`, {
              validate: (v, draft) =>
                draft.questions[index]?.type !== 'SELECT' || v.split('\n').filter((o) => o.trim()).length >= 2
                  ? true
                  : 'Give at least two options',
            })}
          />
          <FieldError id={id('options-error')} message={errs?.optionsText?.message} />
        </div>
      )}
      <div className="grid gap-2">
        <Label htmlFor={id('help')}>Hint (optional)</Label>
        <Input
          id={id('help')}
          {...register(`questions.${index}.helpText`, { maxLength: { value: 300, message: 'Keep the hint under 300 characters' } })}
        />
        <FieldError id={id('help-error')} message={errs?.helpText?.message} />
      </div>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <input id={id('required')} type="checkbox" className="size-4 accent-primary" {...register(`questions.${index}.required`)} />
          <Label htmlFor={id('required')}>Required</Label>
        </div>
        <div className="flex gap-1">
          <Button type="button" variant="ghost" size="sm" disabled={index === 0} onClick={() => onMove(index - 1)} aria-label={`Move question ${number} up`}>
            Up
          </Button>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            disabled={index === count - 1}
            onClick={() => onMove(index + 1)}
            aria-label={`Move question ${number} down`}
          >
            Down
          </Button>
          <Button type="button" variant="ghost" size="sm" onClick={onRemove} aria-label={`Remove question ${number}`}>
            Remove
          </Button>
        </div>
      </div>
    </div>
  )
}
