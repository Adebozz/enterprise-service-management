import { useQuery } from '@tanstack/react-query'
import { useEffect } from 'react'
import type { FieldError as RhfFieldError, UseFormRegisterReturn } from 'react-hook-form'
import { categoriesQuery } from '@/api/queries'
import type { TicketType } from '@/api/types'
import { FieldError } from '@/components/FieldError'
import { NativeSelect } from '@/components/NativeSelect'
import { QueryError } from '@/components/QueryError'
import { Label } from '@/components/ui/label'

interface Props {
  type: TicketType
  categoryId: string
  category: UseFormRegisterReturn
  subcategory: UseFormRegisterReturn
  categoryError?: RhfFieldError
  onCategoryChanged: () => void
}

/** Category, then a subcategory list limited to the chosen category (both optional-free for the type). */
export function CategoryFields({ type, categoryId, category, subcategory, categoryError, onCategoryChanged }: Props) {
  const categories = useQuery(categoriesQuery(type))
  const subcategories = categories.data?.find((c) => c.id === categoryId)?.subcategories ?? []

  useEffect(() => {
    onCategoryChanged() // a subcategory from the previous category would be rejected by the server
  }, [categoryId, onCategoryChanged])

  if (categories.isError) {
    return <QueryError error={categories.error} what="Categories" />
  }
  return (
    <div className="grid gap-4 sm:grid-cols-2">
      <div className="grid gap-2">
        <Label htmlFor="categoryId">Category</Label>
        <NativeSelect
          id="categoryId"
          disabled={categories.isPending}
          aria-invalid={categoryError ? true : undefined}
          aria-describedby={categoryError ? 'categoryId-error' : undefined}
          {...category}
        >
          <option value="">{categories.isPending ? 'Loading…' : 'Select a category'}</option>
          {categories.data?.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
        </NativeSelect>
        <FieldError id="categoryId-error" message={categoryError?.message} />
      </div>
      <div className="grid gap-2">
        <Label htmlFor="subcategoryId">Subcategory (optional)</Label>
        <NativeSelect id="subcategoryId" disabled={subcategories.length === 0} {...subcategory}>
          <option value="">{subcategories.length === 0 ? 'None available' : 'Not sure / other'}</option>
          {subcategories.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name}
            </option>
          ))}
        </NativeSelect>
      </div>
    </div>
  )
}
