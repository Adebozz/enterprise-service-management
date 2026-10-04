import { Link } from 'react-router'
import { Button } from '@/components/ui/button'

export function ForbiddenPage() {
  return (
    <section className="mx-auto max-w-md py-16 text-center">
      <h1 className="text-2xl font-semibold">You don’t have access to this page</h1>
      <p className="mt-2 text-muted-foreground">Your role doesn’t include this area. If you need it, ask an administrator.</p>
      <Button asChild className="mt-6">
        <Link to="/">Go to home</Link>
      </Button>
    </section>
  )
}
