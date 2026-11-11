import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { catalogueQuery } from '@/api/queries'
import { QueryError } from '@/components/QueryError'
import { Skeleton } from '@/components/ui/skeleton'

const cardClass =
  'rounded-xl border p-4 transition-colors hover:bg-muted focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none'

/** What requesters can order. Anything not listed can still be asked for in free text. */
export function CataloguePage() {
  const items = useQuery(catalogueQuery)

  return (
    <section className="grid gap-6">
      <div>
        <h1 className="text-2xl font-semibold">Request something</h1>
        <p className="mt-1 text-muted-foreground">Choose what you need. Each request asks only the questions support needs.</p>
      </div>
      {items.isPending && (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3" aria-busy="true" aria-label="Loading the catalogue">
          <Skeleton className="h-24" />
          <Skeleton className="h-24" />
          <Skeleton className="h-24" />
        </div>
      )}
      {items.isError && <QueryError error={items.error} what="The catalogue" />}
      {items.isSuccess && (
        <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {items.data.map((item) => (
            <li key={item.id} className="grid">
              <Link to={`/catalogue/${item.id}`} className={cardClass}>
                <span className="font-medium">{item.name}</span>
                <span className="mt-1 block text-sm text-muted-foreground">{item.summary}</span>
              </Link>
            </li>
          ))}
          <li className="grid">
            <Link to="/tickets/new/request" className={`${cardClass} border-dashed`}>
              <span className="font-medium">Something else</span>
              <span className="mt-1 block text-sm text-muted-foreground">Not listed here? Describe what you need in your own words.</span>
            </Link>
          </li>
        </ul>
      )}
    </section>
  )
}
