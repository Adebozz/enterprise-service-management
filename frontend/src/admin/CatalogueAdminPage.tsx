import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { adminCatalogueQuery } from '@/api/queries'
import { QueryError } from '@/components/QueryError'
import { Badge } from '@/components/ui/badge'
import { buttonVariants } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'

/** Admins see every catalogue item, including withdrawn ones, and open one to edit it. */
export function CatalogueAdminPage() {
  const items = useQuery(adminCatalogueQuery)

  return (
    <section className="grid gap-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="grid gap-1">
          <h1 className="text-2xl font-semibold">Service catalogue</h1>
          <p className="text-muted-foreground">What requesters can order, the questions each asks, and which team receives it.</p>
        </div>
        <Link to="/admin/catalogue/new" className={buttonVariants()}>
          Add item
        </Link>
      </div>
      {items.isPending && <Skeleton className="h-48 w-full" />}
      {items.isError && <QueryError error={items.error} what="The catalogue" />}
      {items.isSuccess &&
        (items.data.length === 0 ? (
          <p className="rounded-md border border-dashed p-8 text-center text-muted-foreground">The catalogue is empty. Add the first item.</p>
        ) : (
          <div className="overflow-x-auto rounded-md border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Item</TableHead>
                  <TableHead>Routed by</TableHead>
                  <TableHead>Questions</TableHead>
                  <TableHead>Status</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {items.data.map((item) => (
                  <TableRow key={item.id}>
                    <TableCell>
                      <Link to={`/admin/catalogue/${item.id}`} className="font-medium hover:underline">
                        {item.name}
                      </Link>
                      <span className="block max-w-xs truncate text-xs text-muted-foreground">{item.summary}</span>
                    </TableCell>
                    <TableCell>
                      {item.category.name}
                      {item.subcategory && <span className="text-muted-foreground"> › {item.subcategory.name}</span>}
                    </TableCell>
                    <TableCell>{item.fields.length}</TableCell>
                    <TableCell>{item.active ? <Badge variant="secondary">Available</Badge> : <Badge variant="outline">Withdrawn</Badge>}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        ))}
    </section>
  )
}
