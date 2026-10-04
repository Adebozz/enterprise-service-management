import { Button } from '@/components/ui/button'

/** Previous/next paging. `page` is zero-based (as in the API). */
export function Pagination({ page, totalPages, onChange }: { page: number; totalPages: number; onChange: (page: number) => void }) {
  if (totalPages <= 1) return null
  return (
    <nav aria-label="Pagination" className="flex items-center justify-between gap-4">
      <Button variant="outline" size="sm" disabled={page === 0} onClick={() => onChange(page - 1)}>
        Previous
      </Button>
      <span className="text-sm text-muted-foreground" aria-live="polite">
        Page {page + 1} of {totalPages}
      </span>
      <Button variant="outline" size="sm" disabled={page + 1 >= totalPages} onClick={() => onChange(page + 1)}>
        Next
      </Button>
    </nav>
  )
}
