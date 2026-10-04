/** Error text linked to its input via aria-describedby, so screen readers announce it with the field. */
export function FieldError({ id, message }: { id: string; message: string | undefined }) {
  if (!message) return null
  return (
    <p id={id} className="text-sm text-destructive">
      {message}
    </p>
  )
}
