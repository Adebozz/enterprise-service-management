import { Link } from 'react-router'
import { Button } from '@/components/ui/button'

export function NotFoundPage() {
  return (
    <main className="mx-auto max-w-md px-4 py-16 text-center">
      <h1 className="text-2xl font-semibold">Page not found</h1>
      <p className="mt-2 text-muted-foreground">The address may be mistyped, or the page has moved.</p>
      <Button asChild className="mt-6">
        <Link to="/">Go to home</Link>
      </Button>
    </main>
  )
}
